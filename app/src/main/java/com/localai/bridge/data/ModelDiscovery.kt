package com.localai.bridge.data

import android.content.Context
import java.io.File

/**
 * Represents a discovered model available for selection.
 */
data class DiscoveredModel(
    val name: String,
    val path: String,
    val source: ModelSource,
    val sizeMB: Long,
    val variant: ModelDownloader.ModelVariant? = null
)

/**
 * Source of a discovered model.
 */
enum class ModelSource {
    DOWNLOADED,    // Downloaded via HuggingFace
    GALLERY,       // Google AI Edge Gallery
    PREVIOUS       // Previously used model (not in current lists)
}

/**
 * Helper for discovering available models from various sources.
 *
 * Reuses existing ModelDownloader methods to find:
 * - Downloaded models in app storage
 * - Google AI Edge Gallery models
 * - Previously used models from preferences
 */
object ModelDiscovery {

    /**
     * Discovers all available models from all sources.
     *
     * @param context Application context
     * @param previousModelPath Optional path to a previously used model
     * @return List of discovered models, ordered by source priority
     */
    fun discoverAvailableModels(
        context: Context,
        previousModelPath: String? = null
    ): List<DiscoveredModel> {
        val models = mutableListOf<DiscoveredModel>()
        val discoveredPaths = mutableSetOf<String>()

        // Add downloaded models
        ModelDownloader.listDownloadedModels(context).forEach { (variant, file) ->
            models.add(DiscoveredModel(
                name = variant.displayName,
                path = file.absolutePath,
                source = ModelSource.DOWNLOADED,
                sizeMB = file.length() / (1024 * 1024),
                variant = variant
            ))
            discoveredPaths.add(file.absolutePath)
        }

        // Add Gallery models
        ModelDownloader.listGalleryModels().forEach { (variant, file) ->
            models.add(DiscoveredModel(
                name = "${variant.displayName} (Gallery)",
                path = file.absolutePath,
                source = ModelSource.GALLERY,
                sizeMB = file.length() / (1024 * 1024),
                variant = variant
            ))
            discoveredPaths.add(file.absolutePath)
        }

        // Add previous model if not already in the list
        if (!previousModelPath.isNullOrEmpty() && previousModelPath !in discoveredPaths) {
            val file = File(previousModelPath)
            if (file.exists()) {
                models.add(DiscoveredModel(
                    name = file.name,
                    path = previousModelPath,
                    source = ModelSource.PREVIOUS,
                    sizeMB = file.length() / (1024 * 1024)
                ))
            }
        }

        return models
    }

    /**
     * Finds the model that matches the current path.
     *
     * @param currentPath The currently selected model path
     * @param models List of discovered models to search
     * @return The matching model or null if not found
     */
    fun findCurrentModel(currentPath: String?, models: List<DiscoveredModel>): DiscoveredModel? {
        if (currentPath.isNullOrEmpty()) return null
        return models.find { it.path == currentPath }
    }
}
