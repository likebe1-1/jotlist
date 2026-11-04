package com.manst.jotlist.ui.theme

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp

/**
 * 三把尺子：间距模数、圆角阶梯、动效时长。
 * 页面里不再出现一次性数值，全部从这里取。
 */
object JotlistSpacing {
    val xxs = 4.dp
    val xs = 8.dp
    val sm = 12.dp
    val md = 16.dp
    val lg = 24.dp
    val xl = 32.dp

    /** 浏览页（列表/设置/搜索）屏幕边距 */
    val screenGutter = 16.dp

    /** 编辑/阅读页屏幕边距 */
    val editorGutter = 20.dp

    /** 卡片内边距：16 × 12 */
    val cardPaddingH = 16.dp
    val cardPaddingV = 12.dp
}

object JotlistRadius {
    val chip = 8.dp
    val card = 12.dp
    val panel = 16.dp
    val dialog = 28.dp

    val chipShape = RoundedCornerShape(chip)
    val cardShape = RoundedCornerShape(card)
    val panelShape = RoundedCornerShape(panel)
}

object JotlistMotion {
    const val fast = 150
    const val base = 220
    const val slow = 320

    /** 统一的 standard easing，快慢只在时长上区分 */
    val standardEasing = CubicBezierEasing(0.2f, 0f, 0f, 1f)
}
