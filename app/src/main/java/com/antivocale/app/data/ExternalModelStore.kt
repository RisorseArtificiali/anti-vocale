package com.antivocale.app.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Single source of truth for imported external models (spec: External models
 * platform v2a). Persists the record list as one JSON preference via
 * [PreferencesManager]; derives nothing else. Directory validity is injected
 * so the class stays JVM-testable.
 *
 * Provided via [AppModule] (not `@Inject`) because the `dirExists` default
 * parameter is invisible to Dagger and would cause a MissingBinding at use sites.
 */
class ExternalModelStore(
    private val preferencesManager: PreferencesManager,
    private val dirExists: (String) -> Boolean = { java.io.File(it).exists() },
) {
    val recordsFlow: Flow<List<ExternalModelRecord>> =
        preferencesManager.externalModelsJson.map(ExternalModelListJson::decode)

    val validRecordsFlow: Flow<List<ExternalModelRecord>> =
        recordsFlow.map { records -> records.filter { dirExists(it.dir) } }

    suspend fun records(): List<ExternalModelRecord> = recordsFlow.first()

    /** Valid records only: a record whose directory vanished derives no descriptor anywhere. */
    suspend fun validRecords(): List<ExternalModelRecord> = records().filter { dirExists(it.dir) }

    suspend fun byId(id: String): ExternalModelRecord? =
        validRecords().firstOrNull { it.id == id }

    suspend fun add(record: ExternalModelRecord) = mutate { it + record }
    suspend fun update(record: ExternalModelRecord) = mutate { list -> list.map { if (it.id == record.id) record else it } }

    /**
     * Targeted dir redirect: a read-modify-write over the CURRENT record, so
     * concurrent edits to other fields (e.g. the importer rewriting pins on
     * re-import) survive. Callers holding a stale record snapshot must use
     * this instead of [update], whose whole-record writeback would revert them.
     */
    suspend fun updateDir(id: String, dir: String) = mutate { list ->
        list.map { if (it.id == id) it.copy(dir = dir) else it }
    }
    suspend fun delete(id: String): ExternalModelRecord? {
        val removed = records().firstOrNull { it.id == id }
        mutate { list -> list.filterNot { it.id == id } }
        return removed
    }

    // One lock for every whole-list read-modify-write (imports, deletes,
    // updateDir): two unsynchronized mutators would each write the list back
    // from their own snapshot and drop the other's change (an imported
    // record lost to a concurrent delete, or a delete silently reverted).
    private val mutateMutex = Mutex()

    private suspend fun mutate(transform: (List<ExternalModelRecord>) -> List<ExternalModelRecord>) {
        mutateMutex.withLock {
            val current = records()
            preferencesManager.saveExternalModelsJson(ExternalModelListJson.encode(transform(current)))
        }
    }
}
