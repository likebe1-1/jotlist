package com.manst.jotlist

import android.content.Context
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import org.junit.runners.model.Statement

@RunWith(AndroidJUnit4::class)
class TodoUiTest {
    private val composeRule = createAndroidComposeRule<MainActivity>()

    /**
     * 每个用例开始前清空持久化数据。
     *
     * 之前所有用例共用一个安装的 SharedPreferences，第二次运行时"计划置顶"之类的
     * 文本会匹配到多个节点而直接抛异常，用例只能在全新的安装上跑一次。
     */
    private val clearStoredData = TestRule { base, _ ->
        object : Statement() {
            override fun evaluate() {
                InstrumentationRegistry.getInstrumentation().targetContext
                    .getSharedPreferences("todo_prefs", Context.MODE_PRIVATE)
                    .edit()
                    .clear()
                    // 首次启动引导页会挡住主界面，测试里默认视为已看过；
                    // 专门验证引导页的用例会自己把这个标记去掉。
                    .putBoolean(KEY_WELCOME_SHOWN, true)
                    .commit()
                base.evaluate()
            }
        }
    }

    @get:Rule
    val ruleChain: RuleChain = RuleChain.outerRule(clearStoredData).around(composeRule)

    @Test
    fun firstLaunchShowsWelcomeThenEntersApp() {
        // 去掉"已看过"标记并重建 Activity，等价于首次安装后启动。
        InstrumentationRegistry.getInstrumentation().targetContext
            .getSharedPreferences("todo_prefs", Context.MODE_PRIVATE)
            .edit()
            .remove(KEY_WELCOME_SHOWN)
            .commit()
        composeRule.activityRule.scenario.recreate()

        composeRule.onNodeWithText("待办 · 笔记 · 每天自动循环").assertIsDisplayed()
        composeRule.onNodeWithText("开始使用").performClick()
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithTag("add_task").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("add_task").assertExists()

        // 关掉之后不应再出现。
        composeRule.activityRule.scenario.recreate()
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithTag("add_task").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("add_task").assertExists()
        composeRule.onNodeWithText("开始使用").assertDoesNotExist()
    }

    @Test
    fun addTaskAndFilterItAsCompleted() {
        val taskName = "UI test task ${System.nanoTime()}"

        composeRule.onNodeWithTag("add_task").performClick()
        composeRule.onNodeWithTag("task_text_input").performTextInput(taskName)
        Espresso.closeSoftKeyboard()
        composeRule.onNodeWithTag("save_task").performClick()
        composeRule.onNodeWithText(taskName).assertIsDisplayed()

        composeRule.onNodeWithContentDescription("标记${taskName}为已完成").performClick()
        composeRule.onNodeWithTag("category_filter").performClick()
        composeRule.onNodeWithText("进行中").performClick()
        composeRule.onAllNodesWithText(taskName).assertCountEquals(0)

        composeRule.onNodeWithTag("category_filter").performClick()
        composeRule.onNodeWithText("已完成").performClick()
        composeRule.onNodeWithText(taskName).assertIsDisplayed()
    }

    @Test
    fun canCreateAndPinTask() {
        val taskName = "Pinned task ${System.nanoTime()}"

        composeRule.onNodeWithTag("add_task").performClick()
        composeRule.onNodeWithTag("task_text_input").performTextInput(taskName)
        Espresso.closeSoftKeyboard()
        composeRule.onNodeWithTag("pin_task").performClick()
        composeRule.onNodeWithTag("save_task").performClick()

        composeRule.onNodeWithText(taskName).assertIsDisplayed()
        composeRule.onNodeWithText("置顶").assertExists()
    }

    @Test
    fun completingRecurringTaskResetsInsteadOfCloning() {
        val taskName = "Recurring task ${System.nanoTime()}"

        composeRule.onNodeWithTag("add_task").performClick()
        composeRule.onNodeWithTag("task_text_input").performTextInput(taskName)
        Espresso.closeSoftKeyboard()
        composeRule.onNodeWithText("重复").performClick()
        composeRule.onNodeWithText("每天").performClick()
        composeRule.onNodeWithTag("subtask_input").performTextInput("First step")
        Espresso.closeSoftKeyboard()
        composeRule.onNodeWithTag("add_subtask").performClick()
        composeRule.onNodeWithTag("save_task").performClick()

        composeRule.onNodeWithText("每天", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("步骤 0/1", substring = true).assertIsDisplayed()
        composeRule.onNodeWithContentDescription("标记步骤First step为已完成").performClick()
        composeRule.onNodeWithText("步骤 1/1", substring = true).assertIsDisplayed()
        composeRule.onNodeWithContentDescription("标记步骤First step为未完成").performClick()
        composeRule.onNodeWithText("步骤 0/1", substring = true).assertIsDisplayed()

        composeRule.onNodeWithContentDescription("标记${taskName}为已完成").performClick()
        // 进行中里消失；不会复制出第二条。
        composeRule.onAllNodesWithText(taskName).assertCountEquals(0)

        composeRule.onNodeWithTag("category_filter").performClick()
        composeRule.onNodeWithText("已完成").performClick()
        composeRule.onAllNodesWithText(taskName).assertCountEquals(1)
    }

    @Test
    fun canOpenCalendarAndCompletionStatistics() {
        composeRule.onNodeWithContentDescription("更多选项").performClick()
        composeRule.onNodeWithText("日历视图").performClick()
        composeRule.onNodeWithText("日历").assertIsDisplayed()
        composeRule.onNodeWithText("选择日期：", substring = true).assertIsDisplayed()
        composeRule.onNode(hasContentDescription("返回") or hasText("返回")).performClick()

        composeRule.onNodeWithContentDescription("更多选项").performClick()
        composeRule.onNodeWithText("完成统计").performClick()
        composeRule.onNodeWithText("今日完成").assertIsDisplayed()
        composeRule.onNodeWithText("本周完成").assertIsDisplayed()
        composeRule.onNodeWithText("连续完成").assertIsDisplayed()
    }

    @Test
    fun canPlanExistingTaskForCalendarDayAndPinItToToday() {
        val taskName = "Planned task ${System.nanoTime()}"
        composeRule.onNodeWithTag("add_task").performClick()
        composeRule.onNodeWithTag("task_text_input").performTextInput(taskName)
        Espresso.closeSoftKeyboard()
        composeRule.onNodeWithTag("save_task").performClick()

        composeRule.onNodeWithContentDescription("更多选项").performClick()
        composeRule.onNodeWithText("日历视图").performClick()
        composeRule.onNodeWithTag("plan_tasks_for_date").performClick()
        composeRule.onNodeWithText(taskName).performClick()
        composeRule.onNodeWithText("保存计划").performClick()
        composeRule.onNodeWithText("计划置顶").assertIsDisplayed()
        composeRule.onNode(hasContentDescription("返回") or hasText("返回")).performClick()
        composeRule.onNodeWithText("今日计划").assertIsDisplayed()
    }

    @Test
    fun canCreateNoteFromNotesTab() {
        val noteTitle = "UI note ${System.nanoTime()}"

        composeRule.onNodeWithText("笔记").performClick()
        composeRule.onNodeWithTag("add_note").performClick()
        composeRule.onNodeWithTag("note_title_editor").performTextInput(noteTitle)
        Espresso.closeSoftKeyboard()
        composeRule.onNodeWithTag("note_body_editor").performTextInput("Saved note content")
        Espresso.closeSoftKeyboard()
        composeRule.onNodeWithTag("add_tag").performClick()
        composeRule.onNodeWithText("标签名称").performTextInput("work")
        Espresso.closeSoftKeyboard()
        composeRule.onNodeWithText("添加").performClick()
        composeRule.onNodeWithTag("toolbar_todo").performClick()
        composeRule.onNodeWithTag("note_todo_text_0").performTextInput("Call back")
        Espresso.closeSoftKeyboard()
        composeRule.onNodeWithTag("save_note").performClick()

        composeRule.onNodeWithText(noteTitle).assertIsDisplayed()
        composeRule.onNodeWithText("#work").assertIsDisplayed()
        // 列表现在会带一行正文摘要。
        composeRule.onNodeWithText("Saved note content", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText(noteTitle).performClick()
        composeRule.onNodeWithText("Saved note content").assertIsDisplayed()
        composeRule.onNodeWithText("Call back").assertIsDisplayed()
        composeRule.onNodeWithTag("note_more_options").performClick()
        composeRule.onNodeWithText("编辑").performClick()
        composeRule.onNodeWithTag("note_title_editor").assertIsDisplayed()
        composeRule.onNode(hasContentDescription("返回") or hasText("返回")).performClick()
        composeRule.onNodeWithText("Saved note content").assertIsDisplayed()
        composeRule.onNodeWithTag("note_more_options").performClick()
        composeRule.onNodeWithText("转为待办").performClick()
        composeRule.onNodeWithText(noteTitle).assertIsDisplayed()
        composeRule.onNodeWithContentDescription("任务操作").performClick()
        composeRule.onNodeWithText("打开来源笔记").performClick()
        composeRule.onNodeWithText("Saved note content").assertIsDisplayed()
    }

    @Test
    fun canQuickCaptureAndOpenDailyReviewAndTimeline() {
        composeRule.onNodeWithText("笔记").performClick()
        composeRule.onNodeWithTag("notes_more_options").performClick()
        composeRule.onNodeWithText("快速收集").performClick()
        composeRule.onNodeWithText("先记下来，稍后整理")
            .performTextInput("Remember to review this later")
            Espresso.closeSoftKeyboard()
        composeRule.onNodeWithText("收集").performClick()
        composeRule.onNodeWithText("Remember to review this later").performClick()
        composeRule.onNode(hasContentDescription("返回") or hasText("返回")).performClick()

        composeRule.onNodeWithTag("notes_more_options").performClick()
        composeRule.onNodeWithText("今日小结").performClick()
        // 分节标题是"今日完成 · N"，这里断言空状态文案更稳定。
        composeRule.onNodeWithText("今天还没有完成的待办").assertIsDisplayed()
        composeRule.onNodeWithText("今天的笔记 · 1").assertIsDisplayed()
        composeRule.onNode(hasContentDescription("返回") or hasText("返回")).performClick()
        composeRule.onNodeWithTag("notes_more_options").performClick()
        composeRule.onNodeWithText("时间线").performClick()
        composeRule.onNodeWithText("时间线").assertIsDisplayed()
    }

    @Test
    fun unifiedSearchFindsNotesAndTasksAndFiltersByType() {
        val phrase = "UnifiedSearch${System.nanoTime()}"
        val noteTitle = "Search note $phrase"
        val taskTitle = "Search task $phrase"

        composeRule.onNodeWithTag("add_task").performClick()
        composeRule.onNodeWithTag("task_text_input").performTextInput(taskTitle)
        Espresso.closeSoftKeyboard()
        composeRule.onNodeWithTag("save_task").performClick()
        composeRule.onNodeWithText("笔记").performClick()
        composeRule.onNodeWithTag("add_note").performClick()
        composeRule.onNodeWithTag("note_title_editor").performTextInput(noteTitle)
        Espresso.closeSoftKeyboard()
        composeRule.onNodeWithTag("note_body_editor").performTextInput("Body $phrase")
        Espresso.closeSoftKeyboard()
        composeRule.onNodeWithTag("save_note").performClick()
        composeRule.onNodeWithTag("navigation_搜索").performClick()

        composeRule.onNodeWithTag("unified_search_input").performTextInput(phrase)
        Espresso.closeSoftKeyboard()
        composeRule.onNodeWithText(noteTitle).assertIsDisplayed()
        composeRule.onNodeWithText(taskTitle).assertExists()
        composeRule.onNodeWithTag("search_filter_笔记").performClick()
        composeRule.onNodeWithText(noteTitle).assertIsDisplayed()
        composeRule.onAllNodesWithText(taskTitle).assertCountEquals(0)
        composeRule.onNodeWithTag("search_filter_待办").performClick()
        composeRule.onNodeWithText(taskTitle).assertExists()
        composeRule.onAllNodesWithText(noteTitle).assertCountEquals(0)
    }

    @Test
    fun canOpenTriagePageFromMenu() {
        composeRule.onNodeWithContentDescription("更多选项").performClick()
        composeRule.onNodeWithText("整理未完成任务").performClick()
        composeRule.onNodeWithText("整理未完成任务").assertIsDisplayed()
        composeRule.onNodeWithText("没有需要整理的任务。").assertIsDisplayed()
        composeRule.onNode(hasContentDescription("返回") or hasText("返回")).performClick()
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithTag("add_task").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("add_task").assertExists()
    }

    @Test
    fun canUnplanTaskFromCalendarDay() {
        val taskName = "Unplan task ${System.nanoTime()}"
        composeRule.onNodeWithTag("add_task").performClick()
        composeRule.onNodeWithTag("task_text_input").performTextInput(taskName)
        Espresso.closeSoftKeyboard()
        composeRule.onNodeWithTag("save_task").performClick()

        composeRule.onNodeWithContentDescription("更多选项").performClick()
        composeRule.onNodeWithText("日历视图").performClick()
        composeRule.onNodeWithTag("plan_tasks_for_date").performClick()
        composeRule.onNodeWithText(taskName).performClick()
        composeRule.onNodeWithText("保存计划").performClick()
        composeRule.onNodeWithText("计划置顶").assertIsDisplayed()

        composeRule.onNodeWithText("取消计划").performClick()
        composeRule.onNodeWithText("这一天没有安排待办").assertIsDisplayed()
    }

    @Test
    fun unplanIsReflectedWhenPickerReopens() {
        val taskName = "Unplan picker ${System.nanoTime()}"
        composeRule.onNodeWithTag("add_task").performClick()
        composeRule.onNodeWithTag("task_text_input").performTextInput(taskName)
        Espresso.closeSoftKeyboard()
        composeRule.onNodeWithTag("save_task").performClick()

        composeRule.onNodeWithContentDescription("更多选项").performClick()
        composeRule.onNodeWithText("日历视图").performClick()
        composeRule.onNodeWithTag("plan_tasks_for_date").performClick()
        composeRule.onNodeWithText(taskName).performClick()
        composeRule.onNodeWithText("保存计划").performClick()
        composeRule.onNodeWithText("计划置顶").assertIsDisplayed()

        composeRule.onNodeWithText("取消计划").performClick()
        composeRule.onNodeWithText("这一天没有安排待办").assertIsDisplayed()

        // 重新打开选择器直接保存：勾选状态必须已按当前数据刷新，不能把刚取消的计划又写回去。
        composeRule.onNodeWithTag("plan_tasks_for_date").performClick()
        composeRule.onNodeWithText("保存计划").performClick()
        composeRule.onNodeWithText("这一天没有安排待办").assertIsDisplayed()
        composeRule.onAllNodesWithText("计划置顶").assertCountEquals(0)
    }

    @Test
    fun noteWithoutInlineTodosStillShowsLinkedTodo() {
        val noteTitle = "Linked note ${System.nanoTime()}"

        composeRule.onNodeWithText("笔记").performClick()
        composeRule.onNodeWithTag("add_note").performClick()
        composeRule.onNodeWithTag("note_title_editor").performTextInput(noteTitle)
        Espresso.closeSoftKeyboard()
        composeRule.onNodeWithTag("note_body_editor").performTextInput("Body without inline todos")
        Espresso.closeSoftKeyboard()
        composeRule.onNodeWithTag("save_note").performClick()

        composeRule.onNodeWithText(noteTitle).performClick()
        composeRule.onNodeWithTag("note_more_options").performClick()
        composeRule.onNodeWithText("转为待办").performClick()
        composeRule.onNodeWithText("来自笔记", substring = true).assertIsDisplayed()

        // 回到笔记详情：即使这条笔记没有内联待办，"关联待办"也必须显示出来。
        composeRule.onNodeWithText("笔记").performClick()
        composeRule.onNodeWithText(noteTitle).performClick()
        composeRule.onNodeWithText("关联待办").assertIsDisplayed()
        composeRule.onNodeWithText("进行中").assertIsDisplayed()
    }

    @Test
    fun settingsPageOpensAndEnablesDoNotDisturb() {
        composeRule.onNodeWithContentDescription("更多选项").performClick()
        composeRule.onNodeWithText("设置").performClick()

        composeRule.onNodeWithText("外观").assertExists()
        composeRule.onNodeWithText("通知与提醒").assertExists()
        composeRule.onNodeWithText("免打扰").assertExists()
        composeRule.onNodeWithTag("settings_dnd_start").assertDoesNotExist()

        composeRule.onNodeWithTag("settings_dnd_switch").performScrollTo().performClick()
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithTag("settings_dnd_start").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("settings_dnd_start").assertExists()
        composeRule.onNodeWithTag("settings_dnd_end").assertExists()

        composeRule.onNode(hasContentDescription("返回") or hasText("返回")).performClick()
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithTag("add_task").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("add_task").assertExists()
    }

    @Test
    fun canPickTodayFocusTasks() {
        val taskName = "Focus task ${System.nanoTime()}"
        composeRule.onNodeWithTag("add_task").performClick()
        composeRule.onNodeWithTag("task_text_input").performTextInput(taskName)
        Espresso.closeSoftKeyboard()
        composeRule.onNodeWithTag("save_task").performClick()

        composeRule.onNodeWithTag("focus_pick").performClick()
        // 任务名同时出现在弹窗行和背后的列表里，只点可点击的那一行。
        composeRule.onNode(hasText(taskName) and hasAnyAncestor(isDialog())).performClick()
        composeRule.onNodeWithText("保存").performClick()

        composeRule.onNodeWithTag("focus_card").assertIsDisplayed()
        composeRule.onNodeWithText("调整").assertIsDisplayed()
        composeRule.onNodeWithText("先挑 3 件最重要的").assertDoesNotExist()
    }

    @Test
    fun canSwitchListDensityFromSettings() {
        val taskName = "Density task ${System.nanoTime()}"
        composeRule.onNodeWithTag("add_task").performClick()
        composeRule.onNodeWithTag("task_text_input").performTextInput(taskName)
        Espresso.closeSoftKeyboard()
        composeRule.onNodeWithTag("save_task").performClick()
        composeRule.onNodeWithText(taskName).assertIsDisplayed()

        composeRule.onNodeWithContentDescription("更多选项").performClick()
        composeRule.onNodeWithText("设置").performClick()
        composeRule.onNodeWithTag("settings_density_COMPACT").performClick()
        composeRule.onNodeWithTag("settings_density_COMFORTABLE").performClick()
        composeRule.onNode(hasContentDescription("返回") or hasText("返回")).performClick()

        // 换密度后列表照常可用。
        composeRule.onNodeWithText(taskName).assertIsDisplayed()
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithTag("add_task").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("add_task").assertExists()
    }

    @Test
    fun completingTaskWithSubtasksKeepsSingleRow() {
        val taskName = "Subtask task ${System.nanoTime()}"

        composeRule.onNodeWithTag("add_task").performClick()
        composeRule.onNodeWithTag("task_text_input").performTextInput(taskName)
        Espresso.closeSoftKeyboard()
        composeRule.onNodeWithTag("subtask_input").performTextInput("Step one")
        Espresso.closeSoftKeyboard()
        composeRule.onNodeWithTag("add_subtask").performClick()
        composeRule.onNodeWithTag("save_task").performClick()

        composeRule.onAllNodesWithText(taskName).assertCountEquals(1)
        composeRule.onAllNodesWithText("Step one").assertCountEquals(1)

        // 非重复任务完成后不应生成任何副本：进行中里消失，已完成里只有一条。
        composeRule.onNodeWithContentDescription("标记${taskName}为已完成").performClick()
        composeRule.onAllNodesWithText(taskName).assertCountEquals(0)

        composeRule.onNodeWithTag("category_filter").performClick()
        composeRule.onNodeWithText("已完成").performClick()
        composeRule.onAllNodesWithText(taskName).assertCountEquals(1)
        composeRule.onAllNodesWithText("Step one").assertCountEquals(1)
        composeRule.onNodeWithText("步骤 0/1", substring = true).assertIsDisplayed()
    }

    @Test
    fun markdownRenderingOnlyAppliesToNotesMarkedAsMarkdown() {
        val noteTitle = "Markdown note ${System.nanoTime()}"

        composeRule.onNodeWithText("笔记").performClick()
        composeRule.onNodeWithTag("add_note").performClick()
        composeRule.onNodeWithTag("note_title_editor").performTextInput(noteTitle)
        Espresso.closeSoftKeyboard()
        composeRule.onNodeWithTag("note_body_editor")
            .performTextInput("# Markdown heading\n\n**Bold text**\n\n- List item")
            Espresso.closeSoftKeyboard()
        composeRule.onNodeWithTag("toolbar_0").assertIsDisplayed()
        composeRule.onNodeWithTag("toolbar_1").assertIsDisplayed()
        composeRule.onNodeWithTag("toolbar_2").assertIsDisplayed()
        composeRule.onNodeWithTag("toolbar_todo").assertIsDisplayed()
        composeRule.onNodeWithTag("toolbar_todo").performClick()
        composeRule.onNodeWithTag("note_todo_text_0").performTextInput("Review item")
        Espresso.closeSoftKeyboard()
        composeRule.onNodeWithText("Review item").assertIsDisplayed()
        composeRule.onNodeWithTag("markdown_preview_mode").performClick()
        composeRule.onNodeWithText("Markdown heading").assertIsDisplayed()
        composeRule.onNodeWithTag("markdown_preview_mode").performClick()
        composeRule.onNodeWithTag("save_note").performClick()
        composeRule.onAllNodesWithText("Markdown heading").assertCountEquals(0)
        composeRule.onNodeWithText(noteTitle).performClick()

        composeRule.onNodeWithText("已启用排版").assertIsDisplayed()
        composeRule.onNodeWithText("Markdown heading").assertIsDisplayed()
        composeRule.onNodeWithText("Bold text").assertIsDisplayed()
        composeRule.onNodeWithText("• List item").assertIsDisplayed()
    }
}