package com.antivocale.app.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** TASK-462: test double for the records seam; empty by default. */
class FakeExternalRecordsProvider(
    initial: List<ExternalModelRecord> = emptyList(),
) : ExternalModelRecordsProvider {
    override val records: StateFlow<List<ExternalModelRecord>> = MutableStateFlow(initial)
}
