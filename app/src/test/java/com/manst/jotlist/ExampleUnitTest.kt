package com.manst.jotlist

import org.junit.Test

import org.junit.Assert.*

/**
 * Example local unit test, which will execute on the development machine (host).
 *
 * See [testing documentation](http://d.android.com/tools/testing).
 */
class ExampleUnitTest {
    @Test
    fun addition_isCorrect() {
        assertEquals(4, 2 + 2)
    }

    @Test
    fun quicklyCreatedTodosHaveUniqueIds() {
        val ids = List(1_000) { TodoItem(text = "Task").id }

        assertEquals(ids.size, ids.toSet().size)
    }
}