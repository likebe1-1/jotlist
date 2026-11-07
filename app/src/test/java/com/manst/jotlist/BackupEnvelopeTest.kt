package com.manst.jotlist

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupEnvelopeTest {

    @Test
    fun `正确格式与版本通过校验`() {
        assertEquals(BackupValidation.Ok, validateBackup(BACKUP_FORMAT, 1))
    }

    @Test
    fun `格式不对直接判定不是备份文件`() {
        assertEquals(BackupValidation.NotBackup, validateBackup("something-else", 1))
        assertEquals(BackupValidation.NotBackup, validateBackup(null, 1))
    }

    @Test
    fun `未来版本拒载而不是猜着读`() {
        val result = validateBackup(BACKUP_FORMAT, BACKUP_VERSION + 1)
        assertTrue(result is BackupValidation.UnsupportedVersion)
        assertEquals(BACKUP_VERSION + 1, (result as BackupValidation.UnsupportedVersion).version)
    }

    @Test
    fun `缺失版本号也拒载`() {
        val result = validateBackup(BACKUP_FORMAT, null)
        assertTrue(result is BackupValidation.UnsupportedVersion)
    }

    @Test
    fun `白名单只放用户数据与外观设置`() {
        assertTrue(isBackupKeyAllowed(KEY_TODO_LIST))
        assertTrue(isBackupKeyAllowed(KEY_NOTES))
        assertTrue(isBackupKeyAllowed(KEY_COMPLETION_HISTORY))
        assertTrue(isBackupKeyAllowed(KEY_APPEARANCE))
        assertTrue(isBackupKeyAllowed(KEY_UI_DENSITY))
        assertTrue(isBackupKeyAllowed(KEY_DND_ENABLED))
    }

    @Test
    fun `草稿键与内部键不进备份`() {
        assertFalse(isBackupKeyAllowed("note_draft_42"))
        assertFalse(isBackupKeyAllowed("task_draft_new"))
        assertFalse(isBackupKeyAllowed(KEY_WIDGET_PALETTE))
    }
}
