package com.antivocale.app.data

import android.util.Log
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

/**
 * TASK-741 (GH #125) slice 2: the persisted half of the scheduled folder
 * watch - which folders are watched (tree URI, display name, scan period)
 * and each folder's [FileSnapshot] dedup memory, as ONE JSON preference
 * (the ExternalModelStore shape: a single key, a decode that drops
 * malformed elements instead of the whole list, and a mutex-guarded
 * read-modify-write so a snapshot write never clobbers a concurrent
 * record edit on another folder).
 *
 * SIZE CONTRACT: the JSON's worst case is retention-1000 vanished facts
 * PLUS one entry per audio file still in the folder (the planner keeps
 * present files' facts uncapped). Tens to hundreds of recorder files
 * stay in the tens-of-KB range; a pathological 10k-file folder would
 * rewrite roughly 1MB per scan. Past that ceiling, migrate the snapshots
 * to a Room table (incremental writes) instead of widening this key.
 *
 * Unlike ExternalModelStore (whose defaulted dirExists param is invisible to
 * Dagger and needs an AppModule provider), the constructor is fully
 * injectable. @Singleton is NOT optional: the mutex's lost-update
 * rationale assumes every injection site shares ONE instance.
 */
@Singleton
class ScheduledFolderStore @Inject constructor(
    private val preferencesManager: PreferencesManager,
) {
    val foldersFlow: Flow<List<WatchedFolder>> =
        preferencesManager.scheduledFoldersJson.map(ScheduledFoldersJson::decode)

    suspend fun folders(): List<WatchedFolder> = foldersFlow.first()

    suspend fun byUri(treeUri: String): WatchedFolder? =
        folders().firstOrNull { it.treeUri == treeUri }

    suspend fun add(folder: WatchedFolder) = mutate { list ->
        if (list.any { it.treeUri == folder.treeUri }) list else list + folder
    }

    suspend fun remove(treeUri: String) = mutate { list ->
        list.filterNot { it.treeUri == treeUri }
    }

    suspend fun updatePeriod(treeUri: String, periodHours: Int) = mutate { list ->
        require(periodHours in 1..24) { "periodHours must be 1..24 (was $periodHours)" }
        list.map { if (it.treeUri == treeUri) it.copy(periodHours = periodHours) else it }
    }

    /** The scan memory for one folder (empty until the first scan lands). */
    suspend fun snapshotFor(treeUri: String): List<FileSnapshot> =
        byUri(treeUri)?.snapshot ?: emptyList()

    /**
     * Slice 3's worker write: replaces ONE folder's snapshot through the
     * current record (a stale [WatchedFolder] holder writing a period
     * change concurrently survives, same rationale as
     * ExternalModelStore.updateDir).
     */
    suspend fun saveSnapshot(treeUri: String, snapshot: List<FileSnapshot>) = mutate { list ->
        list.map { if (it.treeUri == treeUri) it.copy(snapshot = snapshot) else it }
    }

    private suspend fun mutate(block: (List<WatchedFolder>) -> List<WatchedFolder>) = mutateMutex.withLock {
        val current = folders()
        preferencesManager.saveScheduledFoldersJson(
            ScheduledFoldersJson.encode(block(current))
        )
    }

    private val mutateMutex = Mutex()
}

/** One watched folder and its dedup memory; [periodHours] feeds the worker schedule. */
data class WatchedFolder(
    val treeUri: String,
    val displayName: String,
    val periodHours: Int = DEFAULT_PERIOD_HOURS,
    val snapshot: List<FileSnapshot> = emptyList(),
) {
    companion object {
        const val DEFAULT_PERIOD_HOURS = 6
    }
}

object ScheduledFoldersJson {
    private const val TAG = "ScheduledFoldersJson"

    fun encode(folders: List<WatchedFolder>): String =
        JSONArray(folders.map { it.toJson() }).toString()

    fun decode(raw: String?): List<WatchedFolder> =
        decodeJsonList(raw, TAG, "watched-folder record") { fromJson(it) }

    private fun WatchedFolder.toJson(): JSONObject = JSONObject()
        .put("treeUri", treeUri)
        .put("displayName", displayName)
        .put("periodHours", periodHours)
        .put("snapshot", JSONArray(snapshot.map { snap ->
            JSONObject().put("name", snap.name).put("size", snap.size)
                .put("enqueuedAt", snap.enqueuedAt ?: JSONObject.NULL)
        }))

    private fun fromJson(o: JSONObject): WatchedFolder? {
        val uri = o.optString("treeUri")
        if (uri.isBlank()) return null
        return runCatching {
            val snaps = o.optJSONArray("snapshot") ?: JSONArray()
            WatchedFolder(
                treeUri = uri,
                displayName = o.optString("displayName").ifBlank { uri },
                // Corrupt or hand-seeded values coerce into range: the worker
                // scheduler would crash on a 0 period (review F4).
                periodHours = o.optInt("periodHours", WatchedFolder.DEFAULT_PERIOD_HOURS)
                    .coerceIn(1, 24),
                snapshot = buildList {
                    for (i in 0 until snaps.length()) {
                        val s = snaps.getJSONObject(i)
                        val name = s.optString("name")
                        if (name.isBlank()) continue
                        add(FileSnapshot(name, s.optLong("size", 0), enqueuedAt(s)))
                    }
                },
            )
        }.getOrNull()
    }

    private fun enqueuedAt(o: JSONObject): Long? =
        if (o.isNull("enqueuedAt")) null else o.optLong("enqueuedAt")
}
