package com.rekosk.remesh.ui.screens

import android.graphics.DashPathEffect
import android.graphics.Paint as AndroidPaint
import android.graphics.drawable.BitmapDrawable
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CellTower
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.res.ResourcesCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.rekosk.remesh.R
import com.rekosk.remesh.data.coverage.CoverageDefaults
import com.rekosk.remesh.data.coverage.CoverageMath
import com.rekosk.remesh.data.coverage.RadioParams
import com.rekosk.remesh.data.coverage.TerrainDem
import com.rekosk.remesh.data.model.NodeType
import com.rekosk.remesh.ui.MeshViewModel
import com.rekosk.remesh.ui.components.PALETTE_SIZE
import com.rekosk.remesh.ui.components.avatarColor
import com.rekosk.remesh.ui.components.avatarColorByIndex
import com.rekosk.remesh.ui.components.avatarGlyph
import com.rekosk.remesh.ui.components.color
import java.io.File
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.osmdroid.config.Configuration
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline

/** One endpoint of the line-of-sight check. */
private data class LosPoint(
    val label: String,
    val latE6: Int,
    val lonE6: Int,
    /** Index into the avatar palette (see `avatarColorByIndex`). */
    val colorIndex: Int,
    /** Antenna height above ground, metres; null = default. */
    val antennaM: Double? = null,
) {
    val lat: Double get() = latE6 / 1e6
    val lon: Double get() = lonE6 / 1e6
    val antenna: Double get() = antennaM ?: CoverageDefaults.TX_ANTENNA_M
}

/**
 * The computed A→B terrain profile. Terrain heights already include the 4/3-earth
 * curvature bulge, so the direct ray can be drawn (and tested) as a straight line.
 */
private class LosProfile(
    val distanceM: Double,
    /** Effective terrain elevation per sample (metres ASL + curvature bulge). */
    val terrain: DoubleArray,
    /** First-Fresnel-zone radius per sample, metres (0 at the endpoints). */
    val fresnel: DoubleArray,
    val groundA: Double,
    val groundB: Double,
    val antA: Double,
    val antB: Double,
    val bearingDeg: Double,
    val freqMHz: Double,
    val pathLossDb: Double,
    /** TX power + system gain − sensitivity, from the node's radio config. */
    val budgetDb: Double,
    val blocked: Boolean,
    val fresnelObstructed: Boolean,
    /** Per-sample: terrain intrudes into the 60% Fresnel clearance / cuts the ray. */
    val intrudedAt: BooleanArray,
)

/**
 * Line-of-sight tool: drop points A and B on the map (or tap a shown node) and get the
 * terrain profile between them — direct ray, first Fresnel zone, curvature, and the
 * resulting path loss. Names, antenna heights, colours and frequency are managed from
 * the top-bar settings sheet, styled after the signal-coverage points sheet.
 *
 * Like signal coverage, everything here is ephemeral: the points and profile live in
 * this composition only; just the elevation tiles are cached on disk.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LineOfSightScreen(viewModel: MeshViewModel, onBack: () -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val dark = isSystemInDarkTheme()

    var pointA by remember { mutableStateOf<LosPoint?>(null) }
    var pointB by remember { mutableStateOf<LosPoint?>(null) }
    var freqText by remember { mutableStateOf("") }
    var showNodes by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var showInfo by remember { mutableStateOf(false) }

    val nodes by viewModel.nodePositions.collectAsStateWithLifecycle()
    val defaults by viewModel.defaultRadioParams.collectAsStateWithLifecycle()
    val dem = remember { TerrainDem(File(context.cacheDir, "terrarium")) }

    var profile by remember { mutableStateOf<LosProfile?>(null) }
    var computing by remember { mutableStateOf(false) }

    // First long-press / node tap sets A, the second sets B, further ones move B.
    fun place(latE6: Int, lonE6: Int, name: String?) {
        when {
            pointA == null -> pointA = LosPoint(name ?: "Point A", latE6, lonE6, colorIndex = 0)
            pointB == null -> pointB = LosPoint(name ?: "Point B", latE6, lonE6, colorIndex = 1)
            else -> pointB = pointB?.copy(
                label = name ?: pointB?.label ?: "Point B",
                latE6 = latE6,
                lonE6 = lonE6,
            )
        }
    }

    val towerIcon = remember { ResourcesCompat.getDrawable(context.resources, R.drawable.ic_cell_tower, null) }
    val backingArgb = MaterialTheme.colorScheme.background.toArgb()
    val primaryArgb = MaterialTheme.colorScheme.primary.toArgb()
    val dashArgb = MaterialTheme.colorScheme.onSurfaceVariant.toArgb()

    val mapView = remember {
        Configuration.getInstance().userAgentValue = context.packageName
        MapView(context).apply {
            setTileSource(TileSourceFactory.OpenTopo)
            setMultiTouchControls(true)
            zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
            minZoomLevel = 2.0
            controller.setZoom(6.0)
            controller.setCenter(GeoPoint(25.0, 0.0))
        }
    }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            mapView.onDetach()
        }
    }
    // Long-press the map to drop / move an endpoint.
    DisposableEffect(mapView) {
        val overlay = MapEventsOverlay(object : MapEventsReceiver {
            override fun singleTapConfirmedHelper(p: GeoPoint): Boolean = false
            override fun longPressHelper(p: GeoPoint): Boolean {
                place((p.latitude * 1e6).toInt(), (p.longitude * 1e6).toInt(), null)
                return true
            }
        })
        mapView.overlays.add(0, overlay)
        onDispose { mapView.overlays.remove(overlay) }
    }
    val centered = remember { booleanArrayOf(false) }

    // Recompute the profile whenever the endpoints, heights, frequency, or the node's
    // radio config change. The short delay coalesces rapid edits into one compute.
    val freqMhz = freqText.toDoubleWithComma() ?: defaults.freqMHz
    LaunchedEffect(pointA, pointB, freqMhz, defaults) {
        val a = pointA
        val b = pointB
        if (a == null || b == null) {
            profile = null
            return@LaunchedEffect
        }
        delay(600)
        computing = true
        val res = computeLosProfile(a, b, freqMhz, defaults, dem)
        computing = false
        if (res != null) profile = res
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            CenterAlignedTopAppBar(
                navigationIcon = {
                    Row {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                        IconButton(onClick = { showInfo = true }) {
                            Icon(Icons.Outlined.Info, contentDescription = "What the terms mean")
                        }
                    }
                },
                title = { Text("Line of sight") },
                actions = {
                    IconButton(onClick = { showNodes = !showNodes }) {
                        Icon(
                            imageVector = Icons.Filled.CellTower,
                            contentDescription = "Toggle nodes",
                            tint = if (showNodes) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    IconButton(onClick = { showSettings = true }) {
                        Icon(Icons.Filled.Tune, contentDescription = "Settings")
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
            val a = pointA
            val b = pointB
            val prof = profile
            if (a != null && b != null && prof != null) {
                LosProfilePanel(a, b, prof, dark)
            }

            // clipToBounds: the osmdroid overlays (sight line, marker art) otherwise draw
            // past the view's edge, over the profile panel above the map.
            Box(modifier = Modifier.fillMaxWidth().weight(1f).clipToBounds()) {
                AndroidView(
                    factory = { mapView },
                    modifier = Modifier.fillMaxSize().clipToBounds(),
                    update = { view ->
                        // Rebuild overlays (keep the events overlay at index 0).
                        val events = view.overlays.firstOrNull { it is MapEventsOverlay }
                        view.overlays.clear()
                        if (events != null) view.overlays.add(events)

                        val d = view.resources.displayMetrics.density
                        // The dashed A–B sight line, under all markers.
                        if (a != null && b != null) {
                            view.overlays.add(
                                Polyline(view).apply {
                                    setPoints(listOf(GeoPoint(a.lat, a.lon), GeoPoint(b.lat, b.lon)))
                                    outlinePaint.color = dashArgb
                                    outlinePaint.strokeWidth = 4f * d
                                    outlinePaint.strokeCap = AndroidPaint.Cap.ROUND
                                    outlinePaint.pathEffect =
                                        DashPathEffect(floatArrayOf(6f * d, 6f * d), 0f)
                                    infoWindow = null
                                    setOnClickListener { _, _, _ -> true }
                                },
                            )
                        }
                        // Mesh nodes, rendered exactly like the main map; tapping one
                        // makes it the next endpoint.
                        if (showNodes) {
                            nodes.forEach { node ->
                                val tint = when {
                                    node.isSelf -> primaryArgb
                                    node.type == NodeType.CHAT -> avatarColor(node.id, dark).toArgb()
                                    else -> node.type.color().toArgb()
                                }
                                val tower = if (node.type == NodeType.REPEATER) towerIcon else null
                                val art = nodeMarkerBitmap(
                                    view, node.name.trim().ifBlank { "(unnamed)" }.take(24),
                                    tint, avatarGlyph(node.name), tower, tonal = true, backingArgb,
                                )
                                view.overlays.add(
                                    Marker(view).apply {
                                        position = GeoPoint(node.latitude, node.longitude)
                                        icon = BitmapDrawable(view.resources, art.bitmap)
                                        setAnchor(0.5f, art.anchorV)
                                        infoWindow = null
                                        setOnMarkerClickListener { _, _ ->
                                            place(
                                                (node.latitude * 1e6).toInt(),
                                                (node.longitude * 1e6).toInt(),
                                                node.name.trim().ifBlank { null },
                                            )
                                            true
                                        }
                                    },
                                )
                            }
                        }
                        // The endpoints, in the same tonal wash the coverage points use.
                        listOfNotNull(a?.let { it to "A" }, b?.let { it to "B" }).forEach { (p, glyph) ->
                            val argb = avatarColorByIndex(p.colorIndex, dark).toArgb()
                            val art = nodeMarkerBitmap(
                                view, p.label.take(24), argb, glyph, null, tonal = true, backingArgb,
                            )
                            view.overlays.add(
                                Marker(view).apply {
                                    position = GeoPoint(p.lat, p.lon)
                                    icon = BitmapDrawable(view.resources, art.bitmap)
                                    setAnchor(0.5f, art.anchorV)
                                    infoWindow = null
                                },
                            )
                        }

                        if (!centered[0]) {
                            val focus = a?.let { GeoPoint(it.lat, it.lon) }
                                ?: nodes.firstOrNull { it.isSelf }?.let { GeoPoint(it.latitude, it.longitude) }
                                ?: nodes.firstOrNull()?.let { GeoPoint(it.latitude, it.longitude) }
                            if (focus != null) {
                                view.controller.setZoom(11.0)
                                view.controller.setCenter(focus)
                                centered[0] = true
                            }
                        }
                        view.invalidate()
                    },
                )

                if (a == null || b == null) {
                    LosInfoBanner(
                        "Long-press the map to drop point A, then point B — or show nodes and " +
                            "tap one. Set names, antenna heights and frequency from the settings.",
                    )
                }

                if (computing) {
                    MapLoadingIndicator("Computing profile…")
                }
            }
        }
    }

    if (showInfo) {
        LosTermsDialog(onDismiss = { showInfo = false })
    }

    if (showSettings) {
        LosSettingsSheet(
            pointA = pointA,
            pointB = pointB,
            dark = dark,
            freqText = freqText,
            defaultFreqMhz = defaults.freqMHz,
            onFreq = { freqText = it },
            onPointA = { pointA = it },
            onPointB = { pointB = it },
            onDismiss = { showSettings = false },
        )
    }
}

// ---------------- profile computation ----------------

/**
 * Samples the terrain between the endpoints off a real DEM and derives everything the
 * panel shows. Heavy (terrain-tile fetch + sampling); returns null on failure.
 */
private suspend fun computeLosProfile(
    a: LosPoint,
    b: LosPoint,
    freqMHz: Double,
    defaults: RadioParams,
    dem: TerrainDem,
): LosProfile? = runCatching {
    val dist = haversineM(a.lat, a.lon, b.lat, b.lon).coerceAtLeast(1.0)
    val sampler = withContext(Dispatchers.IO) {
        dem.prepare((a.lat + b.lat) / 2, (a.lon + b.lon) / 2, dist / 2 * 1.1 + 200)
    }
    withContext(Dispatchers.Default) {
        val n = 280
        val lambda = 299.792458 / freqMHz
        val twoKRe = 2 * CoverageDefaults.K_FACTOR * CoverageDefaults.EARTH_RADIUS_M
        val terrain = DoubleArray(n + 1)
        val fresnel = DoubleArray(n + 1)
        for (i in 0..n) {
            val f = i.toDouble() / n
            val d1 = dist * f
            val d2 = dist - d1
            val lat = a.lat + (b.lat - a.lat) * f
            val lon = a.lon + (b.lon - a.lon) * f
            // Curvature bulge folded into the terrain, so the ray stays a straight line.
            terrain[i] = sampler.elevationM(lat, lon) + d1 * d2 / twoKRe
            fresnel[i] = sqrt(lambda * d1 * d2 / dist)
        }
        val losA = terrain[0] + a.antenna
        val losB = terrain[n] + b.antenna

        var blocked = false
        var fresnelObstructed = false
        val intrudedAt = BooleanArray(n + 1)
        for (i in 1 until n) {
            val ray = losA + (losB - losA) * i / n
            val clearance = ray - terrain[i]
            if (clearance < 0) blocked = true
            if (clearance < 0.6 * fresnel[i]) {
                fresnelObstructed = true
                intrudedAt[i] = true
            }
        }

        // Deygout knife-edge diffraction: dominant edge on the full path, then the
        // strongest edge on each sub-path — same approximation the coverage engine uses.
        fun dominantEdge(i0: Int, i1: Int, y0: Double, y1: Double): Pair<Int, Double> {
            var bestI = -1
            var bestV = Double.NEGATIVE_INFINITY
            for (i in i0 + 1 until i1) {
                val d1 = dist * (i - i0) / n
                val dTot = dist * (i1 - i0) / n
                val line = y0 + (y1 - y0) * (i - i0) / (i1 - i0)
                val v = (terrain[i] - line) * sqrt(2.0 / lambda * dTot / (d1 * (dTot - d1)))
                if (v > bestV) {
                    bestV = v
                    bestI = i
                }
            }
            return bestI to bestV
        }

        var diffractionDb = 0.0
        val (mi, mv) = dominantEdge(0, n, losA, losB)
        if (mi > 0 && mv > -0.78) {
            diffractionDb += CoverageMath.knifeEdgeDb(mv)
            val (ai, av) = dominantEdge(0, mi, losA, terrain[mi])
            if (ai > 0 && av > -0.78) diffractionDb += CoverageMath.knifeEdgeDb(av)
            val (bi, bv) = dominantEdge(mi, n, terrain[mi], losB)
            if (bi > 0 && bv > -0.78) diffractionDb += CoverageMath.knifeEdgeDb(bv)
        }
        // Basic loss like the coverage engine: free space near in, two-ray far out,
        // with terrain altitude advantage counting toward the effective heights.
        val htEff = a.antenna + max(0.0, terrain[0] - terrain[n])
        val hrEff = b.antenna + max(0.0, terrain[n] - terrain[0])
        val baseLoss = max(
            CoverageMath.fsplDb(dist, freqMHz),
            CoverageMath.twoRayDb(dist, htEff, hrEff),
        )

        LosProfile(
            distanceM = dist,
            terrain = terrain,
            fresnel = fresnel,
            groundA = terrain[0],
            groundB = terrain[n],
            antA = a.antenna,
            antB = b.antenna,
            bearingDeg = bearingDeg(a.lat, a.lon, b.lat, b.lon),
            freqMHz = freqMHz,
            pathLossDb = baseLoss + diffractionDb,
            budgetDb = defaults.txPowerDbm + CoverageDefaults.SYSTEM_GAIN_DB - defaults.sensitivityDbm,
            blocked = blocked,
            fresnelObstructed = fresnelObstructed,
            intrudedAt = intrudedAt,
        )
    }
}.getOrNull()

// ---------------- profile panel ----------------

/** The header rows + elevation chart shown above the map once both points are set. */
@Composable
private fun LosProfilePanel(a: LosPoint, b: LosPoint, prof: LosProfile, dark: Boolean) {
    val colorA = avatarColorByIndex(a.colorIndex, dark)
    val colorB = avatarColorByIndex(b.colorIndex, dark)
    val lossOverBudget = prof.pathLossDb > prof.budgetDb
    val statusText: String
    val statusColor: Color
    when {
        prof.blocked -> {
            statusText = "Blocked by terrain"
            statusColor = MaterialTheme.colorScheme.error
        }
        prof.fresnelObstructed -> {
            statusText = "Fresnel zone obstructed"
            statusColor = MaterialTheme.colorScheme.error
        }
        else -> {
            statusText = "Line of sight clear"
            statusColor = MaterialTheme.colorScheme.primary
        }
    }

    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = a.label,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        color = colorA,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = "%.0fm + %sm  ∠%.1f°".format(
                            prof.groundA, prof.antA.losFmt(), prof.bearingDeg,
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = "%.5f / %.5f".format(a.lat, a.lon),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Column(modifier = Modifier.weight(1f), horizontalAlignment = Alignment.End) {
                    Text(
                        text = b.label,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        color = colorB,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.End,
                    )
                    Text(
                        text = "%.0fm + %sm  ∠%.1f°".format(
                            prof.groundB, prof.antB.losFmt(), (prof.bearingDeg + 180.0) % 360.0,
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = "%.5f / %.5f".format(b.lat, b.lon),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.height(6.dp))
            LosProfileChart(prof, colorA, colorB)
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "%s MHz".format(prof.freqMHz.losFmt()),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.weight(1f))
                Text(
                    text = statusText,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = statusColor,
                )
                Spacer(Modifier.weight(1f))
                Text(
                    text = "%s  •  %.1f dB".format(prof.distanceM.losDistFmt(), prof.pathLossDb),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = if (lossOverBudget) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}

/** Terrain profile + direct ray + first Fresnel zone, drawn with theme colours. */
@Composable
private fun LosProfileChart(prof: LosProfile, colorA: Color, colorB: Color) {
    val terrainColor = MaterialTheme.colorScheme.onSurfaceVariant
    val rayColor = MaterialTheme.colorScheme.onSurface
    val fresnelColor = MaterialTheme.colorScheme.primary
    val errorColor = MaterialTheme.colorScheme.error
    val gridColor = MaterialTheme.colorScheme.outlineVariant
    val labelArgb = MaterialTheme.colorScheme.onSurfaceVariant.toArgb()

    Canvas(modifier = Modifier.fillMaxWidth().height(160.dp)) {
        val terr = prof.terrain
        val last = terr.size - 1
        val losA = prof.groundA + prof.antA
        val losB = prof.groundB + prof.antB

        var minE = terr.min()
        var maxE = terr.max()
        for (i in terr.indices) {
            val ray = losA + (losB - losA) * i / last
            maxE = max(maxE, ray + prof.fresnel[i])
            minE = min(minE, ray - prof.fresnel[i])
        }
        val span = (maxE - minE).coerceAtLeast(1.0)
        minE -= span * 0.08
        maxE += span * 0.08
        val range = maxE - minE

        val w = size.width
        val h = size.height
        fun x(i: Int) = w * i / last
        fun y(elev: Double) = (h * (1.0 - (elev - minE) / range)).toFloat()

        // Elevation grid + labels.
        val textPaint = AndroidPaint(AndroidPaint.ANTI_ALIAS_FLAG).apply {
            color = labelArgb
            textSize = 9.dp.toPx()
        }
        val yStep = niceStep(range / 4)
        var tick = Math.ceil(minE / yStep) * yStep
        while (tick < maxE) {
            val yy = y(tick)
            drawLine(gridColor, androidx.compose.ui.geometry.Offset(0f, yy),
                androidx.compose.ui.geometry.Offset(w, yy), strokeWidth = 1f)
            drawContext.canvas.nativeCanvas.drawText(
                "${tick.roundToInt()}m", 4.dp.toPx(), yy - 3.dp.toPx(), textPaint,
            )
            tick += yStep
        }
        // Distance grid + labels.
        val xStep = niceStep(prof.distanceM / 5)
        var dTick = xStep
        while (dTick < prof.distanceM * 0.98) {
            val xx = (w * dTick / prof.distanceM).toFloat()
            drawLine(gridColor, androidx.compose.ui.geometry.Offset(xx, 0f),
                androidx.compose.ui.geometry.Offset(xx, h), strokeWidth = 1f)
            val lbl = if (xStep >= 1000) "%.0fkm".format(dTick / 1000) else "%.0fm".format(dTick)
            drawContext.canvas.nativeCanvas.drawText(
                lbl, xx + 3.dp.toPx(), h - 4.dp.toPx(), textPaint,
            )
            dTick += xStep
        }

        // First Fresnel zone: an ellipse around the ray.
        val fres = Path()
        for (i in 0..last) {
            val ray = losA + (losB - losA) * i / last
            val yy = y(ray + prof.fresnel[i])
            if (i == 0) fres.moveTo(x(i), yy) else fres.lineTo(x(i), yy)
        }
        for (i in last downTo 0) {
            val ray = losA + (losB - losA) * i / last
            fres.lineTo(x(i), y(ray - prof.fresnel[i]))
        }
        fres.close()
        drawPath(fres, color = fresnelColor.copy(alpha = 0.14f))
        drawPath(fres, color = fresnelColor.copy(alpha = 0.6f), style = Stroke(width = 1.5.dp.toPx()))

        // Terrain, filled to the chart floor.
        val ground = Path()
        ground.moveTo(0f, y(terr[0]))
        for (i in 1..last) ground.lineTo(x(i), y(terr[i]))
        ground.lineTo(w, h)
        ground.lineTo(0f, h)
        ground.close()
        drawPath(ground, color = terrainColor.copy(alpha = 0.30f))
        val groundLine = Path()
        groundLine.moveTo(0f, y(terr[0]))
        for (i in 1..last) groundLine.lineTo(x(i), y(terr[i]))
        drawPath(groundLine, color = terrainColor, style = Stroke(width = 1.5.dp.toPx()))

        // Terrain that intrudes into the required Fresnel clearance, re-stroked in red.
        var i = 1
        while (i <= last) {
            if (prof.intrudedAt[i]) {
                val seg = Path()
                seg.moveTo(x(i - 1), y(terr[i - 1]))
                while (i <= last && prof.intrudedAt[i]) {
                    seg.lineTo(x(i), y(terr[i]))
                    i++
                }
                if (i <= last) seg.lineTo(x(i), y(terr[i]))
                drawPath(seg, color = errorColor, style = Stroke(width = 2.5.dp.toPx()))
            }
            i++
        }

        // The direct ray.
        drawLine(
            color = rayColor,
            start = androidx.compose.ui.geometry.Offset(0f, y(losA)),
            end = androidx.compose.ui.geometry.Offset(w, y(losB)),
            strokeWidth = 2.dp.toPx(),
        )

        // Antenna masts at each end, in the points' palette colours.
        drawLine(
            color = colorA,
            start = androidx.compose.ui.geometry.Offset(1.5.dp.toPx(), y(prof.groundA)),
            end = androidx.compose.ui.geometry.Offset(1.5.dp.toPx(), y(losA)),
            strokeWidth = 3.dp.toPx(),
        )
        drawCircle(colorA, radius = 4.dp.toPx(), center = androidx.compose.ui.geometry.Offset(1.5.dp.toPx(), y(losA)))
        drawLine(
            color = colorB,
            start = androidx.compose.ui.geometry.Offset(w - 1.5.dp.toPx(), y(prof.groundB)),
            end = androidx.compose.ui.geometry.Offset(w - 1.5.dp.toPx(), y(losB)),
            strokeWidth = 3.dp.toPx(),
        )
        drawCircle(colorB, radius = 4.dp.toPx(), center = androidx.compose.ui.geometry.Offset(w - 1.5.dp.toPx(), y(losB)))
    }
}

// ---------------- settings sheet ----------------

/**
 * Frequency + per-point name/antenna/colour, styled after the signal-coverage
 * points sheet.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LosSettingsSheet(
    pointA: LosPoint?,
    pointB: LosPoint?,
    dark: Boolean,
    freqText: String,
    defaultFreqMhz: Double,
    onFreq: (String) -> Unit,
    onPointA: (LosPoint?) -> Unit,
    onPointB: (LosPoint?) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(bottom = 24.dp),
        ) {
            Text(
                text = "Line of sight",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            )
            var freq by remember { mutableStateOf(freqText) }
            OutlinedTextField(
                value = freq,
                onValueChange = {
                    freq = it
                    onFreq(it)
                },
                label = { Text("Frequency MHz", style = MaterialTheme.typography.labelSmall) },
                placeholder = { Text(defaultFreqMhz.losFmt()) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                textStyle = MaterialTheme.typography.bodyMedium,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
            )
            if (pointA == null && pointB == null) {
                Text(
                    text = "No points yet — long-press the map to add one.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                )
            }
            pointA?.let { p ->
                LosPointSection("A", p, dark, onPoint = onPointA)
            }
            pointB?.let { p ->
                LosPointSection("B", p, dark, onPoint = onPointB)
            }
        }
    }
}

@Composable
private fun LosPointSection(
    slot: String,
    p: LosPoint,
    dark: Boolean,
    onPoint: (LosPoint?) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(20.dp)
                    .clip(CircleShape)
                    .background(avatarColorByIndex(p.colorIndex, dark)),
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text("Point $slot  •  ${p.label}", style = MaterialTheme.typography.titleMedium,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    text = "%.5f, %.5f".format(p.lat, p.lon),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = { onPoint(null) }) {
                Icon(Icons.Filled.Delete, contentDescription = "Delete point $slot")
            }
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier
                .padding(start = 32.dp, top = 4.dp, bottom = 4.dp)
                .horizontalScroll(rememberScrollState()),
        ) {
            for (idx in 0 until PALETTE_SIZE) {
                val c = avatarColorByIndex(idx, dark)
                Box(
                    modifier = Modifier
                        .size(26.dp)
                        .clip(CircleShape)
                        .background(c)
                        .clickable { onPoint(p.copy(colorIndex = idx)) },
                    contentAlignment = Alignment.Center,
                ) {
                    if (idx == p.colorIndex) {
                        Icon(
                            Icons.Filled.Check,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                }
            }
        }
        var name by remember(slot) { mutableStateOf(p.label) }
        var ant by remember(slot) { mutableStateOf(p.antennaM?.losFmt() ?: "") }
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(start = 32.dp, top = 4.dp, bottom = 8.dp),
        ) {
            OutlinedTextField(
                value = name,
                onValueChange = {
                    name = it
                    onPoint(p.copy(label = it.ifBlank { "Point $slot" }))
                },
                label = { Text("Name", style = MaterialTheme.typography.labelSmall) },
                placeholder = { Text("Point $slot") },
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            OutlinedTextField(
                value = ant,
                onValueChange = {
                    ant = it
                    onPoint(p.copy(antennaM = it.toDoubleWithComma()))
                },
                label = { Text("Antenna m", style = MaterialTheme.typography.labelSmall) },
                placeholder = { Text(CoverageDefaults.TX_ANTENNA_M.losFmt()) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                textStyle = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

// ---------------- small helpers ----------------

/**
 * The themed (Material 3 expressive) loading shape, centred over the map while a terrain
 * compute runs. Shared by the map tools ([SignalCoverageScreen] uses it too).
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun BoxScope.MapLoadingIndicator(label: String) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.92f),
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier.align(Alignment.Center),
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 18.dp),
        ) {
            LoadingIndicator(modifier = Modifier.size(44.dp))
            Spacer(Modifier.height(6.dp))
            Text(label, style = MaterialTheme.typography.labelLarge)
        }
    }
}

/** Plain-language glossary for the status line and chart. */
@Composable
private fun LosTermsDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Got it") }
        },
        title = { Text("What the terms mean") },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.verticalScroll(rememberScrollState()),
            ) {
                LosTerm(
                    "Line of sight clear",
                    "The straight ray between the two antennas passes above the terrain " +
                        "with enough clearance. This is the best case for a LoRa link.",
                )
                LosTerm(
                    "Blocked by terrain",
                    "The ground rises above the straight ray somewhere along the path, so " +
                        "the radios cannot \"see\" each other. The signal must diffract over " +
                        "the obstacle, which costs a lot of link budget — the link may still " +
                        "work, but only if the shown path loss stays below the budget.",
                )
                LosTerm(
                    "Fresnel zone obstructed",
                    "Radio waves need more than a pencil-thin ray: they travel in a " +
                        "rugby-ball-shaped volume around the line (the first Fresnel zone, " +
                        "the ellipse on the chart). When terrain pokes into the inner 60% of " +
                        "it (drawn red), part of the wave is lost even though the direct ray " +
                        "is clear — expect a weaker link than the distance alone suggests.",
                )
                LosTerm(
                    "Path loss (dB)",
                    "How much signal is lost between the two points: free-space spreading, " +
                        "ground reflection, and any diffraction over terrain. Shown red when " +
                        "it exceeds the link budget.",
                )
                LosTerm(
                    "Link budget",
                    "TX power + antenna gains + receiver sensitivity, combined — the total " +
                        "loss the link can survive. Taken from your node's radio config.",
                )
                LosTerm(
                    "\"450m + 5m\"",
                    "Ground elevation above sea level, plus the antenna's height above the " +
                        "ground at that point.",
                )
                LosTerm(
                    "∠ bearing",
                    "Compass direction from that point toward the other one (0° = north).",
                )
                LosTerm(
                    "Earth curvature",
                    "Profiles are drawn with the standard 4/3-earth-radius correction, which " +
                        "accounts for the bulge of the Earth and mild atmospheric bending — " +
                        "that is why long paths need extra clearance in the middle.",
                )
            }
        },
    )
}

@Composable
private fun LosTerm(term: String, explanation: String) {
    Column {
        Text(term, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
        Text(
            text = explanation,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun BoxScope.LosInfoBanner(text: String) {
    Surface(
        color = MaterialTheme.colorScheme.primaryContainer,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier
            .align(Alignment.TopCenter)
            .fillMaxWidth()
            .padding(12.dp),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
            modifier = Modifier.padding(12.dp),
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** A "nice" 1/2/5×10ⁿ grid step at least a fifth of [raw]. */
private fun niceStep(raw: Double): Double {
    val mag = 10.0.pow(Math.floor(Math.log10(raw.coerceAtLeast(1.0))))
    return when {
        raw / mag <= 1.0 -> mag
        raw / mag <= 2.0 -> 2 * mag
        raw / mag <= 5.0 -> 5 * mag
        else -> 10 * mag
    }
}

private fun Double.losFmt(): String =
    if (this == Math.rint(this)) toLong().toString() else "%.3f".format(this).trimEnd('0').trimEnd('.')

private fun Double.losDistFmt(): String =
    if (this < 1000) "%.0f m".format(this) else "%.1f km".format(this / 1000)

private fun String.toDoubleWithComma(): Double? = trim().replace(',', '.').toDoubleOrNull()

private fun haversineM(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val dLat = Math.toRadians(lat2 - lat1)
    val dLon = Math.toRadians(lon2 - lon1)
    val a = sin(dLat / 2).pow(2) +
        cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).pow(2)
    return CoverageDefaults.EARTH_RADIUS_M * 2 * Math.asin(sqrt(a))
}

private fun bearingDeg(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val p1 = Math.toRadians(lat1)
    val p2 = Math.toRadians(lat2)
    val dLon = Math.toRadians(lon2 - lon1)
    val y = sin(dLon) * cos(p2)
    val x = cos(p1) * sin(p2) - sin(p1) * cos(p2) * cos(dLon)
    return (Math.toDegrees(atan2(y, x)) + 360.0) % 360.0
}
