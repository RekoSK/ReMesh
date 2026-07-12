package com.rekosk.remesh.ui.screens

import android.graphics.drawable.BitmapDrawable
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CellTower
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
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
import com.rekosk.remesh.data.coverage.CoverageResult
import com.rekosk.remesh.data.coverage.RadioParams
import com.rekosk.remesh.data.coverage.TerrainDem
import com.rekosk.remesh.data.model.CoveragePoint
import com.rekosk.remesh.data.model.NodeType
import com.rekosk.remesh.ui.MeshViewModel
import com.rekosk.remesh.ui.components.PALETTE_SIZE
import com.rekosk.remesh.ui.components.avatarColorByIndex
import com.rekosk.remesh.ui.components.color
import java.io.File
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.GroundOverlay2
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.views.overlay.Marker

/**
 * Signal-coverage tool: drop coloured points on the map, each rendering a terrain-aware
 * MeshCore coverage heatmap. Points are managed from the top-bar menu (not on the map). A
 * top-bar repeater toggle shows repeaters; tapping one shows its theoretical coverage.
 *
 * Everything here is deliberately ephemeral: points, computed coverage, and the fetched
 * terrain all live in this composition only and are forgotten when the screen closes.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SignalCoverageScreen(viewModel: MeshViewModel, onBack: () -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val dark = isSystemInDarkTheme()
    val points = remember { mutableStateListOf<CoveragePoint>() }
    val nodes by viewModel.nodePositions.collectAsStateWithLifecycle()
    val repeaters = remember(nodes) { nodes.filter { it.type == NodeType.REPEATER } }

    val scope = rememberCoroutineScope()
    val dem = remember { TerrainDem(File(context.cacheDir, "terrarium")) }
    val defaults by viewModel.defaultRadioParams.collectAsStateWithLifecycle()
    val results = remember { mutableStateMapOf<String, CoverageResult>() }
    val computing = remember { mutableStateMapOf<String, Boolean>() }
    val repeaterResults = remember { mutableStateMapOf<String, CoverageResult>() }
    val computingRepeaters = remember { mutableStateListOf<String>() }
    var showRepeaters by remember { mutableStateOf(false) }
    var showManage by remember { mutableStateOf(false) }
    var pointCounter by remember { mutableStateOf(0) }

    fun addPoint(latE6: Int, lonE6: Int) {
        val n = pointCounter++
        val used = points.map { it.colorIndex }.toSet()
        val colorIndex = (0 until PALETTE_SIZE).firstOrNull { it !in used } ?: (n % PALETTE_SIZE)
        val label = "Point " + if (n < 26) ('A' + n).toString() else "${n + 1}"
        points.add(CoveragePoint("cov-$n", label, latE6, lonE6, colorIndex))
    }

    fun replacePoint(id: String, transform: (CoveragePoint) -> CoveragePoint) {
        val i = points.indexOfFirst { it.id == id }
        if (i >= 0) points[i] = transform(points[i])
    }

    val towerIcon = remember { ResourcesCompat.getDrawable(context.resources, R.drawable.ic_cell_tower, null) }
    val backingArgb = MaterialTheme.colorScheme.background.toArgb()
    val repeaterArgb = NodeType.REPEATER.color().toArgb()

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
    // Long-press the map to drop a coverage point.
    DisposableEffect(mapView) {
        val overlay = MapEventsOverlay(object : MapEventsReceiver {
            override fun singleTapConfirmedHelper(p: GeoPoint): Boolean = false
            override fun longPressHelper(p: GeoPoint): Boolean {
                addPoint((p.latitude * 1e6).toInt(), (p.longitude * 1e6).toInt())
                return true
            }
        })
        mapView.overlays.add(0, overlay)
        onDispose { mapView.overlays.remove(overlay) }
    }
    val centered = remember { booleanArrayOf(false) }

    // One compute per point, re-run when its position/colour/enabled/radio params change —
    // or when the companion's radio config (the source of the defaults) arrives/changes.
    // The short delay coalesces rapid edits (typing in the parameter fields) into one compute.
    points.forEach { p ->
        key(p.id) {
            LaunchedEffect(
                p.latE6, p.lonE6, p.colorIndex, p.enabled,
                p.txPowerDbm, p.freqMhz, p.antennaM, p.rxSensitivityDbm,
                defaults,
            ) {
                if (!p.enabled) {
                    results.remove(p.id)
                    return@LaunchedEffect
                }
                delay(700)
                computing[p.id] = true
                val res = viewModel.computeCoverage(p, avatarColorByIndex(p.colorIndex, dark).toArgb(), dem)
                computing[p.id] = false
                if (res != null) results[p.id] = res
            }
        }
    }
    // Drop computed coverage for points that no longer exist.
    val pointIdsKey = points.joinToString(separator = ",") { it.id }
    LaunchedEffect(pointIdsKey) {
        val ids = points.map { it.id }.toSet()
        (results.keys - ids).forEach { results.remove(it) }
    }
    // Clear repeater coverage when the toggle goes off.
    LaunchedEffect(showRepeaters) { if (!showRepeaters) repeaterResults.clear() }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            CenterAlignedTopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                title = { Text("Signal coverage") },
                actions = {
                    IconButton(onClick = { showRepeaters = !showRepeaters }) {
                        Icon(
                            imageVector = Icons.Filled.CellTower,
                            contentDescription = "Toggle repeaters",
                            tint = if (showRepeaters) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    IconButton(onClick = { showManage = true }) {
                        Icon(Icons.Filled.Tune, contentDescription = "Manage points")
                    }
                },
            )
        },
    ) { innerPadding ->
        Box(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
            AndroidView(
                factory = { mapView },
                modifier = Modifier.fillMaxSize(),
                update = { view ->
                    // Rebuild overlays (keep the events overlay at index 0).
                    val events = view.overlays.firstOrNull { it is MapEventsOverlay }
                    view.overlays.clear()
                    if (events != null) view.overlays.add(events)

                    // Heatmaps first (under markers).
                    (results.values + repeaterResults.values).forEach { res ->
                        view.overlays.add(
                            GroundOverlay2().apply {
                                setImage(res.bitmap)
                                // top-left (NW) and bottom-right (SE) corners.
                                setPosition(GeoPoint(res.north, res.west), GeoPoint(res.south, res.east))
                            },
                        )
                    }
                    // Coverage-point markers, in the same tonal wash the contact avatars use.
                    points.filter { it.enabled }.forEach { p ->
                        val argb = avatarColorByIndex(p.colorIndex, dark).toArgb()
                        val glyph = p.label.removePrefix("Point").trim().take(1)
                            .ifEmpty { p.label.take(1) }.uppercase()
                        val art = nodeMarkerBitmap(
                            view, p.label, argb, glyph, null, tonal = true, backingArgb,
                        )
                        view.overlays.add(
                            Marker(view).apply {
                                position = GeoPoint(p.latE6 / 1e6, p.lonE6 / 1e6)
                                icon = BitmapDrawable(view.resources, art.bitmap)
                                setAnchor(0.5f, art.anchorV)
                                infoWindow = null
                            },
                        )
                    }
                    // Repeaters + tap-to-compute.
                    if (showRepeaters) {
                        repeaters.forEach { node ->
                            val art = nodeMarkerBitmap(
                                view, node.name.take(20), repeaterArgb, "", towerIcon, tonal = true, backingArgb,
                            )
                            view.overlays.add(
                                Marker(view).apply {
                                    position = GeoPoint(node.latitude, node.longitude)
                                    icon = BitmapDrawable(view.resources, art.bitmap)
                                    setAnchor(0.5f, art.anchorV)
                                    infoWindow = null
                                    setOnMarkerClickListener { _, _ ->
                                        // Tap toggles this repeater's theoretical coverage.
                                        if (repeaterResults.remove(node.id) == null &&
                                            node.id !in computingRepeaters
                                        ) {
                                            computingRepeaters.add(node.id)
                                            scope.launch {
                                                val res = viewModel.computeRepeaterCoverage(
                                                    (node.latitude * 1e6).toInt(),
                                                    (node.longitude * 1e6).toInt(),
                                                    repeaterArgb,
                                                    dem,
                                                )
                                                computingRepeaters.remove(node.id)
                                                if (res != null) repeaterResults[node.id] = res
                                            }
                                        }
                                        true
                                    }
                                },
                            )
                        }
                    }

                    if (!centered[0]) {
                        val focus = points.firstOrNull()?.let { GeoPoint(it.latE6 / 1e6, it.lonE6 / 1e6) }
                            ?: repeaters.firstOrNull()?.let { GeoPoint(it.latitude, it.longitude) }
                        if (focus != null) {
                            view.controller.setZoom(11.0)
                            view.controller.setCenter(focus)
                            centered[0] = true
                        }
                    }
                    view.invalidate()
                },
            )

            InfoBanner(
                "Long-press the map to drop a point. Manage points and colours from the menu; " +
                    "toggle repeaters to check their coverage.",
            )

            if (computing.values.any { it } || computingRepeaters.isNotEmpty()) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(16.dp),
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    Text("Computing coverage…", style = MaterialTheme.typography.labelLarge)
                }
            }
        }
    }

    if (showManage) {
        ManagePointsSheet(
            points = points,
            dark = dark,
            defaults = defaults,
            onToggle = { id, on -> replacePoint(id) { it.copy(enabled = on) } },
            onColor = { id, idx -> replacePoint(id) { it.copy(colorIndex = idx) } },
            onParams = { id, tx, freq, ant, sens ->
                replacePoint(id) {
                    it.copy(txPowerDbm = tx, freqMhz = freq, antennaM = ant, rxSensitivityDbm = sens)
                }
            },
            onDelete = { id -> points.removeAll { it.id == id } },
            onDismiss = { showManage = false },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ManagePointsSheet(
    points: List<CoveragePoint>,
    dark: Boolean,
    defaults: RadioParams,
    onToggle: (String, Boolean) -> Unit,
    onColor: (String, Int) -> Unit,
    onParams: (String, Double?, Double?, Double?, Double?) -> Unit,
    onDelete: (String) -> Unit,
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
                text = "Coverage points",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            )
            if (points.isEmpty()) {
                Text(
                    text = "No points yet — long-press the map to add one.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                )
            }
            points.forEach { p ->
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
                            Text(p.label, style = MaterialTheme.typography.titleMedium)
                            Text(
                                text = "%.5f, %.5f".format(p.latE6 / 1e6, p.lonE6 / 1e6),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Checkbox(checked = p.enabled, onCheckedChange = { onToggle(p.id, it) })
                        IconButton(onClick = { onDelete(p.id) }) {
                            Icon(Icons.Filled.Delete, contentDescription = "Delete point")
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
                                    .clickable { onColor(p.id, idx) },
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
                    PointParamsFields(point = p, defaults = defaults, onParams = onParams)
                }
            }
        }
    }
}

/**
 * The optional radio overrides for one point. Every field defaults to the node's radio
 * config (shown as the placeholder); leaving a field empty keeps the default.
 */
@Composable
private fun PointParamsFields(
    point: CoveragePoint,
    defaults: RadioParams,
    onParams: (String, Double?, Double?, Double?, Double?) -> Unit,
) {
    var tx by remember(point.id) { mutableStateOf(point.txPowerDbm?.fmt() ?: "") }
    var freq by remember(point.id) { mutableStateOf(point.freqMhz?.fmt() ?: "") }
    var ant by remember(point.id) { mutableStateOf(point.antennaM?.fmt() ?: "") }
    var sens by remember(point.id) { mutableStateOf(point.rxSensitivityDbm?.fmt() ?: "") }

    fun push() = onParams(
        point.id,
        tx.toDoubleWithComma(),
        freq.toDoubleWithComma(),
        ant.toDoubleWithComma(),
        sens.toDoubleWithComma(),
    )

    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.padding(start = 32.dp, top = 4.dp, bottom = 8.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ParamField(
                value = tx,
                onValue = { tx = it; push() },
                label = "TX power dBm",
                placeholder = defaults.txPowerDbm.fmt(),
                modifier = Modifier.weight(1f),
            )
            ParamField(
                value = freq,
                onValue = { freq = it; push() },
                label = "Frequency MHz",
                placeholder = defaults.freqMHz.fmt(),
                modifier = Modifier.weight(1f),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ParamField(
                value = ant,
                onValue = { ant = it; push() },
                label = "Antenna m",
                placeholder = CoverageDefaults.TX_ANTENNA_M.fmt(),
                modifier = Modifier.weight(1f),
            )
            ParamField(
                value = sens,
                onValue = { sens = it; push() },
                label = "RX sens. dBm",
                placeholder = defaults.sensitivityDbm.fmt(),
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun ParamField(
    value: String,
    onValue: (String) -> Unit,
    label: String,
    placeholder: String,
    modifier: Modifier = Modifier,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValue,
        label = { Text(label, style = MaterialTheme.typography.labelSmall) },
        placeholder = { Text(placeholder) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        textStyle = MaterialTheme.typography.bodyMedium,
        modifier = modifier,
    )
}

private fun Double.fmt(): String =
    if (this == Math.rint(this)) toLong().toString() else "%.3f".format(this).trimEnd('0').trimEnd('.')

private fun String.toDoubleWithComma(): Double? = trim().replace(',', '.').toDoubleOrNull()

@Composable
private fun BoxScope.InfoBanner(text: String) {
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
