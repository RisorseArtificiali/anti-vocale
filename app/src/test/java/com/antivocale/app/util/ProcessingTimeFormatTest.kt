package com.antivocale.app.util

import org.junit.Assert.assertEquals
import org.junit.Test

class ProcessingTimeFormatTest {

    @Test
    fun `under a second stays milliseconds`() {
        assertEquals("823ms", formatProcessingTime(823))
        assertEquals("0ms", formatProcessingTime(0))
    }

    @Test
    fun `under ten seconds keeps tenths`() {
        assertEquals("4.3s", formatProcessingTime(4_300))
        assertEquals("9.9s", formatProcessingTime(9_940))
    }

    @Test
    fun `the tenths branch never prints a two-digit seconds with tenths`() {
        assertEquals("9.9s", formatProcessingTime(9_949))
        assertEquals("10s", formatProcessingTime(9_950))
    }

    @Test
    fun `ten to fifty-nine seconds drops tenths`() {
        assertEquals("42s", formatProcessingTime(42_300))
        assertEquals("59s", formatProcessingTime(59_400))
    }

    @Test
    fun `the maintainer example 1068 point 7 seconds`() {
        assertEquals("17m 49s", formatProcessingTime(1_068_700))
    }

    @Test
    fun `exact minute has no seconds suffix`() {
        assertEquals("1m 0s", formatProcessingTime(60_000))
    }

    @Test
    fun `past the hour includes hours`() {
        assertEquals("1h 2m 3s", formatProcessingTime(3_723_000))
    }

    @Test
    fun `rounding happens before decomposition`() {
        // 59_960ms rounds to 60s -> 1m 0s, not "1m -0s" or "59s"
        assertEquals("1m 0s", formatProcessingTime(59_960))
    }
}
