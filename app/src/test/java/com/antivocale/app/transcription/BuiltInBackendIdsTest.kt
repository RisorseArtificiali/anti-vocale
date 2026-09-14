package com.antivocale.app.transcription

import com.antivocale.app.data.PreferencesManager
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * TASK-507 review (PR #28 "missed one dispatch site" class): the ONE is-LLM
 * predicate lands with a pinning test. The de9b6aac inversion was a compare
 * against DEFAULT_TRANSCRIPTION_BACKEND flipping under the caller; the first
 * assertion below is the exact pin against that class.
 */
class BuiltInBackendIdsTest {

    @Test
    fun `the app default backend is not the LLM`() {
        // de9b6aac flipped the default from llm to sherpa-onnx and silently
        // inverted every isLlm-comparison built on it for six weeks.
        assertFalse(BuiltInBackendIds.isLlm(PreferencesManager.DEFAULT_TRANSCRIPTION_BACKEND))
    }

    @Test
    fun `isLlm is true only for the LLM backend id`() {
        assertTrue(BuiltInBackendIds.isLlm(LlmTranscriptionBackend.BACKEND_ID))
        BuiltInBackendIds.ALL.forEach { assertFalse(BuiltInBackendIds.isLlm(it)) }
    }

    @Test
    fun `external record ids are not the LLM`() {
        assertFalse(BuiltInBackendIds.isLlm("external:abc-123"))
        assertFalse(BuiltInBackendIds.isLlm(""))
    }
}
