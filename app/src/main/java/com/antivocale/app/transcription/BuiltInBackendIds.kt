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
    /** TASK-853: the Cactus Whistle engine (own Needle runtime, 17MB). */
    const val WHISTLE = "whistle"

    /**
     * TASK-681: the opt-in LAN-offload backend (the user's own OmniVoice
     * server) carries its id on [RemoteOmnivoiceBackend.BACKEND_ID], the
     * LLM pattern. It is deliberately NOT in [ALL]: that list is pinned to
     * the bundled catalog's id set, and this backend has no catalog entry;
     * like the LLM id it joins the selectable space explicitly in
     * [isSelectableBackendId].
     */

    /**
     * All seven built-in catalog entry ids, in canonical UI order (default
     * backend first). TASK-365: the LLM id joins via the backend's own
     * constant (the Gemma catalog fold); its descriptor is hand-written
     * (the registry routes it), not sherpa-derived.
     */
    val ALL: List<String> = listOf(
        PARAKEET, WHISPER, QWEN3_ASR, NEMOTRON, GIGAAM, WHISTLE,
        com.antivocale.app.transcription.LlmTranscriptionBackend.BACKEND_ID)

    /**
     * The ONE backend-id predicate for external callers (Tasker override,
     * debug SPI): the static backends (llm, the OmniVoice LAN-offload id and
     * the six catalog ids) plus any external record id. Keyed on [ALL], not
     * the catalog asset, so callers without BundledCatalog attached work
     * too; BundledModelCatalogTest pins [ALL] == the catalog's id set, so
     * the two views cannot drift. A second copy of this predicate is how
     * the Tasker receiver and the test SPI briefly accepted different id
     * spaces (code review 2026-09-03).
     */
    fun isSelectableBackendId(id: String): Boolean =
        id == com.antivocale.app.transcription.RemoteOmnivoiceBackend.BACKEND_ID ||
            id in ALL ||
            id.startsWith(com.antivocale.app.data.ExternalModelRecord.BACKEND_ID_PREFIX)

    /**
     * The ONE is-LLM predicate. A raw string compare against BACKEND_ID at a
     * UI site silently inverted for six weeks when the
     * DEFAULT_TRANSCRIPTION_BACKEND value it was paired with flipped under it
     * (de9b6aac, found in the 2026-09-13 settings audit); route every UI
     * and cross-layer is-LLM check through here so the next id/default
     * change has one owner to update. Transcription-layer internals may
     * compare the constant directly (same package as the constant's
     * owner).
     */
    fun isLlm(id: String): Boolean =
        id == LlmTranscriptionBackend.BACKEND_ID

    /**
     * TASK-681: the ONE is-LAN-offload predicate, the [isLlm] pattern: every
     * UI and cross-layer check for the OmniVoice backend routes through here
     * so the id has one owner. Transcription-layer internals may compare the
     * constant directly (same package).
     */
    fun isRemoteOmnivoice(id: String): Boolean =
        id == RemoteOmnivoiceBackend.BACKEND_ID

    /**
     * TASK-858: the ONE is-Whistle predicate, the [isLlm] pattern: every UI
     * and cross-layer check for the backend routes through here. Settings
     * cards gate on it (the keyword-biasing card) so the raw id compare has
     * one owner. Transcription-layer internals may compare the constant
     * directly (same package).
     */
    fun isWhistle(id: String): Boolean =
        id == WHISTLE

    /**
     * TASK-865/C: whether [id]'s card and switch affordances are hidden on
     * THIS build because its engine is a stub (-PwhistleEngineDisabled: the
     * F-Droid recipe). The ONE owner of the hide predicate: every surface
     * (catalog card, curated recommendations, the switch shortcut) asks
     * here, so a future stub axis extends one definition, not N copies.
     * Presence-keyed like the gradle property itself: a bare flag or any
     * value stubs; there is deliberately no per-build override.
     */
    fun hiddenOnStubBuild(id: String): Boolean =
        com.antivocale.app.BuildConfig.WHISTLE_ENGINE_DISABLED && isWhistle(id)
}
