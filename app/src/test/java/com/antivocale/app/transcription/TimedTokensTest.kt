package com.antivocale.app.transcription

import org.junit.Assert.assertEquals
import org.junit.Test

class TimedTokensTest {

    @Test
    fun durationsPath_endCarriesTheTokenDuration() {
        val tokens = TimedTokens.fromRecognizer(
            tokens = arrayOf("▁a", "▁b"),
            timestamps = floatArrayOf(0.5f, 1.0f),
            durations = floatArrayOf(0.25f, 0.25f),
        )
        assertEquals(
            listOf(
                TimedToken("▁a", 500, 750),
                TimedToken("▁b", 1000, 1250),
            ),
            tokens,
        )
    }

    @Test
    fun nextTokenPath_endIsTheSuccessorStart_lastTokenGetsThePad() {
        val tokens = TimedTokens.fromRecognizer(
            tokens = arrayOf("▁a", "▁b"),
            timestamps = floatArrayOf(0.5f, 1.0f),
            durations = floatArrayOf(),
        )
        assertEquals(
            listOf(
                TimedToken("▁a", 500, 1000),
                TimedToken("▁b", 1000, 1400),
            ),
            tokens,
        )
    }

    @Test
    fun zeroDurations_fallBackPerTokenLikeTheNextTokenPath() {
        val tokens = TimedTokens.fromRecognizer(
            tokens = arrayOf("▁a", "▁b"),
            timestamps = floatArrayOf(0.5f, 1.0f),
            durations = floatArrayOf(0f, 0f),
        )
        assertEquals(
            listOf(
                TimedToken("▁a", 500, 1000),
                TimedToken("▁b", 1000, 1400),
            ),
            tokens,
        )
    }

    @Test
    fun sizeMismatch_yieldsNoTokens() {
        assertEquals(
            emptyList<TimedToken>(),
            TimedTokens.fromRecognizer(
                tokens = arrayOf("▁a", "▁b"),
                timestamps = floatArrayOf(0.5f, 1.0f, 2.0f),
                durations = floatArrayOf(),
            ),
        )
    }

    @Test
    fun emptyInput_yieldsNoTokens() {
        assertEquals(
            emptyList<TimedToken>(),
            TimedTokens.fromRecognizer(arrayOf(), floatArrayOf(), floatArrayOf()),
        )
    }

    @Test
    fun nanTimestamps_yieldNoTokens() {
        assertEquals(
            emptyList<TimedToken>(),
            TimedTokens.fromRecognizer(
                tokens = arrayOf("▁a", "▁b"),
                timestamps = floatArrayOf(Float.NaN, 1.0f),
                durations = floatArrayOf(),
            ),
        )
    }
}
