package com.manst.jotlist

import android.content.Context
import android.content.Intent
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 覆盖"从桌面小组件进入"的一次性启动指令。
 *
 * 之前这些指令在整个 Activity 生命周期里一直是 true，而切换底部 Tab 会重建对应界面，
 * 于是关掉的"添加任务"弹窗会在切回待办页时自己再弹出来。
 */
@RunWith(AndroidJUnit4::class)
class WidgetEntryTest {
    @get:Rule
    val composeRule = createEmptyComposeRule()

    private fun launchWithAddExtra(): ActivityScenario<MainActivity> {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        context.getSharedPreferences("todo_prefs", Context.MODE_PRIVATE)
            .edit()
            .clear()
            // 小组件场景发生在老用户身上，跳过首次启动引导页。
            .putBoolean(KEY_WELCOME_SHOWN, true)
            .commit()
        val intent = Intent(context, MainActivity::class.java)
            .putExtra(TodoWidgetProvider.EXTRA_OPEN_ADD_TODO, true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        return ActivityScenario.launch(intent)
    }

    @Test
    fun addDialogFromWidgetOpensOnceOnly() {
        launchWithAddExtra().use {
            composeRule.onNodeWithText("任务内容").assertIsDisplayed()
            composeRule.onNodeWithText("取消").performClick()
            composeRule.onNodeWithText("任务内容").assertDoesNotExist()

            composeRule.onNodeWithTag("navigation_笔记").performClick()
            composeRule.onNodeWithTag("navigation_待办").performClick()

            composeRule.onNodeWithText("任务内容").assertDoesNotExist()
            composeRule.onNodeWithTag("add_task").assertIsDisplayed()
        }
    }
}
