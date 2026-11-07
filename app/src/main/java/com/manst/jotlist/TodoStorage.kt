package com.manst.jotlist

import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.*
import androidx.compose.runtime.*
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicLong

internal val todoIdSequence = AtomicLong(System.currentTimeMillis())

internal fun nextTodoId(): Long =
    todoIdSequence.updateAndGet { previous ->
        maxOf(System.currentTimeMillis(), previous + 1L)
    }

// ===== SharedPreferences 持久化 =====
internal const val PREFS_NAME = "todo_prefs"
internal const val KEY_TODO_LIST = "todo_list_json"
internal const val KEY_APPEARANCE = "appearance_mode"
internal const val KEY_NOTES = "notes_json"
internal const val KEY_COMPLETION_HISTORY = "completion_history_json"
internal const val KEY_NOTE_FORMAT_HELP_SHOWN = "note_format_help_shown"
internal const val MAX_COMPLETION_HISTORY_ENTRIES = 2000
internal const val KEY_TRIAGE_DISMISSED_DAY = "triage_banner_dismissed_day"
internal const val STALE_UNDATED_MILLIS = 3L * 24L * 60L * 60L * 1000L
internal const val KEY_DND_ENABLED = "do_not_disturb_enabled"
internal const val KEY_DND_START_MINUTE = "do_not_disturb_start_minute"
internal const val KEY_DND_END_MINUTE = "do_not_disturb_end_minute"
internal const val DEFAULT_DND_START_MINUTE = 22 * 60
internal const val DEFAULT_DND_END_MINUTE = 8 * 60
internal const val KEY_UI_DENSITY = "ui_density"

/** 存储值上限：超过该长度的 JSON 视为损坏，避免解析时 OOM。 */
private const val MAX_STORED_JSON_CHARS = 8 * 1024 * 1024

/** 合理时间戳上限（2100-01-01），防御被篡改/损坏的备份写入极端值。 */
private const val MAX_REASONABLE_TIMESTAMP = 4102444800000L

private fun Long.saneTimestamp(): Long? = takeIf { it in 1L..MAX_REASONABLE_TIMESTAMP }

/**
 * 把到点的重复任务恢复成未完成。返回是否有改动。
 *
 * 应用启动/回到前台/定时都会调用；恢复出来的任务会顺手补上提醒。
 */
internal fun sweepRecurringResets(context: Context): Boolean {
    val loaded = loadTodoList(context) as? TodoListLoadResult.Loaded ?: return false
    val now = System.currentTimeMillis()
    var changed = false
    val updated = loaded.items.map { item ->
        val reset = item.resetIfDue(now)
        if (reset != null) {
            changed = true
            reset
        } else {
            item
        }
    }
    if (!changed) return false
    saveTodoList(context, updated)
    updated.filter { !it.isDone }.forEach { scheduleReminder(context, it) }
    return true
}

internal fun readUiDensity(context: Context): UiDensity {
    val stored = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        .getString(KEY_UI_DENSITY, null)
    return UiDensity.entries.firstOrNull { it.name == stored } ?: UiDensity.STANDARD
}

internal fun saveUiDensity(context: Context, density: UiDensity) {
    context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        .edit()
        .putString(KEY_UI_DENSITY, density.name)
        .apply()
}

/** 免打扰设置：开启后，窗口内的提醒推迟到窗口结束。 */
internal data class DoNotDisturbPreferences(
    val enabled: Boolean,
    val startMinute: Int,
    val endMinute: Int
)

internal fun readDoNotDisturb(context: Context): DoNotDisturbPreferences {
    val preferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    return DoNotDisturbPreferences(
        enabled = preferences.getBoolean(KEY_DND_ENABLED, false),
        startMinute = preferences.getInt(KEY_DND_START_MINUTE, DEFAULT_DND_START_MINUTE),
        endMinute = preferences.getInt(KEY_DND_END_MINUTE, DEFAULT_DND_END_MINUTE)
    )
}

internal fun saveDoNotDisturb(
    context: Context,
    enabled: Boolean,
    startMinute: Int,
    endMinute: Int
) {
    context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        .edit()
        .putBoolean(KEY_DND_ENABLED, enabled)
        .putInt(KEY_DND_START_MINUTE, startMinute)
        .putInt(KEY_DND_END_MINUTE, endMinute)
        .apply()
}

/** 单条待办 → JSON：主存储、回收站、备份共用同一份字段映射。 */
internal fun todoToJson(item: TodoItem): JSONObject = JSONObject().apply {
    put("id", item.id)
    put("text", item.text)
    put("isDone", item.isDone)
    put("createdAt", item.createdAt)
    put("completedAt", item.completedAt ?: JSONObject.NULL)
    put("plannedAt", item.plannedAt ?: JSONObject.NULL)
    put("reviewHiddenUntil", item.reviewHiddenUntil ?: JSONObject.NULL)
    put("focusDate", item.focusDate ?: JSONObject.NULL)
    put("resetAt", item.resetAt ?: JSONObject.NULL)
    put("dueAt", item.dueAt ?: JSONObject.NULL)
    put("category", item.category)
    put("priority", item.priority)
    put("reminderMinutesBefore", item.reminderMinutesBefore)
    put("isPinned", item.isPinned)
    put("recurrence", item.recurrence)
    put("recurrenceNextId", item.recurrenceNextId ?: JSONObject.NULL)
    put("linkedNoteId", item.linkedNoteId ?: JSONObject.NULL)
    put("subtasks", JSONArray().apply {
        item.subtasks.forEach { subtask ->
            put(JSONObject().apply {
                put("id", subtask.id)
                put("text", subtask.text)
                put("isDone", subtask.isDone)
            })
        }
    })
}

/** 单条待办 ← JSON：解析规则与主存储完全一致（含 -1 提醒哨兵）。 */
internal fun todoFromJson(obj: JSONObject): TodoItem {
    val id: Long = obj.getLong("id")
    return TodoItem(
        id = id,
        text = obj.getString("text"),
        isDone = obj.getBoolean("isDone"),
        createdAt = obj.optLong("createdAt", id),
        completedAt = if (obj.isNull("completedAt")) null
        else obj.optLong("completedAt").saneTimestamp(),
        plannedAt = if (obj.isNull("plannedAt")) null
        else obj.optLong("plannedAt").saneTimestamp(),
        reviewHiddenUntil = if (obj.isNull("reviewHiddenUntil")) null
        else obj.optLong("reviewHiddenUntil").saneTimestamp(),
        focusDate = if (obj.isNull("focusDate")) null
        else obj.optLong("focusDate").saneTimestamp(),
        resetAt = if (obj.isNull("resetAt")) null
        else obj.optLong("resetAt").saneTimestamp(),
        dueAt = if (obj.isNull("dueAt")) null
        else obj.optLong("dueAt").saneTimestamp(),
        category = obj.optString("category", ""),
        priority = obj.optInt("priority", 1).coerceIn(0, 2),
        reminderMinutesBefore = obj.optInt("reminderMinutesBefore", 0).coerceIn(-1, 60),
        isPinned = obj.optBoolean("isPinned", false),
        recurrence = Recurrence.entries
            .firstOrNull { it.name == obj.optString("recurrence") }?.name ?: "NONE",
        recurrenceNextId = if (obj.isNull("recurrenceNextId")) {
            null
        } else {
            obj.optLong("recurrenceNextId").takeIf { it > 0L }
        },
        linkedNoteId = if (obj.isNull("linkedNoteId")) null
        else obj.optLong("linkedNoteId").takeIf { it > 0L },
        subtasks = obj.optJSONArray("subtasks")?.let { subtasks ->
            List(subtasks.length()) { index ->
                val subtask = subtasks.getJSONObject(index)
                TodoSubtask(
                    id = subtask.optLong("id", nextTodoId()),
                    text = subtask.getString("text"),
                    isDone = subtask.optBoolean("isDone", false)
                )
            }
        }.orEmpty()
    )
}

internal fun saveTodoList(context: Context, list: List<TodoItem>) {
    val arr = JSONArray()
    list.forEach { item ->
        arr.put(JSONObject().apply {
            put("id", item.id)
            put("text", item.text)
            put("isDone", item.isDone)
            put("createdAt", item.createdAt)
            put("completedAt", item.completedAt ?: JSONObject.NULL)
            put("plannedAt", item.plannedAt ?: JSONObject.NULL)
            put("reviewHiddenUntil", item.reviewHiddenUntil ?: JSONObject.NULL)
            put("focusDate", item.focusDate ?: JSONObject.NULL)
            put("resetAt", item.resetAt ?: JSONObject.NULL)
            put("dueAt", item.dueAt ?: JSONObject.NULL)
            put("category", item.category)
            put("priority", item.priority)
            put("reminderMinutesBefore", item.reminderMinutesBefore)
            put("isPinned", item.isPinned)
            put("recurrence", item.recurrence)
            put("recurrenceNextId", item.recurrenceNextId ?: JSONObject.NULL)
            put("linkedNoteId", item.linkedNoteId ?: JSONObject.NULL)
            put("subtasks", JSONArray().apply {
                item.subtasks.forEach { subtask ->
                    put(JSONObject().apply {
                        put("id", subtask.id)
                        put("text", subtask.text)
                        put("isDone", subtask.isDone)
                    })
                }
            })
        })
    }
    context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        .edit()
        .putString(KEY_TODO_LIST, arr.toString())
        .apply()
}

internal fun loadTodoList(context: Context): TodoListLoadResult {
    val json = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        .getString(KEY_TODO_LIST, null) ?: return TodoListLoadResult.Loaded(emptyList())
    if (json.length > MAX_STORED_JSON_CHARS) return TodoListLoadResult.InvalidJson
    return try {
        val arr = JSONArray(json)
        TodoListLoadResult.Loaded(
            List(arr.length()) { i ->
                val obj = arr.getJSONObject(i)
                val id: Long = obj.getLong("id")
                val createdAt: Long = obj.optLong("createdAt", id)
                TodoItem(
                    id = id,
                    text = obj.getString("text"),
                    isDone = obj.getBoolean("isDone"),
                    createdAt = createdAt,
                    completedAt = if (obj.isNull("completedAt")) null
                    else obj.optLong("completedAt").saneTimestamp(),
                    plannedAt = if (obj.isNull("plannedAt")) null
                    else obj.optLong("plannedAt").saneTimestamp(),
                    reviewHiddenUntil = if (obj.isNull("reviewHiddenUntil")) null
                    else obj.optLong("reviewHiddenUntil").saneTimestamp(),
                    focusDate = if (obj.isNull("focusDate")) null
                    else obj.optLong("focusDate").saneTimestamp(),
                    resetAt = if (obj.isNull("resetAt")) null
                    else obj.optLong("resetAt").saneTimestamp(),
                    dueAt = if (obj.isNull("dueAt")) null
                    else obj.optLong("dueAt").saneTimestamp(),
                    category = obj.optString("category", ""),
                    priority = obj.optInt("priority", 1).coerceIn(0, 2),
                    // -1 是编辑器里「关闭提醒」的哨兵值，不能按 0..60 夹掉，
                    // 否则重新加载后「关闭」会悄悄变回「到期时提醒」。
                    reminderMinutesBefore = obj.optInt("reminderMinutesBefore", 0)
                        .coerceIn(-1, 60),
                    isPinned = obj.optBoolean("isPinned", false),
                    recurrence = Recurrence.entries
                        .firstOrNull { it.name == obj.optString("recurrence") }?.name ?: "NONE",
                    recurrenceNextId = if (obj.isNull("recurrenceNextId")) {
                        null
                    } else {
                        obj.optLong("recurrenceNextId").takeIf { it > 0L }
                    },
                    linkedNoteId = if (obj.isNull("linkedNoteId")) null
                    else obj.optLong("linkedNoteId").takeIf { it > 0L },
                    subtasks = obj.optJSONArray("subtasks")?.let { subtasks ->
                        List(subtasks.length()) { index ->
                            val subtask = subtasks.getJSONObject(index)
                            TodoSubtask(
                                id = subtask.optLong("id", nextTodoId()),
                                text = subtask.getString("text"),
                                isDone = subtask.optBoolean("isDone", false)
                            )
                        }
                    }.orEmpty()
                )
            }
        )
    } catch (_: Exception) {
        // JSONException 之外，损坏/被篡改的存储值还可能触发 ClassCastException 等运行时异常。
        TodoListLoadResult.InvalidJson
    }
}

internal fun loadNotes(context: Context): NotesLoadResult {
    val json = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        .getString(KEY_NOTES, null) ?: return NotesLoadResult.Loaded(emptyList())
    if (json.length > MAX_STORED_JSON_CHARS) return NotesLoadResult.Invalid
    return try {
        val array = JSONArray(json)
        NotesLoadResult.Loaded(
            List(array.length()) { index ->
                val item = array.getJSONObject(index)
                NoteItem(
                    id = item.getLong("id"),
                    title = item.getString("title"),
                    body = item.getString("body"),
                    updatedAt = item.optLong("updatedAt", item.getLong("id")),
                    createdAt = item.optLong(
                        "createdAt",
                        item.optLong("updatedAt", item.getLong("id"))
                    ),
                    tags = item.optJSONArray("tags")?.let { tags ->
                        List(tags.length()) { tagIndex -> tags.getString(tagIndex) }
                    }.orEmpty(),
                    embeddedTodos = item.optJSONArray("embeddedTodos")?.let { todos ->
                        List(todos.length()) { todoIndex ->
                            val embeddedTodo = todos.getJSONObject(todoIndex)
                            NoteInlineTodo(
                                id = embeddedTodo.optLong("id", nextTodoId()),
                                text = embeddedTodo.getString("text"),
                                isDone = embeddedTodo.optBoolean("isDone", false)
                            )
                        }
                    }.orEmpty(),
                    isMarkdown = item.optBoolean("isMarkdown", false)
                )
            }
        )
    } catch (_: Exception) {
        NotesLoadResult.Invalid
    }
}

internal fun saveNotes(context: Context, notes: List<NoteItem>) {
    val array = JSONArray()
    notes.forEach { note ->
        array.put(
            JSONObject().apply {
                put("id", note.id)
                put("title", note.title)
                put("body", note.body)
                put("updatedAt", note.updatedAt)
                put("createdAt", note.createdAt)
                put("isMarkdown", note.isMarkdown)
                put("tags", JSONArray(note.tags))
                put("embeddedTodos", JSONArray().apply {
                    note.embeddedTodos.forEach { embeddedTodo ->
                        put(JSONObject().apply {
                            put("id", embeddedTodo.id)
                            put("text", embeddedTodo.text)
                            put("isDone", embeddedTodo.isDone)
                        })
                    }
                })
            }
        )
    }
    context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        .edit()
        .putString(KEY_NOTES, array.toString())
        .apply()
}

internal fun loadCompletionHistory(context: Context): CompletionHistoryLoadResult {
    val json = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        .getString(KEY_COMPLETION_HISTORY, null)
        ?: return CompletionHistoryLoadResult.Loaded(emptyList())
    if (json.length > MAX_STORED_JSON_CHARS) return CompletionHistoryLoadResult.Invalid
    return try {
        val array = JSONArray(json)
        CompletionHistoryLoadResult.Loaded(List(array.length()) { index -> array.getLong(index) })
    } catch (_: Exception) {
        CompletionHistoryLoadResult.Invalid
    }
}

internal fun saveCompletionHistory(context: Context, history: List<Long>) {
    // 完成记录每次都会整串重写，长期使用后会越来越慢，只保留最近一段。
    val capped = history.takeLast(MAX_COMPLETION_HISTORY_ENTRIES)
    context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        .edit()
        .putString(KEY_COMPLETION_HISTORY, JSONArray(capped).toString())
        .apply()
}

internal fun reserveLoadedTodoIds(items: List<TodoItem>) {
    val largestId = items.flatMap { item ->
        listOfNotNull(item.id, item.recurrenceNextId) + item.subtasks.map { it.id }
    }.maxOrNull() ?: return
    todoIdSequence.updateAndGet { current -> maxOf(current, largestId) }
}

internal fun readAppearance(context: Context): AppearanceMode =
    AppearanceMode.entries.firstOrNull {
        it.name == context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_APPEARANCE, AppearanceMode.SYSTEM.name)
    } ?: AppearanceMode.SYSTEM

internal fun saveAppearance(context: Context, mode: AppearanceMode) {
    context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        .edit()
        .putString(KEY_APPEARANCE, mode.name)
        .apply()
}

