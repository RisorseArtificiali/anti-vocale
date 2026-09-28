package com.antivocale.app.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FloatArraysTest {

    @Test
    fun `concatenates parts in order`() {
        val out = concatFloatArrays(floatArrayOf(1f, 2f), floatArrayOf(3f), floatArrayOf(4f, 5f))
        assertEquals(listOf(1f, 2f, 3f, 4f, 5f), out.toList())
    }

    @Test
    fun `no parts yields an empty array`() {
        assertTrue(concatFloatArrays().isEmpty())
    }

    @Test
    fun `empty parts are skipped without shifting order`() {
        val out = concatFloatArrays(floatArrayOf(), floatArrayOf(7f), floatArrayOf(), floatArrayOf(8f, 9f))
        assertEquals(listOf(7f, 8f, 9f), out.toList())
    }

    @Test
    fun `result is a copy, not an alias of the single part`() {
        val part = floatArrayOf(1f, 2f)
        val out = concatFloatArrays(part)
        part[0] = 99f
        assertEquals(1f, out[0])
    }

    @Test
    fun `list overload concatenates the parts`() {
        val out = concatFloatArrays(listOf(floatArrayOf(1f), floatArrayOf(2f, 3f)))
        assertEquals(listOf(1f, 2f, 3f), out.toList())
    }
}
