package com.manst.jotlist

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** 回收站独立存储键：主列表只保留活数据，组件/搜索/统计不需要额外过滤。 */
internal const val KEY_TRASH = "trash_json"

internal fun loadTrash(context: Context): List<TrashEntry> {
    val json = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        .getString(KEY_TRASH, null) ?: return emptyList()
    return trashFromJson(json)
}

internal fun saveTrash(context: Context, entries: List<TrashEntry>) {
    context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        .edit()
        .putString(KEY_TRASH, trashToJson(entries))
        .apply()
}

internal fun trashToJson(entries: List<TrashEntry>): String {
    val array = JSONArray()
    entries.forEach { entry ->
        array.put(
            JSONObject().apply {
                put("kind", entry.kind.name)
                put("id", entry.id)
                put("payload", entry.payload)
                put("deletedAt", entry.deletedAt)
                put("affectedTodoIds", JSONArray(entry.affectedTodoIds))
            }
        )
    }
    return array.toString()
}

/** 单条损坏只丢这一条，不把整个回收站判成不可读。 */
internal fun trashFromJson(json: String): List<TrashEntry> {
    return try {
        val array = JSONArray(json)
        buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val kind = TrashKind.entries.firstOrNull { it.name == item.optString("kind") }
                    ?: continue
                val payload = item.optString("payload", "")
                if (payload.isBlank()) continue
                add(
                    TrashEntry(
                        kind = kind,
                        id = item.optLong("id", Long.MIN_VALUE),
                        payload = payload,
                        deletedAt = item.optLong("deletedAt", 0L),
                        affectedTodoIds = item.optJSONArray("affectedTodoIds")?.let { ids ->
                            List(ids.length()) { ids.optLong(it, Long.MIN_VALUE) }
                                .filter { it != Long.MIN_VALUE }
                        }.orEmpty()
                    )
                )
            }
        }.filter { it.id != Long.MIN_VALUE && it.deletedAt > 0L }
    } catch (_: Exception) {
        emptyList()
    }
}

/**
 * 直接从事前的主列表 JSON 里取出这一条原始对象作为 payload。
 * 不重新序列化，避免字段映射和 saveTodoList 漂移。
 */
internal fun captureTodoPayload(context: Context, todoId: Long): String? {
    val json = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        .getString(KEY_TODO_LIST, null) ?: return null
    return try {
        val array = JSONArray(json)
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            if (item.optLong("id", Long.MIN_VALUE) == todoId) return item.toString()
        }
        null
    } catch (_: Exception) {
        null
    }
}

/** 追加一条回收站记录（同 id 同类型只保留一条）。 */
internal fun appendTrash(context: Context, entry: TrashEntry) {
    val kept = loadTrash(context).filterNot { it.kind == entry.kind && it.id == entry.id }
    saveTrash(context, kept + entry)
}

/** 恢复或撤销时把某条从回收站拿掉。 */
internal fun removeTrashEntry(context: Context, kind: TrashKind, id: Long) {
    val kept = loadTrash(context).filterNot { it.kind == kind && it.id == id }
    saveTrash(context, kept)
}

/** 启动时清理过期条目。 */
internal fun pruneTrashNow(context: Context, now: Long = System.currentTimeMillis()) {
    val entries = loadTrash(context)
    if (entries.isEmpty()) return
    val kept = pruneTrash(entries, now)
    if (kept.size != entries.size) saveTrash(context, kept)
}
/** 笔记同样从原始 JSON 里取整条对象，避免二次序列化漂移。 */
internal fun captureNotePayload(context: Context, noteId: Long): String? {
    val json = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        .getString(KEY_NOTES, null) ?: return null
    return try {
        val array = JSONArray(json)
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            if (item.optLong("id", Long.MIN_VALUE) == noteId) return item.toString()
        }
        null
    } catch (_: Exception) {
        null
    }
}

/**
 * 从回收站恢复一条待办：写回主列表，并把悬空的 linkedNoteId / recurrenceNextId 清掉。
 * 返回恢复后的对象（失败返回 null），同时把该条移出回收站。
 */
internal fun restoreTodo(context: Context, entry: TrashEntry): TodoItem? {
    if (entry.kind != TrashKind.TODO) return null
    val item = runCatching { todoFromJson(JSONObject(entry.payload)) }.getOrNull() ?: return null
    val loaded = loadTodoList(context) as? TodoListLoadResult.Loaded ?: return null
    if (loaded.items.any { it.id == item.id }) {
        // 同 id 已在主列表里（比如恢复后又被同步回来），不重复插入
        removeTrashEntry(context, TrashKind.TODO, entry.id)
        return item
    }
    val noteIds = (loadNotes(context) as? NotesLoadResult.Loaded)
        ?.items?.map { it.id }?.toSet().orEmpty()
    saveTodoList(context, repairTodoReferences(loaded.items + item, noteIds))
    removeTrashEntry(context, TrashKind.TODO, entry.id)
    return item
}
/**
 * 从回收站恢复一篇笔记：写回笔记列表，并把删除时被清空的待办关系补回去。
 * 补链后统一做一次悬空引用体检。
 */
internal fun restoreNote(context: Context, entry: TrashEntry): NoteItem? {
    if (entry.kind != TrashKind.NOTE) return null
    val note = runCatching { noteFromJson(JSONObject(entry.payload)) }.getOrNull() ?: return null
    val loadedNotes = loadNotes(context) as? NotesLoadResult.Loaded ?: return null
    if (loadedNotes.items.none { it.id == note.id }) {
        saveNotes(context, loadedNotes.items + note)
    }
    if (entry.affectedTodoIds.isNotEmpty()) {
        val todos = (loadTodoList(context) as? TodoListLoadResult.Loaded)?.items.orEmpty()
        if (todos.isNotEmpty()) {
            val relinked = relinkTodos(todos, note.id, entry.affectedTodoIds)
            val noteIds = (loadNotes(context) as? NotesLoadResult.Loaded)
                ?.items?.map { it.id }?.toSet().orEmpty()
            saveTodoList(context, repairTodoReferences(relinked, noteIds))
        }
    }
    removeTrashEntry(context, TrashKind.NOTE, entry.id)
    return note
}

/** 永久删除一条（用户主动清空，不走 30 天）。 */
internal fun purgeTrashEntry(context: Context, entry: TrashEntry) {
    removeTrashEntry(context, entry.kind, entry.id)
}