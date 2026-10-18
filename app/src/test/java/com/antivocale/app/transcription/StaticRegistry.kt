package com.antivocale.app.transcription

import com.antivocale.app.data.ExternalModelRecord
import com.antivocale.app.data.ExternalModelRecordsProvider
import com.antivocale.app.data.ExternalModelStore
import com.antivocale.app.data.FakePreferencesManager
import kotlinx.coroutines.flow.MutableStateFlow

/** Empty synchronous provider: no external records, so no dynamic descriptors. */
fun emptyRecordsProvider(): ExternalModelRecordsProvider =
    object : ExternalModelRecordsProvider {
        override val records = MutableStateFlow(emptyList<ExternalModelRecord>())
    }

/**
 * A [BackendRegistry] over an empty external-model provider: the static eight
 * backends only. Fresh registry per call, and writes through one instance's
 * descriptors are NOT visible to another instance built over a different fake
 * store: tests needing shared store state must construct one registry and
 * reuse it. Tests that exercise dynamic external descriptors seed their own
 * provider (see BackendRegistryTest).
 */
fun staticRegistry(): BackendRegistry {
    seedCatalogForTest()
    return BackendRegistry(
        ExternalModelStore(FakePreferencesManager()),
        emptyRecordsProvider(),
    )
}
