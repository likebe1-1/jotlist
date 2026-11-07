package com.manst.jotlist

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 回收站：删除的待办 / 笔记在这里保留 30 天，可逐条恢复或永久删除。
 * 恢复走 TrashStorage 的 restoreTodo / restoreNote，会自动补回跨对象引用。
 */
@Composable
internal fun TrashScreen(
    modifier: Modifier = Modifier,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    var entries by remember { mutableStateOf(loadTrash(context)) }

    fun reload() {
        entries = loadTrash(context)
    }

    val dateFormat = remember { SimpleDateFormat("M月d日 HH:mm", Locale.getDefault()) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        JotlistHeader(title = "回收站", onBack = onBack) {
            if (entries.isNotEmpty()) {
                TextButton(
                    onClick = {
                        saveTrash(context, emptyList())
                        reload()
                    },
                    modifier = Modifier.testTag("trash_clear_all")
                ) { Text("清空") }
            }
        }

        if (entries.isEmpty()) {
            EmptyState(
                title = "回收站是空的",
                subtitle = "删除的待办和笔记会在这里保留 30 天，可随时恢复"
            )
            return@Column
        }

        Text(
            text = "共 ${entries.size} 条 · 超过 30 天自动清除",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 8.dp)
        )

        LazyColumn(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(entries, key = { "${it.kind.name}-${it.id}" }) { entry ->
                TrashRow(
                    entry = entry,
                    deletedLabel = dateFormat.format(Date(entry.deletedAt)),
                    onRestore = {
                        when (entry.kind) {
                            TrashKind.TODO -> restoreTodo(context, entry)
                            TrashKind.NOTE -> restoreNote(context, entry)
                        }
                        reload()
                    },
                    onDelete = {
                        purgeTrashEntry(context, entry)
                        reload()
                    }
                )
            }
        }
    }
}

@Composable
private fun TrashRow(
    entry: TrashEntry,
    deletedLabel: String,
    onRestore: () -> Unit,
    onDelete: () -> Unit
) {
    val title = remember(entry) {
        runCatching {
            when (entry.kind) {
                TrashKind.TODO -> todoFromJson(org.json.JSONObject(entry.payload)).text
                TrashKind.NOTE -> noteFromJson(org.json.JSONObject(entry.payload)).title
                    .ifBlank { "无标题笔记" }
            }
        }.getOrDefault("（数据已损坏）")
    }
    val kindLabel = if (entry.kind == TrashKind.TODO) "待办" else "笔记"

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = kindLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Text(
                text = "删除于 $deletedLabel",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(
                    onClick = onRestore,
                    modifier = Modifier.testTag("trash_restore_${entry.id}")
                ) { Text("恢复") }
                TextButton(
                    onClick = onDelete,
                    modifier = Modifier.testTag("trash_delete_${entry.id}")
                ) { Text("永久删除") }
            }
        }
    }
}
