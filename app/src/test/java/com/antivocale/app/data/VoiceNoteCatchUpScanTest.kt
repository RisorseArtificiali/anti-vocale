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
class VoiceNoteCatchUpScanTest {

    /** TASK-906: the detector's SAF-lane context; these tests ride the raw
     *  lane and never query it, so a relaxed mock stands in. */
    private fun appContext(): android.content.Context = io.mockk.mockk(relaxed = true)


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
        detector = VoiceNoteDetector(scope, appContext(), preferences)
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
        val business = VoiceNoteDetector(scope, appContext(), io.mockk.mockk<PreferencesManager>(relaxed = true).also {
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
        val business = VoiceNoteDetector(scope, appContext(), io.mockk.mockk<PreferencesManager>(relaxed = true).also {
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

    // TASK-907: the SAF re-fire arm (raw listing denied, grant held). The
    // seam stands in for the level-1 + level-2 walk; a NON-EXISTENT folder
    // makes the raw listFiles() return null, the denial the arm serves.
    // The tree URI MUST be DocumentsContract-well-formed ("tree" as the
    // FIRST PATH SEGMENT, not the authority): getTreeDocumentId throws on
    // the malformed shape, safListFolders swallows it into null, and the
    // re-fire exits at "folder not in the grant" before any query runs
    // (the review pass caught exactly that voiding the production-route
    // test below).
    private val treeUri =
        "content://com.android.externalstorage.documents/tree/primary%3ATest"

    private fun deniedFolder(): File = File(tmp.root, "209877")

    @Test
    fun `a denied folder is served via SAF and a non-qualifying sibling does not block clearing`() = runBlocking {
        val folder = deniedFolder()
        assertTrue("precondition: the raw listing is denied", folder.listFiles() == null)
        detector.catchUpDelayMs = 10
        detector.safRefireForTest = { folderName ->
            assertEquals("209877", folderName)
            // A quiet note plus a non-qualifying sibling: the raw arm's
            // clear gate counts only voice notes, and the SAF arm must
            // mirror it (the sibling once held the entry pending in a
            // relaunch loop forever).
            listOf(
                SweepFile("${folder.absolutePath}/PTT-20261110-WA0001.opus",
                    System.currentTimeMillis() - 60_000),
                SweepFile("${folder.absolutePath}/systemd-tmpfile",
                    System.currentTimeMillis() - 60_000))
        }
        detector.subscribe(treeUri)
        collector()

        detector.scheduleCatchUp(folder)
        assertEquals(1, detector.pendingCatchUpsCount())
        awaitCount(1)
        // The emission and the clear ride one coroutine; the guard window
        // only rules out a spurious second emission from the sibling.
        delay(100)
        assertEquals(listOf("PTT-20261110-WA0001.opus"),
            entries.map { it.path!!.substringAfterLast('/') })
        assertEquals("all voice notes quiet: the entry clears despite the sibling",
            0, detector.pendingCatchUpsCount())
    }

    @Test
    fun `a failed SAF walk keeps the note pending`() = runBlocking {
        val folder = deniedFolder()
        detector.catchUpDelayMs = 10
        detector.safRefireForTest = { null }
        detector.subscribe(treeUri)
        collector()

        // The scan is driven SYNCHRONOUSLY (the delayed launch would leave
        // the keep-pending assertion indistinguishable from "never ran"
        // under a starved runner); the scheduled relaunch re-runs the same
        // keep-pending branch, so the assertion stays stable either way.
        detector.scheduleCatchUp(folder)
        detector.catchUpScan(folder)
        assertTrue("a denied walk is a fact to hold, not an empty folder", entries.isEmpty())
        assertEquals(1, detector.pendingCatchUpsCount())
    }

    @Test
    fun `a folder missing from the grant keeps the note pending`() = runBlocking {
        // Seam unset: the real level-1 query runs against the relaxed mock
        // resolver and answers an empty (successful) folder map, so the
        // re-fire finds no docId for the folder and must hold the entry.
        val folder = deniedFolder()
        detector.catchUpDelayMs = 10
        detector.subscribe(treeUri)
        collector()

        detector.scheduleCatchUp(folder)
        detector.catchUpScan(folder)
        assertTrue(entries.isEmpty())
        assertEquals(1, detector.pendingCatchUpsCount())
    }

    @Test
    fun `a failed SAF children listing keeps the note pending`() = runBlocking {
        // Production route through the honest-null branch: level-1 answers
        // the counter folder's docId, the level-2 children query FAILS
        // (null cursor). The pre-fix shape swallowed that failure into an
        // empty map and cleared the pending entry on the empty guess.
        val folder = deniedFolder()
        // A FRESH cursor per query call (the real provider's shape): the
        // cold walk at subscribe consumes one level-1 cursor, the re-fire's
        // own safListFolders consumes another; a single shared mock's
        // returnsMany sequence exhausts on the first reader and the second
        // sees an empty folder map. Relaxed so use{}'s close() (and any
        // other incidental call) is a no-op instead of throwing into
        // safListFolders' runCatching (caught by the routing verify below).
        fun freshLevel1(): android.database.Cursor = io.mockk.mockk(relaxed = true) {
            io.mockk.every { moveToNext() } returnsMany listOf(true, false)
            io.mockk.every { getString(0) } returns "doc-209877"
            io.mockk.every { getString(1) } returns "209877"
            io.mockk.every { getString(2) } returns
                android.provider.DocumentsContract.Document.MIME_TYPE_DIR
        }
        val resolver = io.mockk.mockk<android.content.ContentResolver> {
            io.mockk.every { query(any(), any(), any(), any(), any()) } answers {
                // Level-2 children URIs carry the folder's docId in their
                // path; the tree-root children query does not.
                if (firstArg<android.net.Uri>().toString().contains("doc-209877")) null
                else freshLevel1()
            }
        }
        val ctx: android.content.Context = io.mockk.mockk {
            io.mockk.every { contentResolver } returns resolver
        }
        val prefs = io.mockk.mockk<PreferencesManager>(relaxed = true) {
            io.mockk.every { voiceNoteWatermark } returns kotlinx.coroutines.flow.flowOf(null)
        }
        val nulled = VoiceNoteDetector(scope, ctx, prefs)
        val nulledEntries = mutableListOf<VoiceNoteEntry>()
        try {
            nulled.catchUpDelayMs = 10
            nulled.subscribe(treeUri)
            scope.launch { nulled.detections.collect { nulledEntries.add(it) } }

            nulled.scheduleCatchUp(folder)
            nulled.catchUpScan(folder)
            assertTrue("a failed listing is a fact to hold, not an empty folder",
                nulledEntries.isEmpty())
            assertEquals(1, nulled.pendingCatchUpsCount())
            // Routing proof (the review pass caught this test once passing
            // through the WRONG early-return): the honest-null branch is
            // only reachable past the level-1 hit, and the level-2 query
            // it makes is exactly the one the mock answers with null.
            io.mockk.verify(atLeast = 1) {
                resolver.query(match<android.net.Uri> {
                    it.toString().contains("doc-209877")
                }, any(), any(), any(), any())
            }
        } finally {
            nulled.dispose()
        }
    }
}
