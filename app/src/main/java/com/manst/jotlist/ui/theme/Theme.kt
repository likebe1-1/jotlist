package com.manst.jotlist.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// 色板与桌面组件对齐：雾蓝靛 #4C5FD5 + 青碧 #0E8C7C。
// 关键约束：海拔单调——越"浮"的表面越亮（浅色）或越亮（深色），
// 列表卡片固定 surfaceContainerLow，表单/设置类卡片高一档。

private val LightColorScheme = lightColorScheme(
    primary = Color(0xFF4C5FD5),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFE1E6FF),
    onPrimaryContainer = Color(0xFF101C4E),

    secondary = Color(0xFF0E8C7C),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFC9EFE7),
    onSecondaryContainer = Color(0xFF06382F),

    tertiary = Color(0xFF7B5BD6),
    onTertiary = Color(0xFFFFFFFF),

    background = Color(0xFFF7F8FC),
    onBackground = Color(0xFF14161F),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF14161F),
    surfaceVariant = Color(0xFFE9ECF5),
    onSurfaceVariant = Color(0xFF5C6174),

    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF1F3F9),
    surfaceContainer = Color(0xFFECEEF5),
    surfaceContainerHigh = Color(0xFFE6E9F0),
    surfaceContainerHighest = Color(0xFFE1E4EC),

    outline = Color(0xFF868EA6),
    outlineVariant = Color(0xFFDCE1EC),

    error = Color(0xFFB3261E),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFF9DEDC),
    onErrorContainer = Color(0xFF410E0B)
)

private val DarkColorScheme = darkColorScheme(
    primary = Color(0xFF9AA8FF),
    onPrimary = Color(0xFF0F1430),
    primaryContainer = Color(0xFF33418F),
    onPrimaryContainer = Color(0xFFDDE1FF),

    secondary = Color(0xFF6FD8C6),
    onSecondary = Color(0xFF00302A),
    secondaryContainer = Color(0xFF005046),
    onSecondaryContainer = Color(0xFFB6F2E6),

    tertiary = Color(0xFFC8B6FF),
    onTertiary = Color(0xFF2A1B54),

    background = Color(0xFF10121A),
    onBackground = Color(0xFFE7E9F2),
    surface = Color(0xFF151823),
    onSurface = Color(0xFFE7E9F2),
    surfaceVariant = Color(0xFF2A2E3B),
    onSurfaceVariant = Color(0xFFA6ABC0),

    surfaceContainerLowest = Color(0xFF0C0E14),
    surfaceContainerLow = Color(0xFF1B1E28),
    surfaceContainer = Color(0xFF1F222D),
    surfaceContainerHigh = Color(0xFF292D39),
    surfaceContainerHighest = Color(0xFF343846),

    outline = Color(0xFF6A7185),
    outlineVariant = Color(0xFF3A3F4E),

    error = Color(0xFFF2B8B5),
    onError = Color(0xFF601410),
    errorContainer = Color(0xFF8C1D18),
    onErrorContainer = Color(0xFFF9DEDC)
)

@Composable
fun MyApplicationTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme

    MaterialTheme(
        colorScheme = colorScheme,
        typography = JotlistTypography,
        content = content
    )
}
