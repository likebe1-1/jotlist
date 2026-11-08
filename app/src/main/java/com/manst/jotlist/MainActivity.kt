package com.manst.jotlist

import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.platform.testTag
import androidx.compose.animation.core.tween
import com.manst.jotlist.ui.theme.MyApplicationTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        pruneTrashNow(this)
        // 关闭系统导航条的对比蒙层，底栏颜色才能一直铺到屏幕底部。
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
        }
        // 启动意图里的指令是一次性的：读完立即清除，避免转屏/重建时反复弹窗。
        val openAddFromWidget =
            intent.getBooleanExtra(TodoWidgetProvider.EXTRA_OPEN_ADD_TODO, false)
        val openQuickCaptureFromWidget =
            intent.getBooleanExtra(TodoWidgetProvider.EXTRA_OPEN_QUICK_CAPTURE, false)
        val openNotesFromWidget =
            intent.getBooleanExtra(NotesWidgetProvider.EXTRA_OPEN_NOTES, false)
        val openNoteIdFromWidget = intent.getLongExtra(NotesWidgetProvider.EXTRA_OPEN_NOTE_ID, 0L)
            .takeIf { it > 0L }
        val openTodoFromNotification = intent.getLongExtra(EXTRA_OPEN_TODO_ID, 0L)
            .takeIf { it > 0L }
        intent.removeExtra(TodoWidgetProvider.EXTRA_OPEN_ADD_TODO)
        intent.removeExtra(TodoWidgetProvider.EXTRA_OPEN_QUICK_CAPTURE)
        intent.removeExtra(NotesWidgetProvider.EXTRA_OPEN_NOTES)
        intent.removeExtra(NotesWidgetProvider.EXTRA_OPEN_NOTE_ID)
        intent.removeExtra(EXTRA_OPEN_TODO_ID)
        setContent {
            // 首次启动先展示品牌页；点"开始使用"后写入标记，之后不再出现。
            var showWelcome by remember { mutableStateOf(!welcomeShown(this@MainActivity)) }
            var appearanceMode by rememberSaveable { mutableStateOf(readAppearance(this@MainActivity)) }
            var uiDensity by rememberSaveable { mutableStateOf(readUiDensity(this@MainActivity)) }
            var destination by rememberSaveable {
                mutableStateOf(
                    if (openQuickCaptureFromWidget || openNotesFromWidget || openNoteIdFromWidget != null) {
                        MainDestination.NOTES
                    } else {
                        MainDestination.TODO
                    }
                )
            }
            var noteEditorVisible by remember { mutableStateOf(false) }
            var requestedNoteId by rememberSaveable { mutableStateOf(openNoteIdFromWidget) }
            var requestedTodoId by rememberSaveable {
                mutableStateOf(openTodoFromNotification)
            }
            // 这些是"一次性"启动指令：被对应界面消费后必须清掉，
            // 否则每次切回该 Tab（界面会被重建）都会再弹一次窗。
            var pendingOpenAddTodo by remember { mutableStateOf(openAddFromWidget) }
            var pendingQuickCapture by remember { mutableStateOf(openQuickCaptureFromWidget) }
            // 进 App 时顺手把两枚桌面组件刷一遍：系统在覆盖安装后不一定会重新请求，
            // 否则组件会停在 initialLayout 的空壳状态。
            LaunchedEffect(Unit) {
                TodoWidgetProvider.refresh(this@MainActivity)
                NotesWidgetProvider.refresh(this@MainActivity)
            }
            val darkTheme = when (appearanceMode) {
                AppearanceMode.SYSTEM -> androidx.compose.foundation.isSystemInDarkTheme()
                AppearanceMode.LIGHT -> false
                AppearanceMode.DARK -> true
            }
            // 引导页是深色渐变，状态栏/导航栏图标必须转成浅色，否则看不清。
            LaunchedEffect(showWelcome, darkTheme) {
                val controller = androidx.core.view.WindowCompat.getInsetsController(
                    window,
                    window.decorView
                )
                val lightIcons = showWelcome || darkTheme
                controller.isAppearanceLightStatusBars = !lightIcons
                controller.isAppearanceLightNavigationBars = !lightIcons
            }
            MyApplicationTheme(
                darkTheme = darkTheme
            ) {
                if (showWelcome) {
                    WelcomeScreen(
                        onStart = {
                            markWelcomeShown(this@MainActivity)
                            showWelcome = false
                        }
                    )
                } else {
                    val snackbarHostState = remember { SnackbarHostState() }
                    Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    snackbarHost = { SnackbarHost(snackbarHostState) },
                    bottomBar = {
                        if (!noteEditorVisible) {
                            // 底栏背景要一并覆盖到系统导航条区域，否则会出现一条白边。
                            Surface(color = NavigationBarDefaults.containerColor) {
                                NavigationBar(
                                    windowInsets = WindowInsets(0, 0, 0, 0),
                                    modifier = Modifier.navigationBarsPadding()
                                ) {
                                    MainDestination.entries.forEach { tab ->
                                        NavigationBarItem(
                                            selected = destination == tab,
                                            onClick = { destination = tab },
                                            colors = NavigationBarItemDefaults.colors(
                                                indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                                                selectedIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                                selectedTextColor = MaterialTheme.colorScheme.onSurface
                                            ),
                                            icon = {
                                                Icon(
                                                    imageVector = when (tab) {
                                                        MainDestination.TODO -> Icons.Default.CheckCircle
                                                        MainDestination.NOTES -> Icons.Default.Edit
                                                        MainDestination.SEARCH -> Icons.Default.Search
                                                    },
                                                    contentDescription = null
                                                )
                                            },
                                        label = { Text(tab.label) },
                                        modifier = Modifier.testTag("navigation_${tab.label}")
                                    )
                                }
                            }
                        }
                    }
                }
                ) { innerPadding ->
                    AnimatedContent(
                        targetState = destination,
                        transitionSpec = {
                            fadeIn(animationSpec = tween(220)) togetherWith
                                fadeOut(animationSpec = tween(160))
                        },
                        label = "main_destination_transition"
                    ) { page ->
                        when (page) {
                            MainDestination.TODO -> TodoAppScreen(
                                modifier = Modifier
                    .padding(innerPadding)
                    .consumeWindowInsets(innerPadding),
                                appearanceMode = appearanceMode,
                                density = uiDensity,
                                snackbarHostState = snackbarHostState,
                                initialOpenAddTodo = pendingOpenAddTodo,
                                onInitialAddTodoConsumed = { pendingOpenAddTodo = false },
                                initialFocusTodoId = requestedTodoId,
                                onFocusHandled = { requestedTodoId = null },
                                onOpenNote = { noteId ->
                                    requestedNoteId = noteId
                                    destination = MainDestination.NOTES
                                },
                                onAppearanceChange = { mode ->
                                    appearanceMode = mode
                                    saveAppearance(this@MainActivity, mode)
                                },
                                onDensityChange = { density ->
                                    uiDensity = density
                                    saveUiDensity(this@MainActivity, density)
                                },
                                onEditorVisibilityChange = { noteEditorVisible = it }
                            )
                            MainDestination.NOTES -> NotesScreen(
                                modifier = Modifier
                    .padding(innerPadding)
                    .consumeWindowInsets(innerPadding),
                                initialOpenQuickCapture = pendingQuickCapture,
                                onInitialQuickCaptureConsumed = { pendingQuickCapture = false },
                                onEditorVisibilityChange = { noteEditorVisible = it },
                                openNoteId = requestedNoteId,
                                onOpenNoteHandled = { requestedNoteId = null },
                                onOpenTodo = { todoId ->
                                    requestedTodoId = todoId
                                    destination = MainDestination.TODO
                                },
                                onCreateLinkedTodo = { todo ->
                                    when (val loaded = loadTodoList(this@MainActivity)) {
                                        is TodoListLoadResult.InvalidJson -> false
                                        is TodoListLoadResult.Loaded -> {
                                            val existing = loaded.items.firstOrNull {
                                                it.linkedNoteId == todo.linkedNoteId && !it.isDone
                                            }
                                            if (existing == null) {
                                                saveTodoList(this@MainActivity, loaded.items + todo)
                                                reserveLoadedTodoIds(loaded.items + todo)
                                                TodoWidgetProvider.refresh(this@MainActivity)
                                            }
                                            noteEditorVisible = false
                                            destination = MainDestination.TODO
                                            true
                                        }
                                    }
                                }
                            )
                            MainDestination.SEARCH -> UnifiedSearchScreen(
                                modifier = Modifier
                    .padding(innerPadding)
                    .consumeWindowInsets(innerPadding),
                                onOpenTodo = { todoId ->
                                    requestedTodoId = todoId
                                    destination = MainDestination.TODO
                                },
                                onOpenNote = { noteId ->
                                    requestedNoteId = noteId
                                    destination = MainDestination.NOTES
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
