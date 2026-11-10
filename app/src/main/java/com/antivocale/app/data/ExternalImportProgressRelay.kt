package com.antivocale.app.data

/**
 * TASK-971: the DI-scoped progress seam for the wiped-import repair. The
 * singleton repairer's reimport lambda is fixed at DI time
 * ([ExternalImportRepairer.reimportFor] builds it once in AppModule), so a
 * runtime party (the foreground service's notification) cannot rebuild the
 * graph to observe per-file progress. This relay is that one seam: the
 * lambda forwards every importer progress tick through [forward], and the
 * service swaps the field for its notification updater for the duration of
 * a repair run (nulling it on destroy). Single subscriber on purpose: the
 * repair is single-flight, and a second listener would race for the same
 * notification id anyway.
 */
class ExternalImportProgressRelay {
    /** The live subscriber, or null when nobody listens. Setting replaces. */
    @Volatile
    var forward: ((fileIndex: Int, fileCount: Int, fileName: String, bytesSoFar: Long, totalBytes: Long) -> Unit)? =
        null

    fun invoke(fileIndex: Int, fileCount: Int, fileName: String, bytesSoFar: Long, totalBytes: Long) {
        forward?.invoke(fileIndex, fileCount, fileName, bytesSoFar, totalBytes)
    }
}
