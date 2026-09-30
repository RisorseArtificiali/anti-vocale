package com.antivocale.app.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * TASK-722: the sealed SaveResult contract. The failure tokens are NAMED
 * CONSTANTS (review round: inline literals made this test decorative); the
 * factory subtext consumption is covered in its own suite.
 */
class TranscriptFileSaverSaveResultTest {

    @Test
    fun `the failure tokens are the named constants, one per failing step`() {
        val tokens = listOf(
            TranscriptFileSaver.SaveResult.FAIL_FOLDER_UNAVAILABLE,
            TranscriptFileSaver.SaveResult.FAIL_NOT_WRITABLE,
            TranscriptFileSaver.SaveResult.FAIL_CREATE_REFUSED,
            TranscriptFileSaver.SaveResult.FAIL_OPEN_STREAM,
        )
        // distinct, non-blank, snake_case (they ride a user-facing string)
        assertEquals(tokens.size, tokens.toSet().size)
        tokens.forEach {
            assert(it.isNotBlank() && it.all { c -> c.isLowerCase() || c == '_' })
        }
    }

    @Test
    fun `failureOrNull is the one-line reduction the service sites use`() {
        assertEquals("create_refused",
            TranscriptFileSaver.SaveResult.Failed(TranscriptFileSaver.SaveResult.FAIL_CREATE_REFUSED).failureOrNull())
        assertNull(TranscriptFileSaver.SaveResult.Saved("a.txt").failureOrNull())
        assertNull(TranscriptFileSaver.SaveResult.NotConfigured.failureOrNull())
    }

    @Test
    fun `the sealed hierarchy stays at three shapes (exhaustive when, no else)`() {
        assertEquals(3, TranscriptFileSaver.SaveResult::class.sealedSubclasses.size)
    }
}
