package com.antivocale.app.transcription

import com.antivocale.app.testing.RepoFiles
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * TASK-816: the decode-boundary whisper loop guard's contract. The case
 * vector lives in eval/loop-guard-cases.json and is SHARED with the eval
 * harness's Python twin (eval/loop_guard_ab.py): both suites must agree on
 * the same file, so the Kotlin artifact and the A/B measurement can never
 * drift into testing two different algorithms. The two 792 incidents are
 * the real device rawTranscript strings (pulled from the History DB), not
 * paraphrases.
 */
class DegenerateRepetitionTest {

    @Test
    fun `the shared case vector passes`() {
        // The vector's cases are tuned to the implementation's constants
        // (WINDOW_WORDS / MAX_DISTINCT_UNITS): a constant change without
        // regenerating the vector (and re-running the corpus A/B that
        // justified it) fails here.
        val cases = JSONArray(vectorFile())
        assertTrue("the vector must stay populated", cases.length() >= 10)
        for (i in 0 until cases.length()) {
            val case = cases.getJSONObject(i)
            val name = case.getString("name")
            val outcome = DegenerateRepetition.trim(case.getString("text"))
            val expected = if (case.isNull("expected")) null else case.getString("expected")
            if (expected == null) {
                assertFalse("case '${name}' must not fire", outcome.fired)
                assertEquals("case '${name}' must return clean text verbatim",
                    case.getString("text"), outcome.text)
            } else {
                assertTrue("case '${name}' must fire", outcome.fired)
                assertEquals("case '${name}' trimmed text", expected, outcome.text)
                assertEquals("case '${name}' dropped count",
                    case.getInt("dropped"), outcome.droppedWords)
            }
        }
    }

    @Test
    fun `no fire returns the original string by reference`() {
        // The no-fire early return never rebuilds the text, so callers
        // comparing by reference or equality are never surprised.
        val clean = "una frase normale che non contiene nessun loop di ripetizione degenerato"
        assertSame(clean, DegenerateRepetition.trim(clean).text)
    }

    @Test
    fun `trim is idempotent`() {
        val looped = "prefisso breve " + "ehm ".repeat(40).trim()
        val once = DegenerateRepetition.trim(looped)
        val twice = DegenerateRepetition.trim(once.text)
        assertFalse(twice.fired)
        assertEquals(once.text, twice.text)
    }

    /**
     * The vector lives OUTSIDE the app module, so the module-relative
     * spelling carries the `../` (the one direction the pre-818 readers
     * lacked). [RepoFiles] owns the resolution.
     */
    private fun vectorFile(): String = RepoFiles.readText("../eval/loop-guard-cases.json")
}
