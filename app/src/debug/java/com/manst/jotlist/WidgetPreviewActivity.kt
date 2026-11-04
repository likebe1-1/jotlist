package com.manst.jotlist

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView

/**
 * 仅 debug 构建可用：把「待办」「笔记」两组小组件按真实 dp 尺寸摆出来，
 * 不依赖任何 Launcher 就能做视觉走查，也用来生成 previewImage。
 *
 * 用法：
 *   adb shell am start -n com.manst.jotlist/.WidgetPreviewActivity
 *   adb shell am start -n com.manst.jotlist/.WidgetPreviewActivity --es pin todo
 *   adb shell am start -n com.manst.jotlist/.WidgetPreviewActivity --es pin note
 */
class WidgetPreviewActivity : Activity() {

    private var previewPalette: WidgetPalette = WidgetPalette.MIST

    private val tasks = listOf(
        "写周报并同步给组里",
        "18:00 前回复客户邮件",
        "买菜：番茄 / 鸡蛋",
        "补完 v5.1 发布说明",
        "预约周四的牙医",
        "整理书桌，只留 6 个图标"
    )

    private val noteSamples = listOf(
        "周会纪要" to "下周先打通同步链路，再补埋点",
        "读书笔记 · 深度工作" to "把注意力当成稀缺资源来排期",
        "灵感" to "桌面组件按条数自动变密？",
        "旅行清单" to "护照、充电器、转换头",
        "购物" to "咖啡豆 500g、燕麦奶",
        "Quote" to "Simplicity is the ultimate sophistication."
    )

    private val rowIds = intArrayOf(
        R.id.widget_row_1, R.id.widget_row_2, R.id.widget_row_3,
        R.id.widget_row_4, R.id.widget_row_5, R.id.widget_row_6
    )

    private val taskIds = intArrayOf(
        R.id.widget_task_1, R.id.widget_task_2, R.id.widget_task_3,
        R.id.widget_task_4, R.id.widget_task_5, R.id.widget_task_6
    )

    private val noteRowIds = intArrayOf(
        R.id.note_row_1, R.id.note_row_2, R.id.note_row_3,
        R.id.note_row_4, R.id.note_row_5, R.id.note_row_6
    )

    private val noteTitleIds = intArrayOf(
        R.id.note_item_title_1, R.id.note_item_title_2, R.id.note_item_title_3,
        R.id.note_item_title_4, R.id.note_item_title_5, R.id.note_item_title_6
    )

    private val noteBodyIds = intArrayOf(
        R.id.note_item_body_1, R.id.note_item_body_2, R.id.note_item_body_3,
        R.id.note_item_body_4, R.id.note_item_body_5, R.id.note_item_body_6
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 调试入口：直接把某个组件钉到桌面，省去手动拖拽
        when (intent?.getStringExtra("pin")) {
            "todo" -> pin(TodoWidgetProvider::class.java)
            "note" -> pin(NotesWidgetProvider::class.java)
        }
        if (intent?.getStringExtra("pin") != null) {
            finish()
            return
        }

        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(28), dp(20), dp(48))
        }

        // 预览用的调色板：默认取设置里保存的那套，也可以用 --es palette PINE 临时覆盖
        previewPalette = intent?.getStringExtra("palette")
            ?.let { name -> WidgetPalette.entries.firstOrNull { it.name.equals(name, true) } }
            ?: readWidgetPalette(this)
        column.addView(
            TextView(this).apply {
                text = "调色板：${previewPalette.label}"
                setTextColor(Color.parseColor("#C9B6FF"))
                textSize = 13f
                setPadding(0, 0, 0, 4)
            }
        )

        addPreview(column, "待办 · 紧凑 2×2 · 160×160dp", R.layout.todo_widget_compact, 160, 160, 2)
        addPreview(column, "待办 · 标准 4×2 · 330×140dp", R.layout.todo_widget_standard, 330, 140, 3)
        addPreview(column, "待办 · 标准 4×3 · 330×190dp", R.layout.todo_widget_standard, 330, 190, 5)
        addPreview(column, "待办 · 扩展 4×4 · 330×250dp", R.layout.todo_widget_expanded, 330, 250, 6)

        addPreview(column, "笔记 · 紧凑 2×2 · 160×160dp", R.layout.note_widget_compact, 160, 160, 2)
        addPreview(column, "笔记 · 标准 4×3 · 330×190dp", R.layout.note_widget_standard, 330, 190, 4)
        addPreview(column, "笔记 · 扩展 4×4 · 330×250dp", R.layout.note_widget_expanded, 330, 250, 6)
        addPreview(column, "笔记 · 空状态 330×190dp", R.layout.note_widget_standard, 330, 190, 0)

        setContentView(
            ScrollView(this).apply {
                setBackgroundColor(Color.parseColor("#12101B"))
                addView(column)
            }
        )
    }

    private fun pin(provider: Class<*>) {
        val manager = AppWidgetManager.getInstance(this)
        if (manager.isRequestPinAppWidgetSupported) {
            manager.requestPinAppWidget(ComponentName(this, provider), null, null)
        }
    }

    private fun addPreview(
        parent: LinearLayout,
        label: String,
        layoutRes: Int,
        widthDp: Int,
        heightDp: Int,
        rowCount: Int
    ) {
        parent.addView(
            TextView(this).apply {
                text = label
                setTextColor(Color.parseColor("#9C97B5"))
                textSize = 11f
                setPadding(0, dp(18), 0, dp(8))
            }
        )

        val frame = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(widthDp), dp(heightDp))
            // 模拟壁纸底色，方便判断圆角、描边的观感（真实桌面里这里是用户壁纸）
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(Color.parseColor("#6F6592"), Color.parseColor("#3E3756"))
            )
        }
        val widget = LayoutInflater.from(this).inflate(layoutRes, frame, false)
        frame.addView(widget)
        parent.addView(frame)

        if (layoutRes == R.layout.todo_widget_compact ||
            layoutRes == R.layout.todo_widget_standard ||
            layoutRes == R.layout.todo_widget_expanded
        ) {
            bindTodo(widget, rowCount)
            applyPalette(widget, isNote = false)
        } else {
            bindNote(widget, rowCount)
            applyPalette(widget, isNote = true)
        }
    }

    /** 把当前预览的调色板套到已经 inflate 的布局上（和 RemoteViews 里的做法一致）。 */
    private fun applyPalette(root: View, isNote: Boolean) {
        val palette = previewPalette
        val accent = if (isNote) palette.noteAccent(this) else palette.todoAccent(this)
        val rootId = if (isNote) R.id.note_widget_root else R.id.widget_root
        root.findViewById<View>(rootId)?.setBackgroundResource(palette.backgroundRes)

        val accentIds = if (isNote) {
            intArrayOf(R.id.note_count, R.id.note_add)
        } else {
            intArrayOf(R.id.widget_count, R.id.widget_add, R.id.widget_quick_capture)
        }
        accentIds.forEach { id ->
            root.findViewById<TextView>(id)?.setTextColor(accent)
        }

        if (!isNote) {
            intArrayOf(
                R.id.widget_complete_1, R.id.widget_complete_2, R.id.widget_complete_3,
                R.id.widget_complete_4, R.id.widget_complete_5, R.id.widget_complete_6
            ).forEach { id ->
                root.findViewById<View>(id)?.setBackgroundResource(palette.ringRes)
            }
        }
    }

    private fun bindTodo(root: View, rowCount: Int) {
        root.findViewById<View>(R.id.widget_date)?.let {
            (it as TextView).text = "11月2日 周日"
            it.visibility = View.VISIBLE
        }

        root.findViewById<TextView>(R.id.widget_count)?.let {
            if (rowCount == 0) it.visibility = View.GONE else it.text = rowCount.toString()
        }

        rowIds.forEachIndexed { index, id ->
            val row = root.findViewById<View>(id) ?: return@forEachIndexed
            if (index < rowCount) {
                row.visibility = View.VISIBLE
                val task = root.findViewById<TextView>(taskIds[index])
                task?.text = tasks[index % tasks.size]
                task?.textSize = when {
                    rowCount <= 2 -> 15f
                    rowCount <= 4 -> 13.5f
                    else -> 12.5f
                }
            } else {
                row.visibility = View.GONE
            }
        }

        root.findViewById<View>(R.id.widget_empty)?.visibility =
            if (rowCount == 0) View.VISIBLE else View.GONE

        root.findViewById<View>(R.id.widget_footer)?.visibility =
            if (rowCount == 0) View.GONE else View.VISIBLE
        root.findViewById<TextView>(R.id.widget_footer_text)?.text = "今天完成 3/8"
        root.findViewById<ProgressBar>(R.id.widget_progress)?.progress = 37
    }

    private fun bindNote(root: View, rowCount: Int) {
        root.findViewById<TextView>(R.id.note_count)?.let {
            if (rowCount == 0) it.visibility = View.GONE else it.text = rowCount.toString()
        }

        noteRowIds.forEachIndexed { index, id ->
            val row = root.findViewById<View>(id) ?: return@forEachIndexed
            if (index < rowCount) {
                row.visibility = View.VISIBLE
                val (title, body) = noteSamples[index % noteSamples.size]
                val titleSize = when {
                    rowCount <= 2 -> 13.5f
                    rowCount <= 4 -> 12f
                    else -> 11f
                }
                root.findViewById<TextView>(noteTitleIds[index])?.apply {
                    text = title
                    textSize = titleSize
                }
                root.findViewById<TextView>(noteBodyIds[index])?.apply {
                    text = body
                    textSize = titleSize - 2.5f
                }
            } else {
                row.visibility = View.GONE
            }
        }

        root.findViewById<View>(R.id.note_empty)?.visibility =
            if (rowCount == 0) View.VISIBLE else View.GONE
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    private fun dp(value: Float): Int =
        (value * resources.displayMetrics.density).toInt()
}
