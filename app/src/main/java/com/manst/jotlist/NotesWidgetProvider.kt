package com.manst.jotlist

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.TypedValue
import android.view.View
import android.widget.RemoteViews

/**
 * 独立的「笔记」桌面组件，和待办组件分属两个 provider：
 * 桌面组件选择器里会各占一项，用户可以只放笔记、不放待办。
 */
class NotesWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        appWidgetIds.forEach { appWidgetId ->
            appWidgetManager.updateAppWidget(appWidgetId, createViews(context, appWidgetId))
        }
    }

    override fun onAppWidgetOptionsChanged(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: android.os.Bundle
    ) {
        appWidgetManager.updateAppWidget(appWidgetId, createViews(context, appWidgetId))
    }

    private fun createViews(context: Context, appWidgetId: Int): RemoteViews {
        val manager = AppWidgetManager.getInstance(context)
        val options = manager.getAppWidgetOptions(appWidgetId)
        val widthDp = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 0)
        val heightDp = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 0)
        val spec = NoteSpec.forSize(widthDp, heightDp)

        val views = RemoteViews(context.packageName, spec.layoutRes)
        val notes = readNotes(context)
        val visibleNotes = notes.take(spec.rowsThatFit(heightDp))

        views.setOnClickPendingIntent(
            R.id.note_add,
            createQuickCapturePendingIntent(context, appWidgetId)
        )

        val hasNotes = notes.isNotEmpty()
        views.setTextViewText(
            R.id.note_count,
            context.getString(R.string.widget_notes_count, notes.size)
        )
        views.setViewVisibility(R.id.note_count, if (hasNotes) View.VISIBLE else View.GONE)
        views.setViewVisibility(R.id.note_empty, if (hasNotes) View.GONE else View.VISIBLE)

        // 笔记少于两条时把字号放大一点，多于四条时收紧，视觉上跟内容量匹配。
        val titleSp = when {
            visibleNotes.size <= 2 -> 13.5f
            visibleNotes.size <= 4 -> 12f
            else -> 11f
        }
        val bodySp = titleSp - 2.5f

        spec.rows.forEachIndexed { index, row ->
            val note = visibleNotes.getOrNull(index)
            views.setViewVisibility(row.container, if (note == null) View.GONE else View.VISIBLE)
            if (note != null) {
                views.setTextViewText(row.title, note.title)
                views.setTextViewText(row.body, note.preview)
                views.setTextViewTextSize(row.title, TypedValue.COMPLEX_UNIT_SP, titleSp)
                views.setTextViewTextSize(row.body, TypedValue.COMPLEX_UNIT_SP, bodySp)
                views.setContentDescription(
                    row.container,
                    context.getString(R.string.widget_notes_open, note.title)
                )
                views.setOnClickPendingIntent(
                    row.container,
                    createOpenNotePendingIntent(context, appWidgetId, note.id)
                )
            }
        }
        return views
    }

    private fun createQuickCapturePendingIntent(
        context: Context,
        appWidgetId: Int
    ): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            putExtra(TodoWidgetProvider.EXTRA_OPEN_QUICK_CAPTURE, true)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            data = Uri.parse("note-widget://capture/$appWidgetId")
        }
        return PendingIntent.getActivity(
            context,
            appWidgetId + 20_000,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun createOpenNotePendingIntent(
        context: Context,
        appWidgetId: Int,
        noteId: Long
    ): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            putExtra(EXTRA_OPEN_NOTES, true)
            putExtra(EXTRA_OPEN_NOTE_ID, noteId)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            data = Uri.parse("note-widget://open/$appWidgetId/$noteId")
        }
        return PendingIntent.getActivity(
            context,
            (appWidgetId * 31 + noteId).toInt(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun readNotes(context: Context): List<WidgetNote> {
        val loaded = loadNotes(context) as? NotesLoadResult.Loaded ?: return emptyList()
        return loaded.items
            .sortedByDescending { it.updatedAt }
            .map { note ->
                val body = note.body.replace('\n', ' ').trim()
                val title = note.title.trim().ifBlank { body.take(24) }
                    .ifBlank { context.getString(R.string.widget_notes_untitled) }
                val preview = if (note.title.isBlank() && body.length <= 24) "" else body
                WidgetNote(id = note.id, title = title, preview = preview)
            }
    }

    companion object {
        const val EXTRA_OPEN_NOTES = "com.manst.jotlist.EXTRA_OPEN_NOTES"
        const val EXTRA_OPEN_NOTE_ID = "com.manst.jotlist.EXTRA_OPEN_NOTE_ID"
        private const val ROW_HEIGHT_DP = 32

        @JvmStatic
        fun refresh(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val provider = ComponentName(context, NotesWidgetProvider::class.java)
            NotesWidgetProvider().onUpdate(context, manager, manager.getAppWidgetIds(provider))
        }
    }

    private data class WidgetNote(val id: Long, val title: String, val preview: String)

    private data class RowIds(val container: Int, val title: Int, val body: Int)

    private enum class NoteSpec(
        val layoutRes: Int,
        val rows: List<RowIds>,
        private val chromeHeightDp: Int
    ) {
        COMPACT(
            layoutRes = R.layout.note_widget_compact,
            rows = listOf(
                RowIds(R.id.note_row_1, R.id.note_item_title_1, R.id.note_item_body_1),
                RowIds(R.id.note_row_2, R.id.note_item_title_2, R.id.note_item_body_2),
                RowIds(R.id.note_row_3, R.id.note_item_title_3, R.id.note_item_body_3)
            ),
            chromeHeightDp = 50
        ),
        STANDARD(
            layoutRes = R.layout.note_widget_standard,
            rows = listOf(
                RowIds(R.id.note_row_1, R.id.note_item_title_1, R.id.note_item_body_1),
                RowIds(R.id.note_row_2, R.id.note_item_title_2, R.id.note_item_body_2),
                RowIds(R.id.note_row_3, R.id.note_item_title_3, R.id.note_item_body_3),
                RowIds(R.id.note_row_4, R.id.note_item_title_4, R.id.note_item_body_4)
            ),
            chromeHeightDp = 50
        ),
        EXPANDED(
            layoutRes = R.layout.note_widget_expanded,
            rows = listOf(
                RowIds(R.id.note_row_1, R.id.note_item_title_1, R.id.note_item_body_1),
                RowIds(R.id.note_row_2, R.id.note_item_title_2, R.id.note_item_body_2),
                RowIds(R.id.note_row_3, R.id.note_item_title_3, R.id.note_item_body_3),
                RowIds(R.id.note_row_4, R.id.note_item_title_4, R.id.note_item_body_4),
                RowIds(R.id.note_row_5, R.id.note_item_title_5, R.id.note_item_body_5),
                RowIds(R.id.note_row_6, R.id.note_item_title_6, R.id.note_item_body_6)
            ),
            chromeHeightDp = 50
        );

        fun rowsThatFit(heightDp: Int): Int {
            if (heightDp <= 0) return rows.size
            val fit = (heightDp - chromeHeightDp) / ROW_HEIGHT_DP
            return fit.coerceIn(1, rows.size)
        }

        companion object {
            fun forSize(widthDp: Int, heightDp: Int): NoteSpec {
                if (heightDp >= 220) return EXPANDED
                if (widthDp >= 200 || widthDp <= 0) return STANDARD
                return COMPACT
            }
        }
    }
}
