package com.antivocale.app.service

import kotlinx.coroutines.Job

/**
 * TASK-899 (ANR audit HIGH-1): the startup work whose completion must be
 * ordered before the first transcription touches a History row. Two jobs
 * arm it: the orphaned-run sweep [com.antivocale.app.service.SuspendedRunRecovery]
 * (its "non-terminal row = orphaned by process death" classification is
 * only safe while no run is active) and the legacy custom-transducer
 * migration (a persisted id must resolve to an external record before
 * any backend resolution, or it silently falls through to the LLM
 * loader). Both used to guarantee that by runBlocking the MAIN thread
 * inside Application.onCreate (hundreds of ms of full-table SQLite, and
 * on the migration launch a SHA-256 over ~326 MB); they now run on the
 * application scope, and the awaits below preserve the ordering instead:
 * the transcription service's drain loop AND every queued-row insert
 * await this gate, so no fresh row can be swept as interrupted and no
 * backend can resolve before the registry is migrated. A plain object
 * (no AppModule binding) because both sides reach it directly: the app
 * arms it, the service and the enqueue paths await it.
 */
internal object StartupSweepGate {

    @Volatile
    private var jobs: List<Job> = emptyList()

    /** Called from Application.onCreate, before any service callback can dispatch. */
    fun arm(job: Job) {
        jobs = jobs + job
    }

    /**
     * Suspends until every armed startup job completed (or immediately
     * when they all did, or when nothing armed in this process).
     */
    suspend fun await() {
        jobs.forEach { it.join() }
    }
}
