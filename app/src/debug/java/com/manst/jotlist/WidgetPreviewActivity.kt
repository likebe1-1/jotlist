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
 * 仅 debug 构建可用：把三套小组件布局按真实 dp 尺寸摆出来，
 * 不依赖任何 Launcher 就能做视觉走查（也是生成 previewImage 的素材来源）。
 *
 * 用法：
 *   adb shell am start -n com.manst.jotlist/.WidgetPreviewActivity
 *   adb shell am start -n com.manst.jotlist/.WidgetPreviewActivity --ez pin true   # 弹"添加到主屏幕"
 */
class WidgetPreviewActivity : Activity() {

    private val tasks = listOf(
        "写周报并同步给组里",
        "18:00 前回复客户邮件",
        "买菜：番茄 / 鸡蛋",
        "补完 v5.1 发布说明",
        "预约周四的牙医",
        "整理书桌，只留 6 个图标"
    )

    private val rowIds = intArrayOf(
        R.id.widget_row_1, R.id.widget_row_2, R.id.widget_row_3,
        R.id.widget_row_4, R.id.widget_row_5, R.id.widget_row_6
    )

    private val taskIds = intArrayOf(
        R.id.widget_task_1, R.id.widget_task_2, R.id.widget_task_3,
        R.id.widget_task_4, R.id.widget_task_5, R.id.widget_task_6
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 调试入口：直接请求把小组件钉到桌面，省去手动拖拽
        if (intent?.getBooleanExtra("pin", false) == true) {
            val manager = AppWidgetManager.getInstance(this)
            if (manager.isRequestPinAppWidgetSupported) {
                manager.requestPinAppWidget(
                    ComponentName(this, TodoWidgetProvider::class.java),
                    null,
                    null
                )
            }
            finish()
            return
        }

        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(28), dp(20), dp(48))
        }

        addPreview(column, "紧凑 2×2 · 160×160dp", R.layout.todo_widget_compact, 160, 160, 4)
        addPreview(column, "标准 4×2 · 330×140dp", R.layout.todo_widget_standard, 330, 140, 3)
        addPreview(column, "标准 4×3 · 330×190dp", R.layout.todo_widget_standard, 330, 190, 5)
        addPreview(column, "扩展 4×4 · 330×250dp", R.layout.todo_widget_expanded, 330, 250, 6)
        addPreview(column, "空状态 · 扩展 330×250dp", R.layout.todo_widget_expanded, 330, 250, 0)

        setContentView(
            ScrollView(this).apply {
                setBackgroundColor(Color.parseColor("#12101B"))
                addView(column)
            }
        )
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

        bind(widget, rowCount)
    }

    private fun bind(root: View, rowCount: Int) {
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
                root.findViewById<TextView>(taskIds[index])?.text = tasks[index % tasks.size]
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

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    private fun dp(value: Float): Int =
        (value * resources.displayMetrics.density).toInt()
}
