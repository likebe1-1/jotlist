package com.manst.jotlist

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 数据安全的设备端回归测试（v7.2 debug 发现的问题）。
 *
 * 这些用例覆盖「恢复/回收站」写路径的关键不变量：
 * - 损坏的备份必须被拒绝，不能清空现有数据后报成功；
 * - 合法备份恢复后数据可读、内容正确；
 * - 回收站恢复真的写回主列表并把条目移出回收站。
 * 之前的纯逻辑单测覆盖不到这些 Android 存储路径，两组界面级 bug 因此逃过了 CI。
 */
@RunWith(AndroidJUnit4::class)
class DataSafetyTest {

    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext

    @Before
    fun setUp() {
        prefs().edit().clear().commit()
    }

    @After
    fun tearDown() {
        prefs().edit().clear().commit()
    }

    private fun prefs() =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun todo(id: Long, text: String) =
        TodoItem(id = id, text = text, isDone = false)

    private fun backupOf(vararg items: TodoItem): String {
        saveTodoList(context, items.toList())
        return buildBackupJson(context, "test")
    }

    @Test
    fun corruptBackupIsRejectedWithoutTouchingData() {
        saveTodoList(context, listOf(todo(1L, "keep me")))
        val broken = JSONObject().apply {
            put("format", BACKUP_FORMAT)
            put("version", BACKUP_VERSION)
            put("data", JSONObject().put(KEY_TODO_LIST, "not-json"))
        }.toString()

        val outcome = restoreBackupJson(context, broken)

        assertTrue("损坏的备份必须失败", outcome is RestoreOutcome.Failed)
        val loaded = loadTodoList(context) as TodoListLoadResult.Loaded
        assertEquals(listOf("keep me"), loaded.items.map { it.text })
    }

    @Test
    fun missingDataIsRejected() {
        val outcome = restoreBackupJson(
            context,
            JSONObject().put("format", BACKUP_FORMAT).put("version", BACKUP_VERSION).toString()
        )
        assertTrue(outcome is RestoreOutcome.Failed)
    }

    @Test
    fun validBackupReplacesDataAndRemainsReadable() {
        val backup = backupOf(todo(10L, "from backup"))
        saveTodoList(context, listOf(todo(20L, "local")))

        val outcome = restoreBackupJson(context, backup)

        assertTrue("合法备份应恢复成功", outcome is RestoreOutcome.Success)
        val loaded = loadTodoList(context) as TodoListLoadResult.Loaded
        assertEquals(listOf("from backup"), loaded.items.map { it.text })
    }

    @Test
    fun trashRestoreWritesTodoAndEmptiesEntry() {
        val item = todo(30L, "restored from trash")
        saveTodoList(context, emptyList())
        appendTrash(
            context,
            TrashEntry(
                kind = TrashKind.TODO,
                id = item.id,
                payload = todoToJson(item).toString(),
                deletedAt = System.currentTimeMillis()
            )
        )
        assertEquals(1, loadTrash(context).size)

        val restored = restoreTodo(context, loadTrash(context).first())

        assertNotNull(restored)
        val loaded = loadTodoList(context) as TodoListLoadResult.Loaded
        assertTrue("恢复的待办应写回主列表", loaded.items.any { it.id == item.id })
        assertTrue("恢复后应从回收站移除", loadTrash(context).isEmpty())
    }
}
