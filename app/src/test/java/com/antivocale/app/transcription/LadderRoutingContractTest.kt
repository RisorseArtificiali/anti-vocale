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
     * TASK-698 -> TASK-699: the silent-stretch tripwire, stage 2 shape. ONE
     * run-level span at processRequest covers every decode, post-pass and
     * preprocessing stretch INSIDE processRequest's call graph (the
     * guarantee is scoped to that graph: a decode loop added in another file
     * is outside this tripwire's sight); the ten former per-stretch spans
     * are stripped. The subtitle arms DO reach processRequest
     * (InferenceService and SubtitleChoiceTimeoutWorker both call it), so
     * they ride inside the span; they never arm the seed (runArmedSeed), so
     * their ticks are no-ops AND a stale foreign seed is not refreshed by
     * them. The count pins BOTH directions:
     * removing the run-level
     * span silently reopens the TASK-602 F1 class (a reopen mid-run offering
     * crash recovery for a live run), and a new per-stretch span is a
     * redundant second ticker under the run one. A new silent stretch needs
     * NO wiring; if a future scope change moves the seeding arms out of
     * processRequest, move the span with them and update this count in the
     * same commit.
     */
    @Test
    fun `every silent stretch runs under withSeedHeartbeat`() {
        val source = orchestratorSource().readText()
        val wrapped = Regex("withSeedHeartbeat\\s*\\{").findAll(source).count()
        assertEquals(
            "expected exactly the TASK-699 stage-2 shape: one run-level " +
                "withSeedHeartbeat span at processRequest covering the whole " +
                "audio/text run. Zero spans reopens TASK-602 F1 (stale-seed " +
                "recovery offered over a live run); more than one means a " +
                "per-stretch span returned - redundant under the run span, " +
                "say why or remove it (TASK-699)",
            1,
            wrapped,
        )
    }
}
