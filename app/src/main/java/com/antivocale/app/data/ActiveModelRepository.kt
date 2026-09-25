package com.antivocale.app.data

import android.content.Context
import com.antivocale.app.data.catalog.BundledCatalog
import com.antivocale.app.transcription.BackendDescriptor
import com.antivocale.app.transcription.BackendRegistry
import com.antivocale.app.transcription.TranscriptionLanguagePolicy
import com.antivocale.app.transcription.variantAwareDisplayName
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Single source of truth for the "currently active model" state.
 *
 * Reactively combines the selected transcription backend with the
 * corresponding per-backend model-path preference so that consumers
 * always see a consistent [ActiveModel].
 *
 * The per-backend dispatch lives in [BackendRegistry]: the backend id is
 * resolved to a [BackendDescriptor] whose model-path flow and display-name
 * derivation supply the emission. Backend ids without a registered
 * descriptor degrade to the generic [PreferencesManager.modelPath].
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class ActiveModelRepository @Inject constructor(
    private val preferencesManager: PreferencesManager,
    @ApplicationContext private val context: Context,
    private val backendRegistry: BackendRegistry,
) {
    /**
     * The active backend plus its saved model path and display name, reactively
     * derived from the backend preference and the matching per-backend model-path
     * flow. Exposed as a cold [Flow]; consumers that want a current-value snapshot
     * can `.first()` it, and view models can `collect` it to stay in sync. Both
     * [ModelViewModel] and [SettingsViewModel] collect this instead of dispatching
     * per-backend themselves, so a model change in one tab is reflected in the
     * other without a manual reload.
     */
    val activeModelFlow: Flow<ActiveModel> =
        preferencesManager.transcriptionBackend.flatMapLatest { backend ->
            val descriptor = backendRegistry.byBackendId(backend)
            modelPathFlowFor(descriptor).map { path ->
                path.toActiveModel(backend, descriptor)
            }
        }

    /**
     * TASK-546 AC3: the language codes the ACTIVE backend conditions on
     * ([TranscriptionLanguagePolicy.offeredLanguages] over the active entry).
     * The ONE owner of this derivation: the Settings language picker and the
     * History chip's re-run picker both collect it, so which catalog lookup
     * and which path feed the offered set can never drift between surfaces.
     */
    val offeredLanguageCodes: Flow<Set<String>> =
        activeModelFlow.map { active ->
            TranscriptionLanguagePolicy.offeredLanguages(
                modelPath = active.modelPath,
                entry = BundledCatalog.byId(active.backendId),
            )
        }

    /**
     * The descriptor's saved-model-path flow, falling back to the generic
     * preference for backend ids the registry does not know.
     */
    private fun modelPathFlowFor(descriptor: BackendDescriptor?): Flow<String?> =
        when {
            descriptor != null -> descriptor.modelPathFlow(preferencesManager)
            else -> preferencesManager.modelPath
        }

    /**
     * Guards against a blank saved path and derives the [ActiveModel] fields:
     * the backend id passes through untouched, and the name comes from the
     * shared variant-aware display-name derivation (TASK-436: fixed family
     * label plus the installed catalog variant, else the descriptor's
     * path-derived name), falling back to the model file name for unregistered
     * backends.
     */
    private fun String?.toActiveModel(backendId: String, descriptor: BackendDescriptor?): ActiveModel {
        val effectivePath = this?.takeUnless { it.isBlank() }
        return ActiveModel(
            backendId = backendId,
            modelPath = effectivePath,
            modelName = effectivePath?.let { path ->
                when (descriptor) {
                    null -> File(path).name
                    else -> variantAwareDisplayName(context, descriptor, path)
                }
            }
        )
    }

}

data class ActiveModel(
    val backendId: String,
    val modelPath: String?,
    val modelName: String?
)
