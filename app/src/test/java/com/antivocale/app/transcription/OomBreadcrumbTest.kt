package com.antivocale.app.transcription

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * TASK-679: the breadcrumb line and the panel state mapping. Pure functions
 * only: the scrub contract (AC#3), the cap, and the panel's derived lines.
 */
class OomBreadcrumbTest {

    private val MB = 1024L * 1024L

    private fun snapshot(
        errorClass: String = "OutOfMemoryError",
        residents: List<ResidentEngine> = listOf(
            ResidentEngine("whisper", "Whisper Small"),
            ResidentEngine("llm", "Gemma (LiteRT-LM)"),
        ),
        freeRamBytes: Long? = 812L * MB,
        totalRamBytes: Long? = 3712L * MB,
        requestBackendId: String? = "whisper",
        audioDurationSeconds: Double? = 1431.0,
        vadEnabled: Boolean? = true,
    ) = OomBreadcrumb.Snapshot(
        errorClass = errorClass,
        residentEngines = residents,
        freeRamBytes = freeRamBytes,
        totalRamBytes = totalRamBytes,
        requestBackendId = requestBackendId,
        audioDurationSeconds = audioDurationSeconds,
        vadEnabled = vadEnabled,
    )

    @Test
    fun `the line names every resident, the request, RAM and the shape`() {
        val line = OomBreadcrumb.build(snapshot())
        assertTrue(line.startsWith("oom error=OutOfMemoryError"))
        assertTrue("the ASR resident is named", line.contains("whisper(Whisper Small)"))
        assertTrue("the LLM resident is named", line.contains("llm(Gemma (LiteRT-LM))"))
        assertTrue("RAM is present", line.contains("ram=812/3712MB"))
        assertTrue("the tipping request is named", line.contains("req=whisper"))
        assertTrue(line.contains("dur=1431s"))
        assertTrue(line.contains("vad=on"))
        assertTrue("one line only", !line.contains("\n"))
    }

    @Test
    fun `unknown RAM reads surface as unknown, omitted fields stay out`() {
        val line = OomBreadcrumb.build(
            snapshot(freeRamBytes = null, totalRamBytes = null,
                requestBackendId = null, audioDurationSeconds = null, vadEnabled = null))
        assertTrue(line.contains("ram=unknown"))
        assertFalse(line.contains("req="))
        assertFalse(line.contains("dur="))
        assertFalse(line.contains("vad="))
    }

    @Test
    fun `no residents is explicit, not blank`() {
        val line = OomBreadcrumb.build(snapshot(residents = emptyList()))
        assertTrue(line.contains("residents=none"))
    }

    @Test
    fun `scrub keeps only the last path segment`() {
        assertEquals(
            "gemma-2b.taskml",
            OomBreadcrumb.scrubToken(
                "/storage/emulated/0/Android/data/com.antivocale.app/files/models/gemma-2b.taskml"))
        assertEquals("Whisper Small", OomBreadcrumb.scrubToken("Whisper Small"))
        assertEquals("b", OomBreadcrumb.scrubToken("a\\b"))
    }

    @Test
    fun `scrub collapses whitespace so the line cannot break`() {
        assertEquals("a b c", OomBreadcrumb.scrubToken("a\nb\t c"))
    }

    /**
     * The scrub contract against transcript-like content (AC#3): the Snapshot
     * has no field transcript text flows through, and the token cap bounds
     * whatever a display name was adversarially set to, so the built line
     * carries none of the bulk content and never a line break.
     */
    @Test
    fun `transcript-like content in a name is capped out of the line`() {
        val transcriptLike = ("then he said hello and she answered goodbye " +
            "and the meeting moved to tuesday ").repeat(30)
        val line = OomBreadcrumb.build(
            snapshot(residents = listOf(ResidentEngine("whisper", transcriptLike))))
        assertTrue(line.length <= OomBreadcrumb.MAX_LENGTH)
        assertFalse("no newline smuggled in", line.contains("\n"))
        // The bulk of the transcript-like payload is absent (token cap 64).
        assertFalse(line.contains("meeting moved to tuesday"))
    }

    @Test
    fun `many residents cannot exceed the persisted cap`() {
        val many = (1..80).map { ResidentEngine("backend-$it", "Display name $it") }
        val line = OomBreadcrumb.build(snapshot(residents = many))
        assertTrue(line.length <= OomBreadcrumb.MAX_LENGTH)
    }

    // ---- Panel state assembly (AC#2, pure mapping) ----

    @Test
    fun `panel state maps residents, RAM and breadcrumb`() {
        val state = MemoryDiagnosticsState.assemble(
            residentEngines = listOf(
                ResidentEngine("whisper", "Whisper Small"),
                ResidentEngine("whisper", "duplicate id is collapsed"),
                ResidentEngine("llm", "Gemma (LiteRT-LM)")),
            freeRamBytes = 812L * MB,
            totalRamBytes = 3712L * MB,
            heapLimitBytes = 256L * MB,
            lastBreadcrumb = OomBreadcrumb.build(snapshot()),
            lastBreadcrumbAtMs = 1695840000000L)
        assertEquals(
            listOf("Whisper Small (whisper)", "Gemma LiteRT-LM (llm)"),
            state.residentLines())
        assertEquals("812MB free of 3712MB", state.ramLine())
        assertEquals("812MB", state.freeRamMb())
        assertEquals("3712MB", state.totalRamMb())
        assertEquals("256MB", state.heapLimitLine())
        assertTrue(state.lastBreadcrumb!!.startsWith("oom error="))
    }

    @Test
    fun `panel state blanks unreadable readings and empty breadcrumbs`() {
        val state = MemoryDiagnosticsState.assemble(
            residentEngines = emptyList(),
            freeRamBytes = 0L,
            totalRamBytes = null,
            heapLimitBytes = 256L * MB,
            lastBreadcrumb = "   ",
            lastBreadcrumbAtMs = 0L)
        assertTrue(state.residentLines().isEmpty())
        assertNull(state.ramLine())
        assertNull(state.freeRamMb())
        assertNull(state.lastBreadcrumb)
        assertNull(state.lastBreadcrumbAtMs)
    }

    @Test
    fun `the export bundle carries the scrubbed fields and nothing else`() {
        val state = MemoryDiagnosticsState.assemble(
            residentEngines = listOf(ResidentEngine("whisper", "Whisper Small")),
            freeRamBytes = 812L * MB,
            totalRamBytes = 3712L * MB,
            heapLimitBytes = 256L * MB,
            lastBreadcrumb = "oom error=OutOfMemoryError residents=whisper(Whisper Small) ram=812/3712MB",
            lastBreadcrumbAtMs = 1695840000000L)
        val bundle = state.exportBundle()
        assertTrue(bundle.contains("resident: Whisper Small (whisper)"))
        assertTrue(bundle.contains("ram: 812MB free of 3712MB"))
        assertTrue(bundle.contains("heap: 256MB"))
        assertTrue(bundle.contains("oom: oom error=OutOfMemoryError"))
        // Scrub contract at the bundle level: no transcript field exists to
        // leak, and the bundle is exactly the panel's scrubbed inputs.
        assertFalse(bundle.contains("transcript"))
        assertFalse(bundle.contains("/storage/"))
    }
}
