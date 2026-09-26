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

    // TASK-681: the OmniVoice LAN-offload id joins the selectable space the
    // LLM way (explicit predicate arm, never inside ALL) and gets the same
    // single-owner predicate treatment isLlm has.
    @Test
    fun `the LAN-offload id is selectable but not part of the catalog list`() {
        assertTrue(BuiltInBackendIds.isSelectableBackendId(RemoteOmnivoiceBackend.BACKEND_ID))
        assertFalse("ALL is pinned to the catalog id set; the remote id must stay out",
            RemoteOmnivoiceBackend.BACKEND_ID in BuiltInBackendIds.ALL)
        assertFalse(BuiltInBackendIds.isLlm(RemoteOmnivoiceBackend.BACKEND_ID))
    }

    @Test
    fun `isRemoteOmnivoice is true only for the LAN-offload backend id`() {
        assertTrue(BuiltInBackendIds.isRemoteOmnivoice(RemoteOmnivoiceBackend.BACKEND_ID))
        BuiltInBackendIds.ALL.forEach { assertFalse(BuiltInBackendIds.isRemoteOmnivoice(it)) }
        assertFalse(BuiltInBackendIds.isRemoteOmnivoice(LlmTranscriptionBackend.BACKEND_ID))
        assertFalse(BuiltInBackendIds.isRemoteOmnivoice("external:abc-123"))
        assertFalse(BuiltInBackendIds.isRemoteOmnivoice(""))
    }
}
