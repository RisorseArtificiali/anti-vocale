package com.antivocale.app.data.local

import android.content.Context
import android.database.sqlite.SQLiteDatabaseCorruptException
import android.database.sqlite.SQLiteDiskIOException
import android.util.Log
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

/**
 * TASK-886: the History DB's corruption-recovery path. The motivating
 * crash: SQLITE_IOERR_SHORT_READ (522) at connection open, on the History
 * list query that runs at EVERY app open - a truncated database file means
 * a permanent LAUNCH CRASH LOOP, escapable only by clear-data/uninstall
 * (exactly how the 2026-10-18 nightly died on the RMX3853). The wrapper
 * intercepts every readable/writable access (the crash fired on a lazily
 * opened SECONDARY connection, so guarding the first open alone would
 * miss it): on the two corruption-class exceptions it closes the helper,
 * RENAMES the database files aside (transcripts preserved on disk for
 * manual recovery, never deleted; the 660/661 model-heal precedent),
 * and retries once; a fresh empty database is created and the app lives.
 * A second corruption during the retry rethrows: a genuinely dying disk
 * must not become a delete-everything loop.
 */
class CorruptionRecoveringOpenHelperFactory(
    private val inner: SupportSQLiteOpenHelper.Factory,
) : SupportSQLiteOpenHelper.Factory {

    override fun create(configuration: SupportSQLiteOpenHelper.Configuration): SupportSQLiteOpenHelper =
        RecoveringHelper(
            delegate = inner.create(configuration),
            context = configuration.context,
        )

    private class RecoveringHelper(
        private val delegate: SupportSQLiteOpenHelper,
        private val context: Context,
    ) : SupportSQLiteOpenHelper by delegate {
        // The interface's own databaseName (delegated to the inner helper)
        // names the files to rename.

        override val writableDatabase: SupportSQLiteDatabase
            get() = access { delegate.writableDatabase }

        override val readableDatabase: SupportSQLiteDatabase
            get() = access { delegate.readableDatabase }

        private fun access(open: () -> SupportSQLiteDatabase): SupportSQLiteDatabase =
            try {
                open()
            } catch (e: SQLiteDiskIOException) {
                recoverAndRetry(e, open)
            } catch (e: SQLiteDatabaseCorruptException) {
                recoverAndRetry(e, open)
            }

        /**
         * Serialized: two threads hitting the same corruption must not race
         * the rename. Idempotent by the file's absence: a racing recovery
         * that already renamed leaves nothing for this one to move, and the
         * retry open then succeeds on the fresh file.
         */
        @Synchronized
        private fun recoverAndRetry(
            cause: Exception,
            retry: () -> SupportSQLiteDatabase,
        ): SupportSQLiteDatabase {
            val name = databaseName ?: throw cause
            Log.e(TAG, "History database corruption (${cause.javaClass.simpleName}: ${cause.message});" +
                " renaming the damaged files aside and recreating; the app would otherwise" +
                " crash at every launch (TASK-886)")
            // Release the damaged handles before renaming; a close failure
            // on an already-broken helper must not block the recovery.
            runCatching { delegate.close() }
            val databasesDir = context.getDatabasePath(name).parentFile
            val stamp = System.currentTimeMillis()
            var renamedAny = false
            DATABASE_SUFFIXES.forEach { suffix ->
                val damaged = File(databasesDir, name + suffix)
                if (damaged.exists()) {
                    val aside = File(databasesDir, "${name}.corrupt-$stamp$suffix")
                    if (damaged.renameTo(aside)) {
                        renamedAny = true
                        Log.w(TAG, "damaged file preserved at ${aside.name}")
                    } else {
                        Log.e(TAG, "could not rename ${damaged.name}; the retry will fail" +
                            " loudly rather than loop")
                    }
                }
            }
            if (renamedAny) HistoryRecoveryLedger.record(stamp)
            // One retry: fresh files make the framework create an empty DB.
            return retry()
        }
    }

    private companion object {
        const val TAG = "DbRecovery"

        /** The main file plus every journaling sidecar SQLite may hold. */
        val DATABASE_SUFFIXES = listOf("", "-wal", "-shm", "-journal")
    }
}

/**
 * TASK-886: process-wide one-shot record that the History DB was damaged
 * and reinitialized, so the History tab can tell the user what happened
 * (their transcripts were preserved aside, not deleted). An object, not
 * an injected singleton, because the recovery fires from the Room open
 * helper where no DI graph exists yet; the UI reads it lazily.
 */
object HistoryRecoveryLedger {
    private val _recoveredAt = kotlinx.coroutines.flow.MutableStateFlow<Long?>(null)

    fun record(atMs: Long) {
        _recoveredAt.value = atMs
    }

    /**
     * Observable (review 2026-10-25): a recovery can fire MID-SESSION (the
     * motivating crash opened a lazy secondary connection), so the History
     * banner must see updates after its first composition; the stamp value
     * also keys the dismissal so a second recovery re-surfaces the notice.
     * Null when no recovery happened this process.
     */
    val recoveredAt: kotlinx.coroutines.flow.StateFlow<Long?> = _recoveredAt.asStateFlow()
}
