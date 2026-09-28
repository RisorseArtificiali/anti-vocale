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

    /**
     * TASK-698: the silent-stretch sweep's tripwire. Every decode or
     * generation stretch that can outlast the staleness gate runs inside
     * withSeedHeartbeat; a new call site added outside it compiles clean and
     * silently reopens the TASK-602 F1 class (the speaker-pass miss this
     * count pins shut). When you add or remove a site, update the count WITH
     * it and say why in the commit. The tenth span is TASK-186's
     * early-preview head decode (a 10s decode before the full chunk 0).
     */
    @Test
    fun `every silent stretch runs under withSeedHeartbeat`() {
        val source = orchestratorSource().readText()
        val wrapped = Regex("withSeedHeartbeat\\s*\\{").findAll(source).count()
        assertEquals(
            "expected the 10 known withSeedHeartbeat spans (the nine TASK-698 sites: " +
                "post-pass funnel, speaker labels, single-chunk conditional, progressive " +
                "decode, parallel decode, pipeline decode, GC retry, final-generative " +
                "pass, recoverEmptyChunk; plus the TASK-186 early-preview head decode); " +
                "a new decode or post-pass site must join them or say why not (TASK-698)",
            10,
            wrapped,
        )
    }
}
