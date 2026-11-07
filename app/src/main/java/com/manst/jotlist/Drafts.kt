package com.manst.jotlist

import android.content.Context
import org.json.JSONObject

/**
 * 草稿：编辑器里的中间状态，按「编辑会话」存 key，避免把 A 的草稿套到 B 上。
 *
 * - 新建：draft_note_new / draft_task_new（同一时间只有一个未保存的新建草稿）
 * - 编辑：draft_note_<id> / draft_task_<id>
 *
 * 草稿是本机会话状态：不进备份白名单，也不参与回收站。
 */
internal const val DRAFT_PREFIX_NOTE_NEW = "draft_note_new"
internal const val DRAFT_PREFIX_TASK_NEW = "draft_task_new"

internal fun noteDraftKey(noteId: Long?): String =
    if (noteId == null) DRAFT_PREFIX_NOTE_NEW else "draft_note_$noteId"

internal fun taskDraftKey(taskId: Long?): String =
    if (taskId == null) DRAFT_PREFIX_TASK_NEW else "draft_task_$taskId"

/**
 * 只有「草稿比已保存内容更新」才提示恢复：
 * - 新建笔记：只要有草稿就提示（草稿时间 > 0）
 * - 编辑已有笔记：草稿晚于该笔记的 updatedAt 才提示，否则说明草稿是旧的
 */
internal fun shouldOfferDraftRestore(
    draftSavedAt: Long,
    savedUpdatedAt: Long?,
    isNew: Boolean
): Boolean {
    if (draftSavedAt <= 0L) return false
    if (isNew) return true
    if (savedUpdatedAt == null) return true
    return draftSavedAt > savedUpdatedAt
}

private const val DRAFT_KEY_SAVED_AT = "saved_at"
private const val DRAFT_KEY_PAYLOAD = "payload"

internal data class EditorDraft(val savedAt: Long, val payload: JSONObject)

internal fun saveEditorDraft(context: Context, key: String, payload: JSONObject, now: Long = System.currentTimeMillis()) {
    val envelope = JSONObject().apply {
        put(DRAFT_KEY_SAVED_AT, now)
        put(DRAFT_KEY_PAYLOAD, payload)
    }
    context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        .edit()
        .putString(key, envelope.toString())
        .apply()
}

internal fun loadEditorDraft(context: Context, key: String): EditorDraft? {
    val raw = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        .getString(key, null) ?: return null
    return runCatching {
        val envelope = JSONObject(raw)
        EditorDraft(
            savedAt = envelope.optLong(DRAFT_KEY_SAVED_AT, 0L),
            payload = envelope.optJSONObject(DRAFT_KEY_PAYLOAD) ?: return null
        )
    }.getOrNull()
}

internal fun clearEditorDraft(context: Context, key: String) {
    context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        .edit()
        .remove(key)
        .apply()
}

/** 清空全部编辑器草稿（数据恢复后旧草稿就失效了）。 */
internal fun clearAllEditorDrafts(context: Context) {
    val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    val keys = prefs.all.keys.filter { it.startsWith("draft_") }
    if (keys.isEmpty()) return
    val editor = prefs.edit()
    keys.forEach { editor.remove(it) }
    editor.apply()
}