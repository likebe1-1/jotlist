package com.manst.jotlist

import android.content.Context
import androidx.annotation.ColorRes
import androidx.annotation.DrawableRes

/**
 * 桌面组件的三套配色。颜色本身放在 values/palette_colors.xml 与 values-night/ 里，
 * 这里只负责把「用户选中的调色板」映射到具体资源。
 */
internal enum class WidgetPalette(
    val label: String,
    val hint: String,
    @param:DrawableRes val backgroundRes: Int,
    @param:DrawableRes val ringRes: Int,
    @param:ColorRes val todoAccentRes: Int,
    @param:ColorRes val noteAccentRes: Int
) {
    MIST(
        label = "雾蓝 · 青碧",
        hint = "冷静克制，默认配色",
        backgroundRes = R.drawable.widget_bg_mist,
        ringRes = R.drawable.widget_ring_mist,
        todoAccentRes = R.color.palette_mist_todo_accent,
        noteAccentRes = R.color.palette_mist_note_accent
    ),
    TERRACOTTA(
        label = "赤陶 · 琥珀",
        hint = "偏暖，像纸质手账",
        backgroundRes = R.drawable.widget_bg_terracotta,
        ringRes = R.drawable.widget_ring_terracotta,
        todoAccentRes = R.color.palette_terracotta_todo_accent,
        noteAccentRes = R.color.palette_terracotta_note_accent
    ),
    PINE(
        label = "松绿 · 石蓝",
        hint = "偏冷，效率工具的观感",
        backgroundRes = R.drawable.widget_bg_pine,
        ringRes = R.drawable.widget_ring_pine,
        todoAccentRes = R.color.palette_pine_todo_accent,
        noteAccentRes = R.color.palette_pine_note_accent
    );

    fun todoAccent(context: Context): Int = context.getColor(todoAccentRes)

    fun noteAccent(context: Context): Int = context.getColor(noteAccentRes)
}

internal const val KEY_WIDGET_PALETTE = "widget_palette"

internal fun readWidgetPalette(context: Context): WidgetPalette {
    val raw = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        .getString(KEY_WIDGET_PALETTE, null)
    return WidgetPalette.entries.firstOrNull { it.name == raw } ?: WidgetPalette.MIST
}

internal fun saveWidgetPalette(context: Context, palette: WidgetPalette) {
    context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        .edit()
        .putString(KEY_WIDGET_PALETTE, palette.name)
        .apply()
}
