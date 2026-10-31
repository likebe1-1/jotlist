package com.manst.jotlist

import android.content.Context
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** 首次启动引导页的标记，写在同一个 SharedPreferences 里。 */
internal const val KEY_WELCOME_SHOWN = "welcome_shown"

internal fun welcomeShown(context: Context): Boolean =
    context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        .getBoolean(KEY_WELCOME_SHOWN, false)

internal fun markWelcomeShown(context: Context) {
    context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        .edit()
        .putBoolean(KEY_WELCOME_SHOWN, true)
        .apply()
}

private val BrandStart = Color(0xFF4A5BC4)
private val BrandEnd = Color(0xFF00796B)

/**
 * 首次启动的品牌页，视觉与商店特色图保持一致：
 * 斜向渐变底 + checklist 字形 + 名称 + 三条卖点 + 开始按钮。
 */
@Composable
internal fun WelcomeScreen(onStart: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .testTag("welcome_screen")
            .background(Brush.linearGradient(listOf(BrandStart, BrandEnd)))
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 32.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(Modifier.weight(1f))

            ChecklistGlyph(modifier = Modifier.size(112.dp))

            Spacer(Modifier.height(28.dp))

            Text(
                text = "Jotlist",
                color = Color.White,
                fontSize = 46.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.5.sp
            )
            Spacer(Modifier.height(10.dp))
            Text(
                text = "待办 · 笔记 · 每天自动循环",
                color = Color.White.copy(alpha = 0.9f),
                fontSize = 16.sp
            )

            Spacer(Modifier.height(44.dp))

            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                WelcomeBullet(Icons.Default.Check, "重复任务完成后，到了下个周期自动恢复")
                WelcomeBullet(Icons.Default.Notifications, "到点提醒，可设免打扰时段")
                WelcomeBullet(Icons.Default.Lock, "数据只存在本机，不联网、无广告")
            }

            Spacer(Modifier.weight(1.2f))

            Button(
                onClick = onStart,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(54.dp)
                    .testTag("welcome_start"),
                shape = RoundedCornerShape(27.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color.White,
                    contentColor = BrandStart
                )
            ) {
                Text("开始使用", fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.height(12.dp))
            Text(
                text = "无需登录 · 无需联网",
                color = Color.White.copy(alpha = 0.7f),
                fontSize = 13.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
private fun WelcomeBullet(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    text: String
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(30.dp)
                .background(Color.White.copy(alpha = 0.16f), RoundedCornerShape(15.dp)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(18.dp)
            )
        }
        Spacer(Modifier.width(14.dp))
        Text(
            text = text,
            color = Color.White.copy(alpha = 0.95f),
            fontSize = 15.sp,
            style = MaterialTheme.typography.bodyMedium
        )
    }
}

/** 用和图标完全相同的 24×24 字形绘制，保证品牌一致。 */
@Composable
private fun ChecklistGlyph(modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val scale = size.minDimension / 24f
        val origin = Offset(
            (size.width - 24f * scale) / 2f,
            (size.height - 24f * scale) / 2f
        )
        drawChecklist(origin, scale, Color.Black.copy(alpha = 0.20f), Offset(0.8f, 1.6f) * scale)
        drawChecklist(origin, scale, Color.White, Offset.Zero)
    }
}

private fun DrawScope.drawChecklist(
    origin: Offset,
    scale: Float,
    color: Color,
    offset: Offset
) {
    fun point(x: Float, y: Float) =
        Offset(origin.x + x * scale + offset.x, origin.y + y * scale + offset.y)

    // 右侧两条横线
    drawRect(color, topLeft = point(13f, 7f), size = Size(9f * scale, 2f * scale))
    drawRect(color, topLeft = point(13f, 15f), size = Size(9f * scale, 2f * scale))

    // 左侧两个对勾
    val checks = listOf(
        listOf(
            5.54f to 11f, 2f to 7.46f, 3.41f to 6.05f,
            5.53f to 8.17f, 9.77f to 3.93f, 11.18f to 5.34f
        ),
        listOf(
            5.54f to 19f, 2f to 15.46f, 3.41f to 14.05f,
            5.53f to 16.17f, 9.77f to 11.93f, 11.18f to 13.34f
        )
    )
    checks.forEach { points ->
        val path = Path()
        points.forEachIndexed { index, (x, y) ->
            val p = point(x, y)
            if (index == 0) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y)
        }
        path.close()
        drawPath(path, color)
    }
}
