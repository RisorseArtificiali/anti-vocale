package com.antivocale.app.data

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.json.JSONArray
import org.json.JSONObject

/**
 * TASK-741 slice 2: the watched-folder store - record mutations, snapshot
 * isolation between folders, JSON round-trip through the preference, and
 * the element-granularity decode contract.
 */
class ScheduledFolderStoreTest {

    private lateinit var fake: FakePreferencesManager
    private lateinit var store: ScheduledFolderStore

    @Before
    fun setUp() {
        fake = FakePreferencesManager()
        store = ScheduledFolderStore(fake)
    }

    private fun folder(uri: String = "content://tree/primary%3ARecordings", name: String = "Recordings") =
        WatchedFolder(treeUri = uri, displayName = name, periodHours = 6)

    @Test
    fun `empty store lists nothing and a record round-trips through the preference`() = runTest {
        assertEquals(emptyList<WatchedFolder>(), store.folders())
        store.add(folder())
        assertEquals(listOf(folder()), store.folders())
        // a fresh store over the same preference decodes the same records
        assertEquals(listOf(folder()), ScheduledFolderStore(fake).folders())
    }

    @Test
    fun `add is idempotent by tree uri`() = runTest {
        store.add(folder())
        store.add(folder().copy(displayName = "Other name", periodHours = 12))
        assertEquals(1, store.folders().size)
        assertEquals("Recordings", store.folders().single().displayName)
    }

    @Test
    fun `remove and updatePeriod touch only the target folder`() = runTest {
        store.add(folder(uri = "content://tree/a"))
        store.add(folder(uri = "content://tree/b"))
        store.updatePeriod("content://tree/a", 24)
        assertEquals(24, store.byUri("content://tree/a")?.periodHours)
        assertEquals(6, store.byUri("content://tree/b")?.periodHours)
        store.remove("content://tree/a")
        assertNull(store.byUri("content://tree/a"))
        assertEquals(6, store.byUri("content://tree/b")?.periodHours)
    }

    @Test
    fun `snapshots are per-folder and a snapshot write leaves other records intact`() = runTest {
        store.add(folder(uri = "content://tree/a"))
        store.add(folder(uri = "content://tree/b"))
        val snaps = listOf(
            FileSnapshot("rec.m4a", 42, enqueuedAt = 1000),
            FileSnapshot("rec2.opus", 7, enqueuedAt = null),
        )
        store.saveSnapshot("content://tree/a", snaps)
        assertEquals(snaps, store.snapshotFor("content://tree/a"))
        assertEquals(emptyList<FileSnapshot>(), store.snapshotFor("content://tree/b"))
        // the record's other fields survive the snapshot write
        assertEquals(6, store.byUri("content://tree/a")?.periodHours)
        // and the snapshot survives a record-field edit on ANOTHER folder
        store.updatePeriod("content://tree/b", 12)
        assertEquals(snaps, store.snapshotFor("content://tree/a"))
    }

    @Test
    fun `a snapshot for an unknown folder is a no-op`() = runTest {
        store.saveSnapshot("content://tree/missing", listOf(FileSnapshot("x.mp3", 1, null)))
        assertEquals(emptyList<WatchedFolder>(), store.folders())
    }

    @Test
    fun `decode drops one malformed record without taking the rest`() {
        val good = JSONObject()
            .put("treeUri", "content://tree/good")
            .put("displayName", "Good")
            .put("periodHours", 6)
            .put("snapshot", JSONArray())
        val bad = JSONObject().put("displayName", "NoUri")
        val decoded = ScheduledFoldersJson.decode(JSONArray(listOf(good, bad)).toString())
        assertEquals(1, decoded.size)
        assertEquals("content://tree/good", decoded.single().treeUri)
    }

    @Test
    fun `a null enqueuedAt round-trips as null`() {
        val snaps = listOf(FileSnapshot("rec.m4a", 42, enqueuedAt = null))
        val encoded = ScheduledFoldersJson.encode(listOf(folder(uri = "u").copy(snapshot = snaps)))
        val decoded = ScheduledFoldersJson.decode(encoded).single().snapshot.single()
        assertEquals(null, decoded.enqueuedAt)
        assertEquals("rec.m4a", decoded.name)
    }

    @Test
    fun `garbage json decodes to empty instead of throwing`() {
        assertEquals(emptyList<WatchedFolder>(), ScheduledFoldersJson.decode("not json at all"))
        assertEquals(emptyList<WatchedFolder>(), ScheduledFoldersJson.decode(null))
    }
}
