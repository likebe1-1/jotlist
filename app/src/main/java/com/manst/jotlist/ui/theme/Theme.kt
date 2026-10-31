package com.manst.jotlist.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColorScheme = darkColorScheme(
    primary = LollipopTealLight,
    secondary = LollipopIndigo,
    tertiary = LollipopTeal,
    background = LollipopDarkSurface,
    surface = LollipopDarkSurface
)

private val LightColorScheme = lightColorScheme(
    primary = LollipopIndigo,
    secondary = LollipopTeal,
    tertiary = LollipopIndigoDark,
    background = LollipopBackground,
    surface = Color.White
)

@Composable
fun MyApplicationTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}