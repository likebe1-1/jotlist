package com.manst.jotlist

import android.content.ComponentName
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 组件导出面的回归测试。
 *
 * 安全审计发现小组件 Provider 曾经 exported=true，第三方应用可伪造广播篡改待办；
 * 修复后固化为测试：Provider 只接收系统广播（exported=false），写操作走非导出
 * 接收器，且应用关闭了系统自动备份，避免以后被误改回去。
 */
@RunWith(AndroidJUnit4::class)
class ExportedComponentsTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val packageManager = context.packageManager

    @Test
    fun widgetProvidersAreNotExported() {
        listOf(
            TodoWidgetProvider::class.java,
            NotesWidgetProvider::class.java
        ).forEach { provider ->
            @Suppress("DEPRECATION")
            val info = packageManager.getReceiverInfo(ComponentName(context, provider), 0)
            assertFalse("${provider.simpleName} 不应导出给第三方应用", info.exported)
        }
    }

    @Test
    fun widgetWriteReceiverIsNotExported() {
        @Suppress("DEPRECATION")
        val info = packageManager.getReceiverInfo(
            ComponentName(context, WidgetActionReceiver::class.java),
            0
        )
        assertFalse("小组件写操作接收器必须非导出", info.exported)
    }

    @Test
    fun autoBackupIsDisabled() {
        val appInfo = packageManager.getApplicationInfo(context.packageName, 0)
        assertEquals(
            "allowBackup 必须关闭，避免待办/笔记进入系统云备份",
            0,
            appInfo.flags and ApplicationInfo.FLAG_ALLOW_BACKUP
        )
    }
}
