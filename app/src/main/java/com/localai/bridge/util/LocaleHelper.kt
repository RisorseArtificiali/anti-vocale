package com.localai.bridge.util

import android.content.Context
import android.content.res.Configuration
import android.os.Build
import java.util.Locale

/**
 * Helper class for managing app locale at runtime.
 * Used to apply user's language preference from settings.
 */
object LocaleHelper {

    /**
     * Applies the specified language to the context.
     *
     * @param context The context to apply the locale to
     * @param language The language code ("system", "en", "it", etc.)
     * @return A new context with the updated locale configuration
     */
    fun setLocale(context: Context, language: String): Context {
        val locale = when (language) {
            "system" -> getSystemLocale()
            else -> Locale(language)
        }

        Locale.setDefault(locale)

        val config = Configuration(context.resources.configuration)
        config.setLocale(locale)

        return context.createConfigurationContext(config)
    }

    /**
     * Gets the system's current locale.
     */
    private fun getSystemLocale(): Locale {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            android.content.res.Resources.getSystem().configuration.locales[0]
        } else {
            @Suppress("DEPRECATION")
            android.content.res.Resources.getSystem().configuration.locale
        }
    }

    /**
     * Gets the current app locale from the context.
     */
    fun getCurrentLocale(context: Context): Locale {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            context.resources.configuration.locales[0]
        } else {
            @Suppress("DEPRECATION")
            context.resources.configuration.locale
        }
    }
}
