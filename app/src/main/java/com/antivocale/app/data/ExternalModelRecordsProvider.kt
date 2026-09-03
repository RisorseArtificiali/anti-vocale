package com.antivocale.app.data

import android.util.Log
import com.antivocale.app.di.ApplicationScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Valid external-model records as a [StateFlow] snapshot, for
 * [com.antivocale.app.transcription.BackendRegistry]'s dynamic descriptors.
 *
 * The seam exists for determinism: the registry must not collect a Flow on a
 * hidden scope (tests read `backends` immediately after a store mutation and
 * would race the collector), so it reads the snapshot while the default
 * implementation keeps it fresh on a background scope. Validity is evaluated
 * only when the JSON preference emits: a deleted model dir keeps deriving a
 * descriptor until the next preference write.
 */
interface ExternalModelRecordsProvider {
    val records: StateFlow<List<ExternalModelRecord>>
}

@Singleton
class DefaultExternalModelRecordsProvider @Inject constructor(
    store: ExternalModelStore,
    // Shared process-lifetime scope (TASK-438): also gains the CrashReporter
    // handler this collector was missing when it built a private scope.
    @ApplicationScope private val scope: CoroutineScope,
) : ExternalModelRecordsProvider {
    private companion object {
        const val TAG = "ExternalModelRecords"
    }

    private val _records = MutableStateFlow<List<ExternalModelRecord>>(emptyList())
    override val records: StateFlow<List<ExternalModelRecord>> = _records

    init {
        // Explicit Default: preserves the pre-TASK-438 private scope's built-in
        // dispatcher; the shared scope carries none.
        scope.launch(Dispatchers.Default) {
            // Keep the last snapshot on failure: an upstream error must not kill
            // the process-wide collector permanently (catch completes the flow);
            // recovery comes only with a process restart.
            store.validRecordsFlow
                .catch { Log.w(TAG, "external records collector failed", it) }
                .collect { _records.value = it }
        }
    }
}
