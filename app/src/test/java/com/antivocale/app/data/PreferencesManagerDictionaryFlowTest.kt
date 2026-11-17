package com.antivocale.app.data

import android.app.Application
import com.antivocale.app.testing.TempDataStoreRule
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
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
 * TASK-1008: the dictionary flow must not re-emit its UNCHANGED value when an
 * unrelated key is written. Every DataStore write re-runs the map; without
 * distinctUntilChanged the value-identical emission reached the TASK-994.1
 * collector, which unloaded the active backend 2 seconds after every backend
 * activation (the measured-footprint write), silently killing long-audio runs
 * that were still preprocessing (the device repro: three runs, empty results,
 * no rows).
 *
 * The store runs on real IO threads (TempDataStoreRule), so this test never
 * races the scheduler: it pins EMISSION ORDER through DataStore's serialized
 * writes. A marker edit that must emit is saved right after the risky
 * unrelated write; if the unrelated write had re-emitted the unchanged
 * dictionary, that duplicate would be delivered BEFORE the marker (writes are
 * serialized, emissions are ordered). First emission after the risky write
 * equals the marker == no spurious re-emission.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class PreferencesManagerDictionaryFlowTest {

    @get:Rule
    val store = TempDataStoreRule("dictionary-flow")

    private val collectorScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    @After
    fun tearDownCollector() {
        collectorScope.cancel()
    }

    @Test
    fun `unrelated writes do not re-emit the dictionary`() = runBlocking {
        store.prefs.saveKeywordBiasing("Boncompagni, Chiaramonte")
        val emissions = Channel<String>(Channel.UNLIMITED)
        val job = collectorScope.launch {
            store.prefs.keywordBiasing.collect { emissions.send(it) }
        }

        // Drain the subscription's initial emission.
        withTimeout(5_000) { assertEquals("Boncompagni, Chiaramonte", emissions.receive()) }

        // The TASK-1008 class: the measured-footprint write at backend
        // activation, an UNRELATED key, must not wake the collector.
        store.prefs.saveKeepAliveTimeout(7)
        // The marker: a real dictionary edit that MUST emit.
        store.prefs.saveKeywordBiasing("Boncompagni")

        val first = withTimeout(5_000) { emissions.receive() }
        assertEquals(
            "the unrelated write must not re-emit the unchanged dictionary (TASK-1008)",
            "Boncompagni",
            first,
        )
        job.cancel()
    }

    /** The knob's flow carries the same guard (the TASK-1008 class, closed
     *  for the feature pair: a future change-reactive collector on the score
     *  must not fire on unrelated writes either). */
    @Test
    fun `unrelated writes do not re-emit the biasing score`() = runBlocking {
        val emissions = Channel<Float>(Channel.UNLIMITED)
        val job = collectorScope.launch {
            store.prefs.biasingScore.collect { emissions.send(it) }
        }
        val initial = withTimeout(5_000) { emissions.receive() }
        assertEquals(PreferencesManager.DEFAULT_BIASING_SCORE, initial)

        store.prefs.saveKeywordBiasing("Boncompagni, Chiaramonte")
        store.prefs.saveBiasingScore(3.5f)
        val first = withTimeout(5_000) { emissions.receive() }
        assertEquals(
            "the unrelated dictionary write must not re-emit the unchanged score",
            3.5f,
            first,
        )
        job.cancel()
    }
}
