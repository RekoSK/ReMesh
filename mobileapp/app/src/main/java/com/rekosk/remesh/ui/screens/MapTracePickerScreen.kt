package com.rekosk.remesh.ui.screens

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.drawable.BitmapDrawable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.LayersClear
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.res.ResourcesCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.rekosk.remesh.R
import com.rekosk.remesh.data.model.NodePosition
import com.rekosk.remesh.data.model.NodeType
import com.rekosk.remesh.ui.MeshViewModel
import com.rekosk.remesh.ui.components.color
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * Builds a trace path by tapping repeaters on the map. Each tap appends the repeater
 * to the ordered path (tapping again removes it); a numbered disc marks its position
 * and a directed line joins consecutive hops. The green check hands the selection to
 * [PathTraceScreen], which the map FAB there brings straight back to this screen.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MapTracePickerScreen(
    viewModel: MeshViewModel,
    onBack: () -> Unit,
    onConfirm: () -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val dark = isSystemInDarkTheme()
    val accentArgb = MaterialTheme.colorScheme.primary.toArgb()
    val backingArgb = MaterialTheme.colorScheme.background.toArgb()
    val positions by viewModel.nodePositions.collectAsStateWithLifecycle()
    val picks by viewModel.tracePicks.collectAsStateWithLifecycle()

    val towerIcon = remember {
        ResourcesCompat.getDrawable(context.resources, R.drawable.ic_cell_tower, null)
    }
    val mapView = remember {
        Configuration.getInstance().userAgentValue = context.packageName
        MapView(context).apply {
            setTileSource(TileSourceFactory.OpenTopo)
            setMultiTouchControls(true)
            zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
            minZoomLevel = 2.0
            controller.setZoom(5.0)
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
    val centered = remember { booleanArrayOf(false) }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            CenterAlignedTopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                title = {
                    Text(if (picks.isEmpty()) "Tap repeaters in order" else "${picks.size} selected")
                },
                actions = {
                    IconButton(onClick = viewModel::clearTracePicks, enabled = picks.isNotEmpty()) {
                        Icon(Icons.Filled.LayersClear, contentDescription = "Clear selection")
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
                    val density = view.resources.displayMetrics.density
                    view.overlays.clear()

                    val repeaters = positions.filter { it.type == NodeType.REPEATER }
                    val byId = repeaters.associateBy { it.id }
                    val ordered = picks.mapNotNull { byId[it] }

                    // The path line sits under the markers, with an arrowhead per leg.
                    if (ordered.size >= 2) {
                        val poly = Polyline(view).apply {
                            setPoints(ordered.map { GeoPoint(it.latitude, it.longitude) })
                            outlinePaint.color = accentArgb
                            outlinePaint.strokeWidth = 6f * density
                            outlinePaint.strokeCap = Paint.Cap.ROUND
                        }
                        view.overlays.add(poly)
                        for (i in 0 until ordered.size - 1) {
                            view.overlays.add(arrowMarker(view, ordered[i], ordered[i + 1], accentArgb, density))
                        }
                    }

                    repeaters.forEach { node ->
                        val order = picks.indexOf(node.id).takeIf { it >= 0 }?.plus(1)
                        view.overlays.add(
                            pickerMarker(view, node, order, accentArgb, backingArgb, towerIcon) {
                                viewModel.toggleTracePick(node.id)
                            },
                        )
                    }

                    if (!centered[0] && repeaters.isNotEmpty()) {
                        val focus = ordered.firstOrNull() ?: repeaters.first()
                        view.controller.setZoom(10.0)
                        view.controller.setCenter(GeoPoint(focus.latitude, focus.longitude))
                        centered[0] = true
                    }
                    view.invalidate()
                },
            )

            if (positions.none { it.type == NodeType.REPEATER }) {
                Text(
                    text = "No repeaters with a known location. Connect and let repeaters " +
                        "advertise their position first.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.align(Alignment.TopCenter).padding(16.dp),
                )
            }

            FloatingActionButton(
                onClick = onConfirm,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .navigationBarsPadding()
                    .padding(16.dp),
            ) {
                Icon(Icons.Filled.Check, contentDescription = "Use this path")
            }
        }
    }
}

/**
 * A repeater pin for the picker. Unpicked repeaters get the tonal tower avatar so they
 * match the rest of the app; a picked one is a solid accent disc with its 1-based order,
 * so the selection reads clearly against the tonal crowd.
 */
private fun pickerMarker(
    map: MapView,
    node: NodePosition,
    order: Int?,
    accentArgb: Int,
    backingArgb: Int,
    towerIcon: android.graphics.drawable.Drawable?,
    onTap: () -> Unit,
): Marker = Marker(map).also { marker ->
    val selected = order != null
    val tint = if (selected) accentArgb else node.type.color().toArgb()
    val glyph = order?.toString() ?: ""
    val tower = if (selected) null else towerIcon
    val art = nodeMarkerBitmap(map, node.name.take(24), tint, glyph, tower, tonal = !selected, backingArgb)

    marker.position = GeoPoint(node.latitude, node.longitude)
    marker.icon = BitmapDrawable(map.resources, art.bitmap)
    marker.setAnchor(0.5f, art.anchorV)
    marker.infoWindow = null
    marker.setOnMarkerClickListener { _, _ ->
        onTap()
        true
    }
}

/** A small triangle at a leg's midpoint, rotated to point from one hop to the next. */
private fun arrowMarker(
    map: MapView,
    from: NodePosition,
    to: NodePosition,
    colorArgb: Int,
    density: Float,
): Marker = Marker(map).also { marker ->
    marker.position = GeoPoint((from.latitude + to.latitude) / 2, (from.longitude + to.longitude) / 2)
    marker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
    marker.icon = BitmapDrawable(map.resources, arrowBitmap(colorArgb, density))
    marker.rotation = bearingDegrees(from, to).toFloat()
    marker.isFlat = true
    marker.infoWindow = null
    marker.setOnMarkerClickListener { _, _ -> false }
}

/** Initial bearing from [a] to [b] in degrees clockwise from north, for arrow rotation. */
private fun bearingDegrees(a: NodePosition, b: NodePosition): Double {
    val lat1 = Math.toRadians(a.latitude)
    val lat2 = Math.toRadians(b.latitude)
    val dLon = Math.toRadians(b.longitude - a.longitude)
    val y = sin(dLon) * cos(lat2)
    val x = cos(lat1) * sin(lat2) - sin(lat1) * cos(lat2) * cos(dLon)
    return (Math.toDegrees(atan2(y, x)) + 360.0) % 360.0
}

private fun arrowBitmap(colorArgb: Int, density: Float): Bitmap {
    val s = (20f * density).toInt().coerceAtLeast(1)
    val bitmap = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val path = Path().apply {
        moveTo(s / 2f, s * 0.08f)
        lineTo(s * 0.86f, s * 0.9f)
        lineTo(s * 0.14f, s * 0.9f)
        close()
    }
    canvas.drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = colorArgb
        style = Paint.Style.FILL
    })
    canvas.drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * density
    })
    return bitmap
}
