package com.antivocale.app.util

import android.content.Context
import android.content.pm.PackageManager
import com.antivocale.app.R
import com.antivocale.app.data.PerAppPreferencesManager

/**
 * Utility for getting app information from package names.
 */
object AppInfoUtils {

    /**
     * One row of the known-app table: package prefix, History grouping name,
     * and the canonical package the Share Back action targets (forks and
     * flavor builds reuse the family client's share entry point). Null
     * shareTarget means "share back to the exact source package".
     */
    private data class KnownApp(
        val prefix: String,
        /** Null on family catch-alls whose members are not censused: the PackageManager-label fallback applies. */
        val displayName: String?,
        val shareTarget: String? = null,
        /**
         * Families match by PREFIX (forks and flavor builds share a family
         * stem); standalone apps match EXACTLY, so sub-packages of a
         * standalone namespace (com.google.android.apps.docs.editors.*, the
         * Docs/Sheets/Slides editors) fall through to the PackageManager label
         * instead of reading "Google Drive" (code review 2026-09-03: prefix
         * matching them was a behavior change beyond the fork census).
         */
        val byPrefix: Boolean = false,
    )

    /**
     * The single known-app table (TASK-433): package prefix -> History
     * grouping name + Share Back target. It drives both the Logs grouping
     * ([knownAppName]) and the notification Share Back targeting
     * ([shareBackTarget]), which were previously two parallel mechanisms (the
     * exact commonNames map here and the prefix when block in
     * ResultNotificationFactory).
     *
     * Lookup is longest-prefix-first, so flavor suffixes
     * (tw.nekomimi.nekogram.beta, org.telegram.messenger.web) resolve to their
     * base entry and specific entries (com.whatsapp.w4b, org.telegram.plus)
     * beat their family prefixes. Brand names are proper nouns and
     * intentionally not localized. Entries guarantee a friendly grouping name
     * regardless of Android package visibility, which on 11+ intermittently
     * hides apps this one has not queried (the raw "com.*" fallback the Logs
     * grouping used to show for Google Files).
     *
     * Fork package ids are verified from each client's own
     * gradle.properties/manifest or the Killergram compatibility table
     * (TASK-433); Nagram ships as org.telegram.messenger and needs no entry.
     */
    private val knownApps = listOf(
        // WhatsApp family.
        KnownApp(PerAppPreferencesManager.WHATSAPP, "WhatsApp", PerAppPreferencesManager.WHATSAPP, byPrefix = true),
        KnownApp("com.whatsapp.w4b", "WhatsApp Business", PerAppPreferencesManager.WHATSAPP, byPrefix = true),
        // Telegram family: the official client, the censused forks, and the
        // bare family prefix as a display-less catch-all so uncensused
        // org.telegram.* packages keep the pre-TASK-433 Share Back behavior
        // (startsWith("org.telegram") -> official client) without gaining an
        // unverified History name.
        KnownApp(PerAppPreferencesManager.TELEGRAM, "Telegram", PerAppPreferencesManager.TELEGRAM, byPrefix = true),
        KnownApp("com.radolyn.ayugram", "AyuGram", PerAppPreferencesManager.TELEGRAM, byPrefix = true),
        KnownApp("tw.nekomimi.nekogram", "Nekogram", PerAppPreferencesManager.TELEGRAM, byPrefix = true),
        KnownApp("com.exteragram.messenger", "exteraGram", PerAppPreferencesManager.TELEGRAM, byPrefix = true),
        KnownApp("org.thunderdog.challegram", "Telegram X", PerAppPreferencesManager.TELEGRAM, byPrefix = true),
        KnownApp("org.telegram.plus", "Plus Messenger", PerAppPreferencesManager.TELEGRAM, byPrefix = true),
        KnownApp("it.owlgram.android", "OwlGram", PerAppPreferencesManager.TELEGRAM, byPrefix = true),
        KnownApp("com.iMe.android", "iMe", PerAppPreferencesManager.TELEGRAM, byPrefix = true),
        KnownApp("org.telegram", null, PerAppPreferencesManager.TELEGRAM, byPrefix = true),
        // Standalone apps: Share Back returns to the exact source package.
        KnownApp(PerAppPreferencesManager.SIGNAL, "Signal"),
        KnownApp("com.google.android.apps.nbu.files", "Files by Google"),
        KnownApp("com.google.android.apps.docs", "Google Drive"),
        KnownApp("com.google.android.gm", "Gmail"),
        KnownApp("com.Slack", "Slack"),
        KnownApp("com.discord", "Discord"),
        KnownApp("com.android.chrome", "Chrome"),
        KnownApp("org.mozilla.firefox", "Firefox")
    ).sortedByDescending { it.prefix.length }

    private fun match(packageName: String): KnownApp? =
        // Exact entries first (standalones must not prefix-swallow sub-packages),
        // then the prefix rows (families, catch-alls), longest stem first.
        knownApps.firstOrNull { !it.byPrefix && it.prefix == packageName }
            ?: knownApps.firstOrNull { it.byPrefix && packageName.startsWith(it.prefix) }

    /**
     * Logical name for a known package, or null when unknown (callers should
     * fall back to the PackageManager label, then to the raw package name).
     */
    fun knownAppName(packageName: String?): String? =
        packageName?.takeIf { it.isNotBlank() }?.let { match(it)?.displayName }

    /**
     * Package the Share Back action should target for a transcription's
     * source app: the canonical client of its family, or the source package
     * itself when it belongs to no family (the generic fallback).
     */
    fun shareBackTarget(packageName: String?): String? =
        packageName?.let { match(it)?.shareTarget ?: it }

    /**
     * Get the display name for an app package.
     *
     * @param context Application context
     * @param packageName Package name (e.g., "com.whatsapp")
     * @return Display name (e.g., "WhatsApp") or package name if not found
     */
    fun getAppName(context: Context, packageName: String?): String {
        if (packageName == null) return ""

        return knownAppName(packageName) ?: try {
            val pm = context.packageManager
            val appInfo = pm.getApplicationInfo(packageName, 0)
            pm.getApplicationLabel(appInfo).toString().takeIf { it.isNotBlank() } ?: packageName
        } catch (e: Exception) {
            packageName
        }
    }

    /**
     * Get the "Send to [App]" text for Share Back button.
     *
     * @param context Application context
     * @param packageName Package name
     * @return Localized string like "Send to WhatsApp"
     */
    fun getSendToText(context: Context, packageName: String?): String {
        if (packageName == null) return ""

        val appName = getAppName(context, packageName)
        return context.getString(R.string.send_to_app, appName)
    }
}
