package com.rekosk.remesh.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.rekosk.remesh.data.model.Contact
import com.rekosk.remesh.data.model.NodeType
import com.rekosk.remesh.ui.screens.settings.FlatDropdown

/**
 * The path buffer is 64 bytes (`MAX_PATH_SIZE`), so the hop ceiling shrinks as the
 * per-hop hash grows: 64, 32, then 21 for 3-byte hashes.
 */
fun pathSizeLabel(bytes: Int): String = "$bytes-byte (max ${64 / bytes} hops)"

val PATH_SIZE_LABELS = listOf(pathSizeLabel(1), pathSizeLabel(2), pathSizeLabel(3))

/** A rounded surface card, the grouping used across the app's menus. */
@Composable
fun MenuCard(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = RoundedCornerShape(16.dp),
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
    ) {
        Column { content() }
    }
}

@Composable
fun MenuRowDivider() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
    )
}

/**
 * The path editor shared by Tools -> Path trace and a repeater's outgoing route: a
 * byte-size selector (overriding the mesh default) and a hash field hinting at the
 * `aa,bb,cc` format, with an X that clears the line. The screen hosting this card
 * provides the `+` FAB that opens [RepeaterPickerDialog].
 */
@Composable
fun PathInputCard(
    byteSize: Int,
    onByteSize: (Int) -> Unit,
    path: String,
    onPath: (String) -> Unit,
    enabled: Boolean = true,
) {
    MenuCard {
        FlatDropdown(
            label = "Path size",
            selected = pathSizeLabel(byteSize.coerceIn(1, 3)),
            options = PATH_SIZE_LABELS,
            onSelectIndex = { onByteSize(it + 1) },
            enabled = enabled,
        )
        MenuRowDivider()
        TextField(
            value = path,
            onValueChange = onPath,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Path") },
            placeholder = { Text("e.g: aa,bb,cc") },
            singleLine = true,
            enabled = enabled,
            trailingIcon = {
                IconButton(onClick = { onPath("") }, enabled = enabled) {
                    Icon(Icons.Filled.Close, contentDescription = "Clear path")
                }
            },
            colors = TextFieldDefaults.colors(
                focusedContainerColor = Color.Transparent,
                unfocusedContainerColor = Color.Transparent,
                disabledContainerColor = Color.Transparent,
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent,
                disabledIndicatorColor = Color.Transparent,
            ),
        )
    }
}

/** Appends a repeater hash to the comma-separated path, avoiding a leading comma. */
fun appendHash(path: String, hash: String): String =
    if (path.isBlank()) hash else "${path.trimEnd(',', ' ')},$hash"

/** The N-byte hash prefix taken from a contact id (`c:` + key-prefix hex). */
fun repeaterHashPrefix(contactId: String, byteSize: Int): String =
    contactId.removePrefix("c:").take(byteSize * 2)

/** The N-byte hash prefix of a contact, taken from its id (`c:` + key-prefix hex). */
fun Contact.hashPrefix(byteSize: Int): String = repeaterHashPrefix(id, byteSize)

/** Joins picked repeater ids into the `aa,bb,cc` path the trace command expects. */
fun tracePathHex(contactIds: List<String>, byteSize: Int): String =
    contactIds.joinToString(",") { repeaterHashPrefix(it, byteSize) }

/** Searchable list of saved repeaters; picking one hands back its N-byte prefix. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RepeaterPickerDialog(
    repeaters: List<Contact>,
    byteSize: Int,
    onDismiss: () -> Unit,
    onPick: (String) -> Unit,
) {
    var query by remember { mutableStateOf("") }
    val filtered = repeaters.filter { it.name.contains(query, ignoreCase = true) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Select repeater") },
        text = {
            Column {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("Search repeaters...") },
                    leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                    singleLine = true,
                )
                Spacer(Modifier.size(8.dp))
                LazyColumn(modifier = Modifier.heightIn(max = 320.dp)) {
                    items(filtered, key = { it.id }) { contact ->
                        val prefix = contact.hashPrefix(byteSize)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onPick(prefix) }
                                .padding(vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            NodeAvatar(
                                type = contact.type,
                                isBlocked = contact.isBlocked,
                                isFavorite = contact.isFavorite,
                                size = 36,
                            )
                            Spacer(Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(contact.name, style = MaterialTheme.typography.bodyLarge)
                                Text(
                                    text = prefix.uppercase(),
                                    style = MaterialTheme.typography.bodySmall,
                                    fontFamily = FontFamily.Monospace,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

/** Repeaters only, since a path is built from repeater hops. */
fun List<Contact>.repeatersOnly(): List<Contact> = filter { it.type == NodeType.REPEATER }
