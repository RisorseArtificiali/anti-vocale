package com.antivocale.app.transcription

/**
 * Canonical bundled-catalog entry ids for the built-in sherpa-onnx backends.
 *
 * Single source for the ids used across DI (the [SherpaBackend] instances),
 * [BackendRegistry], the orchestrator and the manifest share aliases. The
 * catalog asset itself is pinned to this set by BundledModelCatalogTest.
 */
object BuiltInBackendIds {
    const val PARAKEET = "sherpa-onnx"
    const val WHISPER = "whisper"
    const val QWEN3_ASR = "qwen3-asr"
    const val NEMOTRON = "nemotron-streaming"
    const val GIGAAM = "gigaam"

    /** All five built-in catalog entry ids, in canonical UI order (default backend first). */
    val ALL: List<String> = listOf(PARAKEET, WHISPER, QWEN3_ASR, NEMOTRON, GIGAAM)

    /**
     * The ONE backend-id predicate for external callers (Tasker override,
     * debug SPI): the static six (llm + the five catalog ids) plus any
     * external record id. Keyed on [ALL], not the catalog asset, so callers
     * without BundledCatalog attached work too; BundledModelCatalogTest pins
     * [ALL] == the catalog's id set, so the two views cannot drift. A second
     * copy of this predicate is how the Tasker receiver and the test SPI
     * briefly accepted different id spaces (code review 2026-09-03).
     */
    fun isSelectableBackendId(id: String): Boolean =
        id == com.antivocale.app.transcription.LlmTranscriptionBackend.BACKEND_ID ||
            id in ALL ||
            id.startsWith(com.antivocale.app.data.ExternalModelRecord.BACKEND_ID_PREFIX)
}
