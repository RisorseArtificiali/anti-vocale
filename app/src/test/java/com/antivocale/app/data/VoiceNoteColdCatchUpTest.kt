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
import org.junit.Assert.assertNull
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

    /** The mock-backed per-source watermark store: what the detectors read and bump. */
    private val storedWatermarks = mutableMapOf<VoiceNoteSource, String?>()
    private val preferences = mockk<PreferencesManager>(relaxed = true)

    @Before
    fun setUp() {
        storedWatermarks.clear()
        every { preferences.voiceNoteWatermark } answers {
            flowOf(storedWatermarks[VoiceNoteSource.WHATSAPP])
        }
        every { preferences.voiceNoteBusinessWatermark } answers {
            flowOf(storedWatermarks[VoiceNoteSource.WHATSAPP_BUSINESS])
        }
        coEvery { preferences.bumpVoiceNoteWatermark(any()) } answers {
            val candidate = firstArg<String>()
            val current = storedWatermarks[VoiceNoteSource.WHATSAPP]
            if (current == null || candidate > current) {
                storedWatermarks[VoiceNoteSource.WHATSAPP] = candidate
            }
        }
        coEvery { preferences.bumpVoiceNoteBusinessWatermark(any()) } answers {
            val candidate = firstArg<String>()
            val current = storedWatermarks[VoiceNoteSource.WHATSAPP_BUSINESS]
            if (current == null || candidate > current) {
                storedWatermarks[VoiceNoteSource.WHATSAPP_BUSINESS] = candidate
            }
        }
        coEvery { preferences.resetVoiceNoteWatermark() } answers {
            storedWatermarks[VoiceNoteSource.WHATSAPP] = null
        }
        coEvery { preferences.resetVoiceNoteBusinessWatermark() } answers {
            storedWatermarks[VoiceNoteSource.WHATSAPP_BUSINESS] = null
        }
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
    private fun awaitWatermark(
        expected: String?,
        source: VoiceNoteSource = VoiceNoteSource.WHATSAPP,
    ) {
        kotlinx.coroutines.runBlocking {
            withTimeout(2_000) {
                while (storedWatermarks[source] != expected) delay(10)
            }
        }
    }

    @Test
    fun `emits notes newer than the watermark across folder rotation and advances it`() {
        note("100", "PTT-20261020-WA00001.opus", ageMs = 5_000) // AT the watermark
        note("100", "PTT-20261020-WA00002.opus", ageMs = 5_000) // past it
        note("202644", "PTT-20261025-WA00001.opus", ageMs = 5_000) // new counter folder
        storedWatermarks[VoiceNoteSource.WHATSAPP] = keyOf("100", "PTT-20261020-WA00001.opus")

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

    /**
     * TASK-889: the digest's data source. Each sweep round reports its
     * emission count on the burst channel (the coordinator folds the
     * rounds into ONE cumulative digest notification); a round that
     * emits nothing reports nothing (a 0-count digest would be noise).
     */
    @Test
    fun `each sweep round reports its burst count and silence emits nothing`() {
        val bursts = mutableListOf<Int>()
        scope.launch { detector.catchUpBursts.collect { bursts += it } }

        note("100", "PTT-20261025-WA00002.opus", ageMs = 5_000)
        note("100", "PTT-20261025-WA00003.opus", ageMs = 5_000)
        storedWatermarks[VoiceNoteSource.WHATSAPP] = keyOf("100", "PTT-20261025-WA00001.opus")

        kotlinx.coroutines.runBlocking {
            detector.coldCatchUpScan(listOf(File(tmp.root, "100")))
        }

        assertEquals("one burst carrying the round's count", listOf(2), bursts)
        assertEquals("the entries carry the digest-side catchup flag",
            2, entries.count { it.catchup })

        // The first scan advanced the watermark past everything: a quiet
        // re-sweep (the 830 rearm path) must not re-report.
        kotlinx.coroutines.runBlocking {
            detector.coldCatchUpScan(listOf(File(tmp.root, "100")))
        }
        assertEquals("a quiet sweep reports nothing", listOf(2), bursts)
    }

    @Test
    fun `a settle-round recovery counts into the burst channel`() {
        // Same scenario as the retry-round test, now asserting the burst
        // side: the late recovery is part of the same cold-start burst
        // (its result post is suppressed), so it must be counted.
        detector.coldCatchUpTtlMs = Long.MAX_VALUE
        note("100", "PTT-20261020-WA00001.opus", ageMs = 60_000) // settled history
        val fresh = note("100", "PTT-20261025-WA00009.opus", ageMs = 0)
        fresh.setLastModified(System.currentTimeMillis())
        storedWatermarks[VoiceNoteSource.WHATSAPP] = keyOf("100", "PTT-20261020-WA00001.opus")

        val bursts = mutableListOf<Int>()
        scope.launch { detector.catchUpBursts.collect { bursts += it } }

        kotlinx.coroutines.runBlocking {
            detector.coldCatchUpScan(listOf(File(tmp.root, "100")))
            withTimeout(5_000) {
                while (entries.isEmpty()) kotlinx.coroutines.delay(25)
            }
        }

        assertEquals("the settle round reports its own count", listOf(1), bursts)
        assertEquals(1, entries.size)
        assertTrue(entries.single().catchup)
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
        storedWatermarks[VoiceNoteSource.WHATSAPP] = keyOf("100", "PTT-20261025-WA00001.opus")

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
        storedWatermarks[VoiceNoteSource.WHATSAPP] = keyOf("100", "PTT-20261020-WA00002.opus")

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
    fun `a decorated redownload name is recovered and keyed as its original`() {
        // TASK-893: the live filter accepts any PTT-*.opus, but the strict
        // full-name parse rejected decorations like WhatsApp's redownload
        // suffix; those notes were never cold-recovered and never advanced
        // the watermark. The lenient prefix parse keys a decorated name as
        // its ORIGINAL (same note, same position), so the twin is emitted
        // when unseen and suppressed when the original was already seen.
        detector.coldCatchUpTtlMs = Long.MAX_VALUE
        note("100", "PTT-20261025-WA00001.opus", ageMs = 60_000)
        storedWatermarks[VoiceNoteSource.WHATSAPP] = keyOf("100", "PTT-20261020-WA00009.opus")

        kotlinx.coroutines.runBlocking {
            detector.coldCatchUpScan(listOf(File(tmp.root, "100")))
        }
        assertEquals(1, entries.size)

        // A decorated twin of the SAME note is keyed identically: a later
        // catch-up sees it as already-seen, never re-emitting the content.
        assertEquals(
            keyOf("100", "PTT-20261025-WA00001.opus"),
            VoiceNoteFilter.watermarkKeyOf(File("100/PTT-20261025-WA00001 (1).opus")))
        assertEquals(
            keyOf("100", "PTT-20261025-WA00001.opus"),
            VoiceNoteFilter.watermarkKeyOf(File("100/ptt-20261025-wa00001-copy.opus")))

        // A decorated note NEWER than the watermark is recovered too, and
        // advances the watermark to its semantic key.
        val decoratedEntries = mutableListOf<VoiceNoteEntry>()
        val business = businessDetector(decoratedEntries)
        try {
            note("200", "PTT-20261025-WA00012 (1).opus", ageMs = 60_000)
            kotlinx.coroutines.runBlocking {
                business.coldCatchUpScan(listOf(File(tmp.root, "200")))
            }
            assertEquals("the decorated note is recovered", 1, decoratedEntries.size)
            awaitWatermark(
                keyOf("200", "PTT-20261025-WA00012.opus"),
                VoiceNoteSource.WHATSAPP_BUSINESS)
        } finally {
            business.dispose()
        }
    }

    @Test
    fun `an original and its decorated twin emit once per burst`() {
        // Same key = same note: the auto voice-note path dedups by full
        // file name, so without the by-key candidate dedup a burst holding
        // both twins would transcribe the audio twice.
        detector.coldCatchUpTtlMs = Long.MAX_VALUE
        note("100", "PTT-20261025-WA00007.opus", ageMs = 60_000)
        note("100", "PTT-20261025-WA00007 (1).opus", ageMs = 60_000)

        kotlinx.coroutines.runBlocking {
            detector.coldCatchUpScan(listOf(File(tmp.root, "100")))
        }

        assertEquals("the twin pair emits once", 1, entries.size)
    }

    @Test
    fun `unparseable filter-passing names still return null keys`() {
        // The residual gap stays honest: a name the live filter accepts
        // but whose PTT-date-WA prefix does not parse cannot be ordered
        // (no semantic position), so it stays null rather than inventing a
        // lexicographic key that would sort past every real note in the
        // folder and suppress them from catch-up.
        assertNull(VoiceNoteFilter.watermarkKeyOf(File("100/PTT-hello.opus")))
        assertNull(VoiceNoteFilter.watermarkKeyOf(File("100/PTT-20261025.opus")))
        assertNull(VoiceNoteFilter.watermarkKeyOf(File("100/not-a-ptt.opus")))
        assertNull(VoiceNoteFilter.watermarkKeyOf(File("Recordings/PTT-20261025-WA00001.opus")))
    }

    @Test
    fun `a WhatsApp counter reset regresses the watermark instead of wedging recovery`() {
        // Review finding: a reinstall/clear-data rebuilds the tree from low
        // counters; a watermark above every existing folder would silently
        // suppress all future recovery. The scan must detect the regression,
        // reset the watermark, and proceed unfiltered.
        detector.coldCatchUpTtlMs = Long.MAX_VALUE
        note("7", "PTT-20261025-WA00001.opus", ageMs = 60_000) // fresh low-counter tree
        storedWatermarks[VoiceNoteSource.WHATSAPP] = keyOf("202644", "PTT-20261020-WA00002.opus") // old high-counter world

        kotlinx.coroutines.runBlocking {
            detector.coldCatchUpScan(listOf(File(tmp.root, "7")))
        }

        assertEquals("the regressed watermark was reset and the note recovered", 1, entries.size)
        assertTrue(entries.single().path!!.endsWith("PTT-20261025-WA00001.opus"))
        awaitWatermark(keyOf("7", "PTT-20261025-WA00001.opus"))
    }

    /**
     * TASK-891: the two-source fixture. Production hands ONE
     * PreferencesManager to the WhatsApp and the Business detector alike
     * (AppModule builds both coordinators over the same singleton), so
     * these tests share `preferences` the same way. What must NOT be
     * shared is the watermark: the two counter trees are independent
     * number spaces.
     */
    private fun businessDetector(entries: MutableList<VoiceNoteEntry>): VoiceNoteDetector {
        val business = VoiceNoteDetector(scope, preferences, VoiceNoteSource.WHATSAPP_BUSINESS)
        scope.launch { business.detections.collect { entries += it } }
        return business
    }

    @Test
    fun `a Business arm below the personal watermark does not reset it`() {
        // The personal watermark sits at a high counter folder; the
        // Business tree starts at low ones. Sharing one watermark makes
        // the Business arm read "every folder is below the watermark" as a
        // WhatsApp counter regression and wipe the PERSONAL watermark
        // (which then re-emits the personal history on the next catch-up).
        // TTL is widened on the SCANNING arm (the Business detector);
        // the personal detector never scans in this test.
        val businessEntries = mutableListOf<VoiceNoteEntry>()
        val business = businessDetector(businessEntries)
        try {
            business.coldCatchUpTtlMs = Long.MAX_VALUE
            val personalKey = keyOf("202644", "PTT-20261020-WA00009.opus")
            note("wa/202644", "PTT-20261020-WA00009.opus", ageMs = 60_000)
            storedWatermarks[VoiceNoteSource.WHATSAPP] = personalKey
            note("biz/7", "PTT-20261025-WA00001.opus", ageMs = 60_000)

            kotlinx.coroutines.runBlocking {
                business.coldCatchUpScan(listOf(File(tmp.root, "biz/7")))
            }

            assertEquals("the Business note is recovered", 1, businessEntries.size)
            assertEquals("the personal watermark survives the Business arm",
                personalKey, storedWatermarks[VoiceNoteSource.WHATSAPP])
            awaitWatermark(keyOf("biz/7", "PTT-20261025-WA00001.opus"),
                VoiceNoteSource.WHATSAPP_BUSINESS)
        } finally {
            business.dispose()
        }
    }

    @Test
    fun `a genuinely new Business note below the personal watermark is not suppressed`() {
        // The Business note sorts below the personal watermark (older PTT
        // date, same folder number, so the folder prune cannot explain the
        // outcome either). With independent stores the Business arm's own
        // null watermark suppresses nothing; under the shared-key bug this
        // exact shape was the silent-suppression leg: no reset rescues it
        // because the shared watermark's folder matches the scanned one.
        val businessEntries = mutableListOf<VoiceNoteEntry>()
        val business = businessDetector(businessEntries)
        try {
            business.coldCatchUpTtlMs = Long.MAX_VALUE
            val personalKey = keyOf("wa/202644", "PTT-20261020-WA00009.opus")
            note("wa/202644", "PTT-20261020-WA00009.opus", ageMs = 60_000)
            storedWatermarks[VoiceNoteSource.WHATSAPP] = personalKey
            note("biz/202644", "PTT-20261019-WA00001.opus", ageMs = 60_000)

            kotlinx.coroutines.runBlocking {
                business.coldCatchUpScan(listOf(File(tmp.root, "biz/202644")))
            }

            assertEquals("the Business note is emitted despite sorting below" +
                " the personal watermark", 1, businessEntries.size)
            assertEquals("the personal watermark is untouched",
                personalKey, storedWatermarks[VoiceNoteSource.WHATSAPP])
            awaitWatermark(keyOf("biz/202644", "PTT-20261019-WA00001.opus"),
                VoiceNoteSource.WHATSAPP_BUSINESS)
        } finally {
            business.dispose()
        }
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
