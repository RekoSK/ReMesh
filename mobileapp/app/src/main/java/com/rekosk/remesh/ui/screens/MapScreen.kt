package com.rekosk.remesh.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Map
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.rekosk.remesh.ui.MeshViewModel
import com.rekosk.remesh.ui.components.MeshTopBarActions
import com.rekosk.remesh.ui.components.OverflowNav

/**
 * Placeholder. Rendering nodes on a real map needs a tile source -- either a
 * Google Maps API key or an OSM/MapLibre tile provider -- so the decision is
 * deferred rather than guessed at.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MapScreen(
    viewModel: MeshViewModel,
    onOpenConnect: () -> Unit,
    overflow: OverflowNav,
    modifier: Modifier = Modifier,
) {
    val isConnected by viewModel.isRadioConnected.collectAsStateWithLifecycle()

    Scaffold(
        modifier = modifier,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("Node Map") },
                actions = {
                    MeshTopBarActions(
                        isConnected = isConnected,
                        onOpenConnect = onOpenConnect,
                        onAdvert = viewModel::sendAdvert,
                        selfContactUri = viewModel::selfContactUri,
                            overflow = overflow,
                    )
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(32.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(
                imageVector = Icons.Filled.Map,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(72.dp),
            )
            Text(
                text = "Coming soon...",
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.padding(top = 20.dp),
            )
            Text(
                text = "Node positions, repeater coverage and path tracing will live here " +
                    "once a map tile provider is wired up.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}
