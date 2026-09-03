package com.antivocale.app.util

import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import android.os.LocaleList
import android.util.Log
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import java.util.Locale

/**
 * Manages app-wide locale using AndroidX Per-App Language API.
 *
 * This provides:
 * - Immediate language changes without app restart
 * - Automatic integration with Android 13+ system language settings
 * - Automatic persistence of language preference
 */
object LocaleManager {

    private const val TAG = "LocaleManager"

    /**
     * Sets the app locale.
     *
     * @param localeCode "system" for system default, or a language code like "en" or "it"
     */
    fun setLocale(localeCode: String) {
        Log.d(TAG, "setLocale called with: $localeCode")
        val localeList = when (localeCode) {
            "system" -> LocaleListCompat.getEmptyLocaleList()
            // forLanguageTag handles region suffixes like "pt-BR" (values-pt-rBR);
            // the single-arg Locale constructor would treat the whole string as a language
            else -> LocaleListCompat.create(Locale.forLanguageTag(localeCode))
        }
        Log.d(TAG, "Calling setApplicationLocales with: $localeList")
        AppCompatDelegate.setApplicationLocales(localeList)
        Log.d(TAG, "setApplicationLocales completed")
    }

    /**
     * Gets the current app locale.
     *
     * @return The current Locale, or null if following system default
     */
    fun getCurrentLocale(): Locale? {
        val locales = AppCompatDelegate.getApplicationLocales()
        Log.d(TAG, "getApplicationLocales returned: $locales (isEmpty=${locales.isEmpty})")
        return if (locales.isEmpty) {
            null // System default
        } else {
            locales[0]
        }
    }

    /**
     * Gets the current locale code for persistence/UI purposes.
     *
     * @return "system", or a BCP-47 tag like "en", "it", "pt-BR"
     */
    fun getCurrentLocaleCode(): String {
        val locale = getCurrentLocale()
        // toLanguageTag preserves the region ("pt-BR"); language-only would round-trip as "pt"
        val code = locale?.let { if (it.country.isNullOrEmpty()) it.language else it.toLanguageTag() } ?: "system"
        Log.d(TAG, "getCurrentLocaleCode returning: $code")
        return code
    }

    /**
     * The effective UI locale: the explicit per-app locale when one is set,
     * else the system default. This is THE app locale; locale-following
     * behavior (TASK-434 transcription-language default) resolves from it,
     * never from a bare Locale.getDefault().
     */
    fun effectiveLocale(): Locale = getCurrentLocale() ?: Locale.getDefault()

    /**
     * Updates the context with the current locale for Compose content.
     * This is needed for apps using ComponentActivity instead of AppCompatActivity.
     */
    fun updateContextLocale(context: Context): Context {
        val locale = getCurrentLocale()
        return if (locale != null) {
            val config = context.resources.configuration
            val localeList = LocaleList(locale)
            LocaleList.setDefault(localeList)
            config.setLocales(localeList)
            context.createConfigurationContext(config)
        } else {
            context
        }
    }
}
