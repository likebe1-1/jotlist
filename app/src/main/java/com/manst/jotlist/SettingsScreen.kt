package com.manst.jotlist

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
    var menuExpanded by remember { mutableStateOf(false) }
    var paletteDialogVisible by remember { mutableStateOf(false) }
    var draftPalette by remember { mutableStateOf(readWidgetPalette(context)) }
    var appliedPalette by remember { mutableStateOf(readWidgetPalette(context)) }

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

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        JotlistHeader(title = "设置", onBack = onBack) {
            Box {
                IconButton(
                    onClick = {
                        draftPalette = appliedPalette
                        menuExpanded = true
                    },
                    modifier = Modifier.testTag("settings_more")
                ) {
                    Icon(Icons.Filled.MoreVert, contentDescription = "更多设置")
                }
                DropdownMenu(
                    expanded = menuExpanded,
                    onDismissRequest = { menuExpanded = false }
                ) {
                    DropdownMenuItem(
                        text = { Text("组件配色") },
                        onClick = {
                            menuExpanded = false
                            draftPalette = appliedPalette
                            paletteDialogVisible = true
                        },
                        modifier = Modifier.testTag("settings_widget_palette")
                    )
                }
            }
        }

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

        SettingsSection(title = "关于") {
            Text(
                "${context.getString(R.string.app_name)} ${versionName.ifBlank { "5.0" }}",
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
