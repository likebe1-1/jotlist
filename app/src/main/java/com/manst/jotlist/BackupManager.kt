package com.manst.jotlist

import android.content.Context
import org.json.JSONArray
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
    // 先体检再动手：损坏的备份必须拒绝，而不是清空现有数据后报「成功 0 条」。
    validateBackupData(data)?.let { return RestoreOutcome.Failed(it) }

    // 1) 恢复前快照：写不出来就不恢复，避免替换后没有退路
    val snapshotRaw = buildBackupJson(context, "pre-restore")
    val snapshotWritten = runCatching {
        context.openFileOutput(SNAPSHOT_FILE, Context.MODE_PRIVATE).use { stream ->
            stream.write(snapshotRaw.toByteArray())
        }
    }.isSuccess
    if (!snapshotWritten) return RestoreOutcome.Failed("无法创建恢复前快照，已取消恢复")

    // 2) 取消旧闹钟，避免替换数据后旧 ID 的提醒继续响
    (loadTodoList(context) as? TodoListLoadResult.Loaded)?.items?.forEach {
        cancelReminder(context, it)
    }

    // 3) 整体替换：同步 commit，成功前不认为已经恢复
    if (!applyBackupData(context, data)) {
        return RestoreOutcome.Failed("写入本地数据失败，未做修改")
    }

    // 4) 写后校验：解析失败立刻回滚到恢复前快照，不留半损坏状态
    val restoredTodosResult = loadTodoList(context)
    val restoredNotesResult = loadNotes(context)
    val restoredHistoryResult = loadCompletionHistory(context)
    if (restoredTodosResult !is TodoListLoadResult.Loaded ||
        restoredNotesResult !is NotesLoadResult.Loaded ||
        restoredHistoryResult !is CompletionHistoryLoadResult.Loaded
    ) {
        rollbackToSnapshotRaw(context, snapshotRaw)
        return RestoreOutcome.Failed("备份数据无法解析，已回滚到恢复前状态")
    }

    // 5) 重置 ID 序列 -> 重排提醒（含重复任务的下一次恢复闹钟）-> 处理到点的重复任务
    val restoredTodos = restoredTodosResult.items
    reserveLoadedTodoIds(restoredTodos)
    reserveLoadedNoteIds(restoredNotesResult.items)
    restoredTodos.forEach { item ->
        scheduleReminder(context, item)
        item.resetAt?.let { scheduleResetReminder(context, item) }
    }
    sweepRecurringResets(context)

    // 6) 旧草稿已失效（数据整体换过），清掉避免误恢复
    clearAllEditorDrafts(context)

    // 7) 刷新两枚桌面组件 + 通知前台界面重载。
    //    不通知的话，列表还停在恢复前的内存态，下一次自动保存会把恢复结果覆盖掉。
    TodoWidgetProvider.refresh(context)
    NotesWidgetProvider.refresh(context)
    notifyTodoDataChanged(context)

    return RestoreOutcome.Success(
        RestoreSummary(
            todos = restoredTodos.size,
            notes = restoredNotesResult.items.size,
            history = restoredHistoryResult.timestamps.size
        )
    )
}

/** 恢复前的格式体检：core JSON 能解析、字段类型对得上，否则直接拒绝而不是覆盖现有数据。 */
private fun validateBackupData(data: JSONObject): String? {
    for (key in data.keys()) {
        if (!isBackupKeyAllowed(key)) continue
        // 恢复之后再校验 JSON 字符串是否真的能解析成数据对象
        val value = data.opt(key)
        val ok = when (key) {
            KEY_TODO_LIST -> value is String && backupTodosParse(value)
            KEY_NOTES -> value is String && backupNotesParse(value)
            KEY_COMPLETION_HISTORY -> value is String && backupHistoryParses(value)
            KEY_APPEARANCE, KEY_UI_DENSITY, KEY_TRIAGE_DISMISSED_DAY -> value is String
            KEY_DND_ENABLED, KEY_WELCOME_SHOWN, KEY_NOTE_FORMAT_HELP_SHOWN -> value is Boolean
            KEY_DND_START_MINUTE, KEY_DND_END_MINUTE -> value is Int
            else -> true
        }
        if (!ok) return "备份字段 $key 格式不正确，已取消恢复"
    }
    return null
}

private fun backupTodosParse(raw: String): Boolean = runCatching {
    val array = JSONArray(raw)
    for (index in 0 until array.length()) todoFromJson(array.getJSONObject(index))
}.isSuccess

private fun backupNotesParse(raw: String): Boolean = runCatching {
    val array = JSONArray(raw)
    for (index in 0 until array.length()) noteFromJson(array.getJSONObject(index))
}.isSuccess

private fun backupHistoryParses(raw: String): Boolean = runCatching {
    val array = JSONArray(raw)
    for (index in 0 until array.length()) array.getLong(index)
}.isSuccess

/** 只写白名单键；同步 commit 保证要么全部写成功、要么不改动。 */
private fun applyBackupData(context: Context, data: JSONObject): Boolean {
    val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    val editor = prefs.edit()
    BACKUP_KEY_WHITELIST.forEach { editor.remove(it) }
    // 恢复是整体替换：旧回收站属于上一份数据，留着会把已替换掉的条目再恢复回来
    editor.remove(KEY_TRASH)
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
    return editor.commit()
}

/** 回滚：把恢复前快照原样写回去（不再写快照/排提醒，避免递归）。 */
private fun rollbackToSnapshotRaw(context: Context, snapshotRaw: String) {
    val data = runCatching { JSONObject(snapshotRaw).optJSONObject("data") }.getOrNull() ?: return
    applyBackupData(context, data)
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

/** 滚动快照：保存前留两份轮转，供「从上一版恢复」使用。 */
private val SNAPSHOT_CANDIDATES = listOf(SNAPSHOT_NEW, SNAPSHOT_OLD)

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
    SNAPSHOT_CANDIDATES
        .map { java.io.File(context.filesDir, it) }
        .filter { it.exists() }
        .maxOfOrNull { it.lastModified() } ?: 0L

/** 是否存在「上一次恢复前」的快照（存在则可撤销那次恢复）。 */
internal fun hasPreRestoreSnapshot(context: Context): Boolean =
    java.io.File(context.filesDir, SNAPSHOT_FILE).exists()

/**
 * 撤销刚刚执行的那次恢复：把恢复前快照写回去。
 *
 * 恢复成功后界面会继续自动保存，滚动快照很快会被覆盖，所以恢复前的退路
 * 必须是独立文件 + 显式「撤销这次恢复」入口，而不是混进「从上一版恢复」。
 * 撤销成功后删除该文件，保证只可撤销一次。
 */
internal fun restorePreRestoreSnapshot(context: Context): RestoreOutcome {
    val file = java.io.File(context.filesDir, SNAPSHOT_FILE)
    val raw = runCatching { file.readText() }.getOrNull()
        ?: return RestoreOutcome.Failed("没有可撤销的恢复记录")
    val outcome = restoreBackupJson(context, raw)
    if (outcome is RestoreOutcome.Success) {
        runCatching { file.delete() }
    }
    return outcome
}

/** 从最近一份快照恢复（同样是整体替换，走 restoreBackupJson）。 */
internal fun restoreLatestSnapshot(context: Context): RestoreOutcome {
    // 按时间从新到旧尝试：最新一份损坏时回退到上一份，而不是直接报错没有退路。
    val files = SNAPSHOT_CANDIDATES
        .map { java.io.File(context.filesDir, it) }
        .filter { it.exists() }
        .sortedByDescending { it.lastModified() }
    if (files.isEmpty()) return RestoreOutcome.Failed("还没有可用的快照")
    var lastFailure: RestoreOutcome.Failed = RestoreOutcome.Failed("快照文件读取失败")
    for (file in files) {
        val raw = runCatching { file.readText() }.getOrNull() ?: continue
        when (val outcome = restoreBackupJson(context, raw)) {
            is RestoreOutcome.Success -> return outcome
            is RestoreOutcome.Failed -> lastFailure = outcome
        }
    }
    return lastFailure
}