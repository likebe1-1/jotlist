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
