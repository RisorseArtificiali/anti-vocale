package com.antivocale.app.ui.tabs

import android.content.Context
import android.content.res.Configuration
import android.os.LocaleList
import androidx.test.core.app.ApplicationProvider
import com.antivocale.app.R
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * TASK-629: the settings search matches across the locale set (app locale +
 * PHONE locale + the English base), so a query in a language different from
 * the app UI still finds the setting. The phone leg is the one the task was
 * filed for (English UI, Italian user), so these tests pin it LIVE: the
 * app-en + system-it direction must surface the Italian text, and a
 * three-way split (app it, phone de, base en) must yield three distinct
 * variants. Robolectric resolves per-configuration resources only through
 * configuration contexts; the phone locale rides Locale.setDefault (the
 * read's pre-33 fallback source).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SettingsSearchLocaleVariantsTest {

    private val base: Context = ApplicationProvider.getApplicationContext()

    private fun appLocaleContext(tag: String): Context {
        val config = Configuration(base.resources.configuration)
        config.setLocales(LocaleList.forLanguageTags(tag))
        return base.createConfigurationContext(config)
    }

    @Test
    fun `a query in the user's language matches a card rendered in the app language`() {
        // The filed scenario, mirrored exactly: app English, phone Italian.
        val resolver = LocaleVariantResolver(appLocaleContext("en"), listOf(Locale.ITALY))
        val variants = resolver.variants(R.string.settings_group_integrations)
        assertEquals("Integrations", variants.first())
        assertTrue("the Italian text must be matchable: $variants",
            variants.any { it.equals("Integrazioni", ignoreCase = true) })
        assertTrue("the user-visible contract: the Italian query must match",
            matchesQuery("integrazioni", variants))
    }

    @Test
    fun `three different locales yield three distinct variants`() {
        val resolver = LocaleVariantResolver(appLocaleContext("it"), listOf(Locale.GERMANY))
        val variants = resolver.variants(R.string.settings_group_integrations)
        assertEquals(3, variants.distinct().size)
        assertEquals("Integrazioni", variants.first())
    }

    @Test
    fun `a mono-locale setup resolves to a single variant`() {
        val resolver = LocaleVariantResolver(appLocaleContext("en"), listOf(Locale.US))
        assertEquals(listOf("Integrations"), resolver.variants(R.string.settings_group_integrations))
    }

    @Test
    fun `the app-locale text always leads the variants`() {
        val itContext = appLocaleContext("it")
        val resolver = LocaleVariantResolver(itContext, listOf(Locale.ITALY))
        assertEquals(itContext.getString(R.string.settings_group_integrations),
            resolver.variants(R.string.settings_group_integrations).first())
    }

    @Test
    fun `the device finding - a second system locale joins the set`() {
        // The maintainer's phone: system list [en-IT, it-IT], app pinned EN.
        // Reading only [0] missed the Italian entirely (the on-device
        // "No matching settings" for "scuro" 2026-10-02).
        val resolver = LocaleVariantResolver(
            appLocaleContext("en"), listOf(java.util.Locale.forLanguageTag("en-IT"), Locale.ITALY))
        val variants = resolver.variants(R.string.settings_group_integrations)
        assertEquals("Integrations", variants.first())
        assertTrue("the second system locale must be matchable: $variants",
            variants.contains("Integrazioni"))
    }
}
