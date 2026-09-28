package com.antivocale.app.transcription

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * TASK-696: source contract for the empty-chunk ladder routing. Every ladder
 * must run through TranscriptionOrchestrator.recoverEmptyChunk, the
 * seed-heartbeat ownership point; a site calling EmptyChunkRecovery.recover
 * directly compiles clean and silently loses the heartbeat coverage (the
 * popHeldEmpty miss this test pins shut). Reading source from disk follows the
 * AutomationBroadcastSnippetTest precedent.
 */
class LadderRoutingContractTest {

    private fun orchestratorSource(): File {
        val moduleRelative =
            File("src/main/java/com/antivocale/app/transcription/TranscriptionOrchestrator.kt")
        val rootRelative =
            File("app/src/main/java/com/antivocale/app/transcription/TranscriptionOrchestrator.kt")
        return when {
            moduleRelative.exists() -> moduleRelative
            rootRelative.exists() -> rootRelative
            else -> throw IllegalStateException(
                "Cannot locate TranscriptionOrchestrator.kt from ${File(".").absolutePath}")
        }
    }

    @Test
    fun `every ladder call routes through recoverEmptyChunk`() {
        val source = orchestratorSource().readText()
        val directCalls = Regex("EmptyChunkRecovery\\.recover\\s*\\(").findAll(source).toList()
        assertEquals(
            "TranscriptionOrchestrator must call EmptyChunkRecovery.recover exactly once, " +
                "inside recoverEmptyChunk (the seed-heartbeat ownership point); a direct call " +
                "elsewhere loses the heartbeat (TASK-696)",
            1,
            directCalls.size,
        )
        val wrapperStart = source.indexOf("private suspend fun recoverEmptyChunk(")
        assertTrue("recoverEmptyChunk not found in source", wrapperStart >= 0)
        assertTrue(
            "the one EmptyChunkRecovery.recover call must live inside recoverEmptyChunk",
            directCalls.first().range.first > wrapperStart,
        )
    }
}
