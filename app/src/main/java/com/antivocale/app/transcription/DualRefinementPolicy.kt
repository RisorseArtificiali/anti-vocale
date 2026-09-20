package com.antivocale.app.transcription

/**
 * GH #43 (design D3): the one eligibility gate for two-pass transcription
 * (fast streaming first pass + accurate refinement). Pure on purpose: the
 * call site supplies resolved facts, so a duration or calibration-based
 * threshold can be added after the device trial without touching call sites.
 *
 * All conditions must hold; otherwise the request runs single-model exactly
 * as today. The gate returns the chosen fast backend id so the caller never
 * re-derives it.
 */
object DualRefinementPolicy {

    /** Sentinel carried through onSuccess's refinementOutcome when the first
     *  pass shipped unrefined (F4/F5); any other value is the fast model's
     *  display name. */
    const val NOT_REFINED = "not_refined"

    /** Stable skip tokens recorded in ProcessingContext.refinementSkipReason. */
    const val SKIP_FAST_LOAD_FAILED = "fast_load_failed"
    const val SKIP_FAST_BLANK = "fast_blank"
    const val SKIP_REFINE_LOAD_FAILED = "refine_load_failed"
    const val SKIP_REFINE_INFERENCE_FAILED = "refine_inference_failed"

    /** TASK-579: phase 2 completed but its text is a repetition loop
     *  (RepetitionLoopDetector); the first pass is delivered instead. */
    const val SKIP_REFINE_LOOP = "refine_loop_detected"

    /**
     * @param requestType the request's type token ("audio" qualifies; text,
     *   subtitle, and LLM requests never do).
     * @param backendOverride an explicit model override (retranscribe) is a
     *   deliberate single-model choice and disqualifies.
     * @param refinementEnabled the user toggle (default off).
     * @param selectedBackendId the user's selected backend (the accurate side).
     * @param streamingBackendId the installed streaming catalog entry, when
     *   one resolves (the fast side). Null when none is installed.
     */
    fun fastBackendFor(
        requestType: String,
        backendOverride: String?,
        refinementEnabled: Boolean,
        selectedBackendId: String,
        streamingBackendId: String?,
    ): String? {
        if (requestType != "audio") return null
        if (!backendOverride.isNullOrEmpty()) return null
        if (!refinementEnabled) return null
        val fast = streamingBackendId ?: return null
        if (fast == selectedBackendId) return null
        return fast
    }
}
