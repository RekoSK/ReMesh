package com.rekosk.remesh.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
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
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rekosk.remesh.data.model.NodeType
import com.rekosk.remesh.ui.theme.NodeColors
import java.text.BreakIterator

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
 * A dozen readable, saturated avatar colours, tuned per theme: the light set is a
 * touch deeper so white glyphs stay legible on light cards, the dark set a touch
 * brighter for OLED black. Same index in both lists is the "same" colour.
 */
internal val AvatarPaletteLight = listOf(
    Color(0xFFE53935), Color(0xFFD81B60), Color(0xFF8E24AA), Color(0xFF5E35B1),
    Color(0xFF3949AB), Color(0xFF1E88E5), Color(0xFF039BE5), Color(0xFF00897B),
    Color(0xFF43A047), Color(0xFF7CB342), Color(0xFFFB8C00), Color(0xFFF4511E),
    Color(0xFF6D4C41), Color(0xFF546E7A),
)
internal val AvatarPaletteDark = listOf(
    Color(0xFFEF5350), Color(0xFFEC407A), Color(0xFFAB47BC), Color(0xFF7E57C2),
    Color(0xFF5C6BC0), Color(0xFF42A5F5), Color(0xFF29B6F6), Color(0xFF26A69A),
    Color(0xFF66BB6A), Color(0xFF9CCC65), Color(0xFFFFA726), Color(0xFFFF7043),
    Color(0xFF8D6E63), Color(0xFF78909C),
)

/** Number of colours in the avatar/point palette. */
const val PALETTE_SIZE = 14

/** A specific palette colour by index (wraps), for user-chosen point colours. */
fun avatarColorByIndex(index: Int, dark: Boolean): Color {
    val palette = if (dark) AvatarPaletteDark else AvatarPaletteLight
    return palette[((index % palette.size) + palette.size) % palette.size]
}

/**
 * Picks a stable palette colour for a node. The index is derived from the node's
 * id (its public key), so a given node always lands on the same colour across app
 * restarts without us having to persist anything — deterministic, not truly random,
 * yet spread across the palette. [dark] swaps in the theme-matched shade.
 */
fun avatarColor(seed: String, dark: Boolean): Color {
    val palette = if (dark) AvatarPaletteDark else AvatarPaletteLight
    val idx = ((seed.hashCode() % palette.size) + palette.size) % palette.size
    return palette[idx]
}

/**
 * The glyph shown on a companion's avatar: the first emoji found anywhere in the
 * name (an emoji "wins" over letters), otherwise the first character upper-cased.
 * BreakIterator keeps multi-code-point emoji (flags, skin tones, ZWJ sequences) whole.
 */
fun avatarGlyph(name: String): String {
    val trimmed = name.trim()
    if (trimmed.isEmpty()) return "?"
    val breaker = BreakIterator.getCharacterInstance().apply { setText(trimmed) }
    var start = breaker.first()
    var end = breaker.next()
    var firstCluster: String? = null
    while (end != BreakIterator.DONE) {
        val cluster = trimmed.substring(start, end)
        if (firstCluster == null) firstCluster = cluster
        if (isEmojiCluster(cluster)) return cluster
        start = end
        end = breaker.next()
    }
    return (firstCluster ?: "?").uppercase()
}

private fun isEmojiCluster(cluster: String): Boolean {
    if (cluster.isEmpty()) return false
    val cp = cluster.codePointAt(0)
    return cp in 0x1F000..0x1FAFF ||   // emoticons, pictographs, symbols & pictographs extended
        cp in 0x1F1E6..0x1F1FF ||      // regional indicators (flags)
        cp in 0x2600..0x27BF ||        // misc symbols + dingbats
        cp in 0x2B00..0x2BFF ||        // misc symbols & arrows (stars, etc.)
        cp == 0x203C || cp == 0x2049 || cp == 0x2122 || cp == 0x2139
}

/**
 * The circular contact badge. Companions (CHAT) show the first letter — or first
 * emoji — of their name over a per-node colour; every other node type keeps its
 * type icon and type colour so a repeater still reads as "repeater". A blocked node
 * keeps its colour but swaps the glyph for a "blocked" mark, matching the reference app.
 *
 * @param name node display name; needed to draw a companion's letter/emoji glyph.
 * @param colorSeed stable id (public key) used to pick the per-node colour.
 * @param isSelf draws this node in the system accent colour instead of a palette colour.
 */
@Composable
fun NodeAvatar(
    type: NodeType,
    isBlocked: Boolean,
    modifier: Modifier = Modifier,
    size: Int = 48,
    isFavorite: Boolean = false,
    name: String? = null,
    colorSeed: String? = null,
    isSelf: Boolean = false,
) {
    val showGlyph = type == NodeType.CHAT && !isBlocked && !name.isNullOrBlank()
    // Every node uses the same tonal badge as the channel list: a soft, translucent
    // tint behind a full-strength glyph or type icon, rather than white-on-solid.
    // Companions tint by their per-node colour, every other type by its type colour,
    // and my own node by the system accent. A blocked node keeps its solid badge.
    val tint = when {
        isSelf -> MaterialTheme.colorScheme.primary
        showGlyph -> avatarColor(colorSeed ?: name.orEmpty(), isSystemInDarkTheme())
        else -> type.color()
    }
    val background = if (isBlocked) type.color() else tint.copy(alpha = 0.22f)

    Box(modifier = modifier.size(size.dp)) {
        Box(
            modifier = Modifier
                .size(size.dp)
                .clip(CircleShape)
                .background(background),
            contentAlignment = Alignment.Center,
        ) {
            when {
                isBlocked -> Icon(
                    imageVector = Icons.Filled.Block,
                    contentDescription = type.label(),
                    tint = Color(0xFFE53935),
                    modifier = Modifier.size((size * 0.5f).dp),
                )
                showGlyph -> Text(
                    text = avatarGlyph(name),
                    color = tint,
                    fontSize = (size * 0.42f).sp,
                    fontWeight = FontWeight.SemiBold,
                )
                else -> Icon(
                    imageVector = type.icon(),
                    contentDescription = type.label(),
                    tint = tint,
                    modifier = Modifier.size((size * 0.5f).dp),
                )
            }
        }
        if (isFavorite) {
            // A star badge tucked into the bottom-left of the avatar.
            Box(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .size((size * 0.42f).dp)
                    .clip(CircleShape)
                    .background(Color(0xFF1B1B1B)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Filled.Star,
                    contentDescription = "Favourite",
                    tint = Color(0xFFFFC107),
                    modifier = Modifier.size((size * 0.3f).dp),
                )
            }
        }
    }
}
