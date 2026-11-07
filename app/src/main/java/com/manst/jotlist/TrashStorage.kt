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