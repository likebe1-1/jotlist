package com.manst.jotlist.ui.theme

import androidx.compose.ui.graphics.Color

// 列表左侧优先级色条：比卡片底色更饱和，才能在浅色界面上一眼看到。
// 语义色已全部收敛到 Theme.kt 的 colorScheme，这里只保留这三个非语义色。
val PriorityStripeLow = Color(0xFF9AA5B1)
val PriorityStripeMedium = Color(0xFFF2A03D)
val PriorityStripeHigh = Color(0xFFE5534B)
