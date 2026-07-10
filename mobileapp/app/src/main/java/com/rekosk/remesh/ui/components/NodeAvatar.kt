package com.rekosk.remesh.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.CellTower
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.MeetingRoom
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.rekosk.remesh.data.model.NodeType
import com.rekosk.remesh.ui.theme.NodeColors

fun NodeType.color(): Color = when (this) {
    NodeType.CHAT -> NodeColors.Chat
    NodeType.REPEATER -> NodeColors.Repeater
    NodeType.ROOM -> NodeColors.Room
    NodeType.SENSOR -> NodeColors.Sensor
    NodeType.GROUP -> NodeColors.Group
}

fun NodeType.icon(): ImageVector = when (this) {
    NodeType.CHAT -> Icons.AutoMirrored.Filled.Chat
    NodeType.REPEATER -> Icons.Filled.CellTower
    NodeType.ROOM -> Icons.Filled.MeetingRoom
    NodeType.SENSOR -> Icons.Filled.Sensors
    NodeType.GROUP -> Icons.Filled.Groups
}

fun NodeType.label(): String = when (this) {
    NodeType.CHAT -> "Chat"
    NodeType.REPEATER -> "Repeater"
    NodeType.ROOM -> "Room"
    NodeType.SENSOR -> "Sensor"
    NodeType.GROUP -> "Group"
}

/**
 * The circular type badge in the contacts list. A blocked node keeps its type
 * colour but swaps the glyph for a "blocked" mark, matching the reference app.
 */
@Composable
fun NodeAvatar(
    type: NodeType,
    isBlocked: Boolean,
    modifier: Modifier = Modifier,
    size: Int = 48,
) {
    Box(
        modifier = modifier
            .size(size.dp)
            .clip(CircleShape)
            .background(type.color()),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = if (isBlocked) Icons.Filled.Block else type.icon(),
            contentDescription = type.label(),
            tint = if (isBlocked) Color(0xFFE53935) else Color.White,
            modifier = Modifier.size((size * 0.5f).dp),
        )
    }
}
