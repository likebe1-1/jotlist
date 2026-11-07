package com.manst.jotlist

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DraftsTest {

    @Test
    fun `新建草稿 key 与编辑草稿 key 不互相覆盖`() {
        assertEquals("draft_note_new", noteDraftKey(null))
        assertEquals("draft_note_42", noteDraftKey(42L))
        assertEquals("draft_task_new", taskDraftKey(null))
        assertEquals("draft_task_7", taskDraftKey(7L))
    }

    @Test
    fun `新建笔记只要有草稿就提示恢复`() {
        assertTrue(shouldOfferDraftRestore(draftSavedAt = 100L, savedUpdatedAt = null, isNew = true))
        assertTrue(shouldOfferDraftRestore(draftSavedAt = 100L, savedUpdatedAt = 999L, isNew = true))
    }

    @Test
    fun `草稿比已保存内容新才提示`() {
        assertTrue(shouldOfferDraftRestore(200L, 100L, isNew = false))
        assertFalse(shouldOfferDraftRestore(100L, 200L, isNew = false))
        assertFalse(shouldOfferDraftRestore(100L, 100L, isNew = false))
    }

    @Test
    fun `已保存内容时间缺失时提示恢复`() {
        assertTrue(shouldOfferDraftRestore(100L, null, isNew = false))
    }

    @Test
    fun `没有草稿时间不提示`() {
        assertFalse(shouldOfferDraftRestore(0L, null, isNew = true))
    }
}
