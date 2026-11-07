package com.manst.jotlist

import android.content.Context
import org.json.JSONObject

/**
 * 备份 / 恢复：导出白名单键，恢复走「整体替换」。
 *
 * 恢复流程固定为：校验信封 → 写前快照 → 取消旧闹钟 → 覆盖写 → 重置 ID 序列
 * → 重排新闹钟 → 刷新两枚组件。少一步都会留下幽灵通知或断链。
 */
internal data class RestoreSummary(val todos: Int, val notes: Int, val history: Int)

internal sealed interface RestoreOutcome {
    data class Success(val summary: RestoreSummary) : RestoreOutcome
    data class Failed(val message: String) : RestoreOutcome
}

private const val SNAPSHOT_FILE = "restore-before-snapshot.json"
private const val KEY_LAST_BACKUP_AT = "last_backup_at"

/** 导出：白名单键原样打包进信封，草稿与内部键不会出现在这里。 */
internal fun buildBackupJson(context: Context, appVersion: String): String {
    val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    val data = JSONObject()
    BACKUP_KEY_WHITELIST.forEach { key ->
        when (val value = prefs.all[key]) {
            is String -> data.put(key, value)
            is Boolean -> data.put(key, value)
            is Int -> data.put(key, value)
            is Long -> data.put(key, value)
            is Float -> data.put(key, value)
            else -> Unit
        }
    }
    return JSONObject().apply {
        put("format", BACKUP_FORMAT)
        put("version", BACKUP_VERSION)
        put("appVersion", appVersion)
        put("exportedAt", System.currentTimeMillis())
        put(
            "counts",
            JSONObject().apply {
                put("todos", (loadTodoList(context) as? TodoListLoadResult.Loaded)?.items?.size ?: 0)
                put("notes", (loadNotes(context) as? NotesLoadResult.Loaded)?.items?.size ?: 0)
                put(
                    "history",
                    (loadCompletionHistory(context) as? CompletionHistoryLoadResult.Loaded)
                        ?.timestamps?.size ?: 0
                )
            }
        )
        put("data", data)
    }.toString()
}

internal fun restoreBackupJson(context: Context, raw: String): RestoreOutcome {
    val envelope = runCatching { JSONObject(raw) }.getOrNull()
        ?: return RestoreOutcome.Failed("不是有效的 JSON 文件")
    val hasVersion = envelope.has("version")
    val format = envelope.optString("format", "")
    when (val validation = validateBackup(format, envelope.optInt("version", -1).takeIf { hasVersion })) {
        BackupValidation.Ok -> Unit
        BackupValidation.NotBackup -> return RestoreOutcome.Failed("这不是 Jotlist 的备份文件")
        is BackupValidation.UnsupportedVersion ->
            return RestoreOutcome.Failed("备份版本 ${validation.version} 不受支持，请升级 App")
    }
    val data = envelope.optJSONObject("data")
        ?: return RestoreOutcome.Failed("备份缺少 data 字段")

    // 1) 恢复前快照：用户后悔时有退路
    runCatching {
        context.openFileOutput(SNAPSHOT_FILE, Context.MODE_PRIVATE).use { stream ->
            stream.write(buildBackupJson(context, "pre-restore").toByteArray())
        }
    }

    // 2) 取消旧闹钟，避免替换数据后旧 ID 的提醒继续响
    (loadTodoList(context) as? TodoListLoadResult.Loaded)?.items?.forEach {
        cancelReminder(context, it)
    }

    // 3) 整体替换白名单键
    val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    val editor = prefs.edit()
    BACKUP_KEY_WHITELIST.forEach { editor.remove(it) }
    data.keys().forEach { key ->
        if (!isBackupKeyAllowed(key)) return@forEach
        when (val value = data.opt(key)) {
            is String -> editor.putString(key, value)
            is Boolean -> editor.putBoolean(key, value)
            is Int -> editor.putInt(key, value)
            is Long -> editor.putLong(key, value)
            is Double -> editor.putFloat(key, value.toFloat())
            else -> Unit
        }
    }
    editor.apply()

    // 4) 重置 ID 序列 -> 重排提醒 -> 处理到点的重复任务
    val restoredTodos = (loadTodoList(context) as? TodoListLoadResult.Loaded)?.items.orEmpty()
    reserveLoadedTodoIds(restoredTodos)
    restoredTodos.forEach { scheduleReminder(context, it) }
    sweepRecurringResets(context)

    // 5) 旧草稿已失效（数据整体换过），清掉避免误恢复
    clearAllEditorDrafts(context)

    // 6) 刷新两枚桌面组件
    TodoWidgetProvider.refresh(context)
    NotesWidgetProvider.refresh(context)

    return RestoreOutcome.Success(
        RestoreSummary(
            todos = restoredTodos.size,
            notes = (loadNotes(context) as? NotesLoadResult.Loaded)?.items?.size ?: 0,
            history = (loadCompletionHistory(context) as? CompletionHistoryLoadResult.Loaded)
                ?.timestamps?.size ?: 0
        )
    )
}

internal fun markBackupExported(context: Context, at: Long = System.currentTimeMillis()) {
    context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        .edit()
        .putLong(KEY_LAST_BACKUP_AT, at)
        .apply()
}

internal fun lastBackupAt(context: Context): Long =
    context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        .getLong(KEY_LAST_BACKUP_AT, 0L)

private const val SNAPSHOT_NEW = "snapshot-new.json"
private const val SNAPSHOT_OLD = "snapshot-old.json"
private const val KEY_SNAPSHOT_AT = "snapshot_written_at"

/**
 * 滚动快照：每次保存前把「当前（即将被覆盖）的状态」留一份信封。
 * 轮转两份（new / old），并按 60 秒节流——笔记是逐字保存的，不能每次都写盘。
 */
internal fun writeRollingSnapshot(context: Context, force: Boolean = false) {
    val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    val now = System.currentTimeMillis()
    val last = prefs.getLong(KEY_SNAPSHOT_AT, 0L)
    if (!force && now - last < 60_000L) return
    val payload = runCatching { buildBackupJson(context, "snapshot") }.getOrNull() ?: return
    runCatching {
        val dir = context.filesDir
        val newFile = java.io.File(dir, SNAPSHOT_NEW)
        if (newFile.exists()) {
            newFile.copyTo(java.io.File(dir, SNAPSHOT_OLD), overwrite = true)
        }
        java.io.File(dir, SNAPSHOT_NEW).writeText(payload)
        prefs.edit().putLong(KEY_SNAPSHOT_AT, now).apply()
    }
}

/** 最近一份快照的写入时间（没有返回 0）。 */
internal fun latestSnapshotAt(context: Context): Long =
    listOf(SNAPSHOT_NEW, SNAPSHOT_OLD)
        .map { java.io.File(context.filesDir, it) }
        .filter { it.exists() }
        .maxOfOrNull { it.lastModified() } ?: 0L

/** 从最近一份快照恢复（同样是整体替换，走 restoreBackupJson）。 */
internal fun restoreLatestSnapshot(context: Context): RestoreOutcome {
    val file = listOf(SNAPSHOT_NEW, SNAPSHOT_OLD)
        .map { java.io.File(context.filesDir, it) }
        .filter { it.exists() }
        .maxByOrNull { it.lastModified() }
        ?: return RestoreOutcome.Failed("还没有可用的快照")
    val raw = runCatching { file.readText() }.getOrNull()
        ?: return RestoreOutcome.Failed("快照文件读取失败")
    return restoreBackupJson(context, raw)
}