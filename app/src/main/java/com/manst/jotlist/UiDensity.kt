package com.manst.jotlist

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 待办列表的控件尺寸档位。
 *
 * 行高、勾选框、图标、字号统一由这一个枚举决定，避免各处各写一套数值；
 * 用户可以在设置的「外观」里切换紧凑 / 标准 / 宽松。
 */
internal enum class UiDensity(
    val label: String,
    val rowMinHeight: Dp,
    val controlSize: Dp,
    val glyphSize: Dp,
    val titleSize: TextUnit,
    val metaSize: TextUnit,
    val horizontalPadding: Dp,
    val verticalPadding: Dp
) {
    COMPACT("紧凑", 52.dp, 34.dp, 16.dp, 15.sp, 10.sp, 12.dp, 3.dp),
    STANDARD("标准", 64.dp, 40.dp, 18.dp, 16.sp, 11.sp, 14.dp, 6.dp),
    COMFORTABLE("宽松", 76.dp, 44.dp, 20.dp, 17.sp, 12.sp, 16.dp, 10.dp)
}
