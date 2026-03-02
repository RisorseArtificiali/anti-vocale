package com.localai.bridge.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "localai_preferences")

/**
 * Manages persistent app preferences using DataStore.
 */
class PreferencesManager(private val context: Context) {

    companion object {
        private val MODEL_PATH = stringPreferencesKey("model_path")
        private val PREVIOUS_MODEL_PATH = stringPreferencesKey("previous_model_path")
        private val KEEP_ALIVE_TIMEOUT = stringPreferencesKey("keep_alive_timeout")
    }

    /**
     * Flow of the saved model path.
     */
    val modelPath: Flow<String?> = context.dataStore.data.map { preferences ->
        preferences[MODEL_PATH]
    }

    /**
     * Flow of the previous model path (for rollback).
     */
    val previousModelPath: Flow<String?> = context.dataStore.data.map { preferences ->
        preferences[PREVIOUS_MODEL_PATH]
    }

    /**
     * Saves the model path, automatically backing up the current path.
     */
    suspend fun saveModelPath(path: String) {
        context.dataStore.edit { preferences ->
            // Backup current path before overwriting
            val currentPath = preferences[MODEL_PATH]
            if (currentPath != null && currentPath != path) {
                preferences[PREVIOUS_MODEL_PATH] = currentPath
            }
            preferences[MODEL_PATH] = path
        }
    }

    /**
     * Restores the previous model path, if available.
     * Returns true if restored, false if no previous path exists.
     */
    suspend fun restorePreviousModel(): Boolean {
        context.dataStore.edit { preferences ->
            val previousPath = preferences[PREVIOUS_MODEL_PATH]
            val currentPath = preferences[MODEL_PATH]
            
            if (previousPath != null) {
                // Swap: current becomes previous, previous becomes current
                if (currentPath != null) {
                    preferences[PREVIOUS_MODEL_PATH] = currentPath
                } else {
                    preferences.remove(PREVIOUS_MODEL_PATH)
                }
                preferences[MODEL_PATH] = previousPath
            }
        }
        return context.dataStore.data.map { it[PREVIOUS_MODEL_PATH] }.first() != null
    }

    /**
     * Clears the previous model backup.
     */
    suspend fun clearPreviousModelPath() {
        context.dataStore.edit { preferences ->
            preferences.remove(PREVIOUS_MODEL_PATH)
        }
    }

    /**
     * Clears the saved model path.
     */
    suspend fun clearModelPath() {
        context.dataStore.edit { preferences ->
            preferences.remove(MODEL_PATH)
        }
    }

    /**
     * Flow of the keep-alive timeout in minutes.
     */
    val keepAliveTimeout: Flow<Int> = context.dataStore.data.map { preferences ->
        preferences[KEEP_ALIVE_TIMEOUT]?.toIntOrNull() ?: 5 // Default 5 minutes
    }

    /**
     * Saves the keep-alive timeout.
     */
    suspend fun saveKeepAliveTimeout(minutes: Int) {
        context.dataStore.edit { preferences ->
            preferences[KEEP_ALIVE_TIMEOUT] = minutes.toString()
        }
    }
}
