package com.antivocale.app.ui

import android.content.Intent
import android.net.Uri

/**
 * TASK-995: the antivocale://import?url=... deep link (Vibe's vibe://
 * precedent; maintainer-approved 2026-11-12). One tap from an HF model page
 * or a catalog doc opens Anti-Vocale on the Models tab with the import
 * dialog pre-filled; the user confirms there (the dialog is the consent
 * surface: an untrusted link never starts a download by itself).
 *
 * SECURITY: the link is untrusted input. This parser validates the scheme,
 * the host, and the carried URL's own scheme (http/https only), caps its
 * length, refuses relaunch-from-Recents replays (after a process death the
 * system redelivers the original base intent; mutating the live Intent
 * cannot clear the system's copy, so the history flag is the gate), and
 * nothing else rides the link. The import then passes through the same
 * sha-pinned pipeline as every manual import, so a hostile link cannot
 * bypass integrity, only point at a model the importer will verify.
 */
object DeepLinkImport {

    const val SCHEME = "antivocale"
    const val HOST = "import"
    private const val MAX_URL_LENGTH = 2048

    /** The process-level consume-once handoff (MainActivity -> MainScreen),
     *  mirroring [TestNavigation.pending]. */
    val pending = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)

    /**
     * Validates and consumes an antivocale://import?url=<http(s) url> intent
     * data. Anything malformed, or a Recents relaunch, is dropped on the
     * floor (a bad link must never crash or misroute the app). Returns the
     * carried URL.
     */
    fun capture(intent: Intent): String? {
        // A relaunch from Recents after a process death redelivers the task's
        // stored base intent, data included: without this gate a killed
        // process would silently re-run the import on the next plain reopen
        // (the same redelivery TestNavigation's KDoc documents).
        if (intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0) return null
        val data: Uri = intent.data ?: return null
        if (data.scheme?.equals(SCHEME, ignoreCase = true) != true) return null
        if (data.host?.equals(HOST, ignoreCase = true) != true) return null
        val url = data.getQueryParameter("url")?.trim().orEmpty()
        if (url.length > MAX_URL_LENGTH) return null
        // Uri.parse never throws for a non-null string (failures surface on
        // the accessors); the scheme/host reads below are the validation.
        val carried = Uri.parse(url)
        val scheme = carried.scheme?.lowercase()
        if (scheme != "http" && scheme != "https") return null
        if (carried.host.isNullOrBlank()) return null
        intent.data = null
        pending.value = url
        return url
    }
}
