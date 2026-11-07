package com.manst.jotlist

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TrashLogicTest {

    private fun todo(
        id: Long,
        text: String = "t$id",
        linkedNoteId: Long? = null,
        recurrenceNextId: Long? = null
    ) = TodoItem(
        id = id,
        text = text,
        linkedNoteId = linkedNoteId,
        recurrenceNextId = recurrenceNextId
    )

    @Test
    fun `删除前记录指向该笔记的待办`() {
        val todos = listOf(
            todo(1, linkedNoteId = 100),
            todo(2, linkedNoteId = 100),
            todo(3, linkedNoteId = 200),
            todo(4)
        )
        assertEquals(listOf(1L, 2L), todoIdsLinkedToNote(todos, 100))
        assertTrue(todoIdsLinkedToNote(todos, 999).isEmpty())
    }

    @Test
    fun `恢复笔记时把链接补回去`() {
        val todos = listOf(todo(1), todo(2), todo(3))
        val restored = relinkTodos(todos, noteId = 100, todoIds = listOf(1L, 3L))
        assertEquals(100L, restored.first { it.id == 1L }.linkedNoteId)
        assertNull(restored.first { it.id == 2L }.linkedNoteId)
        assertEquals(100L, restored.first { it.id == 3L }.linkedNoteId)
    }

    @Test
    fun `补链不会影响清单外的待办`() {
        val todos = listOf(todo(1, linkedNoteId = 200))
        val restored = relinkTodos(todos, noteId = 100, todoIds = listOf(2L))
        assertEquals(200L, restored.single().linkedNoteId)
    }

    @Test
    fun `指向已删除笔记的引用会被清空`() {
        val todos = listOf(todo(1, linkedNoteId = 999))
        val repaired = repairTodoReferences(todos, noteIds = setOf(100L))
        assertNull(repaired.single().linkedNoteId)
    }

    @Test
    fun `仍存在的引用保持不变`() {
        val todos = listOf(todo(1, linkedNoteId = 100))
        val repaired = repairTodoReferences(todos, noteIds = setOf(100L))
        assertEquals(100L, repaired.single().linkedNoteId)
    }

    @Test
    fun `重复链指向不存在的任务时断开`() {
        val todos = listOf(todo(1, recurrenceNextId = 42), todo(2, recurrenceNextId = 1))
        val repaired = repairTodoReferences(todos, noteIds = emptySet())
        assertNull(repaired.first { it.id == 1L }.recurrenceNextId)
        assertEquals(1L, repaired.first { it.id == 2L }.recurrenceNextId)
    }

    @Test
    fun `回收站条目超过 30 天后过期`() {
        val now = 1_800_000_000_000L
        val day = 24L * 60L * 60L * 1000L
        val fresh = TrashEntry(TrashKind.TODO, 1, "{}", now - 29 * day)
        val old = TrashEntry(TrashKind.NOTE, 2, "{}", now - 31 * day)
        assertFalse(isTrashExpired(fresh, now))
        assertTrue(isTrashExpired(old, now))
    }

    @Test
    fun `清理只保留未过期条目`() {
        val now = 1_800_000_000_000L
        val day = 24L * 60L * 60L * 1000L
        val entries = listOf(
            TrashEntry(TrashKind.TODO, 1, "{}", now - day),
            TrashEntry(TrashKind.TODO, 2, "{}", now - 40 * day)
        )
        assertEquals(listOf(1L), pruneTrash(entries, now).map { it.id })
    }
}
