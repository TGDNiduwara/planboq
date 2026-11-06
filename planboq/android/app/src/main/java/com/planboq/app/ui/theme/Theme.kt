package com.planboq.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Blueprint = Color(0xFF0B3D5C)
private val BlueprintLight = Color(0xFF3E6E8C)
private val Accent = Color(0xFFFFB300)
private val SurfaceLight = Color(0xFFF7F9FB)
private val SurfaceDark = Color(0xFF10181E)

private val LightScheme = lightColorScheme(
    primary = Blueprint,
    secondary = BlueprintLight,
    tertiary = Accent,
    background = SurfaceLight,
)

private val DarkScheme = darkColorScheme(
    primary = Color(0xFF8ECDDD),
    secondary = BlueprintLight,
    tertiary = Accent,
    background = SurfaceDark,
)

@Composable
fun PlanBoqTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkScheme else LightScheme,
        content = content,
    )
}
