package com.manst.jotlist

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import android.app.TimePickerDialog

/**
 * 设置页：外观、通知与提醒、数据、关于。
 *
 * 之前"外观"和"通知设置"散在待办页的更多菜单里，备份/免打扰这类设置也没地方放，
 * 所以独立成一页，后续功能都往这里加。
 */
@Composable
internal fun SettingsScreen(
    modifier: Modifier = Modifier,
    appearanceMode: AppearanceMode,
    onAppearanceChange: (AppearanceMode) -> Unit,
    density: UiDensity,
    onDensityChange: (UiDensity) -> Unit,
    notificationsAllowed: Boolean,
    exactAlarmsAllowed: Boolean,
    onOpenNotificationSettings: () -> Unit,
    onOpenExactAlarmSettings: () -> Unit,
    onClearCompleted: () -> Unit,
    onDoNotDisturbChanged: (enabled: Boolean, startMinute: Int, endMinute: Int) -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val versionName = remember(context) {
        runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull().orEmpty()
    }
    val initialDnd = remember(context) { readDoNotDisturb(context) }
    var dndEnabled by remember(initialDnd) { mutableStateOf(initialDnd.enabled) }
    var dndStart by remember(initialDnd) { mutableStateOf(initialDnd.startMinute) }
    var dndEnd by remember(initialDnd) { mutableStateOf(initialDnd.endMinute) }
    val onChanged = rememberUpdatedState(onDoNotDisturbChanged)

    // 右上角三点菜单：组件配色
    var paletteDialogVisible by remember { mutableStateOf(false) }
    var draftPalette by remember { mutableStateOf(readWidgetPalette(context)) }
    var appliedPalette by remember { mutableStateOf(readWidgetPalette(context)) }
    var showTrash by remember { mutableStateOf(false) }
    var pendingRestoreRaw by remember { mutableStateOf<String?>(null) }
    var backupMessage by remember { mutableStateOf<String?>(null) }
    var lastBackup by remember { mutableStateOf(lastBackupAt(context)) }
    var snapshotAt by remember { mutableStateOf(latestSnapshotAt(context)) }
    var showSnapshotConfirm by remember { mutableStateOf(false) }
    var canUndoRestore by remember { mutableStateOf(hasPreRestoreSnapshot(context)) }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri != null) {
            runCatching {
                val appVersion = context.packageManager
                    .getPackageInfo(context.packageName, 0).versionName.orEmpty()
                val payload = buildBackupJson(context, appVersion)
                context.contentResolver.openOutputStream(uri)?.use { it.write(payload.toByteArray()) }
                    ?: error("无法写入所选文件")
                markBackupExported(context)
                payload.length
            }.onSuccess { size ->
                lastBackup = lastBackupAt(context)
                backupMessage = "已导出已保存的数据（约 ${size / 1024} KB）"
            }.onFailure { backupMessage = "导出失败：${it.message}" }
        }
    }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            runCatching {
                val input = context.contentResolver.openInputStream(uri) ?: error("无法读取文件")
                val maxChars = 8 * 1024 * 1024
                input.bufferedReader().use { reader ->
                    val builder = StringBuilder()
                    val buffer = CharArray(8192)
                    var total = 0
                    while (true) {
                        val read = reader.read(buffer)
                        if (read < 0) break
                        total += read
                        if (total > maxChars) error("备份文件过大（超过 8MB）")
                        builder.append(buffer, 0, read)
                    }
                    builder.toString()
                }
            }.onSuccess { raw ->
                // 先确认再覆盖：恢复是整体替换，误点一次代价太大
                pendingRestoreRaw = raw
            }.onFailure { backupMessage = "恢复失败：${it.message}" }
        }
    }

    fun applyDoNotDisturb(enabled: Boolean, start: Int, end: Int) {
        dndEnabled = enabled
        dndStart = start
        dndEnd = end
        onChanged.value(enabled, start, end)
    }

    fun pickMinuteOfDay(current: Int, onPicked: (Int) -> Unit) {
        TimePickerDialog(
            context,
            { _, hour, minute -> onPicked(hour * 60 + minute) },
            current / 60,
            current % 60,
            true
        ).show()
    }

    /** 恢复备份/快照会替换外观、免打扰等设置：把页面状态和主题回调一起刷新，避免还显示旧值。 */
    fun refreshAfterDataReplace() {
        val restoredDnd = readDoNotDisturb(context)
        dndEnabled = restoredDnd.enabled
        dndStart = restoredDnd.startMinute
        dndEnd = restoredDnd.endMinute
        onAppearanceChange(readAppearance(context))
        onDensityChange(readUiDensity(context))
        snapshotAt = latestSnapshotAt(context)
        lastBackup = lastBackupAt(context)
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        JotlistHeader(title = "设置", onBack = onBack)

        SettingsSection(title = "外观") {
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                AppearanceMode.entries.forEachIndexed { index, mode ->
                    SegmentedButton(
                        selected = appearanceMode == mode,
                        onClick = { onAppearanceChange(mode) },
                        colors = SegmentedButtonDefaults.colors(

                            activeContainerColor = MaterialTheme.colorScheme.primaryContainer,

                            activeContentColor = MaterialTheme.colorScheme.onPrimaryContainer

                        ),

                        shape = SegmentedButtonDefaults.itemShape(
                            index = index,
                            count = AppearanceMode.entries.size
                        ),
                        modifier = Modifier.testTag("settings_appearance_${mode.name}")
                    ) {
                        Text(mode.label)
                    }
                }
            }
            Text(
                "列表密度",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp)
            )
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                UiDensity.entries.forEachIndexed { index, option ->
                    SegmentedButton(
                        selected = density == option,
                        onClick = { onDensityChange(option) },
                        colors = SegmentedButtonDefaults.colors(

                            activeContainerColor = MaterialTheme.colorScheme.primaryContainer,

                            activeContentColor = MaterialTheme.colorScheme.onPrimaryContainer

                        ),

                        shape = SegmentedButtonDefaults.itemShape(
                            index = index,
                            count = UiDensity.entries.size
                        ),
                        modifier = Modifier.testTag("settings_density_${option.name}")
                    ) {
                        Text(option.label)
                    }
                }
            }
            SettingsRow(
                title = "组件配色",
                value = "只影响桌面小组件，App 内配色不变",
                actionLabel = "切换",
                actionTestTag = "settings_widget_palette",
                onAction = {
                    draftPalette = appliedPalette
                    paletteDialogVisible = true
                }
            )
        }

        SettingsSection(title = "通知与提醒") {
            SettingsRow(
                title = "通知权限",
                value = if (notificationsAllowed) "已开启" else "未开启，任务到期不会提醒",
                actionLabel = if (notificationsAllowed) "系统设置" else "去开启",
                actionTestTag = "settings_notifications",
                onAction = onOpenNotificationSettings
            )
            HorizontalDivider()
            SettingsRow(
                title = "精准提醒",
                value = if (exactAlarmsAllowed) "已允许，按设定时间提醒" else "未允许，系统可能延后提醒",
                actionLabel = "设置",
                actionTestTag = "settings_exact_alarm",
                onAction = onOpenExactAlarmSettings
            )
            HorizontalDivider()
            SettingsRow(
                title = "清空已完成",
                value = "删除所有已完成的任务",
                actionLabel = "清空",
                actionTestTag = "settings_clear_completed",
                onAction = onClearCompleted,
                destructive = true
            )
        }

        SettingsSection(title = "免打扰") {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("免打扰时段", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        if (dndEnabled) {
                            "${formatMinuteOfDay(dndStart)} – ${formatMinuteOfDay(dndEnd)} 之间的提醒推迟到结束"
                        } else {
                            "关闭时所有提醒按设定时间发出"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = dndEnabled,
                    onCheckedChange = { applyDoNotDisturb(it, dndStart, dndEnd) },
                    modifier = Modifier.testTag("settings_dnd_switch")
                )
            }
            if (dndEnabled) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = {
                            pickMinuteOfDay(dndStart) { picked ->
                                applyDoNotDisturb(true, picked, dndEnd)
                            }
                        },
                        modifier = Modifier.testTag("settings_dnd_start")
                    ) {
                        Text("开始 ${formatMinuteOfDay(dndStart)}")
                    }
                    OutlinedButton(
                        onClick = {
                            pickMinuteOfDay(dndEnd) { picked ->
                                applyDoNotDisturb(true, dndStart, picked)
                            }
                        },
                        modifier = Modifier.testTag("settings_dnd_end")
                    ) {
                        Text("结束 ${formatMinuteOfDay(dndEnd)}")
                    }
                }
            }
        }


        SettingsSection(title = "备份与恢复") {
            SettingsRow(
                title = "回收站",
                value = "删除的待办和笔记保留 30 天",
                actionLabel = "查看",
                actionTestTag = "settings_trash",
                onAction = { showTrash = true }
            )
            SettingsRow(
                title = "导出备份",
                value = if (lastBackup > 0) {
                    "上次备份：${backupDateLabel(lastBackup)}"
                } else {
                    "还没有备份过（只导出已保存内容，草稿不含在内）"
                },
                actionLabel = "导出",
                actionTestTag = "settings_backup_export",
                onAction = {
                    exportLauncher.launch(
                        "jotlist-backup-${backupDateLabel(System.currentTimeMillis(), "yyyyMMdd")}.json"
                    )
                }
            )
            SettingsRow(
                title = "恢复备份",
                value = "整体替换当前数据，替换前会自动留一份快照",
                actionLabel = "选择文件",
                actionTestTag = "settings_backup_restore",
                onAction = { importLauncher.launch(arrayOf("application/json")) }
            )
            SettingsRow(
                title = "从上一版恢复",
                value = if (snapshotAt > 0) {
                    "上次自动快照：${backupDateLabel(snapshotAt)}"
                } else {
                    "还没有快照（保存一次后自动生成）"
                },
                actionLabel = "回退",
                actionTestTag = "settings_snapshot_restore",
                onAction = {
                    // 从快照恢复同样是破坏性操作：不能一点就换掉当前数据
                    if (snapshotAt <= 0L) {
                        backupMessage = "还没有可用的快照"
                    } else {
                        showSnapshotConfirm = true
                    }
                }
            )
            backupMessage?.let { message ->
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (canUndoRestore) {
                TextButton(
                    onClick = {
                        backupMessage = when (val outcome = restorePreRestoreSnapshot(context)) {
                            is RestoreOutcome.Success ->
                                "已撤销上次恢复：${outcome.summary.todos} 条待办、${outcome.summary.notes} 篇笔记"
                            is RestoreOutcome.Failed -> "撤销失败：${outcome.message}"
                        }
                        canUndoRestore = hasPreRestoreSnapshot(context)
                        refreshAfterDataReplace()
                    },
                    modifier = Modifier.testTag("settings_undo_restore")
                ) { Text("撤销这次恢复") }
            }
        }

    if (showSnapshotConfirm) {
        AlertDialog(
            onDismissRequest = { showSnapshotConfirm = false },
            title = { Text("从上一版恢复？") },
            text = {
                Text(
                    "会用 ${backupDateLabel(snapshotAt)} 的自动快照整体替换当前待办、笔记与设置" +
                        "（回收站会清空）；回退前的状态可以通过「撤销这次恢复」找回。"
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showSnapshotConfirm = false
                        val snapshotOutcome = restoreLatestSnapshot(context)
                        backupMessage = when (snapshotOutcome) {
                            is RestoreOutcome.Success ->
                                "已回退到快照：${snapshotOutcome.summary.todos} 条待办、${snapshotOutcome.summary.notes} 篇笔记"
                            is RestoreOutcome.Failed -> "回退失败：${snapshotOutcome.message}"
                        }
                        canUndoRestore = snapshotOutcome is RestoreOutcome.Success
                        refreshAfterDataReplace()
                    },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    ),
                    modifier = Modifier.testTag("snapshot_restore_confirm")
                ) { Text("回退") }
            },
            dismissButton = {
                TextButton(
                    onClick = { showSnapshotConfirm = false },
                    modifier = Modifier.testTag("snapshot_restore_cancel")
                ) { Text("取消") }
            }
        )
    }

    pendingRestoreRaw?.let { raw ->
        AlertDialog(
            onDismissRequest = { pendingRestoreRaw = null },
            title = { Text("恢复这份备份？") },
            text = {
                Text(
                    "当前待办、笔记与外观设置会被替换（回收站会清空）。" +
                        "恢复前的数据可以通过恢复后的「撤销这次恢复」找回。"
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val result = runCatching { restoreBackupJson(context, raw) }.getOrNull()
                        backupMessage = when (result) {
                            is RestoreOutcome.Success ->
                                "已恢复 ${result.summary.todos} 条待办、${result.summary.notes} 篇笔记"
                            is RestoreOutcome.Failed -> "恢复失败：${result.message}"
                            null -> "恢复失败：文件内容异常"
                        }
                        canUndoRestore = result is RestoreOutcome.Success
                        refreshAfterDataReplace()
                        pendingRestoreRaw = null
                    },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    ),
                    modifier = Modifier.testTag("restore_confirm")
                ) { Text("替换并恢复") }
            },
            dismissButton = {
                TextButton(
                    onClick = { pendingRestoreRaw = null },
                    modifier = Modifier.testTag("restore_cancel")
                ) { Text("取消") }
            }
        )
    }

        if (showTrash) {
            Dialog(
                onDismissRequest = { showTrash = false },
                properties = DialogProperties(usePlatformDefaultWidth = false)
            ) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    TrashScreen(onBack = { showTrash = false })
                }
            }
        }

        SettingsSection(title = "关于") {
            Text(
                "${context.getString(R.string.app_name)} ${versionName.ifBlank { com.manst.jotlist.BuildConfig.VERSION_NAME }}",
                style = MaterialTheme.typography.bodyLarge
            )
            Text(
                "待办与笔记，数据只保存在本机，不会上传到任何服务器。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }

    if (paletteDialogVisible) {
        AlertDialog(
            onDismissRequest = { paletteDialogVisible = false },
            title = { Text("组件配色") },
            text = {
                Column {
                    Text(
                        "只影响桌面上的待办 / 笔记组件，App 界面外观不受影响。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                    WidgetPalette.entries.forEach { palette ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { draftPalette = palette }
                                .padding(vertical = 6.dp)
                                .testTag("palette_${palette.name}"),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = draftPalette == palette,
                                onClick = { draftPalette = palette }
                            )
                            Column(modifier = Modifier.padding(start = 4.dp)) {
                                Text(palette.label, style = MaterialTheme.typography.bodyLarge)
                                Text(
                                    palette.hint,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        appliedPalette = draftPalette
                        saveWidgetPalette(context, draftPalette)
                        TodoWidgetProvider.refresh(context)
                        NotesWidgetProvider.refresh(context)
                        paletteDialogVisible = false
                    }
                ) { Text("应用") }
            },
            dismissButton = {
                TextButton(onClick = { paletteDialogVisible = false }) { Text("取消") }
            }
        )
    }
}

@Composable
private fun SettingsSection(title: String, content: @Composable () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary
            )
            content()
        }
    }
}

@Composable
private fun SettingsRow(
    title: String,
    value: String,
    actionLabel: String,
    actionTestTag: String,
    onAction: () -> Unit,
    destructive: Boolean = false
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                value,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        TextButton(
            onClick = onAction,
            modifier = Modifier.testTag(actionTestTag)
        ) {
            Text(
                actionLabel,
                color = if (destructive) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.primary
                }
            )
        }
    }
}

private fun backupDateLabel(timestamp: Long, pattern: String = "M月d日 HH:mm"): String =
    java.text.SimpleDateFormat(pattern, java.util.Locale.getDefault())
        .format(java.util.Date(timestamp))
