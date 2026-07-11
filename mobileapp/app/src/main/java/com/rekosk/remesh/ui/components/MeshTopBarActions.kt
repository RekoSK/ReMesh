package com.rekosk.remesh.ui.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.Box
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.Podcasts
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material.icons.filled.SettingsInputAntenna
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext

/** Where the overflow menu can take you. */
data class OverflowNav(
    val onAddContact: () -> Unit,
    val onDiscoverContacts: () -> Unit,
    val onMyContactCode: () -> Unit,
    val onTools: () -> Unit,
)

/**
 * The action row shared by Contacts, Channels and Map, so the three top bars
 * never drift apart. Left to right: Advert, overflow.
 *
 * Connecting to a node lives in the "Me" bottom-bar tab, not up here.
 * There is deliberately no refresh button -- refreshing is pull-to-refresh.
 */
@Composable
fun MeshTopBarActions(
    onAdvert: (flood: Boolean) -> Unit,
    selfContactUri: () -> String?,
    overflow: OverflowNav,
) {
    val context = LocalContext.current
    var advertMenuOpen by remember { mutableStateOf(false) }
    var overflowOpen by remember { mutableStateOf(false) }

    Box {
        IconButton(onClick = { advertMenuOpen = true }) {
            Icon(Icons.Filled.Podcasts, contentDescription = "Advert")
        }
        DropdownMenu(
            expanded = advertMenuOpen,
            onDismissRequest = { advertMenuOpen = false },
        ) {
            DropdownMenuItem(
                leadingIcon = { Icon(Icons.Filled.Sensors, contentDescription = null) },
                text = { Text("Advert  •  Zero hop") },
                onClick = {
                    advertMenuOpen = false
                    onAdvert(false)
                },
            )
            DropdownMenuItem(
                leadingIcon = { Icon(Icons.Filled.SettingsInputAntenna, contentDescription = null) },
                text = { Text("Advert  •  Flood") },
                onClick = {
                    advertMenuOpen = false
                    onAdvert(true)
                },
            )
            DropdownMenuItem(
                leadingIcon = { Icon(Icons.Filled.ContentCopy, contentDescription = null) },
                text = { Text("Advert  •  To clipboard") },
                onClick = {
                    advertMenuOpen = false
                    copySelfContact(context, selfContactUri())
                },
            )
        }
    }

    Box {
        IconButton(onClick = { overflowOpen = true }) {
            Icon(Icons.Filled.MoreVert, contentDescription = "More")
        }
        DropdownMenu(expanded = overflowOpen, onDismissRequest = { overflowOpen = false }) {
            OverflowItem(Icons.Filled.PersonAdd, "Add contact") {
                overflowOpen = false
                overflow.onAddContact()
            }
            OverflowItem(Icons.Filled.Explore, "Discover contacts") {
                overflowOpen = false
                overflow.onDiscoverContacts()
            }
            OverflowItem(Icons.Filled.QrCode2, "My contact code") {
                overflowOpen = false
                overflow.onMyContactCode()
            }
            OverflowItem(Icons.Filled.Build, "Tools") {
                overflowOpen = false
                overflow.onTools()
            }
        }
    }
}

@Composable
private fun OverflowItem(icon: ImageVector, label: String, onClick: () -> Unit) {
    DropdownMenuItem(
        leadingIcon = { Icon(icon, contentDescription = null) },
        text = { Text(label) },
        onClick = onClick,
    )
}

private fun copySelfContact(context: Context, uri: String?) {
    if (uri == null) {
        Toast.makeText(context, "Connect to a node first", Toast.LENGTH_SHORT).show()
        return
    }
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    // minSdk 35, so the system always shows its own copy confirmation. No toast here.
    clipboard.setPrimaryClip(ClipData.newPlainText("MeshCore contact", uri))
}
