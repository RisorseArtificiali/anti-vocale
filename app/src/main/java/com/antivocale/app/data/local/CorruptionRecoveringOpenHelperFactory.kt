package com.antivocale.app.data.local

import android.content.Context
import android.database.sqlite.SQLiteDatabaseCorruptException
import android.database.sqlite.SQLiteDiskIOException
import android.util.Log
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

/** The aside-naming infix the recovery mints and every consumer matches on. */
internal const val DB_ASIDE_INFIX = ".corrupt-"

/**
 * TASK-886: the History DB's corruption-recovery path. The motivating
 * crash: SQLITE_IOERR_SHORT_READ (522) at connection open, on the History
 * list query that runs at EVERY app open - a truncated database file means
 * a permanent LAUNCH CRASH LOOP, escapable only by clear-data/uninstall
 * (exactly how the 2026-10-18 nightly died on the RMX3853). The wrapper
 * intercepts every readable/writable access (the crash fired on a lazily
 * opened SECONDARY connection, so guarding the first open alone would
 * miss it): on the two corruption-class exceptions it closes the helper,
 * moves the database files ASIDE (preserving transcripts for manual
 * recovery; the 660/661 model-heal precedent) and retries once; a fresh
 * empty database is created and the app lives. TASK-892: when the rename
 * itself fails (dying disk), the damaged file is DELETED instead; losing
 * the bytes is the smaller harm next to reopening the same corruption at
 * every launch, and the ledger's preservedAside flag keeps the banner
 * honest about which happened. A second corruption during the retry
 * rethrows: a genuinely dying disk must not become a delete-everything
 * loop.
 */
class CorruptionRecoveringOpenHelperFactory(
    private val inner: SupportSQLiteOpenHelper.Factory,
) : SupportSQLiteOpenHelper.Factory {

    override fun create(configuration: SupportSQLiteOpenHelper.Configuration): SupportSQLiteOpenHelper =
        RecoveringHelper(
            delegate = inner.create(configuration),
            context = configuration.context,
        )

    internal class RecoveringHelper(
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
                RecoveringDatabase(open, this)
            } catch (e: SQLiteDiskIOException) {
                // The LIVE resolver, not a pinned instance: a later
                // query-path recovery swaps the database underneath this
                // wrapper again, and the guard's per-call resolution must
                // follow it (the 890 contract holds for every holder).
                recoverThenReopen(e, open)
                RecoveringDatabase(open, this)
            } catch (e: SQLiteDatabaseCorruptException) {
                recoverThenReopen(e, open)
                RecoveringDatabase(open, this)
            }

        /**
         * The query-path recovery: same rename-aside + ledger, then one
         * reopen. Public within the factory: [RecoveringDatabase.guard]
         * calls it and then re-resolves the live database itself (its
         * callers hold the wrapper across statements, so the per-call
         * resolution IS the reopen contract there).
         */
        fun recoverAfterQueryCorruption(cause: Exception) {
            recoverThenReopen(cause) { delegate.readableDatabase }
        }

        /**
         * Serialized: two threads hitting the same corruption must not race
         * the rename. Idempotent by the file's absence: a racing recovery
         * that already renamed leaves nothing for this one to move, and the
         * retry open then succeeds on the fresh file. Callers re-resolve
         * the live database through the helper afterwards; nothing needs
         * the return value.
         *
         * TASK-892: a rename that FAILS on a dying disk falls back to
         * delete(). Leaving the damaged file in place would reopen the
         * same corruption at EVERY launch, the exact permanent crash loop
         * this class exists to kill; losing the bytes is the smaller harm,
         * and the ledger records preservedAside=false so the banner says
         * so honestly. Repeated recoveries cap the aside sets at
         * [ASIDE_SETS_KEPT] so a sick disk cannot accumulate them forever.
         */
        @Synchronized
        private fun recoverThenReopen(
            cause: Exception,
            reopen: () -> SupportSQLiteDatabase,
        ) {
            val name = databaseName ?: throw cause
            Log.e(TAG, "History database corruption (${cause.javaClass.simpleName}: ${cause.message});" +
                " renaming the damaged files aside and recreating; the app would otherwise" +
                " crash at every launch (TASK-886)")
            // Release the damaged handles before renaming; a close failure
            // on an already-broken helper must not block the recovery.
            runCatching { delegate.close() }
            val databasesDir = context.getDatabasePath(name).parentFile
            // Strictly monotonic: two recoveries in the same millisecond
            // must not share an aside name (a second rename would silently
            // REPLACE the first preserved set) nor a notice stamp (the
            // banner's resurfacing guarantee keys on it).
            val stamp = LAST_STAMP.updateAndGet { prev ->
                maxOf(System.currentTimeMillis(), prev + 1)
            }
            var mainPreserved = false
            var minted = false
            var deletedAny = false
            DATABASE_SUFFIXES.forEach { suffix ->
                val damaged = File(databasesDir, name + suffix)
                if (damaged.exists()) {
                    val aside = File(databasesDir, "${name}$DB_ASIDE_INFIX$stamp$suffix")
                    if (renameAside(damaged, aside)) {
                        if (suffix == MAIN_SUFFIX) mainPreserved = true
                        minted = true
                        Log.w(TAG, "damaged file preserved at ${aside.name}")
                    } else if (damaged.delete()) {
                        deletedAny = true
                        Log.e(TAG, "rename aside failed for ${damaged.name};" +
                            " deleted it to escape the relaunch crash loop (TASK-892)")
                    } else {
                        Log.e(TAG, "could not rename OR delete ${damaged.name};" +
                            " the retry will fail loudly")
                    }
                }
            }
            // The cap runs only on attempts that ADDED a set: an attempt
            // that preserved nothing must not spend the user's last
            // recoverable snapshots.
            if (minted) pruneAsideSets(databasesDir, name)
            // Recorded only on attempts that MOVED something. An idempotent
            // racing attempt (a prior recovery already renamed everything)
            // must not overwrite the first recovery's notice with a
            // preservedAside=false re-surface of the same event.
            if (minted || deletedAny) {
                HistoryRecoveryLedger.record(stamp, mainPreserved)
            }
            // One retry: fresh files make the framework create an empty DB.
            reopen()
        }

        /** Test seam for a dying disk: rename indirection, injectable. */
        @androidx.annotation.VisibleForTesting
        internal var renameAside: (File, File) -> Boolean = { from, to -> from.renameTo(to) }

        /** Keeps the newest [ASIDE_SETS_KEPT] recovery sets; deletes older. */
        private fun pruneAsideSets(databasesDir: File?, name: String) {
            val pattern = Regex(Regex.escape("${name}$DB_ASIDE_INFIX") + "(\\d+)")
            val stamps = databasesDir?.listFiles()
                .orEmpty()
                .mapNotNull { f ->
                    pattern.find(f.name)?.groupValues[1]?.toLongOrNull()?.let { it to f }
                }
                .groupBy({ it.first }, { it.second })
            stamps.keys.sortedDescending().drop(ASIDE_SETS_KEPT).forEach { old ->
                stamps.getValue(old).forEach { file ->
                    if (file.delete()) Log.w(TAG, "capped aside set ${old}: deleted ${file.name}")
                }
            }
        }
    }

    /**
     * TASK-886 device-trial verdict (2026-10-25): the corruption surfaces
     * at the open of a LAZILY ACQUIRED SECONDARY connection, which happens
     * INSIDE SupportSQLiteDatabase.query() on the already-returned database
     * object; the helper-level catch alone never sees it (the on-device
     * trial reproduced the original crash THROUGH the shipped wrapper).
     * Every query therefore re-issues through [guard]: on the corruption
     * classes the helper recovers (close + rename aside + ledger) and the
     * SAME query replays once against the fresh database.
     *
     * TASK-890: [resolve] re-reads the database from the helper on EVERY
     * guarded call. Room 2.7.1 re-acquires a connection per use, but an
     * in-flight block (a suspend DAO call, a withTransaction body, the
     * invalidation tracker's block) holds this wrapper across statements:
     * a recovery mid-block closes the inner handle underneath it, and a
     * wrapper holding the old instance would crash the block with
     * IllegalStateException (not a corruption class). Per-call resolution
     * repairs every holder shape through one contract, the helper's:
     * readableDatabase/writableDatabase after close() return the live
     * database. The `by` delegation keeps the construction-time instance
     * for the non-query surface: a recovery inside a multi-statement
     * WRITE block aborts that block regardless (its transaction began on
     * the closed handle; TASK-895), so only the guarded reads
     * claim liveness.
     */
    internal class RecoveringDatabase(
        private val resolve: () -> SupportSQLiteDatabase,
        private val owner: RecoveringHelper,
    ) : SupportSQLiteDatabase by resolve() {

        internal val innerDb: SupportSQLiteDatabase get() = resolve()

        override fun query(query: String): android.database.Cursor =
            guard { it.query(query) }

        override fun query(query: androidx.sqlite.db.SupportSQLiteQuery): android.database.Cursor =
            guard { it.query(query) }

        override fun query(
            query: androidx.sqlite.db.SupportSQLiteQuery,
            cancellationSignal: android.os.CancellationSignal?,
        ): android.database.Cursor =
            guard { it.query(query, cancellationSignal) }

        private fun <R> guard(reissue: (SupportSQLiteDatabase) -> R): R =
            try {
                reissue(resolve())
            } catch (e: SQLiteDiskIOException) {
                owner.recoverAfterQueryCorruption(e)
                reissue(resolve())
            } catch (e: SQLiteDatabaseCorruptException) {
                owner.recoverAfterQueryCorruption(e)
                reissue(resolve())
            }
    }

    private companion object {
        const val TAG = "DbRecovery"

        /** The main file plus every journaling sidecar SQLite may hold. */
        val DATABASE_SUFFIXES = listOf("", "-wal", "-shm", "-journal")
        const val MAIN_SUFFIX = ""

        /** TASK-892: aside sets kept before the cap deletes the oldest. */
        const val ASIDE_SETS_KEPT = 2

        val LAST_STAMP = java.util.concurrent.atomic.AtomicLong(0L)
    }
}

/**
 * TASK-886: process-wide one-shot record that the History DB was damaged
 * and reinitialized, so the History tab can tell the user what happened.
 * An object, not an injected singleton, because the recovery fires from
 * the Room open helper where no DI graph exists yet; the UI reads it
 * lazily. TASK-892: the record carries whether the damaged files were
 * PRESERVED aside or had to be deleted (rename failed on a dying disk);
 * the banner's wording depends on it.
 */
object HistoryRecoveryLedger {

    /** One recovery event: [stamp] keys the banner dismissal. */
    data class RecoveryNotice(val stamp: Long, val preservedAside: Boolean)

    private val _lastRecovery = kotlinx.coroutines.flow.MutableStateFlow<RecoveryNotice?>(null)

    fun record(atMs: Long, preservedAside: Boolean) {
        _lastRecovery.value = RecoveryNotice(atMs, preservedAside)
    }

    /**
     * Observable (review 2026-10-25): a recovery can fire MID-SESSION (the
     * motivating crash opened a lazy secondary connection), so the History
     * banner must see updates after its first composition; the notice's
     * stamp also keys the dismissal so a second recovery re-surfaces the
     * notice. Null when no recovery happened this process.
     */
    val lastRecovery: kotlinx.coroutines.flow.StateFlow<RecoveryNotice?> = _lastRecovery.asStateFlow()
}

/** Test seam: the database the wrapper's GUARDED QUERY surface currently
 *  resolves (it re-reads the helper per call, so after a recovery this is
 *  the live database; the non-query delegation surface is TASK-895's
 *  documented gap). Identity assertions go through here. */
@androidx.annotation.VisibleForTesting
fun unwrappedDb(db: SupportSQLiteDatabase): SupportSQLiteDatabase =
    (db as? CorruptionRecoveringOpenHelperFactory.RecoveringDatabase)?.innerDb ?: db
