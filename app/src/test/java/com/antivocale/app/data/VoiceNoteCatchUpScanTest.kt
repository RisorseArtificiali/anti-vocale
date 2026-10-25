package com.antivocale.app.data

import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * TASK-831: the counter-folder catch-up scan on a real temp filesystem
 * (Robolectric provides one). Under Robolectric the WhatsApp root does
 * not exist, so subscribe() arms the detector without attaching any real
 * FileObserver; the real inotify event cannot fire, so the schedule site
 * is driven through its @VisibleForTesting seam (the real event path is
 * device-trial territory). The detector's scope is a test scope cancelled
 * in tearDown so no scan outlives the test.
 */
@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk = [34])
class VoiceNoteCatchUpScanTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var scope: CoroutineScope
    private lateinit var detector: VoiceNoteDetector
    private val entries = mutableListOf<VoiceNoteEntry>()

    @Before
    fun setUp() {
        scope = CoroutineScope(Dispatchers.Unconfined)
        // TASK-778: the detector now carries the watermark preferences; the
        // 831 scan never reads it, but the constructor needs the stub.
        val preferences = io.mockk.mockk<PreferencesManager>(relaxed = true)
        io.mockk.every { preferences.voiceNoteWatermark } returns
            kotlinx.coroutines.flow.flowOf(null)
        detector = VoiceNoteDetector(scope, preferences)
    }

    @After
    fun tearDown() {
        detector.dispose()
        scope.cancel()
    }

    private fun note(folder: File, name: String, lastModifiedAgoMs: Long): File {
        val f = File(folder, name)
        assertTrue(f.createNewFile())
        assertTrue(f.setLastModified(System.currentTimeMillis() - lastModifiedAgoMs))
        return f
    }

    private fun collector() {
        scope.launch { detector.detections.collect { entries.add(it) } }
    }

    private suspend fun awaitCount(n: Int) {
        withTimeout(5_000) { while (entries.size < n) delay(25) }
    }

    @Test
    fun `direct scan emits only quiet notes`() = runBlocking {
        val folder = tmp.newFolder("202642")
        note(folder, "PTT-20261015-WA0001.opus", lastModifiedAgoMs = 60_000)
        note(folder, "PTT-20261015-WA0002.opus", lastModifiedAgoMs = 0)
        note(folder, "systemd-tmpfile", lastModifiedAgoMs = 60_000)
        detector.catchUpDelayMs = 2_000
        detector.subscribe()
        collector()

        detector.catchUpScan(folder)
        delay(200)
        // The fresh note (WA0002, mid-write stand-in) must NOT emit; the
        // non-qualifying sibling must not either.
        assertEquals(listOf("PTT-20261015-WA0001.opus"),
            entries.map { it.path!!.substringAfterLast('/') })
    }

    @Test
    fun `scheduled path dedups, rescans fresh notes, and converges`() = runBlocking {
        val folder = tmp.newFolder("202643")
        note(folder, "PTT-20261015-WA0003.opus", lastModifiedAgoMs = 60_000)
        note(folder, "PTT-20261015-WA0004.opus", lastModifiedAgoMs = 0)
        detector.catchUpDelayMs = 150
        detector.subscribe()
        collector()

        // CREATE and MOVED_TO both fire for one folder: the pending set
        // must collapse the two schedules into one scan.
        detector.scheduleCatchUp(folder)
        detector.scheduleCatchUp(folder)
        assertEquals(1, detector.pendingCatchUpsCount())

        // Round 1: only the quiet note. Round 2: the fresh one has gone
        // quiet (150ms) and emits; then the entry clears. (Which round
        // emits WA0004 is timing-dependent at the >= boundary; the
        // assertion is on the eventual set, order-insensitive.)
        awaitCount(2)
        delay(300)
        assertEquals(listOf("PTT-20261015-WA0003.opus", "PTT-20261015-WA0004.opus"),
            entries.map { it.path!!.substringAfterLast('/') }.sorted())
        assertEquals(0, detector.pendingCatchUpsCount())
    }

    @Test
    fun `a disarm inside the delay window does not lose the note`() = runBlocking {
        val folder = tmp.newFolder("202644")
        note(folder, "PTT-20261015-WA0005.opus", lastModifiedAgoMs = 60_000)
        detector.catchUpDelayMs = 500
        detector.subscribe()
        collector()

        detector.scheduleCatchUp(folder)
        detector.dispose()
        assertEquals("orphaned, not dropped", 1, detector.pendingCatchUpsCount())

        detector.subscribe()
        awaitCount(1)
        delay(100)  // the scan clears its entry after emitting
        assertEquals("PTT-20261015-WA0005.opus",
            entries.single().path!!.substringAfterLast('/'))
        assertEquals(0, detector.pendingCatchUpsCount())
    }

    @Test
    fun `the two sources' detectors emit independently, even for the same PTT name`() = runBlocking {
        // WhatsApp Business support: one detector per source, each with its
        // own seen filter. A same-named note in both trees is two notes;
        // the Business detector must not swallow it as a duplicate of the
        // WhatsApp one (or the reverse).
        val name = "PTT-20261020-WA0001.opus"
        val waFolder = tmp.newFolder("wa", "202642")
        val w4bFolder = tmp.newFolder("w4b", "202642")
        note(waFolder, name, lastModifiedAgoMs = 60_000)
        note(w4bFolder, name, lastModifiedAgoMs = 60_000)
        val business = VoiceNoteDetector(scope, io.mockk.mockk<PreferencesManager>(relaxed = true).also {
                // The Business detector reads its OWN watermark member at
                // arm time; an unstubbed relaxed Flow answers empty and
                // .first() kills the shared scope.
                io.mockk.every { it.voiceNoteBusinessWatermark } returns kotlinx.coroutines.flow.flowOf(null)
            }, VoiceNoteSource.WHATSAPP_BUSINESS)
        val businessEntries = mutableListOf<VoiceNoteEntry>()
        try {
            detector.catchUpDelayMs = 2_000
            business.catchUpDelayMs = 2_000
            detector.subscribe()
            business.subscribe()
            collector()
            scope.launch { business.detections.collect { businessEntries.add(it) } }

            detector.catchUpScan(waFolder)
            business.catchUpScan(w4bFolder)
            awaitCount(1)
            withTimeout(5_000) { while (businessEntries.isEmpty()) delay(25) }
            // A re-scan of the same folder is deduped per detector.
            business.catchUpScan(w4bFolder)
            delay(200)

            assertEquals(listOf(File(waFolder, name).absolutePath), entries.map { it.path })
            assertEquals(listOf(File(w4bFolder, name).absolutePath), businessEntries.map { it.path })
        } finally {
            business.dispose()
        }
    }

    @Test
    fun `a disposed Business detector scans nothing while WhatsApp keeps working`() = runBlocking {
        val waFolder = tmp.newFolder("wa2", "202642")
        val w4bFolder = tmp.newFolder("w4b2", "202642")
        note(waFolder, "PTT-20261020-WA0002.opus", lastModifiedAgoMs = 60_000)
        note(w4bFolder, "PTT-20261020-WA0002.opus", lastModifiedAgoMs = 60_000)
        val business = VoiceNoteDetector(scope, io.mockk.mockk<PreferencesManager>(relaxed = true).also {
                io.mockk.every { it.voiceNoteBusinessWatermark } returns kotlinx.coroutines.flow.flowOf(null)
            }, VoiceNoteSource.WHATSAPP_BUSINESS)
        val businessEntries = mutableListOf<VoiceNoteEntry>()
        detector.catchUpDelayMs = 2_000
        detector.subscribe()
        business.subscribe()
        business.dispose()
        collector()
        scope.launch { business.detections.collect { businessEntries.add(it) } }

        business.catchUpScan(w4bFolder)
        detector.catchUpScan(waFolder)
        awaitCount(1)
        delay(200)
        assertTrue("monitoring off must read nothing for that source", businessEntries.isEmpty())
    }
}
