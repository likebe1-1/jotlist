package com.manst.jotlist

/**
 * 回收站：独立存储，主列表（todo_list_json / notes_json）只保留活数据。
 * 这样小组件、搜索、统计都读不到已删数据，不需要在每处补过滤。
 */
internal enum class TrashKind { TODO, NOTE }

internal data class TrashEntry(
    val kind: TrashKind,
    val id: Long,
    /** 被删除对象的原始 JSON，恢复时原样写回主列表。 */
    val payload: String,
    val deletedAt: Long,
    /**
     * 删除笔记时被「连累」的待办：它们原本的 linkedNoteId 被清空，
     * 恢复这篇笔记时要把这些关系补回去。
     */
    val affectedTodoIds: List<Long> = emptyList()
)

/** 回收站保留天数：超过就由启动清理任务物理删除。 */
internal const val TRASH_TTL_DAYS = 30

private const val MILLIS_PER_DAY = 24L * 60L * 60L * 1000L

internal fun isTrashExpired(
    entry: TrashEntry,
    now: Long,
    ttlDays: Int = TRASH_TTL_DAYS
): Boolean = now - entry.deletedAt > ttlDays * MILLIS_PER_DAY

/** 返回仍然有效（未过期）的条目，供启动时清理。 */
internal fun pruneTrash(
    entries: List<TrashEntry>,
    now: Long,
    ttlDays: Int = TRASH_TTL_DAYS
): List<TrashEntry> = entries.filterNot { isTrashExpired(it, now, ttlDays) }
