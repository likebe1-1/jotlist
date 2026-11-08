package com.manst.jotlist

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.FormatBold
import androidx.compose.material.icons.filled.FormatListBulleted
import androidx.compose.material.icons.filled.Title
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.HelpOutline
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.animation.core.tween
import java.util.Calendar

@Composable
internal fun NotesScreen(
    modifier: Modifier = Modifier,
    initialOpenQuickCapture: Boolean = false,
    onInitialQuickCaptureConsumed: () -> Unit = {},
    onEditorVisibilityChange: (Boolean) -> Unit = {},
    openNoteId: Long? = null,
    onOpenNoteHandled: () -> Unit = {},
    onCreateLinkedTodo: (TodoItem) -> Boolean = { false },
    onOpenTodo: (Long) -> Unit = {}
) {
    val context = LocalContext.current
    val notes = remember { mutableStateListOf<NoteItem>() }
    val todos = remember { mutableStateListOf<TodoItem>() }
    var isLoaded by remember { mutableStateOf(false) }
    var canSave by remember { mutableStateOf(false) }
    var showInvalidDataWarning by remember { mutableStateOf(false) }
    var showEditor by remember { mutableStateOf(false) }
    var editorTarget by remember { mutableStateOf<NoteItem?>(null) }
    var detailTarget by remember { mutableStateOf<NoteItem?>(null) }
    var editingFromDetail by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<NoteItem?>(null) }
    var selectedTag by rememberSaveable { mutableStateOf<String?>(null) }
    var section by rememberSaveable { mutableStateOf(NotesSection.LIST) }
    var moreMenuExpanded by remember { mutableStateOf(false) }
    var showQuickCapture by rememberSaveable { mutableStateOf(false) }
    var quickCaptureText by rememberSaveable { mutableStateOf("") }
    var todoLoadWarning by remember { mutableStateOf(false) }

    LaunchedEffect(initialOpenQuickCapture) {
        if (initialOpenQuickCapture) {
            showQuickCapture = true
            onEditorVisibilityChange(true)
            onInitialQuickCaptureConsumed()
        }
    }

    LaunchedEffect(context) {
        when (val result = loadNotes(context)) {
            is NotesLoadResult.Loaded -> {
                notes.clear()
                notes.addAll(result.items)
                result.items.maxOfOrNull { it.id }?.let { largestId ->
                    todoIdSequence.updateAndGet { current -> maxOf(current, largestId) }
                }
                when (val todoResult = loadTodoList(context)) {
                    is TodoListLoadResult.Loaded -> todos.addAll(todoResult.items)
                    TodoListLoadResult.InvalidJson -> todoLoadWarning = true
                }
                isLoaded = true
                canSave = true
            }
            NotesLoadResult.Invalid -> {
                isLoaded = true
                showInvalidDataWarning = true
            }
        }
    }

    LaunchedEffect(canSave, notes.toList()) {
        if (canSave) {
            saveNotes(context, notes.toList())
            // 笔记有变化就刷新"笔记"桌面组件，待办组件不受影响。
            NotesWidgetProvider.refresh(context)
        }
    }

    LaunchedEffect(openNoteId, isLoaded) {
        val requestedId = openNoteId
        if (requestedId != null && isLoaded) {
            detailTarget = notes.firstOrNull { it.id == requestedId }
            if (detailTarget != null) onEditorVisibilityChange(true)
            onOpenNoteHandled()
        }
    }

    BackHandler(
        enabled = showEditor || detailTarget != null || section != NotesSection.LIST ||
            showQuickCapture
    ) {
        if (showEditor) {
            showEditor = false
            editorTarget = null
            if (!editingFromDetail) onEditorVisibilityChange(false)
            editingFromDetail = false
        } else if (showQuickCapture) {
            showQuickCapture = false
            quickCaptureText = ""
            onEditorVisibilityChange(false)
        } else if (detailTarget != null) {
            detailTarget = null
            onEditorVisibilityChange(false)
        } else {
            section = NotesSection.LIST
        }
    }

    if (showQuickCapture) {
        // 弹窗打开即聚焦输入框，省一次点击（widget / 通知进入快速收集时尤其需要）。
        val quickCaptureFocus = remember { FocusRequester() }
        LaunchedEffect(Unit) { runCatching { quickCaptureFocus.requestFocus() } }
        AlertDialog(
            onDismissRequest = {
                showQuickCapture = false
                quickCaptureText = ""
                onEditorVisibilityChange(false)
            },
            title = { Text("快速收集") },
            text = {
                OutlinedTextField(
                    value = quickCaptureText,
                    onValueChange = {
                        quickCaptureText = it
                    },
                    label = { Text("先记下来，稍后整理") },
                    minLines = 3,
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(quickCaptureFocus)
                )
                if (!canSave) {
                    Text(
                        "笔记数据暂时无法读取，现在收集的内容不会被保存。",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = quickCaptureText.isNotBlank() && canSave,
                    onClick = {
                        val text = quickCaptureText.trim()
                        val now = System.currentTimeMillis()
                        val note = NoteItem(
                            title = text.lineSequence().first().take(36)
                                .ifBlank { "快速收集" },
                            body = text,
                            createdAt = now,
                            updatedAt = now,
                            tags = listOf("收集箱")
                        )
                        notes.add(0, note)
                        showQuickCapture = false
                        quickCaptureText = ""
                        onEditorVisibilityChange(false)
                    }
                ) { Text("收集") }
            },
            dismissButton = {
                TextButton(onClick = {
                    showQuickCapture = false
                    quickCaptureText = ""
                    onEditorVisibilityChange(false)
                }) { Text("取消") }
            }
        )
    }

    if (showEditor) {
        NoteEditorScreen(
            modifier = modifier,
            initialNote = editorTarget,
            onDismiss = {
                showEditor = false
                editorTarget = null
                if (!editingFromDetail) onEditorVisibilityChange(false)
                editingFromDetail = false
            },
            onSave = { note ->
                val index = notes.indexOfFirst { it.id == note.id }
                if (index >= 0) notes[index] = note else notes.add(0, note)
                showEditor = false
                editorTarget = null
                if (editingFromDetail) {
                    detailTarget = note
                } else {
                    onEditorVisibilityChange(false)
                }
                editingFromDetail = false
            }
        )
        return
    }

    detailTarget?.let { note ->
        val linkedTodos = todos.filter { it.linkedNoteId == note.id }
        NoteDetailScreen(
            modifier = modifier,
            note = note,
            linkedTodos = linkedTodos,
            onBack = {
                detailTarget = null
                onEditorVisibilityChange(false)
            },
            onEdit = {
                editorTarget = note
                editingFromDetail = true
                showEditor = true
            },
            onCreateTodo = {
                val todo = TodoItem(
                    text = note.title.ifBlank {
                        note.body.lineSequence().firstOrNull().orEmpty()
                    },
                    linkedNoteId = note.id
                )
                val created = onCreateLinkedTodo(todo)
                if (created) {
                    detailTarget = null
                    onEditorVisibilityChange(false)
                }
                created
            },
            onOpenTodo = onOpenTodo,
            onDelete = {
                pendingDelete = note
                detailTarget = null
                onEditorVisibilityChange(false)
            },
            onEmbeddedTodoChecked = { todoId, isDone ->
                val index = notes.indexOfFirst { it.id == note.id }
                if (index >= 0) {
                    // 勾选笔记内待办不算「编辑笔记」，不能更新 updatedAt：
                    // 否则未恢复的草稿会被判定为旧草稿，提示被跳过且被清理，写了一半的内容静默丢失。
                    val updatedNote = note.copy(
                        embeddedTodos = note.embeddedTodos.map { todo ->
                            if (todo.id == todoId) todo.copy(isDone = isDone) else todo
                        }
                    )
                    notes[index] = updatedNote
                    detailTarget = updatedNote
                }
            }
        )
        return
    }

    if (section != NotesSection.LIST) {
        when (section) {
            NotesSection.DAILY_REVIEW -> DailyReviewPage(
                modifier = modifier,
                notes = notes,
                todos = todos,
                todoLoadWarning = todoLoadWarning,
                onBack = { section = NotesSection.LIST },
                onOpenNote = { note ->
                    detailTarget = note
                    onEditorVisibilityChange(true)
                },
                onOpenTodo = onOpenTodo
            )
            NotesSection.TIMELINE -> ActivityTimelinePage(
                modifier = modifier,
                notes = notes,
                todos = todos,
                todoLoadWarning = todoLoadWarning,
                onBack = { section = NotesSection.LIST },
                onOpenNote = { note ->
                    detailTarget = note
                    onEditorVisibilityChange(true)
                },
                onOpenTodo = onOpenTodo
            )
            NotesSection.LIST -> Unit
        }
        return
    }

    pendingDelete?.let { note ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("删除笔记？") },
            text = { Text("“${note.title}”将被删除。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        // 删除前留一份原始 JSON，并记下被连累的待办关系
                        val trashedPayload = captureNotePayload(context, note.id)
                        val affectedTodoIds = todos.filter { it.linkedNoteId == note.id }.map { it.id }
                        notes.remove(note)
                        var todosChanged = false
                        todos.indices.forEach { index ->
                            if (todos[index].linkedNoteId == note.id) {
                                todos[index] = todos[index].copy(linkedNoteId = null)
                                todosChanged = true
                            }
                        }
                        if (todosChanged) {
                            saveTodoList(context, todos.toList())
                            TodoWidgetProvider.refresh(context)
                        }
                        pendingDelete = null
                        trashedPayload?.let { payload ->
                            appendTrash(
                                context,
                                TrashEntry(
                                    kind = TrashKind.NOTE,
                                    id = note.id,
                                    payload = payload,
                                    deletedAt = System.currentTimeMillis(),
                                    affectedTodoIds = affectedTodoIds
                                )
                            )
                        }
                        // 草稿清理与 payload 是否捕获成功无关：笔记都删了，草稿不能留。
                        clearEditorDraft(context, noteDraftKey(note.id))
                    },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) { Text("删除") }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("取消") }
            }
        )
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        JotlistHeader(title = "笔记") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                FilledTonalButton(
                    onClick = {
                        editorTarget = null
                        showEditor = true
                        onEditorVisibilityChange(true)
                    },
                    enabled = isLoaded,
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                    ),
                    modifier = Modifier.testTag("add_note")
                ) {
                    Icon(
                        imageVector = Icons.Default.Edit,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Text(
                        text = "写笔记",
                        modifier = Modifier.padding(start = 6.dp)
                    )
                }
                Box {
                    IconButton(
                        onClick = { moreMenuExpanded = true },
                        modifier = Modifier.testTag("notes_more_options")
                    ) {
                        Icon(Icons.Default.MoreVert, contentDescription = "笔记功能")
                    }
                    DropdownMenu(
                        expanded = moreMenuExpanded,
                        onDismissRequest = { moreMenuExpanded = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text("快速收集") },
                            onClick = {
                                moreMenuExpanded = false
                                showQuickCapture = true
                                onEditorVisibilityChange(true)
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("今日小结") },
                            onClick = {
                                moreMenuExpanded = false
                                section = NotesSection.DAILY_REVIEW
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("时间线") },
                            onClick = {
                                moreMenuExpanded = false
                                section = NotesSection.TIMELINE
                            }
                        )
                    }
                }
            }
        }

        if (showInvalidDataWarning) {
            Text(
                text = "保存的笔记无法读取；为保护原数据，当前无法编辑笔记。",
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall
            )
        }
        if (todoLoadWarning) {
            Text(
                "待办数据无法读取；部分关联与回顾内容可能缺失。",
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall
            )
        }

        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            FilterChip(
                selected = selectedTag == null,
                onClick = { selectedTag = null },
                label = { Text("全部") }
            )
            notes.flatMap { it.tags }.distinct().sorted().forEach { tag ->
                FilterChip(
                    selected = selectedTag == tag,
                    onClick = { selectedTag = if (selectedTag == tag) null else tag },
                    label = { Text(tag) }
                )
            }
        }

        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            val visibleNotes = notes.filter { note ->
                selectedTag == null || selectedTag in note.tags
            }.sortedByDescending { it.updatedAt }
            if (visibleNotes.isEmpty() && !showInvalidDataWarning) {
                item {
                    EmptyState(
                        title = if (notes.isEmpty()) "还没有笔记" else "这个标签下没有笔记",
                        subtitle = if (notes.isEmpty()) {
                            "点右上角「写笔记」，或从桌面笔记组件快速收集"
                        } else {
                            "换个标签，或清掉筛选"
                        }
                    )
                }
            }
            items(visibleNotes, key = { it.id }) { note ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            detailTarget = note
                            onEditorVisibilityChange(true)
                        },
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                    ),
                    elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
                ) {
                    Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                        Text(
                            note.title,
                            style = MaterialTheme.typography.titleSmall.copy(
                                fontWeight = FontWeight.SemiBold
                            ),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        val preview = note.body
                            .lineSequence()
                            .map { it.trim() }
                            .firstOrNull { it.isNotEmpty() }
                            .orEmpty()
                        if (preview.isNotEmpty()) {
                            Text(
                                preview,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(top = 2.dp)
                            )
                        }
                        if (note.tags.isNotEmpty()) {
                            Text(
                                text = note.tags.joinToString("  ") { "#$it" },
                                color = MaterialTheme.colorScheme.primary,
                                style = MaterialTheme.typography.labelSmall,
                                modifier = Modifier.padding(top = 2.dp),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
        }
    }
}

internal fun applyNoteFormat(
    value: TextFieldValue,
    prefix: String,
    suffix: String = ""
): TextFieldValue {
    val start = minOf(value.selection.start, value.selection.end)
    val end = maxOf(value.selection.start, value.selection.end)
    val selectedText = value.text.substring(start, end)
    if (suffix.isNotEmpty()) {
        val replacement = "$prefix$selectedText$suffix"
        val newText = value.text.replaceRange(start, end, replacement)
        val selection = if (selectedText.isEmpty()) {
            TextRange(start + prefix.length)
        } else {
            TextRange(start, start + replacement.length)
        }
        return TextFieldValue(newText, selection)
    }

    val lineStart = value.text.lastIndexOf('\n', (start - 1).coerceAtLeast(0))
        .let { if (start == 0) 0 else it + 1 }
    val hasPrefix = value.text.startsWith(prefix, lineStart)
    val newText = if (hasPrefix) {
        value.text.removeRange(lineStart, lineStart + prefix.length)
    } else {
        value.text.substring(0, lineStart) + prefix + value.text.substring(lineStart)
    }
    val delta = if (hasPrefix) -prefix.length else prefix.length
    return TextFieldValue(
        newText,
        TextRange((value.selection.start + delta).coerceAtLeast(lineStart), (value.selection.end + delta).coerceAtLeast(lineStart))
    )
}

@Composable
internal fun NoteBodyEditor(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    isMarkdown: Boolean,
    onAddTodo: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            "正文",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (isMarkdown) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                listOf(
                    "标题" to "# ",
                    "加粗" to "**",
                    "列表" to "- "
                ).forEach { (label, format) ->
                    OutlinedButton(
                        onClick = {
                            onValueChange(
                                if (label == "加粗") {
                                    applyNoteFormat(value, format, format)
                                } else {
                                    applyNoteFormat(value, format)
                                }
                            )
                        },
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                        modifier = Modifier.testTag("note_format_$label")
                    ) {
                        Text(label)
                    }
                }
                OutlinedButton(
                    onClick = onAddTodo,
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                    modifier = Modifier.testTag("note_format_待办")
                ) {
                    Text("待办")
                }
            }
        }
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier
                .fillMaxWidth()
                .defaultMinSize(minHeight = 240.dp)
                .testTag("note_body_editor"),
            textStyle = MaterialTheme.typography.bodyLarge.copy(
                lineHeight = 30.sp,
                color = MaterialTheme.colorScheme.onSurface
            ),
            decorationBox = { innerTextField ->
                Box {
                    if (value.text.isBlank()) {
                        Text(
                            "在这里写下你的想法…",
                            style = MaterialTheme.typography.bodyLarge.copy(lineHeight = 30.sp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    innerTextField()
                }
            }
        )
    }
}

@Composable
internal fun NoteEditorScreen(
    modifier: Modifier = Modifier,
    initialNote: NoteItem?,
    onDismiss: () -> Unit,
    onSave: (NoteItem) -> Unit
) {
    // 与待办编辑器同理：草稿只活在本次编辑会话，避免进程被杀后把旧草稿塞回来。
    var title by remember(initialNote?.id) {
        mutableStateOf(initialNote?.title.orEmpty())
    }
    var bodyField by remember(initialNote?.id) {
        mutableStateOf(TextFieldValue(initialNote?.body.orEmpty()))
    }
    val body = bodyField.text
    var isMarkdown by remember(initialNote?.id) {
        mutableStateOf(initialNote?.isMarkdown ?: false)
    }
    var markdownPreviewVisible by remember(initialNote?.id) { mutableStateOf(false) }
    val tags = remember(initialNote?.id) {
        mutableStateListOf<String>().apply { addAll(initialNote?.tags.orEmpty()) }
    }
    var removedTag by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(removedTag) {
        if (removedTag != null) {
            kotlinx.coroutines.delay(4_000)
            removedTag = null
        }
    }
    val embeddedTodos = remember(initialNote?.id) {
        mutableStateListOf<NoteInlineTodo>().apply {
            addAll(initialNote?.embeddedTodos.orEmpty())
        }
    }
    var showFormatHelp by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val preferences = remember(context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    // —— 草稿：防抖自动保存，重开时提示恢复（不自动套用）——
    val draftKey = noteDraftKey(initialNote?.id)
    var pendingDraft by remember { mutableStateOf<EditorDraft?>(null) }
    LaunchedEffect(draftKey) {
        val draft = loadEditorDraft(context, draftKey)
        if (draft != null &&
            shouldOfferDraftRestore(draft.savedAt, initialNote?.updatedAt, initialNote == null)
        ) {
            pendingDraft = draft
        }
    }
    LaunchedEffect(title, body, tags.toList(), embeddedTodos.toList(), isMarkdown) {
        if (pendingDraft != null) return@LaunchedEffect
        val changed = if (initialNote == null) {
            title.isNotBlank() || body.isNotBlank() ||
                tags.isNotEmpty() || embeddedTodos.isNotEmpty()
        } else {
            // 保存时会 trim 标题/正文，这里也按 trim 后比较，避免只多了个空格就产生「草稿」/
            // 「未保存修改」的误提示。
            title.trim() != initialNote.title || body.trim() != initialNote.body ||
                tags.toList() != initialNote.tags ||
                embeddedTodos.toList() != initialNote.embeddedTodos ||
                isMarkdown != initialNote.isMarkdown
        }
        if (!changed) {
            // 改回原样（或从未改动）：草稿没有意义，清掉避免下次误提示
            clearEditorDraft(context, draftKey)
            return@LaunchedEffect
        }
        kotlinx.coroutines.delay(800)
        saveEditorDraft(
            context,
            draftKey,
            noteToJson(
                NoteItem(
                    id = initialNote?.id ?: 0L,
                    title = title.trim(),
                    body = body,
                    updatedAt = System.currentTimeMillis(),
                    createdAt = initialNote?.createdAt ?: System.currentTimeMillis(),
                    isMarkdown = isMarkdown,
                    tags = tags.toList(),
                    embeddedTodos = embeddedTodos.toList()
                )
            )
        )
    }

    // —— 编辑态保护：返回不再直接丢内容 ——
    val dirty = if (initialNote == null) {
        title.isNotBlank() || body.isNotBlank() || tags.isNotEmpty() || embeddedTodos.isNotEmpty()
    } else {
        // 与保存逻辑一致：trim 后比较，纯尾随空白不算未保存修改
        title.trim() != initialNote.title ||
            body.trim() != initialNote.body ||
            tags.toList() != initialNote.tags ||
            embeddedTodos.toList() != initialNote.embeddedTodos ||
            isMarkdown != initialNote.isMarkdown
    }
    var showDiscardConfirm by remember { mutableStateOf(false) }
    var showTagDialog by remember { mutableStateOf(false) }
    var tagDraft by remember { mutableStateOf("") }
    var hintMessage by remember { mutableStateOf<String?>(null) }

    fun attemptDismiss() {
        if (dirty) {
            showDiscardConfirm = true
        } else {
            // 没有改动就关掉：把可能残留的旧草稿清掉，避免下次误提示
            clearEditorDraft(context, draftKey)
            onDismiss()
        }
    }

    BackHandler { attemptDismiss() }

    // 新建笔记直接聚焦标题；标题回车跳到正文
    val titleFocus = remember { FocusRequester() }
    val bodyFocus = remember { FocusRequester() }
    LaunchedEffect(initialNote?.id) {
        if (initialNote == null) runCatching { titleFocus.requestFocus() }
    }

    fun saveNote() {
        val trimmedBody = body.trim()
        val resolvedTitle = title.trim().ifBlank {
            trimmedBody.lineSequence().firstOrNull()?.trim()?.take(36).orEmpty()
        }
        if (resolvedTitle.isBlank() && trimmedBody.isBlank()) {
            hintMessage = "写点什么再保存"
            titleFocus.requestFocus()
            return
        }
        clearEditorDraft(context, draftKey)
        onSave(
            NoteItem(
                id = initialNote?.id ?: nextTodoId(),
                title = resolvedTitle.ifBlank { "无标题笔记" },
                body = trimmedBody,
                updatedAt = System.currentTimeMillis(),
                createdAt = initialNote?.createdAt ?: System.currentTimeMillis(),
                isMarkdown = isMarkdown,
                tags = tags.distinct(),
                embeddedTodos = embeddedTodos.filter { it.text.isNotBlank() }
            )
        )
    }

    pendingDraft?.let { draft ->
        AlertDialog(
            onDismissRequest = { pendingDraft = null },
            title = { Text("恢复上次的草稿？") },
            text = { Text("这篇笔记上次没有保存完，可以接着写，也可以丢掉重新开始。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        runCatching { noteFromJson(draft.payload) }.getOrNull()?.let { restored ->
                            title = restored.title
                            bodyField = TextFieldValue(restored.body)
                            isMarkdown = restored.isMarkdown
                            tags.clear()
                            tags.addAll(restored.tags)
                            embeddedTodos.clear()
                            embeddedTodos.addAll(restored.embeddedTodos)
                        }
                        pendingDraft = null
                    },
                    modifier = Modifier.testTag("draft_restore")
                ) { Text("恢复") }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        clearEditorDraft(context, draftKey)
                        pendingDraft = null
                    },
                    modifier = Modifier.testTag("draft_discard")
                ) { Text("丢弃") }
            }
        )
    }

    if (showDiscardConfirm) {
        AlertDialog(
            onDismissRequest = { showDiscardConfirm = false },
            title = { Text("放弃这次编辑？") },
            text = { Text("这篇笔记还没保存，离开后修改会丢失。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDiscardConfirm = false
                        clearEditorDraft(context, draftKey)
                        onDismiss()
                    },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) { Text("放弃") }
            },
            dismissButton = {
                TextButton(onClick = { showDiscardConfirm = false }) { Text("继续编辑") }
            }
        )
    }

    if (showTagDialog) {
        AlertDialog(
            onDismissRequest = { showTagDialog = false },
            title = { Text("添加标签") },
            text = {
                OutlinedTextField(
                    value = tagDraft,
                    onValueChange = { tagDraft = it },
                    singleLine = true,
                    label = { Text("标签名称") }
                )
            },
            confirmButton = {
                TextButton(
                    enabled = tagDraft.isNotBlank(),
                    onClick = {
                        val clean = tagDraft.trim().trimStart('#')
                        if (clean.isNotEmpty() && clean !in tags) tags.add(clean)
                        tagDraft = ""
                        showTagDialog = false
                    }
                ) { Text("添加") }
            },
            dismissButton = {
                TextButton(onClick = { showTagDialog = false }) { Text("取消") }
            }
        )
    }

    if (showFormatHelp) {
        AlertDialog(
            onDismissRequest = {
                showFormatHelp = false
                preferences.edit().putBoolean(KEY_NOTE_FORMAT_HELP_SHOWN, true).apply()
            },
            title = { Text("用工具轻松排版") },
            text = {
                Text("底部工具条：H 插入标题、B 加粗、• 列表、☑ 插入待办。选中文字后点 B 可直接套用加粗。")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showFormatHelp = false
                        preferences.edit().putBoolean(KEY_NOTE_FORMAT_HELP_SHOWN, true).apply()
                    }
                ) { Text("知道了") }
            }
        )
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .imePadding()
            .padding(horizontal = 20.dp)
    ) {
        // 顶栏固定：返回 + 标题 + 主操作
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = { attemptDismiss() }) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "返回"
                )
            }
            Text(
                text = if (initialNote == null) "新建笔记" else "编辑笔记",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 4.dp)
            )
            FilledTonalButton(
                onClick = { saveNote() },
                colors = ButtonDefaults.filledTonalButtonColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                ),
                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
                modifier = Modifier.testTag("save_note")
            ) {
                Text("保存")
            }
        }

        // 中间可滚动区域：标题、标签、正文、内嵌待办
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
        ) {
            BasicTextField(
                value = title,
                onValueChange = {
                    title = it
                    if (hintMessage != null) hintMessage = null
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(titleFocus)
                    .testTag("note_title_editor"),
                textStyle = MaterialTheme.typography.headlineMedium.copy(
                    fontSize = 32.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                ),
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                keyboardActions = KeyboardActions(onNext = { bodyFocus.requestFocus() }),
                decorationBox = { innerTextField ->
                    Box {
                        if (title.isBlank()) {
                            Text(
                                "标题",
                                style = MaterialTheme.typography.headlineMedium.copy(
                                    fontSize = 32.sp,
                                    fontWeight = FontWeight.Bold
                                ),
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f)
                            )
                        }
                        innerTextField()
                    }
                }
            )
            hintMessage?.let { message ->
                Text(
                    text = message,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 6.dp)
                )
            }
            if (initialNote != null) {
                Text(
                    text = "创建于 ${formatMonthDay(initialNote.createdAt)} · 更新于 ${formatMonthDay(initialNote.updatedAt)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp)
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                tags.forEach { tag ->
                    InputChip(
                        selected = false,
                        onClick = {
                            removedTag = tag
                            tags.remove(tag)
                        },
                        label = { Text("#$tag", style = MaterialTheme.typography.labelMedium) },
                        trailingIcon = {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "删除标签#$tag",
                                modifier = Modifier.size(14.dp)
                            )
                        }
                    )
                }
                AssistChip(
                    onClick = { showTagDialog = true },
                    modifier = Modifier.testTag("add_tag"),
                    label = { Text("＋", style = MaterialTheme.typography.labelMedium) }
                )
            }
            removedTag?.let { tag ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "已删除 #$tag",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    TextButton(
                        onClick = {
                            if (tag !in tags) tags.add(tag)
                            removedTag = null
                        },
                        modifier = Modifier.testTag("tag_undo")
                    ) { Text("撤销") }
                }
            }
            HorizontalDivider(
                modifier = Modifier.padding(top = 12.dp, bottom = 8.dp),
                color = MaterialTheme.colorScheme.outlineVariant
            )
            if (markdownPreviewVisible) {
                if (body.isBlank()) {
                    Text(
                        "输入内容后会显示排版预览。",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.padding(vertical = 12.dp)
                    )
                } else {
                    MarkdownBody(markdown = body, modifier = Modifier.padding(vertical = 8.dp))
                }
            } else {
                BasicTextField(
                    value = bodyField,
                    onValueChange = {
                        bodyField = it
                        // 正文一改动就清掉保存提示，避免提示挂在那儿不走
                        if (hintMessage != null) hintMessage = null
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .defaultMinSize(minHeight = 240.dp)
                        .focusRequester(bodyFocus)
                        .testTag("note_body_editor"),
                    textStyle = MaterialTheme.typography.bodyLarge.copy(
                        lineHeight = 28.sp,
                        color = MaterialTheme.colorScheme.onSurface
                    ),
                    decorationBox = { innerTextField ->
                        Box {
                            if (body.isBlank()) {
                                Text(
                                    "在这里写下你的想法…",
                                    style = MaterialTheme.typography.bodyLarge.copy(lineHeight = 28.sp),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            innerTextField()
                        }
                    }
                )
            }
            // 内嵌待办：有内容才出现，不再单独占一块标题
            embeddedTodos.forEachIndexed { index, todo ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 44.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(
                        checked = todo.isDone,
                        onCheckedChange = { checked ->
                            embeddedTodos[index] = todo.copy(isDone = checked)
                        }
                    )
                    BasicTextField(
                        value = todo.text,
                        onValueChange = { text ->
                            embeddedTodos[index] = todo.copy(text = text)
                        },
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodyLarge.copy(
                            color = MaterialTheme.colorScheme.onSurface
                        ),
                        modifier = Modifier
                            .weight(1f)
                            .testTag("note_todo_text_$index"),
                        decorationBox = { innerTextField ->
                            Box {
                                if (todo.text.isEmpty()) {
                                    Text(
                                        "待办内容",
                                        style = MaterialTheme.typography.bodyLarge,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                innerTextField()
                            }
                        }
                    )
                    IconButton(onClick = { embeddedTodos.removeAt(index) }) {
                        Icon(Icons.Default.Delete, contentDescription = "删除笔记待办")
                    }
                }
            }
            Spacer(Modifier.height(32.dp))
        }

        // 吸底工具条：永远在键盘上方，不随正文滚走
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 52.dp)
                .testTag("note_toolbar"),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            ToolbarIcon(
                icon = Icons.Default.Title,
                description = "插入标题",
                tag = "toolbar_0",
                onClick = {
                    isMarkdown = true
                    markdownPreviewVisible = false
                    bodyField = applyNoteFormat(bodyField, "# ")
                }
            )
            ToolbarIcon(
                icon = Icons.Default.FormatBold,
                description = "加粗",
                tag = "toolbar_1",
                onClick = {
                    isMarkdown = true
                    markdownPreviewVisible = false
                    bodyField = applyNoteFormat(bodyField, "**", "**")
                }
            )
            ToolbarIcon(
                icon = Icons.Default.FormatListBulleted,
                description = "插入列表",
                tag = "toolbar_2",
                onClick = {
                    isMarkdown = true
                    markdownPreviewVisible = false
                    bodyField = applyNoteFormat(bodyField, "- ")
                }
            )
            ToolbarIcon(
                icon = Icons.Default.CheckCircle,
                description = "插入待办",
                tag = "toolbar_todo",
                onClick = {
                    isMarkdown = true
                    embeddedTodos.add(NoteInlineTodo(text = ""))
                }
            )
            Spacer(Modifier.weight(1f))
            ToolbarIcon(
                icon = if (markdownPreviewVisible) Icons.Default.Edit else Icons.Default.Visibility,
                description = if (markdownPreviewVisible) "编辑" else "预览",
                tag = "markdown_preview_mode",
                onClick = {
                    if (!markdownPreviewVisible && !isMarkdown) isMarkdown = true
                    markdownPreviewVisible = !markdownPreviewVisible
                }
            )
            ToolbarIcon(
                icon = Icons.Default.HelpOutline,
                description = "排版说明",
                tag = "note_format_help",
                onClick = { showFormatHelp = true }
            )
        }
    }
}

@Composable
internal fun DailyReviewPage(
    modifier: Modifier,
    notes: List<NoteItem>,
    todos: List<TodoItem>,
    todoLoadWarning: Boolean,
    onBack: () -> Unit,
    onOpenNote: (NoteItem) -> Unit,
    onOpenTodo: (Long) -> Unit
) {
    val now = System.currentTimeMillis()
    val todayStart = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis
    val completedToday = todos.filter {
        it.isDone && it.completedAt?.let { completedAt -> isSameLocalDay(completedAt, now) } == true
    }.sortedByDescending { it.completedAt }
    val dueToday = todos.filter { item ->
        !item.isDone && item.dueAt?.let { isSameLocalDay(it, now) } == true
    }.sortedBy { it.dueAt }
    // 与「整理未完成任务」用同一套口径：逾期，或搁置多日的无期限任务。
    val pendingTriage = todos.filter { it.needsTriage(todayStart, now) }
        .sortedBy { it.dueAt ?: Long.MAX_VALUE }
    val todayNotes = notes.filter {
        isSameLocalDay(it.updatedAt, now) || isSameLocalDay(it.createdAt, now)
    }.sortedByDescending { it.updatedAt }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
    ) {
        JotlistHeader(title = "今日小结", onBack = onBack)
        Text(
            formatReminderDate(now),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 12.dp, bottom = 8.dp)
        )
        if (todoLoadWarning) {
            Text("待办数据无法读取，待办回顾内容可能不完整。", color = MaterialTheme.colorScheme.error)
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(bottom = 16.dp)
        ) {
            item { ReviewSectionTitle("今日完成", completedToday.size) }
            if (completedToday.isEmpty()) {
                item { ReviewEmptyText("今天还没有完成的待办") }
            } else {
                items(completedToday, key = { "completed-${it.id}" }) { todo ->
                    ReviewTodoCard(todo, "完成于 ${todo.completedAt?.let(::formatTime).orEmpty()}") {
                        onOpenTodo(todo.id)
                    }
                }
            }
            item { ReviewSectionTitle("今日安排", dueToday.size) }
            if (dueToday.isEmpty()) {
                item { ReviewEmptyText("今天没有到期的待办") }
            } else {
                items(dueToday, key = { "due-${it.id}" }) { todo ->
                    ReviewTodoCard(todo, todo.dueAt?.let(::formatTime).orEmpty()) {
                        onOpenTodo(todo.id)
                    }
                }
            }
            item { ReviewSectionTitle("待整理", pendingTriage.size) }
            if (pendingTriage.isEmpty()) {
                item { ReviewEmptyText("没有逾期或搁置的待办") }
            } else {
                items(pendingTriage, key = { "triage-${it.id}" }) { todo ->
                    ReviewTodoCard(
                        todo,
                        todo.dueAt?.let { "截止 ${formatDueTime(it)}" }
                            ?: "无截止时间 · 创建于 ${formatMonthDay(todo.createdAt)}"
                    ) {
                        onOpenTodo(todo.id)
                    }
                }
            }
            item { ReviewSectionTitle("今天的笔记", todayNotes.size) }
            if (todayNotes.isEmpty()) {
                item { ReviewEmptyText("今天还没有记录笔记") }
            } else {
                items(todayNotes, key = { "note-${it.id}" }) { note ->
                    Card(
                        Modifier
                            .fillMaxWidth()
                            .clickable { onOpenNote(note) }
                    ) {
                        Column(Modifier.padding(16.dp)) {
                            Text(
                                note.title,
                                style = MaterialTheme.typography.titleMedium.copy(
                                    fontWeight = FontWeight.Bold
                                )
                            )
                            if (note.tags.isNotEmpty()) {
                                Text(
                                    note.tags.joinToString("  ") { "#$it" },
                                    color = MaterialTheme.colorScheme.primary,
                                    style = MaterialTheme.typography.labelMedium
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun ActivityTimelinePage(
    modifier: Modifier,
    notes: List<NoteItem>,
    todos: List<TodoItem>,
    todoLoadWarning: Boolean,
    onBack: () -> Unit,
    onOpenNote: (NoteItem) -> Unit,
    onOpenTodo: (Long) -> Unit
) {
    data class Entry(
        val id: String,
        val timestamp: Long,
        val title: String,
        val detail: String,
        val note: NoteItem? = null,
        val todoId: Long? = null
    )

    val entries = buildList {
        notes.forEach { note ->
            add(
                Entry(
                    id = "note-created-${note.id}",
                    timestamp = note.createdAt,
                    title = note.title,
                    detail = "创建笔记",
                    note = note
                )
            )
            if (note.updatedAt > note.createdAt + 1_000L) {
                add(
                    Entry(
                        id = "note-updated-${note.id}",
                        timestamp = note.updatedAt,
                        title = note.title,
                        detail = "更新笔记",
                        note = note
                    )
                )
            }
        }
        todos.forEach { todo ->
            add(
                Entry(
                    id = "todo-created-${todo.id}",
                    timestamp = todo.createdAt,
                    title = todo.text,
                    detail = "创建待办",
                    todoId = todo.id
                )
            )
            todo.completedAt?.let { completedAt ->
                add(
                    Entry(
                        id = "todo-completed-${todo.id}",
                        timestamp = completedAt,
                        title = todo.text,
                        detail = "完成待办",
                        todoId = todo.id
                    )
                )
            }
        }
    }.sortedByDescending { it.timestamp }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
    ) {
        JotlistHeader(title = "时间线", onBack = onBack)
        if (todoLoadWarning) {
            Text("待办数据无法读取，时间线可能不完整。", color = MaterialTheme.colorScheme.error)
        }
        if (entries.isEmpty()) {
            ReviewEmptyText("还没有笔记或待办记录")
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(vertical = 12.dp)
            ) {
                items(entries, key = { it.id }) { entry ->
                    Card(
                        Modifier
                            .fillMaxWidth()
                            .clickable {
                                entry.note?.let(onOpenNote)
                                    ?: entry.todoId?.let(onOpenTodo)
                            }
                    ) {
                        Row(
                            modifier = Modifier.padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = if (entry.note != null) {
                                    Icons.Default.Edit
                                } else {
                                    Icons.Default.CheckCircle
                                },
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Column(Modifier.padding(start = 12.dp).weight(1f)) {
                                Text(
                                    entry.title,
                                    style = MaterialTheme.typography.titleMedium.copy(
                                        fontWeight = if (entry.note != null) {
                                            FontWeight.Bold
                                        } else {
                                            FontWeight.Normal
                                        }
                                    )
                                )
                                entry.note?.tags?.takeIf { it.isNotEmpty() }?.let { tags ->
                                    Text(
                                        tags.joinToString("  ") { "#$it" },
                                        color = MaterialTheme.colorScheme.primary,
                                        style = MaterialTheme.typography.labelMedium
                                    )
                                }
                                Text(
                                    entry.detail,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Text(
                                formatTimelineTime(entry.timestamp),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun ReviewSectionTitle(title: String, count: Int) {
    Text(
        "$title · $count",
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(top = 12.dp, bottom = 2.dp)
    )
}

@Composable
internal fun ReviewEmptyText(text: String) {
    Text(
        text,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
    )
}

@Composable
internal fun ReviewTodoCard(todo: TodoItem, detail: String, onClick: () -> Unit) {
    Card(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(todo.text, style = MaterialTheme.typography.titleMedium)
            Text(
                detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
internal fun MarkdownBody(markdown: String, modifier: Modifier = Modifier) {
    val lines = markdown.lines()
    var inCodeBlock = false
    val codeBlockLines = mutableListOf<String>()

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        lines.forEach { sourceLine ->
            val line = sourceLine.trimEnd()
            if (line.trimStart().startsWith("```")) {
                if (inCodeBlock) {
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = MaterialTheme.shapes.small
                    ) {
                        Text(
                            text = codeBlockLines.joinToString("\n"),
                            modifier = Modifier.fillMaxWidth().padding(12.dp),
                            fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                    codeBlockLines.clear()
                }
                inCodeBlock = !inCodeBlock
                return@forEach
            }
            if (inCodeBlock) {
                codeBlockLines.add(line)
                return@forEach
            }
            if (line.isBlank()) {
                Spacer(Modifier.height(4.dp))
                return@forEach
            }
            if (line.trim() in setOf("---", "***", "___")) {
                HorizontalDivider()
                return@forEach
            }

            val heading = Regex("^(#{1,6})\\s+(.+)$").matchEntire(line)
            if (heading != null) {
                val level = heading.groupValues[1].length
                val style = when (level) {
                    1 -> MaterialTheme.typography.headlineMedium
                    2 -> MaterialTheme.typography.headlineSmall
                    3 -> MaterialTheme.typography.titleLarge
                    else -> MaterialTheme.typography.titleMedium
                }
                Text(
                    text = markdownInline(heading.groupValues[2]),
                    style = style.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onSurface
                )
                return@forEach
            }

            val quote = line.trimStart().startsWith("> ")
            val quoteText = if (quote) line.trimStart().removePrefix("> ") else line
            val listMatch = Regex("^\\s*([-*+]\\s+|\\d+[.)]\\s+)(.*)$").matchEntire(quoteText)
            val taskListMatch = Regex("^\\s*[-*+]\\s+\\[([ xX])\\]\\s+(.*)$")
                .matchEntire(quoteText)
            val displayText = when {
                taskListMatch != null ->
                    "${if (taskListMatch.groupValues[1].isBlank()) "☐" else "☑"} ${taskListMatch.groupValues[2]}"
                listMatch != null -> "• ${listMatch.groupValues[2]}"
                else -> quoteText
            }
            Row(verticalAlignment = Alignment.Top) {
                if (quote) {
                    Surface(
                        modifier = Modifier
                            .padding(end = 10.dp)
                            .width(3.dp)
                            .heightIn(min = 24.dp),
                        color = MaterialTheme.colorScheme.primary
                    ) {}
                }
                Text(
                    text = markdownInline(displayText),
                    style = MaterialTheme.typography.bodyLarge.copy(lineHeight = 30.sp),
                    color = if (quote) MaterialTheme.colorScheme.onSurfaceVariant
                    else MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f)
                )
            }
        }
        if (inCodeBlock && codeBlockLines.isNotEmpty()) {
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = MaterialTheme.shapes.small
            ) {
                Text(
                    text = codeBlockLines.joinToString("\n"),
                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
    }
}

@Composable
internal fun markdownInline(source: String): AnnotatedString {
    val pattern = Regex("""(\*\*.+?\*\*|__.+?__|~~.+?~~|\*.+?\*|_.+?_|`[^`]+`|\[[^\]]+]\([^)]+\))""")
    val codeBackground = MaterialTheme.colorScheme.surfaceVariant
    val linkColor = MaterialTheme.colorScheme.primary
    return buildAnnotatedString {
        var cursor = 0
        pattern.findAll(source).forEach { match ->
            append(source.substring(cursor, match.range.first))
            val token = match.value
            when {
                token.startsWith("**") || token.startsWith("__") ->
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                        append(token.substring(2, token.length - 2))
                    }
                token.startsWith("~~") ->
                    withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) {
                        append(token.substring(2, token.length - 2))
                    }
                token.startsWith("`") ->
                    withStyle(
                        SpanStyle(
                            fontFamily = FontFamily.Monospace,
                            background = codeBackground
                        )
                    ) {
                        append(token.substring(1, token.length - 1))
                    }
                token.startsWith("[") -> {
                    val link = Regex("""\[([^\]]+)]\(([^)]+)\)""").matchEntire(token)
                    withStyle(
                        SpanStyle(
                            color = linkColor,
                            textDecoration = TextDecoration.Underline
                        )
                    ) {
                        append(link?.groupValues?.get(1) ?: token)
                    }
                }
                else -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                    append(token.substring(1, token.length - 1))
                }
            }
            cursor = match.range.last + 1
        }
        append(source.substring(cursor))
    }
}

@Composable
internal fun NoteDetailScreen(
    modifier: Modifier,
    note: NoteItem,
    linkedTodos: List<TodoItem>,
    onBack: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onCreateTodo: () -> Boolean,
    onOpenTodo: (Long) -> Unit,
    onEmbeddedTodoChecked: (Long, Boolean) -> Unit
) {
    var menuExpanded by remember(note.id) { mutableStateOf(false) }
    var conversionError by remember(note.id) { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp)
    ) {
        JotlistHeader(title = "", onBack = onBack) {
            Box {
                IconButton(
                    onClick = { menuExpanded = true },
                    modifier = Modifier.testTag("note_more_options")
                ) {
                    Icon(Icons.Default.MoreVert, contentDescription = "笔记更多选项")
                }
                DropdownMenu(
                    expanded = menuExpanded,
                    onDismissRequest = { menuExpanded = false }
                ) {
                    DropdownMenuItem(
                        text = { Text(if (linkedTodos.isEmpty()) "转为待办" else "打开关联待办") },
                        onClick = {
                            menuExpanded = false
                            if (linkedTodos.isEmpty()) {
                                conversionError = !onCreateTodo()
                            } else {
                                onOpenTodo(linkedTodos.first().id)
                            }
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("编辑") },
                        onClick = {
                            menuExpanded = false
                            onEdit()
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("删除") },
                        onClick = {
                            menuExpanded = false
                            onDelete()
                        }
                    )
                }
            }
            }
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                text = note.title,
                style = MaterialTheme.typography.headlineMedium.copy(
                    fontSize = 32.sp,
                    fontWeight = FontWeight.Bold
                ),
                color = MaterialTheme.colorScheme.onSurface
            )
            if (note.tags.isNotEmpty()) {
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    note.tags.forEach { tag ->
                        Surface(
                            color = MaterialTheme.colorScheme.secondaryContainer,
                            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                            shape = MaterialTheme.shapes.small
                        ) {
                            Text(
                                text = "#$tag",
                                style = MaterialTheme.typography.labelLarge,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                            )
                        }
                    }
                }
            }
            if (note.isMarkdown) {
                Text(
                    text = "已启用排版",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            if (note.isMarkdown) {
                MarkdownBody(note.body)
            } else {
                Text(
                    text = note.body.ifBlank { "（没有正文）" },
                    style = MaterialTheme.typography.bodyLarge.copy(lineHeight = 30.sp),
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.fillMaxWidth()
                )
            }
            if (note.embeddedTodos.isNotEmpty()) {
                HorizontalDivider()
                Text("笔记内待办", style = MaterialTheme.typography.titleMedium)
                note.embeddedTodos.forEach { todo ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("note_detail_todo_${todo.id}"),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(
                            checked = todo.isDone,
                            onCheckedChange = { onEmbeddedTodoChecked(todo.id, it) }
                        )
                        Text(
                            text = todo.text,
                            modifier = Modifier.weight(1f),
                            textDecoration = if (todo.isDone) {
                                TextDecoration.LineThrough
                            } else {
                                TextDecoration.None
                            }
                        )
                    }
                }
            }
            if (linkedTodos.isNotEmpty()) {
                HorizontalDivider()
                Text("关联待办", style = MaterialTheme.typography.titleMedium)
                linkedTodos.forEach { todo ->
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onOpenTodo(todo.id) }
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Default.CheckCircle,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Text(
                                todo.text,
                                modifier = Modifier.padding(start = 12.dp).weight(1f),
                                textDecoration = if (todo.isDone) {
                                    TextDecoration.LineThrough
                                } else {
                                    TextDecoration.None
                                }
                            )
                            Text(if (todo.isDone) "已完成" else "进行中")
                        }
                    }
                }
            }
            // 关联待办与转换失败提示不能藏在"有内联待办"的分支里：
            // 只有内联待办的笔记被"转为待办"后，详情页原本什么都看不到。
            if (conversionError) {
                Text(
                    "待办数据无法读取，暂时不能转换。",
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}

@Composable
private fun ToolbarIcon(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
    tag: String,
    onClick: () -> Unit
) {
    IconButton(
        onClick = onClick,
        modifier = Modifier
            .size(44.dp)
            .testTag(tag)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = description,
            modifier = Modifier.size(22.dp)
        )
    }
}
