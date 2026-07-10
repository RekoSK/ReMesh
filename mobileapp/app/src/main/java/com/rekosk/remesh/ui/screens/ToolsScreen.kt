package com.rekosk.remesh.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.ReceiptLong
import androidx.compose.material.icons.filled.Route
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp

/** Where each working tool goes. The map-backed ones have nowhere to go yet. */
data class ToolsNav(
    val onPathTrace: () -> Unit,
    val onPacketLog: () -> Unit,
    val onDiscoverNearby: () -> Unit,
    val onNoiseFloor: () -> Unit,
)

/**
 * The eight diagnostics the reference app offers.
 *
 * Three of them plot onto a map and one needs region support, neither of which this
 * app has yet; they are listed but disabled, so the menu still matches and nothing
 * silently goes missing.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ToolsScreen(onBack: () -> Unit, nav: ToolsNav) {
    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                title = { Text("Tools") },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 12.dp),
        ) {
            Spacer(Modifier.size(8.dp))
            ToolRow(
                icon = Icons.Filled.Route,
                title = "Path trace  •  Manual",
                subtitle = "Trace a path by entering repeaters from the list.",
                onClick = nav.onPathTrace,
            )
            ToolRow(
                icon = Icons.Filled.Map,
                title = "Path trace  •  Using map",
                subtitle = "Trace a path by picking repeaters on the map.",
                enabled = false,
            )
            ToolRow(
                icon = Icons.Filled.Wifi,
                title = "Signal coverage",
                subtitle = "Check coverage on the map.",
                enabled = false,
            )
            ToolRow(
                icon = Icons.Filled.Visibility,
                title = "Line of sight",
                subtitle = "Check line of sight on the map.",
                enabled = false,
            )
            ToolRow(
                icon = Icons.Filled.ReceiptLong,
                title = "Packet log",
                subtitle = "Watch a live log of received packets.",
                onClick = nav.onPacketLog,
            )
            ToolRow(
                icon = Icons.Filled.Explore,
                title = "Discover nearby nodes",
                subtitle = "Scan the mesh for nodes in direct range.",
                onClick = nav.onDiscoverNearby,
            )
            ToolRow(
                icon = Icons.Filled.Public,
                title = "Discover regions",
                subtitle = "Scan the mesh for surrounding regions.",
                enabled = false,
            )
            ToolRow(
                icon = Icons.Filled.GraphicEq,
                title = "Noise floor",
                subtitle = "Watch the noise floor in real time.",
                onClick = nav.onNoiseFloor,
            )
            Spacer(Modifier.size(16.dp))
        }
    }
}

@Composable
private fun ToolRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    enabled: Boolean = true,
    onClick: () -> Unit = {},
) {
    val contentColor =
        if (enabled) MaterialTheme.colorScheme.onSurface
        else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)

    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
            .clickable(enabled = enabled, onClick = onClick),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, contentDescription = null, tint = contentColor)
            Spacer(Modifier.width(20.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium, color = contentColor)
                Text(
                    text = if (enabled) subtitle else "$subtitle  (Coming soon)",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(
                        alpha = if (enabled) 1f else 0.5f,
                    ),
                )
            }
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = contentColor,
            )
        }
    }
}
