package com.antivocale.app.data

import android.content.Context
import com.antivocale.app.transcription.BackendDescriptor
import com.antivocale.app.transcription.BackendRegistry
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
 * descriptor keep today's fallback behavior: the disabled GGUF backend
 * (`gemma4_gguf`, deliberately unregistered because it has no BACKEND_ID
 * constant) reads its own `ggufModelPath` preference, and any other unknown
 * id degrades to the generic [PreferencesManager.modelPath].
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
            modelPathFlowFor(backend, descriptor).map { path ->
                path.toActiveModel(backend, descriptor)
            }
        }

    /**
     * The descriptor's saved-model-path flow, falling back for backend ids the
     * registry does not know: the disabled GGUF backend's dedicated preference,
     * then the generic preference for any other unknown id.
     */
    private fun modelPathFlowFor(backend: String, descriptor: BackendDescriptor?): Flow<String?> =
        when {
            descriptor != null -> descriptor.modelPathFlow(preferencesManager)
            backend == GGUF_BACKEND_ID -> preferencesManager.ggufModelPath
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

    private companion object {
        /** Backend id of the disabled GGUF backend; see the class KDoc. */
        const val GGUF_BACKEND_ID = "gemma4_gguf"
    }
}

data class ActiveModel(
    val backendId: String,
    val modelPath: String?,
    val modelName: String?
)
