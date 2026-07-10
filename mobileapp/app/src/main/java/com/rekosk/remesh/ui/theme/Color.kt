package com.rekosk.remesh.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * Node-type accents. These sit outside the dynamic colour scheme on purpose:
 * a repeater has to read as "repeater" regardless of the user's wallpaper.
 */
object NodeColors {
    val Chat = Color(0xFF1E88E5)
    val Repeater = Color(0xFFF39C12)
    val Room = Color(0xFF9C27B0)
    val Sensor = Color(0xFFFF6D00)
    val Group = Color(0xFF00897B)
    val Public = Color(0xFF2E7D32)
}
