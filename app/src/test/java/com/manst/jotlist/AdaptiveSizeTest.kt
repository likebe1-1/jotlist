package com.manst.jotlist

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AdaptiveSizeTest {

    @Test
    fun `手机宽度用底栏`() {
        assertFalse(isWideScreen(360))
        assertFalse(isWideScreen(411))
        assertFalse(isWideScreen(839))
    }

    @Test
    fun `平板宽度用侧栏`() {
        assertTrue(isWideScreen(840))
        assertTrue(isWideScreen(1280))
    }
}
