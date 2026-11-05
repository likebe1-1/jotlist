package com.manst.jotlist

import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.filled.Close
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
internal fun UnifiedSearchScreen(
    modifier: Modifier,
    onOpenTodo: (Long) -> Unit,
    onOpenNote: (Long) -> Unit
) {
    val context = LocalContext.current
    var query by rememberSaveable { mutableStateOf("") }
    var filter by rememberSaveable { mutableStateOf("全部") }
    var tasks by remember { mutableStateOf<List<TodoItem>>(emptyList()) }
    var notes by remember { mutableStateOf<List<NoteItem>>(emptyList()) }
    var isLoaded by remember { mutableStateOf(false) }
    var taskLoadError by remember { mutableStateOf(false) }
    var noteLoadError by remember { mutableStateOf(false) }

    LaunchedEffect(context) {
        when (val result = loadTodoList(context)) {
            is TodoListLoadResult.Loaded -> tasks = result.items
            TodoListLoadResult.InvalidJson -> taskLoadError = true
        }
        when (val result = loadNotes(context)) {
            is NotesLoadResult.Loaded -> notes = result.items
            NotesLoadResult.Invalid -> noteLoadError = true
        }
        isLoaded = true
    }

    val noteResults = if (query.isBlank() || filter == "待办") {
        emptyList()
    } else {
        // 标签在数据里不带 #，但用户习惯输入 "#工作"，两种都当作标签关键词。
        val tagQuery = query.removePrefix("#")
        notes.filter { note ->
            note.title.contains(query, ignoreCase = true) ||
                note.body.contains(query, ignoreCase = true) ||
                note.tags.any { it.contains(tagQuery, ignoreCase = true) } ||
                note.embeddedTodos.any { it.text.contains(query, ignoreCase = true) }
        }
    }
    val taskResults = if (query.isBlank() || filter == "笔记") {
        emptyList()
    } else {
        tasks.filter { it.text.contains(query, ignoreCase = true) }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .imePadding()
            .padding(16.dp)
    ) {
        JotlistHeader(title = "搜索")
        val searchFocus = remember { FocusRequester() }
        val focusManager = LocalFocusManager.current
        val keyboardController = LocalSoftwareKeyboardController.current
        LaunchedEffect(Unit) { searchFocus.requestFocus() }
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            label = { Text("搜索笔记、标签和待办") },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
            trailingIcon = {
                if (query.isNotEmpty()) {
                    IconButton(onClick = { query = "" }) {
                        Icon(Icons.Default.Close, contentDescription = "清除搜索内容")
                    }
                }
            },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(
                onSearch = {
                    // clearFocus 只移走焦点，软键盘不会自动收起，需要显式 hide。
                    focusManager.clearFocus()
                    keyboardController?.hide()
                }
            ),
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(searchFocus)
                .testTag("unified_search_input")
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            listOf("全部", "笔记", "待办").forEach { option ->
                FilterChip(
                    selected = filter == option,
                    onClick = { filter = option },
                    label = { Text(option) },
                    modifier = Modifier.testTag("search_filter_$option")
                )
            }
        }
        if (taskLoadError) {
            Text("待办数据无法读取，搜索结果可能不完整。", color = MaterialTheme.colorScheme.error)
        }
        if (noteLoadError) {
            Text("笔记数据无法读取，搜索结果可能不完整。", color = MaterialTheme.colorScheme.error)
        }
        if (query.isBlank()) {
            Text(
                "输入关键词，搜索笔记正文、标题、标签和待办。",
                modifier = Modifier.padding(top = 20.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else if (!isLoaded) {
            Text("正在搜索…", modifier = Modifier.padding(top = 20.dp))
        } else if (noteResults.isEmpty() && taskResults.isEmpty()) {
            Text(
                "没有找到匹配内容。",
                modifier = Modifier.padding(top = 20.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .testTag("unified_search_results"),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(top = 12.dp, bottom = 16.dp)
            ) {
                if (noteResults.isNotEmpty()) {
                    item { Text("笔记", style = MaterialTheme.typography.titleMedium) }
                    items(noteResults, key = { "search-note-${it.id}" }) { note ->
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onOpenNote(note.id) }
                        ) {
                            Column(Modifier.padding(14.dp)) {
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
                                if (note.body.contains(query, ignoreCase = true)) {
                                    Text(
                                        note.body,
                                        maxLines = 2,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }
                if (taskResults.isNotEmpty()) {
                    item { Text("待办", style = MaterialTheme.typography.titleMedium) }
                    items(taskResults, key = { "search-task-${it.id}" }) { task ->
                        val isOverdue = !task.isDone &&
                            task.dueAt?.let { due -> due < System.currentTimeMillis() } == true
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onOpenTodo(task.id) }
                        ) {
                            Column(modifier = Modifier.padding(14.dp)) {
                                Text(
                                    task.text,
                                    style = MaterialTheme.typography.bodyLarge,
                                    textDecoration = if (task.isDone) {
                                        TextDecoration.LineThrough
                                    } else {
                                        TextDecoration.None
                                    },
                                    color = if (task.isDone) {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    } else {
                                        MaterialTheme.colorScheme.onSurface
                                    }
                                )
                                Text(
                                    buildString {
                                        append(
                                            when {
                                                task.isDone -> "已完成"
                                                isOverdue -> "已逾期"
                                                task.dueAt != null -> "截止 ${formatDueTime(task.dueAt)}"
                                                else -> "未设截止时间"
                                            }
                                        )
                                        if (task.category.isNotBlank()) {
                                            append(" · ${task.category}")
                                        }
                                        if (task.plannedAt != null) {
                                            append(" · 已计划 ${formatMonthDay(task.plannedAt)}")
                                        }
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (isOverdue) {
                                        MaterialTheme.colorScheme.error
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
