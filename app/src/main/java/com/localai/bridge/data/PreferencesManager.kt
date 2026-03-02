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
        private val KEEP_ALIVE_TIMEOUT = stringPreferencesKey("keep_alive_timeout")
        private val LANGUAGE_PREFERENCE = stringPreferencesKey("language_preference")
    }

    /**
     * Flow of the saved model path.
     */
    val modelPath: Flow<String?> = context.dataStore.data.map { preferences ->
        preferences[MODEL_PATH]
    }

    /**
     * Saves the model path.
     */
    suspend fun saveModelPath(path: String) {
        context.dataStore.edit { preferences ->
            preferences[MODEL_PATH] = path
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

    /**
     * Flow of the language preference.
     * Returns "system" by default (follows system locale).
     */
    val languagePreference: Flow<String> = context.dataStore.data.map { preferences ->
        preferences[LANGUAGE_PREFERENCE] ?: "system"
    }

    /**
     * Saves the language preference.
     * @param language "system", "en", or "it"
     */
    suspend fun saveLanguagePreference(language: String) {
        context.dataStore.edit { preferences ->
            preferences[LANGUAGE_PREFERENCE] = language
        }
    }
}
