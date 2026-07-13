package com.rekosk.remesh.ui.screens

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color as AndroidColor
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import androidx.core.content.res.ResourcesCompat
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LocationOff
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.filled.CellTower
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import android.widget.Toast
import com.rekosk.remesh.R
import com.rekosk.remesh.data.OnlineMapNode
import com.rekosk.remesh.data.model.NodePosition
import com.rekosk.remesh.data.model.NodeType
import com.rekosk.remesh.ui.MeshViewModel
import com.rekosk.remesh.ui.components.MeshTopBarActions
import com.rekosk.remesh.ui.components.OnlineMapToggle
import com.rekosk.remesh.ui.components.OverflowNav
import com.rekosk.remesh.ui.components.avatarColor
import com.rekosk.remesh.ui.components.avatarGlyph
import com.rekosk.remesh.ui.components.color
import com.rekosk.remesh.ui.theme.NodeColors
import kotlin.math.ceil
import org.osmdroid.config.Configuration
import org.osmdroid.events.DelayedMapListener
import org.osmdroid.events.MapListener
import org.osmdroid.events.ScrollEvent
import org.osmdroid.events.ZoomEvent
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker

/** Most online-map pins drawn at once; zooming in narrows the filter and shows the rest. */
private const val MAX_ONLINE_PINS = 250

/**
 * The Node Map. Renders OpenTopoMap tiles through osmdroid and drops a marker for
 * every node that has a position -- our own node and any contact whose last advert
 * carried coordinates. Tapping a contact marker opens that contact's detail screen.
 *
 * osmdroid is a plain Android [MapView], so it lives inside an [AndroidView] and its
 * lifecycle (resume/pause/detach) is driven off the composition's lifecycle owner.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun MapScreen(
    viewModel: MeshViewModel,
    overflow: OverflowNav,
    onOpenContact: (String) -> Unit = {},
    onOpenOnlineNode: (OnlineMapNode) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val dark = isSystemInDarkTheme()
    val primaryArgb = MaterialTheme.colorScheme.primary.toArgb()
    // The opaque colour behind an avatar in the app, so the tonal disc composites the
    // same on the map as it does on a contact row.
    val backingArgb = MaterialTheme.colorScheme.background.toArgb()
    val positions by viewModel.nodePositions.collectAsStateWithLifecycle()
    val onlineEnabled by viewModel.onlineNodesEnabled.collectAsStateWithLifecycle()
    val onlineNodes by viewModel.onlineMapNodes.collectAsStateWithLifecycle()
    val isLoadingOnline by viewModel.isLoadingOnlineNodes.collectAsStateWithLifecycle()

    // The repeater "PFP" is a cell-tower glyph, drawn white into the marker disc.
    val towerIcon = remember {
        ResourcesCompat.getDrawable(context.resources, R.drawable.ic_cell_tower, null)
    }

    // osmdroid needs a user agent set before it fetches its first tile, or the tile
    // servers reject the request. The package name is exactly what its docs ask for.
    val mapView = remember {
        Configuration.getInstance().userAgentValue = context.packageName
        MapView(context).apply {
            setTileSource(TileSourceFactory.OpenTopo)
            setMultiTouchControls(true)
            zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
            minZoomLevel = 2.0
            controller.setZoom(4.0)
            // A neutral world view until we know where the nodes are.
            controller.setCenter(GeoPoint(25.0, 0.0))
        }
    }

    // Drive the MapView's lifecycle and make sure its background threads stop with us.
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

    // Remembered so a recomposition (e.g. positions refreshing) doesn't yank the
    // camera back while the user is panning; we only auto-centre the very first fix.
    val centered = remember { booleanArrayOf(false) }

    // The online fetch can match thousands of nodes across the world; building a pin for
    // each would freeze the map. Pins are limited to the visible viewport (re-filtered
    // shortly after each pan/zoom via this tick) and their bitmaps cached per fetch.
    var viewportTick by remember { mutableIntStateOf(0) }
    DisposableEffect(mapView) {
        val listener = DelayedMapListener(object : MapListener {
            override fun onScroll(event: ScrollEvent?): Boolean {
                viewportTick++
                return false
            }

            override fun onZoom(event: ZoomEvent?): Boolean {
                viewportTick++
                return false
            }
        }, 250)
        mapView.addMapListener(listener)
        onDispose { mapView.removeMapListener(listener) }
    }
    val onlineArtCache = remember(onlineNodes, dark) { HashMap<String, MarkerArt>() }

    Scaffold(
        modifier = modifier,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("Node Map") },
                actions = {
                    MeshTopBarActions(
                        onAdvert = viewModel::sendAdvert,
                        selfContactUri = viewModel::selfContactUri,
                        overflow = overflow,
                        onlineMap = OnlineMapToggle(enabled = onlineEnabled) {
                            viewModel.toggleOnlineNodes { message ->
                                Toast.makeText(context, message, Toast.LENGTH_LONG).show()
                            }
                        },
                    )
                },
            )
        },
    ) { innerPadding ->
        Box(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
            AndroidView(
                factory = { mapView },
                modifier = Modifier.fillMaxSize(),
                update = { view ->
                    viewportTick // read so a pan/zoom re-runs this block
                    view.overlays.clear()
                    val now = System.currentTimeMillis()
                    positions.forEach { node ->
                        view.overlays.add(
                            node.toMarker(view, dark, primaryArgb, backingArgb, towerIcon, now, onOpenContact),
                        )
                    }
                    if (onlineNodes.isNotEmpty()) {
                        val box = view.boundingBox
                            ?.takeIf { view.width > 0 }
                            ?.increaseByScale(1.3f)
                        var shown = 0
                        for (node in onlineNodes) {
                            if (shown >= MAX_ONLINE_PINS) break
                            if (box != null && !box.contains(node.latitude, node.longitude)) continue
                            view.overlays.add(
                                node.toOnlineMarker(
                                    view, dark, backingArgb, towerIcon, onlineArtCache, onOpenOnlineNode,
                                ),
                            )
                            shown++
                        }
                    }
                    if (!centered[0]) {
                        val focus = positions.firstOrNull { it.isSelf } ?: positions.firstOrNull()
                        if (focus != null) {
                            view.controller.setZoom(11.0)
                            view.controller.setCenter(GeoPoint(focus.latitude, focus.longitude))
                            centered[0] = true
                        }
                    }
                    view.invalidate()
                },
            )

            if (positions.isEmpty()) MapEmptyHint()
            // Quiet themed loader while the online list downloads — small, but on the same
            // rounded surface backdrop every other loading indicator in the app sits on.
            if (isLoadingOnline) {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.92f),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 12.dp),
                ) {
                    LoadingIndicator(
                        modifier = Modifier
                            .padding(8.dp)
                            .size(32.dp),
                    )
                }
            }
            MapAttribution(Modifier.align(Alignment.BottomStart))
        }
    }
}

/**
 * Builds the on-map pin for one node: a coloured disc carrying the node's "PFP"
 * (a companion's letter/emoji, a repeater's tower icon) with a rounded pill beneath
 * it showing the name and, on its right, how long ago the node was last heard --
 * matching the reference MeshCore app. Tapping a contact opens its detail screen.
 */
private fun NodePosition.toMarker(
    map: MapView,
    dark: Boolean,
    primaryArgb: Int,
    backingArgb: Int,
    towerIcon: Drawable?,
    now: Long,
    onOpenContact: (String) -> Unit,
): Marker = Marker(map).also { marker ->
    val tint = when {
        isSelf -> primaryArgb
        type == NodeType.CHAT -> avatarColor(id, dark).toArgb()
        else -> type.color().toArgb()
    }
    val tower = if (type == NodeType.REPEATER) towerIcon else null
    val ago = lastSeenEpochMs?.let { discoveryAgo(it, now) }
    val label = buildString {
        append(name.trim().ifBlank { "(unnamed)" }.take(24))
        if (ago != null) append("  •  ").append(ago)
    }
    val art = nodeMarkerBitmap(map, label, tint, avatarGlyph(name), tower, tonal = true, backingArgb)

    marker.position = GeoPoint(latitude, longitude)
    marker.icon = BitmapDrawable(map.resources, art.bitmap)
    // Anchor the geo point at the disc's centre; the pill hangs below it.
    marker.setAnchor(0.5f, art.anchorV)
    marker.infoWindow = null // the label is baked into the bitmap; no default bubble.
    marker.setOnMarkerClickListener { _, _ ->
        if (!isSelf) onOpenContact(id)
        true
    }
}

/**
 * The pin for a node fetched from the public online map (map.meshcore.io): the same
 * avatar disc a contact would get -- its colour seeded with the contact id the node
 * would have -- plus a green "online" dot on the disc and "name  •  online" in the
 * pill. Tapping it opens the unsaved node's contact menu.
 */
private fun OnlineMapNode.toOnlineMarker(
    map: MapView,
    dark: Boolean,
    backingArgb: Int,
    towerIcon: Drawable?,
    artCache: MutableMap<String, MarkerArt>,
    onOpen: (OnlineMapNode) -> Unit,
): Marker = Marker(map).also { marker ->
    // The bitmap is the expensive part of a pin, and an online node's look never changes
    // within one fetch — render it once and reuse it across viewport re-filters.
    val art = artCache.getOrPut(publicKeyHex) {
        val tint = if (type == NodeType.CHAT) avatarColor(contactId, dark).toArgb() else type.color().toArgb()
        val tower = if (type == NodeType.REPEATER) towerIcon else null
        val label = "${name.trim().ifBlank { "(unnamed)" }.take(24)}  •  online"
        nodeMarkerBitmap(
            map, label, tint, avatarGlyph(name), tower,
            tonal = true, backingArgb = backingArgb, onlineBadge = true,
        )
    }

    marker.position = GeoPoint(latitude, longitude)
    marker.icon = BitmapDrawable(map.resources, art.bitmap)
    marker.setAnchor(0.5f, art.anchorV)
    marker.infoWindow = null
    marker.setOnMarkerClickListener { _, _ ->
        onOpen(this)
        true
    }
}

/** A rendered marker plus the vertical anchor that puts the disc centre on the fix. */
internal class MarkerArt(val bitmap: Bitmap, val anchorV: Float)

/**
 * Draws one avatar disc + name pill. [tonal] gives the [NodeAvatar] look -- a soft
 * [tint]-at-22% wash over [backingArgb] with the glyph/icon in full [tint] -- while a
 * non-tonal disc is a solid [tint] with a white glyph, used to flag a picked repeater.
 */
internal fun nodeMarkerBitmap(
    map: MapView,
    label: String,
    tint: Int,
    glyph: String,
    towerIcon: Drawable?,
    tonal: Boolean,
    backingArgb: Int,
    /** Draws the small green "online" dot on the disc's bottom-right. */
    onlineBadge: Boolean = false,
): MarkerArt {
    val d = map.resources.displayMetrics.density
    val diameter = 40f * d
    val margin = 1f * d // a hair of transparent padding so the antialiased edge isn't clipped
    val circleOuter = diameter + margin * 2f
    val gap = 3f * d
    val radius = diameter / 2f
    val contentColor = if (tonal) tint else AndroidColor.WHITE

    val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = AndroidColor.WHITE
        textSize = 12f * d
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    val padH = 8f * d
    val padV = 4f * d
    val fm = labelPaint.fontMetrics
    val pillH = (fm.descent - fm.ascent) + padV * 2f
    val pillW = labelPaint.measureText(label) + padH * 2f

    val width = maxOf(circleOuter, pillW)
    val height = circleOuter + gap + pillH
    val bitmap = Bitmap.createBitmap(
        ceil(width).toInt().coerceAtLeast(1),
        ceil(height).toInt().coerceAtLeast(1),
        Bitmap.Config.ARGB_8888,
    )
    val canvas = Canvas(bitmap)
    val cx = width / 2f
    val circleCy = circleOuter / 2f

    if (tonal) {
        // Opaque backing then a 22% tint, so the disc matches the avatar on a contact row.
        canvas.drawCircle(cx, circleCy, radius, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = backingArgb })
        canvas.drawCircle(cx, circleCy, radius, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = tint
            alpha = 56 // 0.22 * 255
        })
    } else {
        canvas.drawCircle(cx, circleCy, radius, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = tint })
    }

    if (towerIcon != null) {
        val half = (diameter * 0.5f) / 2f
        towerIcon.setTint(contentColor)
        towerIcon.setBounds(
            (cx - half).toInt(), (circleCy - half).toInt(),
            (cx + half).toInt(), (circleCy + half).toInt(),
        )
        towerIcon.draw(canvas)
    } else if (glyph.isNotEmpty()) {
        val glyphPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = contentColor
            textAlign = Paint.Align.CENTER
            textSize = diameter * 0.42f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        val baseline = circleCy - (glyphPaint.descent() + glyphPaint.ascent()) / 2f
        canvas.drawText(glyph, cx, baseline, glyphPaint)
    }

    if (onlineBadge) {
        // Bottom-right of the disc (45° off centre), ringed with the backing colour so
        // the dot stays legible over any avatar tint.
        val badgeCx = cx + radius * 0.707f
        val badgeCy = circleCy + radius * 0.707f
        val badgeRadius = 4.5f * d
        canvas.drawCircle(badgeCx, badgeCy, badgeRadius + 1.5f * d, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = backingArgb
        })
        canvas.drawCircle(badgeCx, badgeCy, badgeRadius, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = NodeColors.Online.toArgb()
        })
    }

    val pillTop = circleOuter + gap
    val pillLeft = cx - pillW / 2f
    val pillRect = RectF(pillLeft, pillTop, pillLeft + pillW, pillTop + pillH)
    canvas.drawRoundRect(pillRect, pillH / 2f, pillH / 2f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xE6263238.toInt() // dark slate, ~90% opaque, like the reference app
    })
    labelPaint.textAlign = Paint.Align.CENTER
    canvas.drawText(label, cx, pillTop + padV - fm.ascent, labelPaint)

    return MarkerArt(bitmap, circleCy / height)
}

/** Compact "how long ago", e.g. "now", "12m", "7h", "2d" -- the marker's time badge. */
private fun discoveryAgo(epochMs: Long, now: Long): String {
    val diff = (now - epochMs).coerceAtLeast(0)
    return when {
        diff < 60_000L -> "now"
        diff < 3_600_000L -> "${diff / 60_000L}m"
        diff < 86_400_000L -> "${diff / 3_600_000L}h"
        else -> "${diff / 86_400_000L}d"
    }
}

@Composable
private fun MapEmptyHint() {
    Box(
        modifier = Modifier
            .padding(16.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Filled.LocationOff,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(12.dp))
            Text(
                text = "No node positions yet. Connect and let nodes advertise their " +
                    "location to see them here.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** OpenTopoMap's licence asks that its and OpenStreetMap's attribution stay visible. */
@Composable
private fun MapAttribution(modifier: Modifier = Modifier) {
    Text(
        text = "© OpenStreetMap contributors, SRTM · © OpenTopoMap (CC-BY-SA)",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurface,
        fontSize = 9.sp,
        fontWeight = FontWeight.Medium,
        modifier = modifier
            .padding(4.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.7f))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}
