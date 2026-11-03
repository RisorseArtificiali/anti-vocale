package com.antivocale.app.testing

import android.content.Context
import java.util.Locale

/**
 * The one owner of "which locales does this build ship" for the resource
 * guards: the shipped locale set derived from the values-* directories, and
 * a per-locale Context built from the application resources. Extracted
 * (TASK-942 review) from the three copies that had started to diverge.
 */
object ShippedLocales {

    private val localeShape = Regex("[a-z]{2,3}(-[A-Za-z0-9]{2,8})?")

    /** The shipped locale tags: "en" plus every values-* locale directory. */
    fun tags(): List<String> {
        val res = RepoFiles.resolve("src/main/res")
        val dirs = res.listFiles { f -> f.isDirectory }
            ?.map { it.name }
            ?.filter { it.startsWith("values-") }
            ?.map { it.removePrefix("values-").replace("-r", "-") }
            // only locale dirs: a future values-night overlay is not a locale
            ?.filter { localeShape.matches(it) } ?: emptyList()
        return (listOf("en") + dirs).distinct()
    }

    fun contextFor(appContext: Context, tag: String): Context {
        val config = android.content.res.Configuration(appContext.resources.configuration)
        config.setLocale(Locale.forLanguageTag(tag))
        return appContext.createConfigurationContext(config)
    }
}
