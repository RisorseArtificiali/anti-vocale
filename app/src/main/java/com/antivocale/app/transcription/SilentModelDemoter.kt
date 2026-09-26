package com.antivocale.app.transcription

import android.util.Log
import com.antivocale.app.data.PreferencesManager
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.withLock

/**
 * TASK-675: silent-model demotion, the two-signal discipline.
 *
 * A model that LOADS but decodes EMPTY while speech was present is broken on
 * this device (the catalog class that decodes garbage or nothing on some
 * hardware). Omnivoice's demote_model shape, mapped onto our signals:
 *
 *  - signal 1 (silent decode): the run raised
 *    [TranscriptionException.NoTranscriptionProduced] or the progressive
 *    path's BlankSegmentsException, with at least one chunk that decoded
 *    blank (an all-FAILED run is a different failure class and never counts).
 *  - signal 2 (speech was present): what the failure site can honestly see.
 *    On the VAD paths the post-preprocessing duration is the VAD-KEPT speech
 *    length, so duration > 0 means the VAD itself confirmed speech. On the
 *    pipeline path (which never strips silence) and on VAD-off runs the only
 *    available signal is decoded audio duration > 0: "the clip carried audio
 *    content". That weaker signal is why the threshold exists.
 *
 * Safety rules (the spec):
 *  - demote only after [DEMOTION_THRESHOLD] qualifying silent decodes, never
 *    on the first occurrence (a wrong file share must not demote a good
 *    model);
 *  - the counter lives in memory and a session is ONE APP PROCESS: a fresh
 *    process starts from zero and only the persisted demoted set survives;
 *  - remote and LLM backends are never demotable (an empty result is
 *    meaningful there, and the remote arm never decodes on this device);
 *  - demotion never gates manual selection: the user can always pick the
 *    model again, and that pick CLEARS the entry (the give-it-another-chance
 *    path);
 *  - no automatic downloads are ever triggered by a demotion.
 *
 * Every public member is fail-open: a demotion must never break the
 * transcription run that observed it.
 */
@Singleton
class SilentModelDemoter @Inject constructor(
    private val preferencesManager: PreferencesManager,
) {
    companion object {
        private const val TAG = "SilentModelDemoter"

        /** Silent decodes with speech present required before a demotion lands. */
        internal const val DEMOTION_THRESHOLD = 2

        /**
         * The one eligibility predicate: local ASR backends only. The LLM
         * backend and the OmniVoice LAN-offload backend are excluded by id
         * (the LLM's empty output is a legitimate answer; the remote backend
         * decodes nothing on this device, so a silent decode here says
         * nothing about the model).
         */
        fun isDemotable(backendId: String): Boolean =
            backendId != LlmTranscriptionBackend.BACKEND_ID &&
                backendId != RemoteOmnivoiceBackend.BACKEND_ID
    }

    /**
     * Per-process counters of qualifying silent decodes, keyed by backend id.
     * One app process = one session (documented contract); a restart clears
     * the counts and only the persisted set below survives.
     */
    private val silentDecodeCounts = java.util.concurrent.ConcurrentHashMap<String, Int>()

    /** Review F6: serializes the mark/clear sequences (a check-then-act
     *  across a cold flow read raced the manual clear). */
    private val demotionMutex = kotlinx.coroutines.sync.Mutex()

    /** The persisted demoted set, for UI state and auto-selection guards. */
    val demotedBackends: Flow<Set<String>> get() = preferencesManager.demotedBackends

    /** True when [backendId] sits in the persisted demoted set. */
    suspend fun isDemoted(backendId: String): Boolean =
        preferencesManager.demotedBackends.first().contains(backendId)

    /**
     * Records one silent-decode observation. Increments the session counter
     * only when BOTH signals fired (speech was present); writes the persisted
     * set when the counter reaches [DEMOTION_THRESHOLD]. Never raises.
     */
    suspend fun recordSilentDecode(backendId: String, speechConfirmed: Boolean) {
        // Review F1: only the STRONG signal qualifies (the VAD itself
        // confirmed speech). A bare positive duration proves samples
        // existed, not speech, and on the default VAD-off paths two music
        // files would demote a perfectly good model: the design note's
        // confirm-gate rule, honored at the call sites that know the truth.
        if (!isDemotable(backendId) || !speechConfirmed) return
        runCatching {
            demotionMutex.withLock {
                val count = silentDecodeCounts.merge(backendId, 1, Int::plus) ?: return@withLock
                if (count < DEMOTION_THRESHOLD) return@withLock
                if (!isDemoted(backendId)) {
                    preferencesManager.markBackendDemoted(backendId)
                    Log.w(TAG, "Demoted backend '$backendId': $count silent decodes with VAD-confirmed speech this session")
                }
            }
        }.onFailure { Log.w(TAG, "Demotion recording failed for '$backendId'", it) }
    }

    /**
     * Review F3+F5: a SUCCESSFUL decode is the strongest rehabilitation
     * signal. It resets the session counter (two isolated blanks between
     * hundreds of successes must not demote a working model) and clears a
     * persisted demotion (a model the user demonstrably uses through any
     * path, including share aliases and the benchmark, stops carrying the
     * notice). Never raises.
     */
    suspend fun onSuccessfulDecode(backendId: String) {
        if (!isDemotable(backendId)) return
        runCatching {
            demotionMutex.withLock {
                silentDecodeCounts.remove(backendId)
                if (isDemoted(backendId)) {
                    preferencesManager.clearDemotedBackend(backendId)
                    Log.i(TAG, "Cleared demotion for '$backendId': a decode succeeded")
                }
            }
        }.onFailure { Log.w(TAG, "Demotion clearing failed for '$backendId'", it) }
    }

    /** Review F7: deleting a model prunes its demotion entry (external ids
     *  never recur: a re-import mints a fresh uuid, so a stale entry would
     *  accumulate forever). Never raises. */
    suspend fun onModelDeleted(backendId: String) {
        runCatching {
            demotionMutex.withLock {
                silentDecodeCounts.remove(backendId)
                if (isDemoted(backendId)) preferencesManager.clearDemotedBackend(backendId)
            }
        }.onFailure { Log.w(TAG, "Demotion prune failed for '$backendId'", it) }
    }

    /**
     * The manual-selection path: picking the model again is the explicit
     * give-it-another-chance, so the persisted entry clears and the session
     * counter restarts from zero (the next demotion needs a fresh N=2).
     * Never raises.
     */
    suspend fun onManualSelection(backendId: String) {
        if (!isDemotable(backendId)) return
        runCatching {
            demotionMutex.withLock {
                silentDecodeCounts.remove(backendId)
                if (isDemoted(backendId)) {
                    preferencesManager.clearDemotedBackend(backendId)
                    Log.i(TAG, "Demotion cleared for '$backendId' by manual selection")
                }
            }
        }.onFailure { Log.w(TAG, "Demotion clear failed for '$backendId'", it) }
    }
}
