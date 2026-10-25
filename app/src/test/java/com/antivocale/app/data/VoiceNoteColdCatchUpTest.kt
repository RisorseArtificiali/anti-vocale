package com.antivocale.app.data

import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * TASK-778 (with the 829 option-C decision): the cold-start catch-up
 * sweep. Notes that arrived while the process was dead are invisible to
 * the RAM-only seen filter; the persisted watermark is what separates
 * them from history. These tests pin the sweep's decision rules on a real
 * temp filesystem: the watermark comparison (including counter-folder
 * rotation), the TTL staleness window, the burst cap (newest K), the
 * watermark's advance on emission, and the quiet gate for notes mid-write
 * at arm time. The watermark store is a mockk-backed variable so the
 * detector's own save path is what updates it.
 */
@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk = [34])
class VoiceNoteColdCatchUpTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var scope: CoroutineScope
    private lateinit var detector: VoiceNoteDetector
    private val entries = mutableListOf<VoiceNoteEntry>()

    /** The mock-backed watermark store: what the detector reads and bumps. */
    private var storedWatermark: String? = null
    private val preferences = mockk<PreferencesManager>(relaxed = true)

    @Before
    fun setUp() {
        storedWatermark = null
        every { preferences.voiceNoteWatermark } answers { flowOf(storedWatermark) }
        coEvery { preferences.bumpVoiceNoteWatermark(any()) } answers {
            val candidate = firstArg<String>()
            if (storedWatermark == null || candidate > storedWatermark!!) {
                storedWatermark = candidate
            }
        }
        coEvery { preferences.resetVoiceNoteWatermark() } answers { storedWatermark = null }
        scope = CoroutineScope(Dispatchers.Unconfined)
        detector = VoiceNoteDetector(scope, preferences)
        // Unconfined collector: emissions land in `entries` synchronously.
        scope.launch { detector.detections.collect { entries += it } }
        detector.subscribe()
    }

    @After
    fun tearDown() {
        detector.dispose()
        scope.cancel()
    }

    private fun note(folderName: String, fileName: String, ageMs: Long = 0): File {
        val folder = File(tmp.root, folderName).apply { mkdirs() }
        val file = File(folder, fileName).apply { writeText("x") }
        if (ageMs > 0) {
            file.setLastModified(System.currentTimeMillis() - ageMs)
        }
        return file
    }

    /** The production key derivation (the single ordering definition). */
    private fun keyOf(folderName: String, fileName: String): String =
        VoiceNoteFilter.watermarkKeyOf(File(folderName, fileName))!!

    /**
     * advanceWatermark dispatches to Dispatchers.IO even under the
     * Unconfined test scope, so watermark assertions poll instead of
     * racing the write.
     */
    private fun awaitWatermark(expected: String?) {
        kotlinx.coroutines.runBlocking {
            withTimeout(2_000) {
                while (storedWatermark != expected) delay(10)
            }
        }
    }

    @Test
    fun `emits notes newer than the watermark across folder rotation and advances it`() {
        note("100", "PTT-20261020-WA00001.opus", ageMs = 5_000) // AT the watermark
        note("100", "PTT-20261020-WA00002.opus", ageMs = 5_000) // past it
        note("202644", "PTT-20261025-WA00001.opus", ageMs = 5_000) // new counter folder
        storedWatermark = keyOf("100", "PTT-20261020-WA00001.opus")

        kotlinx.coroutines.runBlocking {
            detector.coldCatchUpScan(listOf(File(tmp.root, "100"), File(tmp.root, "202644")))
        }

        assertEquals(2, entries.size)
        assertTrue("all catch-up entries carry the flag",
            entries.all { it.catchup })
        assertTrue("the rotated-folder note is among them",
            entries.any { it.path!!.endsWith("202644/PTT-20261025-WA00001.opus") })
        awaitWatermark(keyOf("202644", "PTT-20261025-WA00001.opus"))
    }

    @Test
    fun `TTL excludes stale notes`() {
        detector.coldCatchUpTtlMs = 60_000
        note("100", "PTT-20261024-WA00001.opus", ageMs = 5_000) // fresh
        note("100", "PTT-20261023-WA00001.opus", ageMs = 120_000) // stale

        kotlinx.coroutines.runBlocking {
            detector.coldCatchUpScan(listOf(File(tmp.root, "100")))
        }

        assertEquals(1, entries.size)
        assertTrue(entries.single().path!!.endsWith("PTT-20261024-WA00001.opus"))
    }

    @Test
    fun `cap keeps the newest five`() {
        (1..7).forEach { n ->
            note("100", "PTT-20261025-WA%05d.opus".format(n), ageMs = 5_000)
        }

        kotlinx.coroutines.runBlocking {
            detector.coldCatchUpScan(listOf(File(tmp.root, "100")))
        }

        assertEquals("the burst is capped", 5, entries.size)
        assertTrue("the newest five survive, the oldest two are dropped",
            entries.none { it.path!!.endsWith("WA00001.opus") || it.path!!.endsWith("WA00002.opus") })
        awaitWatermark(keyOf("100", "PTT-20261025-WA00007.opus"))
    }

    @Test
    fun `watermark at the newest note swallows the sweep`() {
        note("100", "PTT-20261025-WA00001.opus", ageMs = 5_000)
        storedWatermark = keyOf("100", "PTT-20261025-WA00001.opus")

        kotlinx.coroutines.runBlocking {
            detector.coldCatchUpScan(listOf(File(tmp.root, "100")))
        }

        assertTrue("nothing to catch up: the process never missed a note", entries.isEmpty())
    }

    @Test
    fun `a note mid-write at arm time waits for its live event`() {
        detector.coldCatchUpTtlMs = Long.MAX_VALUE // only the quiet gate can exclude it
        val fresh = note("100", "PTT-20261025-WA00009.opus", ageMs = 0) // written "now"
        fresh.setLastModified(System.currentTimeMillis())

        kotlinx.coroutines.runBlocking {
            detector.coldCatchUpScan(listOf(File(tmp.root, "100")), allowRetry = false)
        }

        assertTrue("a file younger than the quiet gate must not be emitted half-written",
            entries.isEmpty())
    }

    @Test
    fun `a fresh note with no live event is recovered by the retry round`() {
        // Review finding: a note COMPLETED inside the quiet-gate window at
        // arm time has no live CLOSE_WRITE coming (its write ended before
        // the watcher attached); without the retry it would be lost until
        // the next arm. The watermark is seeded over the settled history
        // (a null watermark would catch the history up too, by design), so
        // the retry emits exactly the once-fresh note and nothing else.
        detector.coldCatchUpTtlMs = Long.MAX_VALUE
        detector.coldCatchUpCap = 5
        note("100", "PTT-20261020-WA00001.opus", ageMs = 60_000) // settled history
        val fresh = note("100", "PTT-20261025-WA00009.opus", ageMs = 0)
        fresh.setLastModified(System.currentTimeMillis())
        note("100", "PTT-20261020-WA00002.opus", ageMs = 60_000) // settled history
        storedWatermark = keyOf("100", "PTT-20261020-WA00002.opus")

        kotlinx.coroutines.runBlocking {
            detector.coldCatchUpScan(listOf(File(tmp.root, "100")))
            assertTrue("the fresh note is not emitted in the first round", entries.isEmpty())
            // The settle round runs catchUpDelayMs later on IO; poll with a
            // generous deadline instead of a fixed sleep (CI flakes).
            withTimeout(5_000) {
                while (entries.isEmpty()) kotlinx.coroutines.delay(25)
            }
        }

        assertEquals("the settle round recovered exactly the fresh note", 1, entries.size)
        assertTrue(entries.single().path!!.endsWith("PTT-20261025-WA00009.opus"))
        awaitWatermark(keyOf("100", "PTT-20261025-WA00009.opus"))
    }

    @Test
    fun `a WhatsApp counter reset regresses the watermark instead of wedging recovery`() {
        // Review finding: a reinstall/clear-data rebuilds the tree from low
        // counters; a watermark above every existing folder would silently
        // suppress all future recovery. The scan must detect the regression,
        // reset the watermark, and proceed unfiltered.
        detector.coldCatchUpTtlMs = Long.MAX_VALUE
        note("7", "PTT-20261025-WA00001.opus", ageMs = 60_000) // fresh low-counter tree
        storedWatermark = keyOf("202644", "PTT-20261020-WA00002.opus") // old high-counter world

        kotlinx.coroutines.runBlocking {
            detector.coldCatchUpScan(listOf(File(tmp.root, "7")))
        }

        assertEquals("the regressed watermark was reset and the note recovered", 1, entries.size)
        assertTrue(entries.single().path!!.endsWith("PTT-20261025-WA00001.opus"))
        awaitWatermark(keyOf("7", "PTT-20261025-WA00001.opus"))
    }

    @Test
    fun `live-path emissions advance the watermark too`() {
        // The 831 scan seam drives the SAME onDetection path the observer
        // callbacks use, so its emission proves the shared advance.
        note("300", "PTT-20261025-WA00001.opus", ageMs = 3_000)
        kotlinx.coroutines.runBlocking {
            detector.catchUpScan(File(tmp.root, "300"))
        }

        assertEquals(1, entries.size)
        awaitWatermark(keyOf("300", "PTT-20261025-WA00001.opus"))
    }
}
