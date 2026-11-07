package com.manst.jotlist

/**
 * 回收站的关系修复逻辑。纯函数、无 Android 依赖，JVM 单测直接覆盖。
 *
 * 为什么需要：linkedNoteId / recurrenceNextId 是跨对象引用。删除笔记时待办的
 * linkedNoteId 会被清空；恢复后如果不补，详情里「打开来源笔记」就永远消失。
 * 反过来，恢复一条指向已不存在对象的待办时，悬空引用必须清掉，否则点开是空白。
 */

/** 删除笔记前，记下有哪些待办指向它（恢复时按这个清单补回关系）。 */
internal fun todoIdsLinkedToNote(todos: List<TodoItem>, noteId: Long): List<Long> =
    todos.filter { it.linkedNoteId == noteId }.map { it.id }

/** 恢复笔记时，把之前被清空的 linkedNoteId 补回去。 */
internal fun relinkTodos(todos: List<TodoItem>, noteId: Long, todoIds: List<Long>): List<TodoItem> {
    if (todoIds.isEmpty()) return todos
    val targets = todoIds.toSet()
    return todos.map { todo ->
        if (todo.id in targets) todo.copy(linkedNoteId = noteId) else todo
    }
}

/**
 * 恢复条目之后统一体检：指向不存在对象的 linkedNoteId / recurrenceNextId 一律清空。
 * 重复链只要求「下一环存在」，不重建闭环——重建需要业务上下文，容易串链。
 */
internal fun repairTodoReferences(
    todos: List<TodoItem>,
    noteIds: Set<Long>
): List<TodoItem> {
    val todoIds = todos.map { it.id }.toSet()
    return todos.map { todo ->
        var fixed = todo
        todo.linkedNoteId?.let { linked ->
            if (linked !in noteIds) fixed = fixed.copy(linkedNoteId = null)
        }
        fixed.recurrenceNextId?.let { next ->
            if (next !in todoIds) fixed = fixed.copy(recurrenceNextId = null)
        }
        fixed
    }
}
