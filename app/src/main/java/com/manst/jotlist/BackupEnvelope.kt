package com.manst.jotlist

/**
 * 备份信封：带格式标记与版本号，恢复时先验版本再写数据。
 * 纯数据 + 校验逻辑，不依赖 Android，便于 JVM 单测。
 */
internal const val BACKUP_FORMAT = "jotlist-backup"
internal const val BACKUP_VERSION = 1

internal data class BackupEnvelope(
    val format: String = BACKUP_FORMAT,
    val version: Int = BACKUP_VERSION,
    val appVersion: String,
    val exportedAt: Long,
    val counts: Map<String, Int>,
    /** 白名单键的 JSON 字符串（todo / notes / history / settings）。 */
    val data: String
)

internal sealed interface BackupValidation {
    data object Ok : BackupValidation
    data object NotBackup : BackupValidation
    data class UnsupportedVersion(val version: Int) : BackupValidation
}

internal fun validateBackup(format: String?, version: Int?): BackupValidation {
    if (format != BACKUP_FORMAT) return BackupValidation.NotBackup
    if (version == null) return BackupValidation.UnsupportedVersion(-1)
    return if (version in 1..BACKUP_VERSION) {
        BackupValidation.Ok
    } else {
        BackupValidation.UnsupportedVersion(version)
    }
}

/**
 * 导出白名单：只导出「用户数据 + 会影响外观的设置」。
 * 草稿键（note_draft_* / task_draft_*）属于本机会话状态，明确排除：
 * 把 A 机写了一半的草稿带到 B 机、恢复后弹「恢复草稿？」是打扰。
 */
internal val BACKUP_KEY_WHITELIST: Set<String> = setOf(
    KEY_TODO_LIST,
    KEY_NOTES,
    KEY_COMPLETION_HISTORY,
    KEY_APPEARANCE,
    KEY_UI_DENSITY,
    KEY_DND_ENABLED,
    KEY_DND_START_MINUTE,
    KEY_DND_END_MINUTE,
    "welcome_shown",
    KEY_NOTE_FORMAT_HELP_SHOWN,
    KEY_TRIAGE_DISMISSED_DAY
)

internal fun isBackupKeyAllowed(key: String): Boolean = key in BACKUP_KEY_WHITELIST
