package com.rekosk.remesh.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

/**
 * minSdk is 35, so dynamic color is always available -- there is no static
 * fallback palette. The app deliberately has no colours of its own beyond the
 * node-type accents in [NodeColors].
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ReMeshTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val colorScheme =
        if (darkTheme) dynamicDarkColorScheme(context).toOled()
        else dynamicLightColorScheme(context)

    MaterialExpressiveTheme(
        colorScheme = colorScheme,
        motionScheme = MotionScheme.expressive(),
        typography = Typography,
        content = content,
    )
}

/**
 * True black behind everything, so unlit OLED pixels stay off.
 *
 * Only the page-level roles go black. The `surfaceContainer*` roles keep their
 * dynamic tones, because cards, chat bubbles and the navigation bar rely on them
 * to separate from the page -- blacking those out too would collapse the whole
 * screen into one flat void.
 */
private fun ColorScheme.toOled(): ColorScheme = copy(
    background = Color.Black,
    surface = Color.Black,
    surfaceContainerLowest = Color.Black,
    surfaceDim = Color.Black,
)
