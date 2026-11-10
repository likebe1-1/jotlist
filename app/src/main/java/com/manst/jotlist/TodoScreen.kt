package com.manst.jotlist

import android.Manifest
import android.app.AlarmManager
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Context
import android.content.BroadcastReceiver
import android.content.Intent
import android.content.IntentFilter
import android.provider.Settings
import android.os.Build
import android.net.Uri
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Label
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.manst.jotlist.ui.theme.MyApplicationTheme
import java.util.Calendar
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Size
import com.manst.jotlist.ui.theme.PriorityStripeHigh
import com.manst.jotlist.ui.theme.PriorityStripeLow
import com.manst.jotlist.ui.theme.PriorityStripeMedium

@Composable
internal fun TodoAppScreen(
    modifier: Modifier = Modifier,
    appearanceMode: AppearanceMode = AppearanceMode.SYSTEM,
    density: UiDensity = UiDensity.STANDARD,
    initialOpenAddTodo: Boolean = false,
    onInitialAddTodoConsumed: () -> Unit = {},
    initialFocusTodoId: Long? = null,
    onFocusHandled: () -> Unit = {},
    onOpenNote: (Long) -> Unit = {},
    onAppearanceChange: (AppearanceMode) -> Unit = {},
    onDensityChange: (UiDensity) -> Unit = {},
    onEditorVisibilityChange: (Boolean) -> Unit = {},
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() }
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    /** 操作后给一条可撤销的 Snackbar：勾错、删错都不用去筛选里翻。 */
    fun offerUndo(message: String, onUndo: () -> Unit) {
        scope.launch {
            val result = snackbarHostState.showSnackbar(
                message = message,
                actionLabel = "撤销",
                duration = SnackbarDuration.Short
            )
            if (result == SnackbarResult.ActionPerformed) onUndo()
        }
    }

    val todoList = remember { mutableStateListOf<TodoItem>() }

    /** 删除不再弹确认框：先删掉，再给几秒撤销窗口。 */
    val deleteWithUndo: (TodoItem) -> Unit = { item ->
        val index = todoList.indexOfFirst { it.id == item.id }
        if (index >= 0) {
            cancelReminder(context, item)
            val trashedPayload = captureTodoPayload(context, item.id)
            todoList.removeAt(index)
            trashedPayload?.let { payload ->
                appendTrash(
                    context,
                    TrashEntry(
                        kind = TrashKind.TODO,
                        id = item.id,
                        payload = payload,
                        deletedAt = System.currentTimeMillis()
                    )
                )
            }
            // 任务已删除：残留草稿会在「回收站恢复」后弹出误导性的「恢复上次的草稿？」
            clearEditorDraft(context, taskDraftKey(item.id))
            offerUndo("已删除「${item.text}」") {
                removeTrashEntry(context, TrashKind.TODO, item.id)
                todoList.add(index.coerceIn(0, todoList.size), item)
                // 删除时撤掉了提醒，撤销删除要把提醒排回去。
                if (item.isDone) {
                    item.resetAt?.let { scheduleResetReminder(context, item) }
                } else {
                    scheduleReminder(context, item)
                }
            }
        }
    }
    val todoListState = rememberLazyListState()
    var showClearConfirm by remember { mutableStateOf(false) }
    var isReady by remember { mutableStateOf(false) }
    var mayPersist by remember { mutableStateOf(false) }
    var showInvalidDataDialog by remember { mutableStateOf(false) }
    var persistenceWarning by remember { mutableStateOf(false) }
    var editorTarget by remember { mutableStateOf<TodoItem?>(null) }
    var isCreatingTask by remember { mutableStateOf(false) }
    var secondaryPage by rememberSaveable { mutableStateOf(TodoSecondaryPage.LIST) }
    var selectedCalendarDate by rememberSaveable { mutableStateOf(System.currentTimeMillis()) }
    var completionHistory by remember { mutableStateOf<List<Long>>(emptyList()) }
    var linkedNoteTitles by remember { mutableStateOf<Map<Long, String>>(emptyMap()) }
    var completionHistoryReady by remember { mutableStateOf(false) }
    var completionHistoryWarning by remember { mutableStateOf(false) }
    // 默认只看"进行中"：完成后旧的那条离开列表，重复任务完成后只会看到滚到下一次的那条，
    // 不会出现"做完一条又多一条"的观感。"已完成"筛选里依然可以回看。
    var filter by rememberSaveable { mutableStateOf(TodoFilter.ACTIVE) }
    var categoryFilter by rememberSaveable { mutableStateOf<String?>(null) }
    // 切换筛选/分类后列表长度会变，共享滚动状态会停在新列表的最后一条；回到顶部更符合预期。
    LaunchedEffect(filter, categoryFilter) {
        if (todoListState.firstVisibleItemIndex > 0) todoListState.scrollToItem(0)
    }
    var highlightedTodoId by remember { mutableStateOf<Long?>(null) }
    val triageDismissedDay = LocalDate.now().toString()
    // 关掉后当天不再出现（之前是普通 remember，切个 Tab 界面重建就又冒出来）。
    var triageBannerDismissed by remember(triageDismissedDay) {
        mutableStateOf(
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getString(KEY_TRIAGE_DISMISSED_DAY, null) == triageDismissedDay
        )
    }
    var pendingReminder by remember { mutableStateOf<TodoItem?>(null) }
    var showFocusPicker by remember { mutableStateOf(false) }
    var reminderPermissionWarning by remember { mutableStateOf(false) }
    var exactAlarmWarning by remember { mutableStateOf(false) }
    var showExactAlarmDialog by remember { mutableStateOf(false) }
    var notificationsAllowed by remember { mutableStateOf(notificationsEnabled(context)) }
    var exactAlarmsAllowed by remember { mutableStateOf(canScheduleExactAlarms(context)) }
    val exactAlarmSettingsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        exactAlarmsAllowed = canScheduleExactAlarms(context)
        pendingReminder?.let { item ->
            exactAlarmWarning = !scheduleReminder(context, item)
        }
        if (exactAlarmsAllowed) {
            todoList.filter { !it.isDone }.forEach { scheduleReminder(context, it) }
        }
        pendingReminder = null
    }
    val requestExactReminder: (TodoItem) -> Unit = { item ->
        if (item.reminderMinutesBefore < 0) {
            // 用户明确关闭了提醒：既不用排闹钟，也不该弹「允许精准提醒」。
            pendingReminder = null
            exactAlarmWarning = false
        } else if (scheduleReminder(context, item)) {
            exactAlarmsAllowed = true
            exactAlarmWarning = false
        } else {
            exactAlarmsAllowed = false
            pendingReminder = item
            showExactAlarmDialog = true
        }
    }
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        notificationsAllowed = notificationsEnabled(context)
        pendingReminder?.let { item ->
            if (granted) requestExactReminder(item)
            else {
                reminderPermissionWarning = true
                pendingReminder = null
            }
        }
    }
    val notificationSettingsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        notificationsAllowed = notificationsEnabled(context)
    }
    // 启动时加载
    LaunchedEffect(context) {
        // 先把到期的重复任务恢复成未完成，再读列表。
        sweepRecurringResets(context)
        when (val result = loadTodoList(context)) {
            is TodoListLoadResult.InvalidJson -> {
                isReady = true
                showInvalidDataDialog = true
            }
            is TodoListLoadResult.Loaded -> {
                when (val history = loadCompletionHistory(context)) {
                    is CompletionHistoryLoadResult.Loaded -> {
                        completionHistory = history.timestamps
                        completionHistoryReady = true
                    }
                    CompletionHistoryLoadResult.Invalid -> completionHistoryWarning = true
                }
                reserveLoadedTodoIds(result.items)
                todoList.clear()
                todoList.addAll(result.items)
                linkedNoteTitles = (loadNotes(context) as? NotesLoadResult.Loaded)
                    ?.items?.associate { it.id to it.title }.orEmpty()
                result.items.filter { !it.isDone }.forEach { scheduleReminder(context, it) }
                exactAlarmWarning = result.items.any {
                    !it.isDone && it.reminderMinutesBefore >= 0 &&
                        it.dueAt?.let { dueAt -> dueAt > System.currentTimeMillis() } == true
                } && !exactAlarmsAllowed
                isReady = true
                mayPersist = true
            }
        }
    }
    LaunchedEffect(initialOpenAddTodo, isReady) {
        if (initialOpenAddTodo && isReady) {
            isCreatingTask = true
            onInitialAddTodoConsumed()
        }
    }
    // 全屏编辑器打开时收起底部导航，避免误触把草稿切走。
    val editing = isCreatingTask || editorTarget != null
    LaunchedEffect(editing) { onEditorVisibilityChange(editing) }
    DisposableEffect(Unit) {
        onDispose { onEditorVisibilityChange(false) }
    }
    // 从搜索结果或通知打开某个待办：真实定位到它，而不是把任务文字塞进筛选框。
    LaunchedEffect(initialFocusTodoId, isReady) {
        val focusId = initialFocusTodoId
        if (focusId == null || !isReady) return@LaunchedEffect
        if (todoList.none { it.id == focusId }) {
            onFocusHandled()
            return@LaunchedEffect
        }
        filter = TodoFilter.ALL
        categoryFilter = null
        secondaryPage = TodoSecondaryPage.LIST
        highlightedTodoId = focusId
        onFocusHandled()
    }
    LaunchedEffect(highlightedTodoId) {
        if (highlightedTodoId != null) {
            delay(2_500)
            highlightedTodoId = null
        }
    }
    // 通知和桌面小组件会直接改 SharedPreferences，若不重新读取，
    // 内存里的旧列表会在下一次保存时把它们写入的完成状态覆盖掉。
    val reloadFromStorage: () -> Unit = {
        if (mayPersist) {
            sweepRecurringResets(context)
            when (val result = loadTodoList(context)) {
                is TodoListLoadResult.Loaded -> {
                    if (result.items != todoList.toList()) {
                        reserveLoadedTodoIds(result.items)
                        todoList.clear()
                        todoList.addAll(result.items)
                    }
                }
                TodoListLoadResult.InvalidJson -> Unit
            }
            (loadCompletionHistory(context) as? CompletionHistoryLoadResult.Loaded)
                ?.let { history ->
                    completionHistory = history.timestamps
                    completionHistoryReady = true
                }
            linkedNoteTitles = (loadNotes(context) as? NotesLoadResult.Loaded)
                ?.items?.associate { it.id to it.title }.orEmpty()
        }
    }
    val lifecycleOwner = LocalLifecycleOwner.current
    var hasResumedOnce by remember { mutableStateOf(false) }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event != Lifecycle.Event.ON_RESUME) return@LifecycleEventObserver
            if (!hasResumedOnce) {
                hasResumedOnce = true
                return@LifecycleEventObserver
            }
            reloadFromStorage()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    // 应用仍在前台时用户点了通知操作，走广播立即刷新，不必等下一次 onResume。
    DisposableEffect(context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(receiverContext: Context?, broadcastIntent: Intent?) {
                reloadFromStorage()
            }
        }
        val filter = IntentFilter(ACTION_TODO_DATA_CHANGED)
        // 用 ContextCompat 统一处理版本差异：API 33+ 会带上 RECEIVER_NOT_EXPORTED，
        // 更老版本内部退化为两参注册。这样既符合 Android 14 的显式 flag 要求，
        // 也不会让 lint 的 UnspecifiedRegisterReceiverFlag 检查误报 else 分支。
        ContextCompat.registerReceiver(
            context,
            receiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        onDispose {
            runCatching { context.unregisterReceiver(receiver) }
        }
    }
    // 页面停留在前台跨过午夜时，也能把到点的重复任务恢复出来。
    LaunchedEffect(context) {
        while (true) {
            kotlinx.coroutines.delay(60_000)
            if (sweepRecurringResets(context)) reloadFromStorage()
        }
    }

    if (showExactAlarmDialog) {
        AlertDialog(
            onDismissRequest = {
                showExactAlarmDialog = false
                pendingReminder?.let { exactAlarmWarning = !scheduleReminder(context, it) }
                pendingReminder = null
            },
            title = { Text("允许精准提醒") },
            text = {
                Text("Android 需要你在系统设置中允许精确闹钟。未授权时仍会设置提醒，但系统可能延迟通知。")
            },
            confirmButton = {
                TextButton(onClick = {
                    showExactAlarmDialog = false
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        exactAlarmSettingsLauncher.launch(
                            Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)
                                .setData(Uri.parse("package:${context.packageName}"))
                        )
                    }
                }) {
                    Text("打开设置")
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    showExactAlarmDialog = false
                    pendingReminder?.let { exactAlarmWarning = !scheduleReminder(context, it) }
                    pendingReminder = null
                }) {
                    Text("稍后")
                }
            }
        )
    }

    // 只有成功加载或用户明确重置后才允许覆盖持久化数据
    LaunchedEffect(mayPersist, todoList.toList()) {
        if (mayPersist) {
            saveTodoList(context, todoList.toList())
            TodoWidgetProvider.refresh(context)
        }
    }

    if (showInvalidDataDialog) {
        AlertDialog(
            onDismissRequest = {
                showInvalidDataDialog = false
                persistenceWarning = true
            },
            title = { Text("保存的数据无法读取") },
            text = {
                Text("待办数据格式无效，原有保存内容不会被覆盖。你可以重置保存数据，或继续使用但不保存更改。")
            },
            confirmButton = {
                TextButton(onClick = {
                    todoList.clear()
                    mayPersist = true
                    showInvalidDataDialog = false
                }) {
                    Text("重置保存数据")
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    showInvalidDataDialog = false
                    persistenceWarning = true
                }) {
                    Text("继续但不保存")
                }
            }
        )
    }

    if (isCreatingTask || editorTarget != null) {
        TodoEditorScreen(
            modifier = modifier,
            initialItem = editorTarget,
            onDismiss = {
                isCreatingTask = false
                editorTarget = null
            },
            onSave = { edited ->
                val existing = todoList.indexOfFirst { it.id == edited.id }
                // 编辑期间任务可能被通知/小组件改过（例如已完成）。
                // 保存时只覆盖用户能编辑的字段，不要把完成状态和循环链条冲掉。
                val saved = if (existing >= 0) {
                    val current = todoList[existing]
                    val merged = edited.copy(
                        isDone = current.isDone,
                        completedAt = current.completedAt,
                        recurrenceNextId = current.recurrenceNextId
                    )
                    cancelReminder(context, current)
                    todoList[existing] = merged
                    merged
                } else {
                    todoList.add(edited)
                    edited
                }
                // 提醒被关闭（-1）时不需要再申请通知/精准闹钟权限，也不该弹授权提示。
                if (saved.dueAt != null && !saved.isDone && saved.reminderMinutesBefore >= 0) {
                    if (
                        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                        ContextCompat.checkSelfPermission(
                            context,
                            Manifest.permission.POST_NOTIFICATIONS
                        ) != PackageManager.PERMISSION_GRANTED
                    ) {
                        pendingReminder = saved
                        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    } else if (notificationsAllowed) {
                        requestExactReminder(saved)
                    } else {
                        reminderPermissionWarning = true
                    }
                }
                isCreatingTask = false
                editorTarget = null
            },
            onDelete = editorTarget?.let { target ->
                {
                    isCreatingTask = false
                    editorTarget = null
                    deleteWithUndo(target)
                }
            },
            knownCategories = todoList.map { it.category.trim() }
                .filter { it.isNotEmpty() }
                .distinct()
                .sorted()
        )
        return
    }


    // 清空已完成确认
    if (showClearConfirm) {
        AlertDialog(
            onDismissRequest = { showClearConfirm = false },
            title = { Text("清空已完成") },
            text = { Text("确定要清空所有已完成的任务吗？") },
            confirmButton = {
                TextButton(
                    onClick = {
                        val cleared = todoList.filter { it.isDone }
                        cleared.forEach { item ->
                            cancelReminder(context, item)
                            clearEditorDraft(context, taskDraftKey(item.id))
                            captureTodoPayload(context, item.id)?.let { payload ->
                                appendTrash(
                                    context,
                                    TrashEntry(
                                        kind = TrashKind.TODO,
                                        id = item.id,
                                        payload = payload,
                                        deletedAt = System.currentTimeMillis()
                                    )
                                )
                            }
                        }
                        todoList.removeAll { it.isDone }
                        showClearConfirm = false
                        offerUndo("已清空 ${cleared.size} 条已完成") {
                            todoList.addAll(cleared)
                            cleared.forEach { item ->
                                // 撤销后这些任务还留在列表里，回收站里的副本必须同步移除
                                removeTrashEntry(context, TrashKind.TODO, item.id)
                                item.resetAt?.let { scheduleResetReminder(context, item) }
                            }
                        }
                    },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) { Text("清空") }
            },
            dismissButton = {
                TextButton(onClick = { showClearConfirm = false }) { Text("取消") }
            }
        )
    }

    BackHandler(enabled = secondaryPage != TodoSecondaryPage.LIST) {
        secondaryPage = TodoSecondaryPage.LIST
    }

    when (secondaryPage) {
        TodoSecondaryPage.CALENDAR -> {
            CalendarTodoPage(
                modifier = modifier,
                todos = todoList,
                selectedDate = selectedCalendarDate,
                onDateSelected = { selectedCalendarDate = it },
                onPlanTasks = { selectedIds ->
                    todoList.indices.forEach { index ->
                        val item = todoList[index]
                        val wasPlannedThisDay = item.plannedAt?.let {
                            isSameLocalDay(it, selectedCalendarDate)
                        } == true
                        if (wasPlannedThisDay || item.id in selectedIds) {
                            todoList[index] = item.copy(
                                plannedAt = selectedCalendarDate.takeIf { item.id in selectedIds }
                            )
                        }
                    }
                },
                onUnplanTask = { item ->
                    val index = todoList.indexOfFirst { it.id == item.id }
                    if (index >= 0) todoList[index] = item.copy(plannedAt = null)
                },
                onBack = { secondaryPage = TodoSecondaryPage.LIST }
            )
            return
        }
        TodoSecondaryPage.DAILY_REVIEW -> {
            val now = System.currentTimeMillis()
            val dayStart = startOfDay(now)
            val tomorrowStart = Calendar.getInstance().apply {
                timeInMillis = dayStart
                add(Calendar.DAY_OF_YEAR, 1)
            }.timeInMillis
            // 逾期、搁置多日的无期限任务，以及今天到期的任务，一起交给用户决定去留。
            val reviewItems = todoList.filter { item ->
                item.needsTriage(dayStart, now) ||
                    (!item.isDone &&
                        (item.reviewHiddenUntil == null || item.reviewHiddenUntil < now) &&
                        item.dueAt?.let { it < tomorrowStart } == true)
            }.sortedBy { it.dueAt ?: Long.MAX_VALUE }
            DailyTaskReviewPage(
                modifier = modifier,
                tasks = reviewItems,
                onMoveToToday = { item ->
                    val current = System.currentTimeMillis()
                    val index = todoList.indexOfFirst { it.id == item.id }
                    if (index >= 0) {
                        if (item.dueAt == null) {
                            // 没有截止时间的任务不该因为"整理一下"就凭空多出一个提醒时间，
                            // 只把它计划到今天，等用户自己决定什么时候做。
                            todoList[index] = item.copy(
                                plannedAt = dayStart,
                                reviewHiddenUntil = tomorrowStart - 1L
                            )
                        } else {
                            cancelReminder(context, item)
                            val dueToday = localDayTime(current, item.dueAt, 18)
                                .coerceAtLeast(current + 60_000L)
                            val updated = item.copy(
                                dueAt = dueToday,
                                reviewHiddenUntil = tomorrowStart - 1L
                            )
                            todoList[index] = updated
                            if (notificationsAllowed) scheduleReminder(context, updated)
                        }
                    }
                },
                onMoveToTomorrow = { item ->
                    val index = todoList.indexOfFirst { it.id == item.id }
                    if (index >= 0) {
                        if (item.dueAt == null) {
                            todoList[index] = item.copy(
                                plannedAt = tomorrowStart,
                                reviewHiddenUntil = null
                            )
                        } else {
                            cancelReminder(context, item)
                            val updated = item.copy(
                                dueAt = localDayTime(
                                    tomorrowStart,
                                    item.dueAt,
                                    9
                                ),
                                reviewHiddenUntil = null
                            )
                            todoList[index] = updated
                            if (notificationsAllowed) scheduleReminder(context, updated)
                        }
                    }
                },
                onDismissToday = { item ->
                    val endOfToday = tomorrowStart - 1L
                    val index = todoList.indexOfFirst { it.id == item.id }
                    if (index >= 0) {
                        todoList[index] = item.copy(reviewHiddenUntil = endOfToday)
                    }
                },
                onBack = { secondaryPage = TodoSecondaryPage.LIST }
            )
            return
        }
        TodoSecondaryPage.STATISTICS -> {
            CompletionStatisticsPage(
                modifier = modifier,
                history = completionHistory,
                historyWarning = completionHistoryWarning,
                onBack = { secondaryPage = TodoSecondaryPage.LIST }
            )
            return
        }
        TodoSecondaryPage.SETTINGS -> {
            SettingsScreen(
                modifier = modifier,
                appearanceMode = appearanceMode,
                onAppearanceChange = onAppearanceChange,
                density = density,
                onDensityChange = onDensityChange,
                notificationsAllowed = notificationsAllowed,
                exactAlarmsAllowed = exactAlarmsAllowed,
                onOpenNotificationSettings = {
                    if (
                        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                        ContextCompat.checkSelfPermission(
                            context,
                            Manifest.permission.POST_NOTIFICATIONS
                        ) != PackageManager.PERMISSION_GRANTED
                    ) {
                        notificationPermissionLauncher.launch(
                            Manifest.permission.POST_NOTIFICATIONS
                        )
                    } else {
                        notificationSettingsLauncher.launch(
                            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                                .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                        )
                    }
                },
                onOpenExactAlarmSettings = {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        exactAlarmSettingsLauncher.launch(
                            Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)
                                .setData(Uri.parse("package:${context.packageName}"))
                        )
                    }
                },
                onClearCompleted = {
                    if (todoList.any { it.isDone }) showClearConfirm = true
                },
                onDoNotDisturbChanged = { enabled, startMinute, endMinute ->
                    saveDoNotDisturb(context, enabled, startMinute, endMinute)
                    // 立刻按新窗口重排，否则已设好的闹钟要等下次编辑才生效。
                    todoList.filter { !it.isDone }.forEach { scheduleReminder(context, it) }
                },
                onBack = { secondaryPage = TodoSecondaryPage.LIST }
            )
            return
        }
        TodoSecondaryPage.LIST -> Unit
    }

    var categoryMenuExpanded by remember { mutableStateOf(false) }
    var moreMenuExpanded by remember { mutableStateOf(false) }
    var recurringChoiceFor by remember { mutableStateOf<TodoItem?>(null) }

    val completeTask: (TodoItem, RecurrenceCompletionChoice) -> Unit = { item, choice ->
        val index = todoList.indexOfFirst { it.id == item.id }
        if (index >= 0) {
            val updated = completeTodoItem(item, choice)
            todoList[index] = updated
            cancelReminder(context, item)
            val completedAt = updated.completedAt ?: System.currentTimeMillis()
            if (completionHistoryReady) {
                completionHistory = completionHistory + completedAt
                saveCompletionHistory(context, completionHistory)
            } else {
                completionHistoryWarning = true
            }
            // 重复任务不再复制新任务：同一条任务到点自动恢复。
            val resetAt = updated.resetAt
            if (resetAt != null) {
                // 即使之后没再打开 App，也要把"下一次"的提醒排上。
                scheduleResetReminder(context, updated)
                android.widget.Toast.makeText(
                    context,
                    "已完成，${formatDueTime(resetAt)} 自动恢复",
                    android.widget.Toast.LENGTH_SHORT
                ).show()
            } else if (choice == RecurrenceCompletionChoice.PAUSE) {
                android.widget.Toast.makeText(
                    context,
                    "已暂停重复",
                    android.widget.Toast.LENGTH_SHORT
                ).show()
            }
            offerUndo("已完成「${item.text}」") {
                val restoreIndex = todoList.indexOfFirst { it.id == updated.id }
                if (restoreIndex >= 0) todoList[restoreIndex] = item
                // 撤销完成要把统计里刚记的这次完成一起回滚，否则「今日完成/连续天数」会虚高。
                if (completionHistoryReady) {
                    val historyIndex = completionHistory.lastIndexOf(completedAt)
                    if (historyIndex >= 0) {
                        completionHistory = completionHistory.toMutableList().apply {
                            removeAt(historyIndex)
                        }
                        saveCompletionHistory(context, completionHistory)
                    }
                }
                // 恢复原提醒，并撤掉为重复任务排的「下次自动恢复」闹钟。
                cancelReminderForId(context, item.id)
                scheduleReminder(context, item)
            }
        }
    }

    // 逾期重复任务完成时，让用户决定下一次从哪天开始。
    // M3 没有三键对话框：这里把三个选项做成正文里的全宽按钮，语义和视觉都更清楚。
    recurringChoiceFor?.let { item ->
        AlertDialog(
            onDismissRequest = {
                completeTask(item, RecurrenceCompletionChoice.SKIP)
                recurringChoiceFor = null
            },
            title = { Text("这是重复任务") },
            text = {
                Column {
                    Text("「${item.text}」已经逾期。下一次从哪里开始算？")
                    Spacer(modifier = Modifier.height(8.dp))
                    TextButton(
                        onClick = {
                            completeTask(item, RecurrenceCompletionChoice.CONTINUE_FROM_TODAY)
                            recurringChoiceFor = null
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("从今天继续") }
                    TextButton(
                        onClick = {
                            completeTask(item, RecurrenceCompletionChoice.SKIP)
                            recurringChoiceFor = null
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("跳过本次") }
                    TextButton(
                        onClick = {
                            completeTask(item, RecurrenceCompletionChoice.PAUSE)
                            recurringChoiceFor = null
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("暂停重复") }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { recurringChoiceFor = null }) { Text("取消") }
            }
        )
    }

    Box(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp)
        ) {
        JotlistHeader(title = "待办") {
            Box {
                IconButton(onClick = { moreMenuExpanded = true }) {
                    Icon(Icons.Default.MoreVert, contentDescription = "更多选项")
                }
                DropdownMenu(
                    expanded = moreMenuExpanded,
                    onDismissRequest = { moreMenuExpanded = false }
                ) {
                    DropdownMenuItem(
                        text = { Text("清空已完成") },
                        enabled = todoList.any { it.isDone },
                        onClick = {
                            moreMenuExpanded = false
                            showClearConfirm = true
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("日历视图") },
                        onClick = {
                            moreMenuExpanded = false
                            secondaryPage = TodoSecondaryPage.CALENDAR
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("完成统计") },
                        onClick = {
                            moreMenuExpanded = false
                            secondaryPage = TodoSecondaryPage.STATISTICS
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("整理未完成任务") },
                        onClick = {
                            moreMenuExpanded = false
                            secondaryPage = TodoSecondaryPage.DAILY_REVIEW
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("设置") },
                        modifier = Modifier.testTag("open_settings"),
                        onClick = {
                            moreMenuExpanded = false
                            secondaryPage = TodoSecondaryPage.SETTINGS
                        }
                    )
                }
            }
        }

        if (persistenceWarning) {
            Text(
                text = "保存数据无法读取；当前更改不会保存。",
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall
            )
        }
        if (reminderPermissionWarning) {
            Text(
                text = "通知权限未开启，任务已保存，但不会发送提醒。",
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall
            )
        }
        if (exactAlarmWarning) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "精准提醒未授权，系统可能延迟通知。",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        exactAlarmSettingsLauncher.launch(
                            Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)
                                .setData(Uri.parse("package:${context.packageName}"))
                        )
                    }
                }) {
                    Text("启用精准提醒")
                }
            }
        }

        val nowMillis = System.currentTimeMillis()
        val todayStart = startOfDay(nowMillis)
        val triageCount = todoList.count { it.needsTriage(todayStart, nowMillis) }
        val todayFocusIds = todoList.filter {
            !it.isDone && it.focusDate?.let { day -> isSameLocalDay(day, nowMillis) } == true
        }.map { it.id }.toSet()
        // 以前是有逾期任务就自动跳到整理页，会打断"我只是想加一条"的场景；
        // 改成可关闭的横幅，既提醒又不夺走控制权。
        if (triageCount > 0 && !triageBannerDismissed) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp)
                    .testTag("triage_banner"),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer
                )
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, top = 8.dp, end = 4.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "有 $triageCount 项任务需要整理",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(
                        onClick = { secondaryPage = TodoSecondaryPage.DAILY_REVIEW }
                    ) { Text("去整理") }
                    IconButton(
                        onClick = {
                            triageBannerDismissed = true
                            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                                .edit()
                                .putString(KEY_TRIAGE_DISMISSED_DAY, triageDismissedDay)
                                .apply()
                        },
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(
                            Icons.Default.Close,
                            contentDescription = "关闭整理提示",
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }
        }

        FocusTodayCard(
            density = density,
            tasks = todoList.filter { it.id in todayFocusIds }.take(3),
            onPick = { showFocusPicker = true },
            onToggle = { task, checked ->
                // 走统一的完成逻辑，重复任务才会排上"自动恢复"。
                if (checked) {
                    completeTask(task, RecurrenceCompletionChoice.SKIP)
                    val index = todoList.indexOfFirst { it.id == task.id }
                    if (index >= 0) {
                        todoList[index] = todoList[index].copy(focusDate = null)
                    }
                }
            },
            onRemove = { task ->
                val index = todoList.indexOfFirst { it.id == task.id }
                if (index >= 0) todoList[index] = task.copy(focusDate = null)
            }
        )

        if (showFocusPicker) {
            FocusPickerDialog(
                candidates = todoList.filter { !it.isDone },
                selectedIds = todayFocusIds,
                onDismiss = { showFocusPicker = false },
                onConfirm = { selected ->
                    todoList.indices.forEach { index ->
                        val item = todoList[index]
                        when {
                            item.id in selected -> todoList[index] = item.copy(focusDate = todayStart)
                            item.id in todayFocusIds -> todoList[index] = item.copy(focusDate = null)
                        }
                    }
                    showFocusPicker = false
                }
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // 状态：只在「全部 / 进行中 / 已完成」之间切换
            SingleChoiceSegmentedButtonRow(modifier = Modifier.weight(1f)) {
                TodoFilter.entries.forEachIndexed { index, option ->
                    SegmentedButton(
                        selected = filter == option,
                        onClick = { filter = option },
                        colors = SegmentedButtonDefaults.colors(

                            activeContainerColor = MaterialTheme.colorScheme.primaryContainer,

                            activeContentColor = MaterialTheme.colorScheme.onPrimaryContainer

                        ),

                        shape = SegmentedButtonDefaults.itemShape(
                            index = index,
                            count = TodoFilter.entries.size
                        ),
                        modifier = Modifier.testTag("filter_${option.name}")
                    ) {
                        Text(option.label, maxLines = 1)
                    }
                }
            }
            // 分类是另一个正交维度，单独一个下拉，不再和状态混在一个菜单里
            Box {
                OutlinedButton(
                    onClick = { categoryMenuExpanded = true },
                    modifier = Modifier.testTag("category_filter"),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Text(
                        text = categoryFilter ?: "分类",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Icon(Icons.Default.ArrowDropDown, contentDescription = null)
                }
                DropdownMenu(
                    expanded = categoryMenuExpanded,
                    onDismissRequest = { categoryMenuExpanded = false }
                ) {
                    DropdownMenuItem(
                        text = { Text("全部分类", fontWeight = FontWeight.Bold) },
                        onClick = {
                            categoryFilter = null
                            categoryMenuExpanded = false
                        },
                        trailingIcon = { if (categoryFilter == null) Text("✓") }
                    )
                    todoList.map { it.category.trim() }.filter { it.isNotEmpty() }
                        .distinct().sorted().forEach { category ->
                            DropdownMenuItem(
                                text = { Text(category) },
                                onClick = {
                                    categoryFilter = category
                                    categoryMenuExpanded = false
                                },
                                trailingIcon = { if (categoryFilter == category) Text("✓") }
                            )
                        }
                }
            }
        }

        Spacer(modifier = Modifier.height(4.dp))

        val visibleTodos = todoList
            .filter { item ->
                val matchesStatus = when (filter) {
                    TodoFilter.ALL -> true
                    TodoFilter.ACTIVE -> !item.isDone
                    TodoFilter.COMPLETED -> item.isDone
                }
                matchesStatus &&
                    (categoryFilter == null ||
                        item.category.equals(categoryFilter, ignoreCase = true))
            }
            .sortedWith(
                compareByDescending<TodoItem> {
                    !it.isDone && it.id in todayFocusIds
                }
                    .thenByDescending {
                        !it.isDone && it.plannedAt?.let { plannedAt ->
                            isSameLocalDay(plannedAt, System.currentTimeMillis())
                        } == true
                    }
                    .thenByDescending { it.isPinned }
                    .thenBy { it.isDone }
                    .thenByDescending { it.priority }
                    .thenBy { it.dueAt ?: Long.MAX_VALUE }
                    .thenByDescending { it.createdAt }
            )

        // 从搜索/通知定位过来的任务：滚动到它并短暂高亮。
        LaunchedEffect(highlightedTodoId, visibleTodos) {
            val id = highlightedTodoId ?: return@LaunchedEffect
            val index = visibleTodos.indexOfFirst { it.id == id }
            if (index >= 0) todoListState.animateScrollToItem(index)
        }

        LazyColumn(
            state = todoListState,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(bottom = 80.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (visibleTodos.isEmpty()) {
                item {
                    EmptyState(
                        title = if (todoList.isEmpty()) "还没有任务" else "没有符合条件的任务",
                        subtitle = if (todoList.isEmpty()) {
                            "点右下角「新建待办」，或直接从桌面组件添加"
                        } else {
                            "换个筛选条件试试"
                        }
                    )
                }
            }
            items(visibleTodos, key = { it.id }) { item ->
                TodoRow(
                    density = density,
                    item = item,
                    onCheckedChange = { isChecked ->
                        if (isChecked) {
                            val overdue = item.dueAt?.let { it < System.currentTimeMillis() } == true
                            if (item.recurrence != Recurrence.NONE.name &&
                                item.recurrenceNextId == null &&
                                overdue
                            ) {
                                // 逾期重复任务：让用户决定下一次从哪天算，避免默默跳过好几天。
                                recurringChoiceFor = item
                            } else {
                                completeTask(item, RecurrenceCompletionChoice.SKIP)
                            }
                        } else {
                            val index = todoList.indexOfFirst { it.id == item.id }
                            if (index >= 0) {
                                val updated = item.copy(
                                    isDone = false,
                                    completedAt = null,
                                    resetAt = null
                                )
                                todoList[index] = updated
                                val nextId = item.recurrenceNextId
                                if (nextId != null) {
                                    val nextIndex = todoList.indexOfFirst { it.id == nextId }
                                    if (nextIndex >= 0 && !todoList[nextIndex].isDone) {
                                        cancelReminder(context, todoList[nextIndex])
                                        todoList.removeAt(nextIndex)
                                        todoList[index] = updated.copy(recurrenceNextId = null)
                                    }
                                }
                                if (updated.dueAt != null && notificationsAllowed) {
                                    exactAlarmWarning = !scheduleReminder(context, updated)
                                }
                            }
                        }
                    },
                    onEdit = { editorTarget = item },
                    linkedNoteTitle = item.linkedNoteId?.let(linkedNoteTitles::get),
                    onOpenNote = { noteId -> onOpenNote(noteId) },
                    onSubtaskChecked = { subtaskId, isChecked ->
                        val index = todoList.indexOfFirst { it.id == item.id }
                        if (index >= 0) {
                            val current = todoList[index]
                            todoList[index] = current.copy(
                                subtasks = current.subtasks.map { subtask ->
                                    if (subtask.id == subtaskId) {
                                        subtask.copy(isDone = isChecked)
                                    } else {
                                        subtask
                                    }
                                }
                            )
                        }
                    },
                    onReschedule = {
                        val newDue = Calendar.getInstance().apply {
                            timeInMillis = item.dueAt ?: System.currentTimeMillis()
                            add(Calendar.DAY_OF_YEAR, 1)
                            while (timeInMillis <= System.currentTimeMillis()) {
                                add(Calendar.DAY_OF_YEAR, 1)
                            }
                        }.timeInMillis
                        val updated = item.copy(
                            dueAt = newDue,
                            reviewHiddenUntil = null
                        )
                        val index = todoList.indexOfFirst { it.id == item.id }
                        if (index >= 0) todoList[index] = updated
                        cancelReminder(context, item)
                        if (notificationsAllowed) {
                            exactAlarmWarning = !scheduleReminder(context, updated)
                        }
                    },
                    onDelete = { deleteWithUndo(item) },
                    highlighted = highlightedTodoId == item.id,
                    onUnplan = {
                        val index = todoList.indexOfFirst { it.id == item.id }
                        if (index >= 0) {
                            todoList[index] = item.copy(plannedAt = null)
                        }
                    }
                )
            }
        }
        }
        ExtendedFloatingActionButton(
            onClick = { if (isReady) isCreatingTask = true },
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(16.dp)
                .testTag("add_task"),
            icon = { Icon(Icons.Default.Add, contentDescription = null) },
            text = { Text("新建待办") }
        )
    }
}

@Composable
internal fun CalendarTodoPage(
    modifier: Modifier,
    todos: List<TodoItem>,
    selectedDate: Long,
    onDateSelected: (Long) -> Unit,
    onPlanTasks: (Set<Long>) -> Unit,
    onUnplanTask: (TodoItem) -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    var showTaskPicker by remember(selectedDate) { mutableStateOf(false) }
    // 勾选状态每次打开选择器时按当前数据重算；之前只在进入页面时算一次，
    // 取消计划之后再打开会看到已经过期的勾选。
    var selectedTaskIds by remember(selectedDate) { mutableStateOf(emptySet<Long>()) }
    val selectedCalendar = Calendar.getInstance().apply { timeInMillis = selectedDate }
    val dayTodos = todos.filter { item ->
        item.plannedAt?.let { isSameLocalDay(it, selectedDate) } == true ||
            item.dueAt?.let { dueAt ->
            val dueCalendar = Calendar.getInstance().apply { timeInMillis = dueAt }
            dueCalendar.get(Calendar.YEAR) == selectedCalendar.get(Calendar.YEAR) &&
                dueCalendar.get(Calendar.DAY_OF_YEAR) ==
                selectedCalendar.get(Calendar.DAY_OF_YEAR)
        } == true
    }.sortedWith(
        compareByDescending<TodoItem> {
            !it.isDone && it.plannedAt?.let { plannedAt ->
                isSameLocalDay(plannedAt, selectedDate)
            } == true
        }.thenBy { it.dueAt ?: Long.MAX_VALUE }
    )

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        JotlistHeader(title = "日历", onBack = onBack)
        OutlinedButton(
            onClick = {
                DatePickerDialog(
                    context,
                    { _, year, month, day ->
                        val selected = Calendar.getInstance().apply {
                            timeInMillis = selectedDate
                            set(Calendar.YEAR, year)
                            set(Calendar.MONTH, month)
                            set(Calendar.DAY_OF_MONTH, day)
                        }
                        onDateSelected(selected.timeInMillis)
                    },
                    selectedCalendar.get(Calendar.YEAR),
                    selectedCalendar.get(Calendar.MONTH),
                    selectedCalendar.get(Calendar.DAY_OF_MONTH)
                ).show()
            }
        ) {
            Text("选择日期：${formatReminderDate(selectedDate)}")
            Icon(Icons.Default.ArrowDropDown, contentDescription = null)
        }
        OutlinedButton(
            onClick = {
                selectedTaskIds = todos.filter { item ->
                    !item.isDone &&
                        item.plannedAt?.let { isSameLocalDay(it, selectedDate) } == true
                }.map { it.id }.toSet()
                showTaskPicker = true
            },
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp)
                .testTag("plan_tasks_for_date")
        ) {
            Text("为这一天安排待办")
        }
        if (dayTodos.isEmpty()) {
            Text(
                "这一天没有安排待办",
                modifier = Modifier.padding(top = 24.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(vertical = 12.dp)
            ) {
                items(dayTodos, key = { it.id }) { item ->
                    val plannedThisDay = item.plannedAt?.let {
                        isSameLocalDay(it, selectedDate)
                    } == true
                    Card(Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier.padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                if (plannedThisDay) {
                                    "计划置顶"
                                } else {
                                    item.dueAt?.let(::formatTime).orEmpty()
                                },
                                color = MaterialTheme.colorScheme.primary
                            )
                            Text(
                                item.text,
                                modifier = Modifier.padding(start = 12.dp).weight(1f),
                                textDecoration = if (item.isDone) {
                                    TextDecoration.LineThrough
                                } else {
                                    TextDecoration.None
                                }
                            )
                            if (item.isDone) Text("已完成")
                            if (plannedThisDay) {
                                TextButton(
                                    onClick = { onUnplanTask(item) },
                                    modifier = Modifier.semantics {
                                        contentDescription = "取消${item.text}的计划"
                                    }
                                ) {
                                    Text("取消计划")
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    if (showTaskPicker) {
        val selectableTasks = todos.filter { !it.isDone }
        AlertDialog(
            onDismissRequest = { showTaskPicker = false },
            title = { Text("安排 ${formatReminderDate(selectedDate)} 的待办") },
            text = {
                if (selectableTasks.isEmpty()) {
                    Text("目前没有未完成的待办。")
                } else {
                    LazyColumn(
                        modifier = Modifier.heightIn(max = 360.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        items(selectableTasks, key = { it.id }) { task ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        selectedTaskIds = if (task.id in selectedTaskIds) {
                                            selectedTaskIds - task.id
                                        } else {
                                            selectedTaskIds + task.id
                                        }
                                    },
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Checkbox(
                                    checked = task.id in selectedTaskIds,
                                    onCheckedChange = { checked ->
                                        selectedTaskIds = if (checked) {
                                            selectedTaskIds + task.id
                                        } else {
                                            selectedTaskIds - task.id
                                        }
                                    }
                                )
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(task.text)
                                    task.plannedAt?.let { plannedAt ->
                                        val thisDay = isSameLocalDay(plannedAt, selectedDate)
                                        Text(
                                            if (thisDay) {
                                                "已计划在这一天"
                                            } else {
                                                "已计划 ${formatMonthDay(plannedAt)}，勾选可改到这一天"
                                            },
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onPlanTasks(selectedTaskIds)
                        showTaskPicker = false
                    }
                ) { Text("保存计划") }
            },
            dismissButton = {
                TextButton(onClick = { showTaskPicker = false }) { Text("取消") }
            }
        )
    }
}

@Composable
internal fun CompletionStatisticsPage(
    modifier: Modifier,
    history: List<Long>,
    historyWarning: Boolean,
    onBack: () -> Unit
) {
    val zone = ZoneId.systemDefault()
    val today = LocalDate.now(zone)
    val completionDays = history.map { timestamp ->
        Instant.ofEpochMilli(timestamp).atZone(zone).toLocalDate()
    }
    val weekStart = today.minusDays((today.dayOfWeek.value - 1).toLong())
    val todayCount = completionDays.count { it == today }
    val weekCount = completionDays.count { !it.isBefore(weekStart) && !it.isAfter(today) }
    val uniqueDays = completionDays.toSet()
    var streakStart = if (today in uniqueDays) today else today.minusDays(1)
    var streak = 0
    while (streakStart.minusDays(streak.toLong()) in uniqueDays) streak++

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        JotlistHeader(title = "完成统计", onBack = onBack)
        if (historyWarning) {
            Text(
                "完成记录暂时无法读取，统计可能不完整。",
                color = MaterialTheme.colorScheme.error
            )
        }
        StatisticsCard("今日完成", "${todayCount} 项")
        StatisticsCard("本周完成", "${weekCount} 项")
        StatisticsCard("连续完成", "$streak 天")
    }
}

@Composable
internal fun DailyTaskReviewPage(
    modifier: Modifier,
    tasks: List<TodoItem>,
    onMoveToToday: (TodoItem) -> Unit,
    onMoveToTomorrow: (TodoItem) -> Unit,
    onDismissToday: (TodoItem) -> Unit,
    onBack: () -> Unit
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
    ) {
        JotlistHeader(title = "整理未完成任务", onBack = onBack)
        Text(
            "把逾期、搁置多日或今天到期的任务重新安排，暂时不处理的会在明天再次出现。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 12.dp)
        )
        if (tasks.isEmpty()) {
            Text(
                "没有需要整理的任务。",
                modifier = Modifier.padding(top = 24.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(bottom = 16.dp)
            ) {
                items(tasks, key = { it.id }) { task ->
                    Card(Modifier.fillMaxWidth()) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(task.text, style = MaterialTheme.typography.titleMedium)
                            Text(
                                task.dueAt?.let { "截止 ${formatDueTime(it)}" }
                                    ?: "无截止时间 · 创建于 ${formatMonthDay(task.createdAt)}",
                                style = MaterialTheme.typography.bodySmall,
                                color = if (task.dueAt?.let { it < System.currentTimeMillis() } == true) {
                                    MaterialTheme.colorScheme.error
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                }
                            )
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                TextButton(onClick = { onMoveToToday(task) }) {
                                    Text("移到今天")
                                }
                                TextButton(onClick = { onMoveToTomorrow(task) }) {
                                    Text("明天再做")
                                }
                                TextButton(onClick = { onDismissToday(task) }) {
                                    Text("今天先忽略")
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun StatisticsCard(title: String, value: String) {
    Card(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(20.dp).fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(
                value,
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.primary
            )
        }
    }
}

@Composable
internal fun TodoRow(
    density: UiDensity,
    item: TodoItem,
    onCheckedChange: (Boolean) -> Unit,
    onSubtaskChecked: (Long, Boolean) -> Unit,
    onEdit: () -> Unit,
    linkedNoteTitle: String?,
    onOpenNote: (Long) -> Unit,
    onReschedule: () -> Unit,
    onDelete: () -> Unit,
    highlighted: Boolean = false,
    onUnplan: () -> Unit = {}
) {
    val now = System.currentTimeMillis()
    val overdue = !item.isDone && item.dueAt?.let { it < now } == true
    val stripeColor = when {
        item.isDone -> MaterialTheme.colorScheme.outlineVariant
        item.priority.coerceIn(0, 2) == 0 -> PriorityStripeLow
        item.priority.coerceIn(0, 2) == 1 -> PriorityStripeMedium
        else -> PriorityStripeHigh
    }
    var menuExpanded by remember { mutableStateOf(false) }

    // 一行元信息承载所有次要状态，避免多行 chip 把卡片撑高。
    val meta = buildList {
        item.dueAt?.let { due ->
            add(
                if (overdue) {
                    "逾期 ${daysBetween(due, now)} 天"
                } else {
                    "截止 ${formatDueTime(due)}"
                }
            )
        }
        item.plannedAt?.let { planned ->
            add(if (isSameLocalDay(planned, now)) "今日计划" else "计划 ${formatMonthDay(planned)}")
        }
        if (item.isPinned) add("置顶")
        if (item.category.isNotBlank()) add(item.category)
        Recurrence.entries.firstOrNull { it.name == item.recurrence }
            ?.takeIf { it != Recurrence.NONE }
            ?.let { add(it.label) }
        if (item.subtasks.isNotEmpty()) {
            add("步骤 ${item.subtasks.count { it.isDone }}/${item.subtasks.size}")
        }
        if (linkedNoteTitle != null) add("来自笔记")
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .drawWithContent {
                drawContent()
                drawRect(
                    color = stripeColor,
                    size = Size(4.dp.toPx(), size.height)
                )
            },
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(
            containerColor = when {
                highlighted -> MaterialTheme.colorScheme.primaryContainer
                item.isDone -> MaterialTheme.colorScheme.surfaceVariant
                else -> MaterialTheme.colorScheme.surfaceContainerLow
            }
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = density.rowMinHeight)
                // 整行点击进编辑；复选框和 ⋮ 各自消费自己的点击，不会冒泡到这里
                .clickable(onClick = onEdit)
                .padding(start = density.horizontalPadding, end = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 视觉上还是 34/40/44dp 的小方框，但热区撑到 48dp 无障碍最小值
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) { onCheckedChange(!item.isDone) }
                    .semantics {
                        contentDescription = if (item.isDone) {
                            "标记${item.text}为未完成"
                        } else {
                            "标记${item.text}为已完成"
                        }
                    },
                contentAlignment = Alignment.Center
            ) {
                Checkbox(
                    checked = item.isDone,
                    onCheckedChange = null,
                    modifier = Modifier.size(density.controlSize)
                )
            }
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(vertical = density.verticalPadding)
            ) {
                Text(
                    text = item.text,
                    fontSize = density.titleSize,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textDecoration = if (item.isDone) {
                        TextDecoration.LineThrough
                    } else {
                        TextDecoration.None
                    },
                    color = if (item.isDone) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    }
                )
                if (meta.isNotEmpty()) {
                    Text(
                        text = meta.joinToString(" · "),
                        fontSize = density.metaSize,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = if (overdue) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                }
            }
            Box {
                IconButton(
                    onClick = { menuExpanded = true },
                    modifier = Modifier.size(density.controlSize)
                ) {
                    Icon(
                        Icons.Default.MoreVert,
                        contentDescription = "任务操作",
                        modifier = Modifier.size(density.glyphSize),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                DropdownMenu(
                    expanded = menuExpanded,
                    onDismissRequest = { menuExpanded = false }
                ) {
                    DropdownMenuItem(
                        text = { Text("编辑") },
                        onClick = {
                            menuExpanded = false
                            onEdit()
                        }
                    )
                    if (overdue) {
                        DropdownMenuItem(
                            text = { Text("推迟一天") },
                            onClick = {
                                menuExpanded = false
                                onReschedule()
                            }
                        )
                    }
                    if (item.plannedAt != null) {
                        DropdownMenuItem(
                            text = { Text("取消计划") },
                            onClick = {
                                menuExpanded = false
                                onUnplan()
                            }
                        )
                    }
                    item.linkedNoteId?.let { noteId ->
                        DropdownMenuItem(
                            text = { Text("打开来源笔记") },
                            onClick = {
                                menuExpanded = false
                                onOpenNote(noteId)
                            }
                        )
                    }
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
        if (item.subtasks.isNotEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(
                        start = density.horizontalPadding + density.controlSize + 4.dp,
                        end = 8.dp,
                        bottom = 6.dp
                    ),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                item.subtasks.forEach { subtask ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("subtask_row_${subtask.id}"),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(
                            checked = subtask.isDone,
                            onCheckedChange = { onSubtaskChecked(subtask.id, it) },
                            modifier = Modifier
                                .size(density.controlSize - 6.dp)
                                .semantics {
                                    contentDescription =
                                        "标记步骤${subtask.text}${if (subtask.isDone) "为未完成" else "为已完成"}"
                                }
                        )
                        Text(
                            text = subtask.text,
                            fontSize = 13.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                            textDecoration = if (subtask.isDone) {
                                TextDecoration.LineThrough
                            } else {
                                TextDecoration.None
                            },
                            color = if (subtask.isDone) {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            }
                        )
                    }
                }
            }
        }
    }
}

/** 逾期天数，按自然日算，最少 1 天。 */
private fun daysBetween(from: Long, to: Long): Int {
    val days = ((startOfDay(to) - startOfDay(from)) / (24L * 60 * 60 * 1000)).toInt()
    return days.coerceAtLeast(1)
}

@Composable
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
internal fun TodoEditorScreen(
    modifier: Modifier = Modifier,
    initialItem: TodoItem?,
    onDismiss: () -> Unit,
    onSave: (TodoItem) -> Unit,
    onDelete: (() -> Unit)? = null,
    knownCategories: List<String> = emptyList()
) {
    // 草稿只活在本次编辑会话里，避免进程重建后把旧草稿塞回另一个任务。
    var text by remember(initialItem?.id) {
        mutableStateOf(initialItem?.text.orEmpty())
    }
    var category by remember(initialItem?.id) {
        mutableStateOf(initialItem?.category.orEmpty())
    }
    var priority by remember(initialItem?.id) {
        mutableStateOf(initialItem?.priority ?: 1)
    }
    var dueAtMillis by remember(initialItem?.id) {
        mutableStateOf(initialItem?.dueAt ?: 0L)
    }
    var reminderMinutesBefore by remember(initialItem?.id) {
        mutableStateOf(initialItem?.reminderMinutesBefore ?: 0)
    }
    var isPinned by remember(initialItem?.id) {
        mutableStateOf(initialItem?.isPinned ?: false)
    }
    var recurrence by remember(initialItem?.id) {
        mutableStateOf(
            Recurrence.entries.firstOrNull { it.name == initialItem?.recurrence }
                ?: Recurrence.NONE
        )
    }
    val subtasks = remember(initialItem?.id) {
        mutableStateListOf<TodoSubtask>().apply { addAll(initialItem?.subtasks.orEmpty()) }
    }
    var newSubtaskText by remember(initialItem?.id) { mutableStateOf("") }
    var recurrenceMenuExpanded by remember { mutableStateOf(false) }
    var reminderMenuExpanded by remember { mutableStateOf(false) }
    var categoryMenuExpanded by remember { mutableStateOf(false) }
    var showCategoryDialog by remember { mutableStateOf(false) }
    var categoryDraft by remember { mutableStateOf("") }
    // 保存被拦下时给一句人话，而不是让按钮默默灰掉
    var hintMessage by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    val priorityLabels = listOf("低", "中", "高")
    val dueAt = dueAtMillis.takeIf { it > 0L }
    // —— 草稿：防抖自动保存，重开时提示恢复 ——
    val taskDraft = taskDraftKey(initialItem?.id)
    var pendingDraft by remember { mutableStateOf<EditorDraft?>(null) }
    LaunchedEffect(taskDraft) {
        val draft = loadEditorDraft(context, taskDraft)
        if (draft != null &&
            shouldOfferDraftRestore(draft.savedAt, initialItem?.createdAt, initialItem == null)
        ) {
            pendingDraft = draft
        }
    }
    LaunchedEffect(text, category, priority, dueAtMillis, reminderMinutesBefore, isPinned, recurrence, subtasks.toList()) {
        if (pendingDraft != null) return@LaunchedEffect
        val changed = if (initialItem == null) {
            text.isNotBlank() || subtasks.isNotEmpty() ||
                dueAtMillis > 0L || category.isNotBlank()
        } else {
            // 保存时会 trim 标题/分类，这里同步按 trim 比较，纯空白差异不算草稿/未保存修改
            text.trim() != initialItem.text || category.trim() != initialItem.category ||
                priority != initialItem.priority ||
                dueAtMillis != (initialItem.dueAt ?: 0L) ||
                reminderMinutesBefore != initialItem.reminderMinutesBefore ||
                isPinned != initialItem.isPinned ||
                recurrence.name != initialItem.recurrence ||
                subtasks.toList() != initialItem.subtasks
        }
        if (!changed) {
            clearEditorDraft(context, taskDraft)
            return@LaunchedEffect
        }
        kotlinx.coroutines.delay(800)
        saveEditorDraft(
            context,
            taskDraft,
            todoToJson(
                TodoItem(
                    id = initialItem?.id ?: 0L,
                    text = text,
                    category = category,
                    priority = priority,
                    dueAt = dueAtMillis.takeIf { it > 0L },
                    reminderMinutesBefore = reminderMinutesBefore,
                    isPinned = isPinned,
                    recurrence = recurrence.name,
                    subtasks = subtasks.toList(),
                    createdAt = initialItem?.createdAt ?: System.currentTimeMillis()
                )
            )
        )
    }
    val calendar = Calendar.getInstance().apply {
        timeInMillis = dueAt ?: System.currentTimeMillis()
    }
    val invalidDueTime = dueAt?.let { it <= System.currentTimeMillis() } ?: false

    // —— 编辑态保护：系统返回键不再直接退出 App，有改动先确认 ——
    val dirty = if (initialItem == null) {
        text.isNotBlank() || category.isNotBlank() || subtasks.isNotEmpty() || dueAtMillis > 0L
    } else {
        // 与保存逻辑一致：trim 后比较
        text.trim() != initialItem.text ||
            category.trim() != initialItem.category ||
            priority != initialItem.priority ||
            dueAtMillis != (initialItem.dueAt ?: 0L) ||
            reminderMinutesBefore != initialItem.reminderMinutesBefore ||
            isPinned != initialItem.isPinned ||
            recurrence.name != initialItem.recurrence ||
            subtasks.toList() != initialItem.subtasks
    }
    var showDiscardConfirm by remember { mutableStateOf(false) }

    fun attemptDismiss() {
        if (dirty) {
            showDiscardConfirm = true
        } else {
            clearEditorDraft(context, taskDraftKey(initialItem?.id))
            onDismiss()
        }
    }

    BackHandler { attemptDismiss() }

    // 新建任务时自动聚焦并弹键盘：少点一次输入框
    val titleFocus = remember { FocusRequester() }
    LaunchedEffect(initialItem?.id) {
        if (initialItem == null) runCatching { titleFocus.requestFocus() }
    }

    fun persist() {
        clearEditorDraft(context, taskDraftKey(initialItem?.id))
        onSave(
            TodoItem(
                id = initialItem?.id ?: nextTodoId(),
                text = text.trim(),
                isDone = initialItem?.isDone ?: false,
                createdAt = initialItem?.createdAt ?: System.currentTimeMillis(),
                dueAt = dueAt,
                category = category.trim(),
                priority = priority,
                reminderMinutesBefore = reminderMinutesBefore,
                isPinned = isPinned,
                recurrence = recurrence.name,
                subtasks = subtasks.toList(),
                recurrenceNextId = initialItem?.recurrenceNextId,
                linkedNoteId = initialItem?.linkedNoteId,
                completedAt = initialItem?.completedAt,
                plannedAt = initialItem?.plannedAt,
                focusDate = initialItem?.focusDate,
                reviewHiddenUntil = null
            )
        )
    }

    fun attemptSave() {
        if (text.isBlank()) {
            hintMessage = "先写点什么，再保存"
            titleFocus.requestFocus()
            return
        }
        if (invalidDueTime) {
            hintMessage = "截止时间早于现在，请重新选择"
            return
        }
        hintMessage = null
        persist()
    }

    pendingDraft?.let { draft ->
        AlertDialog(
            onDismissRequest = { pendingDraft = null },
            title = { Text("恢复上次的草稿？") },
            text = { Text("这个任务上次没有保存完，可以接着编辑，也可以丢掉重新开始。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        runCatching { todoFromJson(draft.payload) }.getOrNull()?.let { restored ->
                            text = restored.text
                            category = restored.category
                            priority = restored.priority
                            dueAtMillis = restored.dueAt ?: 0L
                            reminderMinutesBefore = restored.reminderMinutesBefore
                            isPinned = restored.isPinned
                            recurrence = Recurrence.entries
                                .firstOrNull { it.name == restored.recurrence } ?: Recurrence.NONE
                            subtasks.clear()
                            subtasks.addAll(restored.subtasks)
                        }
                        pendingDraft = null
                    },
                    modifier = Modifier.testTag("draft_restore")
                ) { Text("恢复") }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        clearEditorDraft(context, taskDraftKey(initialItem?.id))
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
            text = { Text("未保存的修改会丢失。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDiscardConfirm = false
                        clearEditorDraft(context, taskDraftKey(initialItem?.id))
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

    if (showCategoryDialog) {
        AlertDialog(
            onDismissRequest = { showCategoryDialog = false },
            title = { Text("新建分类") },
            text = {
                OutlinedTextField(
                    value = categoryDraft,
                    onValueChange = { categoryDraft = it },
                    singleLine = true,
                    label = { Text("分类名称") }
                )
            },
            confirmButton = {
                TextButton(
                    enabled = categoryDraft.isNotBlank(),
                    onClick = {
                        category = categoryDraft.trim()
                        showCategoryDialog = false
                    }
                ) { Text("创建") }
            },
            dismissButton = {
                TextButton(onClick = { showCategoryDialog = false }) { Text("取消") }
            }
        )
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .imePadding()
            .padding(horizontal = 20.dp)
    ) {
        // 顶栏：返回 + 标题 + 主操作（保存不再和取消同级）
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
                text = if (initialItem == null) "新建任务" else "编辑任务",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 4.dp)
            )
            FilledTonalButton(
                colors = ButtonDefaults.filledTonalButtonColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                ),
                onClick = { attemptSave() },
                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
                modifier = Modifier.testTag("save_task")
            ) {
                Text("保存")
            }
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
        ) {
            // 页面唯一的大字号元素：无边框、18sp/26sp，内容优先
            BasicTextField(
                value = text,
                onValueChange = {
                    text = it
                    if (hintMessage != null) hintMessage = null
                },
                textStyle = MaterialTheme.typography.titleMedium.copy(
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = 18.sp,
                    lineHeight = 26.sp
                ),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { attemptSave() }),
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(titleFocus)
                    .testTag("task_text_input"),
                decorationBox = { innerTextField ->
                    Box {
                        if (text.isEmpty()) {
                            Text(
                                text = "写下一件要做的事…",
                                style = MaterialTheme.typography.titleMedium.copy(fontSize = 18.sp),
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
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
            HorizontalDivider(
                modifier = Modifier.padding(top = 16.dp, bottom = 4.dp),
                color = MaterialTheme.colorScheme.outlineVariant
            )

            // 分类：已有分类点选，避免手打重复
            Box {
                EditorFieldRow(
                    icon = Icons.Default.Label,
                    label = "分类",
                    value = category.ifBlank { "未分类" },
                    filled = category.isNotBlank(),
                    onClick = { categoryMenuExpanded = true }
                )
                DropdownMenu(
                    expanded = categoryMenuExpanded,
                    onDismissRequest = { categoryMenuExpanded = false }
                ) {
                    DropdownMenuItem(
                        text = { Text("未分类") },
                        onClick = {
                            category = ""
                            categoryMenuExpanded = false
                        },
                        trailingIcon = { if (category.isBlank()) Text("✓") }
                    )
                    knownCategories.forEach { option ->
                        DropdownMenuItem(
                            text = { Text(option) },
                            onClick = {
                                category = option
                                categoryMenuExpanded = false
                            },
                            trailingIcon = { if (category == option) Text("✓") }
                        )
                    }
                    HorizontalDivider()
                    DropdownMenuItem(
                        text = { Text("新建分类…") },
                        onClick = {
                            categoryDraft = category
                            categoryMenuExpanded = false
                            showCategoryDialog = true
                        }
                    )
                }
            }

            // 优先级：三段按钮，色点与列表左侧色条同源
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 52.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "优先级",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.width(12.dp))
                SingleChoiceSegmentedButtonRow(modifier = Modifier.weight(1f)) {
                    priorityLabels.forEachIndexed { index, label ->
                        SegmentedButton(
                            selected = priority == index,
                            onClick = { priority = index },
                            shape = SegmentedButtonDefaults.itemShape(index, priorityLabels.size),
                            colors = SegmentedButtonDefaults.colors(
                                activeContainerColor = MaterialTheme.colorScheme.primaryContainer,
                                activeContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    modifier = Modifier
                                        .size(6.dp)
                                        .clip(CircleShape)
                                        .background(
                                            when (index) {
                                                0 -> PriorityStripeLow
                                                1 -> PriorityStripeMedium
                                                else -> PriorityStripeHigh
                                            }
                                        )
                                )
                                Spacer(Modifier.width(6.dp))
                                Text(label, style = MaterialTheme.typography.labelLarge)
                            }
                        }
                    }
                }
            }

            // 截止：一行字段 + 快捷项，避免"选日期→再选时间"两步走
            Box {
                EditorFieldRow(
                    icon = Icons.Default.Schedule,
                    label = "截止",
                    value = dueAt?.let { "${formatReminderDate(it)} ${formatTime(it)}" } ?: "未设置",
                    filled = dueAt != null,
                    onClick = {
                        DatePickerDialog(
                            context,
                            { _, year, month, day ->
                                val updated = Calendar.getInstance().apply {
                                    timeInMillis = dueAt ?: System.currentTimeMillis()
                                    set(Calendar.YEAR, year)
                                    set(Calendar.MONTH, month)
                                    set(Calendar.DAY_OF_MONTH, day)
                                    if (dueAt == null) {
                                        // 只选日期时默认 09:00，避免沿用"现在"导致立刻逾期
                                        set(Calendar.HOUR_OF_DAY, 9)
                                        set(Calendar.MINUTE, 0)
                                    }
                                    set(Calendar.SECOND, 0)
                                    set(Calendar.MILLISECOND, 0)
                                }
                                dueAtMillis = if (updated.timeInMillis <= System.currentTimeMillis()) {
                                    nextWholeHour().timeInMillis
                                } else {
                                    updated.timeInMillis
                                }
                            },
                            calendar.get(Calendar.YEAR),
                            calendar.get(Calendar.MONTH),
                            calendar.get(Calendar.DAY_OF_MONTH)
                        ).show()
                    }
                )
            }
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                listOf(
                    "今天 18:00" to quickTodayEvening(),
                    "明天 9:00" to quickTomorrowMorning(),
                    "下周一 9:00" to quickNextMonday()
                ).forEach { (label, value) ->
                    AssistChip(
                        onClick = { dueAtMillis = value },
                        label = { Text(label, style = MaterialTheme.typography.labelMedium) }
                    )
                }
                AssistChip(
                    onClick = {
                        val base = Calendar.getInstance().apply {
                            timeInMillis = dueAt ?: System.currentTimeMillis()
                        }
                        TimePickerDialog(
                            context,
                            { _, hour, minute ->
                                val picked = Calendar.getInstance().apply {
                                    timeInMillis = dueAt ?: System.currentTimeMillis()
                                    set(Calendar.HOUR_OF_DAY, hour)
                                    set(Calendar.MINUTE, minute)
                                    set(Calendar.SECOND, 0)
                                    set(Calendar.MILLISECOND, 0)
                                }
                                dueAtMillis = if (picked.timeInMillis <= System.currentTimeMillis()) {
                                    nextWholeHour().timeInMillis
                                } else {
                                    picked.timeInMillis
                                }
                            },
                            base.get(Calendar.HOUR_OF_DAY),
                            base.get(Calendar.MINUTE),
                            true
                        ).show()
                    },
                    label = { Text("自定义时间", style = MaterialTheme.typography.labelMedium) }
                )
                if (dueAt != null) {
                    AssistChip(
                        onClick = { dueAtMillis = 0L },
                        modifier = Modifier.testTag("clear_due"),
                        label = { Text("清除", style = MaterialTheme.typography.labelMedium) }
                    )
                }
            }
            if (invalidDueTime) {
                Text(
                    text = "截止时间必须晚于当前时间。",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(bottom = 4.dp)
                )
            }

            // 提醒：只有设了截止时间才出现；支持关闭
            if (dueAt != null) {
                Box {
                    EditorFieldRow(
                        icon = Icons.Default.Notifications,
                        label = "提醒",
                        value = when {
                            reminderMinutesBefore < 0 -> "关闭"
                            reminderMinutesBefore == 0 -> "到期时"
                            else -> "提前 $reminderMinutesBefore 分钟"
                        },
                        filled = reminderMinutesBefore >= 0,
                        onClick = { reminderMenuExpanded = true }
                    )
                    DropdownMenu(
                        expanded = reminderMenuExpanded,
                        onDismissRequest = { reminderMenuExpanded = false }
                    ) {
                        listOf(
                            -1 to "关闭",
                            0 to "准时",
                            5 to "提前 5 分钟",
                            10 to "提前 10 分钟",
                            30 to "提前 30 分钟",
                            60 to "提前 1 小时"
                        ).forEach { (minutes, label) ->
                            DropdownMenuItem(
                                text = { Text(label) },
                                onClick = {
                                    reminderMinutesBefore = minutes
                                    reminderMenuExpanded = false
                                },
                                trailingIcon = {
                                    if (reminderMinutesBefore == minutes) Text("✓")
                                }
                            )
                        }
                    }
                }
            }

            Box {
                EditorFieldRow(
                    icon = Icons.Default.Repeat,
                    label = "重复",
                    value = recurrence.label,
                    filled = recurrence != Recurrence.NONE,
                    onClick = { recurrenceMenuExpanded = true }
                )
                DropdownMenu(
                    expanded = recurrenceMenuExpanded,
                    onDismissRequest = { recurrenceMenuExpanded = false }
                ) {
                    Recurrence.entries.forEach { option ->
                        DropdownMenuItem(
                            text = { Text(option.label) },
                            onClick = {
                                recurrence = option
                                recurrenceMenuExpanded = false
                            },
                            trailingIcon = { if (recurrence == option) Text("✓") }
                        )
                    }
                }
            }

            Spacer(Modifier.height(8.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Spacer(Modifier.height(8.dp))

            Text(
                text = "步骤 ${subtasks.count { it.isDone }}/${subtasks.size}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 8.dp)
            )
            subtasks.forEachIndexed { index, subtask ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(
                        checked = subtask.isDone,
                        onCheckedChange = { checked ->
                            subtasks[index] = subtask.copy(isDone = checked)
                        }
                    )
                    Text(
                        text = subtask.text,
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyLarge,
                        textDecoration = if (subtask.isDone) {
                            TextDecoration.LineThrough
                        } else {
                            TextDecoration.None
                        },
                        color = if (subtask.isDone) {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        }
                    )
                    IconButton(onClick = { subtasks.removeAt(index) }) {
                        Icon(Icons.Default.Delete, contentDescription = "删除步骤")
                    }
                }
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp)
                )
                BasicTextField(
                    value = newSubtaskText,
                    onValueChange = { newSubtaskText = it },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(
                        color = MaterialTheme.colorScheme.onSurface
                    ),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(
                        onDone = {
                            if (newSubtaskText.isNotBlank()) {
                                subtasks.add(TodoSubtask(text = newSubtaskText.trim()))
                                newSubtaskText = ""
                            }
                        }
                    ),
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = 8.dp)
                        .testTag("subtask_input"),
                    decorationBox = { innerTextField ->
                        Box {
                            if (newSubtaskText.isEmpty()) {
                                Text(
                                    text = "添加步骤，回车即添加",
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                                )
                            }
                            innerTextField()
                        }
                    }
                )
                TextButton(
                    enabled = newSubtaskText.isNotBlank(),
                    onClick = {
                        subtasks.add(TodoSubtask(text = newSubtaskText.trim()))
                        newSubtaskText = ""
                    },
                    modifier = Modifier.testTag("add_subtask")
                ) { Text("添加") }
            }

            Spacer(Modifier.height(16.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 56.dp)
                    .clickable { isPinned = !isPinned },
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("置顶", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        text = "在列表中靠前显示",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = isPinned,
                    onCheckedChange = { isPinned = it },
                    modifier = Modifier.testTag("pin_task")
                )
            }

            if (initialItem != null && onDelete != null) {
                TextButton(
                    onClick = {
                        clearEditorDraft(context, taskDraftKey(initialItem.id))
                        onDelete()
                    },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    ),
                    modifier = Modifier
                        .padding(top = 8.dp)
                        .testTag("delete_task")
                ) { Text("删除任务") }
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun EditorSection(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            title,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        content()
    }
}


/**
 * 今日三件事：把当天最重要的任务固定在最上方。
 * 任务多的时候先看这 3 条，降低"看不完"的压力。
 */
@Composable
private fun FocusTodayCard(
    density: UiDensity,
    tasks: List<TodoItem>,
    onPick: () -> Unit,
    onToggle: (TodoItem, Boolean) -> Unit,
    onRemove: (TodoItem) -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 6.dp)
            .testTag("focus_card"),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    start = density.horizontalPadding,
                    top = 4.dp,
                    end = 12.dp,
                    bottom = 4.dp
                ),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "今日三件事",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f)
                )
                if (tasks.isEmpty()) {
                    Text(
                        "先挑 3 件最重要的",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                TextButton(
                    onClick = onPick,
                    modifier = Modifier.testTag("focus_pick")
                ) {
                    Text(if (tasks.isEmpty()) "挑选" else "调整")
                }
            }
            if (tasks.isNotEmpty()) {
                tasks.forEach { task ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = false,
                            onCheckedChange = { checked -> onToggle(task, checked) },
                            modifier = Modifier
                                .size(density.controlSize)
                                .semantics {
                                    contentDescription = "完成今日重点${task.text}"
                                }
                        )
                        Text(
                            task.text,
                            fontSize = density.titleSize,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(
                            onClick = { onRemove(task) },
                            modifier = Modifier.size(density.controlSize)
                        ) {
                            Icon(
                                Icons.Default.Close,
                                contentDescription = "移出今日三件事",
                                modifier = Modifier.size(density.glyphSize)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FocusPickerDialog(
    candidates: List<TodoItem>,
    selectedIds: Set<Long>,
    onDismiss: () -> Unit,
    onConfirm: (Set<Long>) -> Unit
) {
    var selected by remember { mutableStateOf(selectedIds) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("今日三件事") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    "最多选 3 件今天最重要的事。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (candidates.isEmpty()) {
                    Text("没有未完成的任务。", modifier = Modifier.padding(top = 16.dp))
                } else {
                    LazyColumn(modifier = Modifier.heightIn(max = 360.dp)) {
                        items(candidates, key = { it.id }) { task ->
                            val checked = task.id in selected
                            val enabled = checked || selected.size < 3
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable(enabled = enabled) {
                                        selected = if (checked) {
                                            selected - task.id
                                        } else {
                                            selected + task.id
                                        }
                                    },
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Checkbox(
                                    checked = checked,
                                    enabled = enabled,
                                    onCheckedChange = null
                                )
                                Text(task.text, modifier = Modifier.weight(1f))
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(selected) }) { Text("保存") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}

@Preview(showBackground = true)
@Composable
fun TodoPreview() {
    MyApplicationTheme {
        TodoAppScreen()
    }
}

/** 编辑器里的一行属性：图标 + 标签 + 右值 + chevron，有值时图标用主色。 */
@Composable
private fun EditorFieldRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    value: String,
    filled: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = if (filled) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            modifier = Modifier.size(18.dp)
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 12.dp)
        )
        Spacer(Modifier.weight(1f))
        Text(
            text = value,
            style = MaterialTheme.typography.bodyLarge,
            color = if (filled) {
                MaterialTheme.colorScheme.onSurface
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 12.dp)
        )
        Icon(
            imageVector = Icons.Default.ChevronRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp)
        )
    }
}

/** 今天 18:00，已过则顺延到明天同一时刻。 */
private fun quickTodayEvening(): Long = Calendar.getInstance().apply {
    set(Calendar.HOUR_OF_DAY, 18)
    set(Calendar.MINUTE, 0)
    set(Calendar.SECOND, 0)
    set(Calendar.MILLISECOND, 0)
    if (timeInMillis <= System.currentTimeMillis()) add(Calendar.DAY_OF_MONTH, 1)
}.timeInMillis

/** 明天 09:00。 */
private fun quickTomorrowMorning(): Long = Calendar.getInstance().apply {
    add(Calendar.DAY_OF_MONTH, 1)
    set(Calendar.HOUR_OF_DAY, 9)
    set(Calendar.MINUTE, 0)
    set(Calendar.SECOND, 0)
    set(Calendar.MILLISECOND, 0)
}.timeInMillis

/** 下周一 09:00（今天是周一则取 7 天后）。 */
private fun quickNextMonday(): Long = Calendar.getInstance().apply {
    val delta = (Calendar.MONDAY - get(Calendar.DAY_OF_WEEK) + 7) % 7
    add(Calendar.DAY_OF_MONTH, if (delta == 0) 7 else delta)
    set(Calendar.HOUR_OF_DAY, 9)
    set(Calendar.MINUTE, 0)
    set(Calendar.SECOND, 0)
    set(Calendar.MILLISECOND, 0)
}.timeInMillis

/** 下一个整点：只选日期但时间已过时兜底，避免存进去就是逾期。 */
private fun nextWholeHour(): Calendar = Calendar.getInstance().apply {
    add(Calendar.HOUR_OF_DAY, 1)
    set(Calendar.MINUTE, 0)
    set(Calendar.SECOND, 0)
    set(Calendar.MILLISECOND, 0)
}
