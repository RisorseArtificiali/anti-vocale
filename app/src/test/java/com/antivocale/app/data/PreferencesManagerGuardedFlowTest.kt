package com.antivocale.app.data

import android.app.Application
import com.antivocale.app.testing.TempDataStoreRule
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * TASK-1009: every preference-flow shape in PreferencesManagerImpl suppresses
 * the value-identical re-emission an unrelated DataStore write causes. The
 * dictionary pair was pinned by [PreferencesManagerDictionaryFlowTest]; this
 * class pins the closure per SHAPE, because each shape guards by a different
 * route: the prefFlow builder, its nullable twin, the unseeded twins (no
 * cache mirror; Boolean and Set values ride the same generic), and the manual
 * guard on a two-key legacy fallback. The JSON-blob projections guard the raw
 * string before decoding, which the suite exercises through these same
 * builder semantics and does not re-pin here.
 *
 * Same harness argument as the dictionary test: the store runs on real IO
 * threads (TempDataStoreRule), DataStore writes are serialized and emissions
 * ordered, so a marker edit saved right after the risky unrelated write must
 * be the FIRST value received if (and only if) the unrelated write did not
 * re-emit the unchanged value.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class PreferencesManagerGuardedFlowTest {

    @get:Rule
    val store = TempDataStoreRule("guarded-flows")

    // Deliberately separate from TempDataStoreRule.scope (which owns the
    // DataStore itself): a misbehaving collector must not be able to cancel
    // the store's scope mid-test.
    private val collectorScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    @After
    fun tearDownCollector() {
        collectorScope.cancel()
    }

    private suspend fun <T> assertUnrelatedWriteIsInvisible(
        flow: Flow<T>,
        initial: T,
        markerWrite: suspend () -> Unit,
        expectedAfterMarker: T,
        shape: String,
    ) {
        val emissions = Channel<T>(Channel.UNLIMITED)
        val job = collectorScope.launch { flow.collect { emissions.send(it) } }
        withTimeout(5_000) { assertEquals(initial, emissions.receive()) }

        store.prefs.saveKeywordBiasing("unrelated write")
        markerWrite()

        val first = withTimeout(5_000) { emissions.receive() }
        assertEquals(
            "the unrelated write must not re-emit the unchanged $shape flow (TASK-1009)",
            expectedAfterMarker,
            first,
        )
        job.cancel()
    }

    /** The prefFlow builder shape (map + cache seed + guard in one place). */
    @Test
    fun `unrelated writes do not re-emit a builder flow`() = runBlocking {
        assertUnrelatedWriteIsInvisible(
            store.prefs.whatsNewLastShownVersion,
            initial = PreferencesManager.DEFAULT_WHATS_NEW_LAST_SHOWN_VERSION,
            markerWrite = { store.prefs.saveWhatsNewLastShownVersion(42) },
            expectedAfterMarker = 42,
            shape = "builder",
        )
    }

    /** The prefFlowNullable twin (null passthrough preserved, TASK-685). */
    @Test
    fun `unrelated writes do not re-emit a nullable builder flow`() = runBlocking {
        assertUnrelatedWriteIsInvisible(
            store.prefs.modelFilterLanguage,
            initial = null,
            markerWrite = { store.prefs.saveModelFilterLanguage("it") },
            expectedAfterMarker = "it",
            shape = "nullable builder",
        )
    }

    /** The unseeded builder shape (no cache mirror). */
    @Test
    fun `unrelated writes do not re-emit an unseeded builder flow`() = runBlocking {
        assertUnrelatedWriteIsInvisible(
            store.prefs.refinementEnabled,
            initial = false,
            markerWrite = { store.prefs.saveRefinementEnabled(true) },
            expectedAfterMarker = true,
            shape = "unseeded builder",
        )
    }

    /** The unseeded nullable twin (crash-recovery state, TASK-685 era). */
    @Test
    fun `unrelated writes do not re-emit an unseeded nullable flow`() = runBlocking {
        assertUnrelatedWriteIsInvisible(
            store.prefs.partialTranscriptionText,
            initial = null,
            markerWrite = { store.prefs.savePartialTranscriptionState("boncompagni") },
            expectedAfterMarker = "boncompagni",
            shape = "unseeded nullable",
        )
    }

    /** The manual-guard shape: two-key legacy fallback (keepAliveTimeout). */
    @Test
    fun `unrelated writes do not re-emit the legacy-fallback flow`() = runBlocking {
        assertUnrelatedWriteIsInvisible(
            store.prefs.keepAliveTimeout,
            initial = PreferencesManager.DEFAULT_KEEP_ALIVE_TIMEOUT,
            markerWrite = { store.prefs.saveKeepAliveTimeout(9) },
            expectedAfterMarker = 9,
            shape = "legacy-fallback",
        )
    }

    /** The manual-guard shape: per-entry two-key fallback (sherpaModelPath),
     *  the app's hottest change-reactive chain (TASK-1009 review). */
    @Test
    fun `unrelated writes do not re-emit the sherpa model path`() = runBlocking {
        assertUnrelatedWriteIsInvisible(
            store.prefs.sherpaModelPath("parakeet-v3"),
            initial = null,
            markerWrite = { store.prefs.saveSherpaModelPath("parakeet-v3", "/models/parakeet-v3") },
            expectedAfterMarker = "/models/parakeet-v3",
            shape = "sherpa model path",
        )
    }
}
