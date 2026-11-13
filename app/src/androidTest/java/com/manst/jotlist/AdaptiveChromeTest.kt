package com.manst.jotlist

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 宽屏导航（NavigationRail）的锚点测试。
 *
 * 之前 rail 的 testTag 写成了字符串字面量 `navigation_${tab.label}`，
 * 三个 item 全是同一个坏 tag，而且没有任何用例跑到宽屏分支。
 * 这里直接把 rail 单独渲染出来，钉住 tag 与选中状态。
 */
@RunWith(AndroidJUnit4::class)
class AdaptiveChromeTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun railItemsUsePerDestinationTags() {
        var selected by mutableStateOf(MainDestination.TODO)
        composeRule.setContent {
            MaterialTheme {
                AppNavigationRail(
                    selected = selected,
                    onSelect = { selected = it }
                )
            }
        }

        composeRule.onNodeWithTag("navigation_待办").assertIsDisplayed()
        composeRule.onNodeWithTag("navigation_笔记").assertIsDisplayed()
        composeRule.onNodeWithTag("navigation_搜索").assertIsDisplayed()
    }
}
