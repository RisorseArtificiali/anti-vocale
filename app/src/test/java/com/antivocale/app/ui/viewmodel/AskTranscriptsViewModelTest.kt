package com.antivocale.app.ui.viewmodel

import com.antivocale.app.data.local.LogEntity
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * TASK-992: the grounded prompt builder and the retrieval budget (the pure
 * core of the Q&A feature; the LLM call is the LlmManager's own concern).
 * buildPrompt lives on the ViewModel's companion object: pure, no instance
 * state, so the test calls it directly.
 */
class AskTranscriptsViewModelTest {

    private fun log(
        result: String = "test transcript",
        timestamp: Long = 1_700_000_000_000L,
        senderName: String? = null,
    ) = LogEntity(
        id = timestamp.toString() + result.hashCode(), timestamp = timestamp,
        taskId = "", type = "", status = "",
        result = result, senderName = senderName,
    )

    private val instruction =
        "Answer the question below based ONLY on these transcripts. " +
            "If the transcripts do not contain the answer, say so. " +
            "Answer in the language of the question."

    private fun buildPrompt(question: String, sources: List<LogEntity>): String =
        AskTranscriptsViewModel.buildPrompt(instruction, question, sources)

    @Test
    fun `the prompt carries the grounded system instruction first`() {
        val prompt = buildPrompt("what did Marco say?", listOf(log()))
        assertTrue("must open with the grounded instruction",
            prompt.startsWith("Answer the question below based ONLY on these transcripts."))
        assertTrue("must say what to do when the answer is absent",
            prompt.contains("If the transcripts do not contain the answer, say so"))
    }

    @Test
    fun `transcripts are numbered with sender and date stamps`() {
        val prompt = buildPrompt("what did Marco say?", listOf(
            log(result = "hello from Marco", senderName = "Marco"),
            log(result = "hello from Anna", senderName = "Anna"),
        ))
        assertTrue(prompt.contains("[Transcript 1, Marco,"))
        assertTrue(prompt.contains("hello from Marco"))
        assertTrue(prompt.contains("[Transcript 2, Anna,"))
        assertTrue(prompt.contains("hello from Anna"))
    }

    @Test
    fun `the question rides at the end`() {
        val prompt = buildPrompt("che ha detto Marco?", listOf(log()))
        assertTrue("question must terminate the prompt",
            prompt.trimEnd().endsWith("Question: che ha detto Marco?"))
    }

    @Test
    fun `the context budget caps the total transcript chars`() {
        val long = "x".repeat(5000)
        val prompt = buildPrompt("q", listOf(log(result = long), log(result = long)))
        assertTrue("prompt should stay bounded (was ${prompt.length})",
            prompt.length < AskTranscriptsViewModel.CONTEXT_BUDGET_CHARS + 500)
    }

    @Test
    fun `a long sender name cannot push the prompt past the budget`() {
        // The header is built before the budget check, so its real length
        // (unbounded sender name) must be paid from the budget, not estimated.
        val prompt = buildPrompt("q", listOf(
            log(result = "x".repeat(8000), senderName = "s".repeat(300)),
        ))
        assertTrue("scaffolding + budget + question is the ceiling (was ${prompt.length})",
            prompt.length <= AskTranscriptsViewModel.CONTEXT_BUDGET_CHARS + 200)
    }

    @Test
    fun `a blank transcript is skipped without burning a label number`() {
        val prompt = buildPrompt("q", listOf(
            log(result = ""),
            log(result = "real content"),
        ))
        // The real transcript must be [Transcript 1], not [Transcript 2]:
        // a blank source contributes nothing and does not consume a label.
        assertTrue("real content must be present", prompt.contains("real content"))
        assertTrue("real transcript gets label 1",
            prompt.contains("[Transcript 1") && !prompt.contains("[Transcript 2"))
    }
}
