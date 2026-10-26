package com.antivocale.app.data

import androidx.datastore.core.CorruptionException
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import com.antivocale.app.util.CrashReporter

/**
 * TASK-908 (Crashlytics 2026-10-26, 1.14.1): a corrupt preferences proto
 * crash-looped the app at create because a DataStore without a corruption
 * handler propagates the CorruptionException out of Application.onCreate.
 * The user-visible contract: a corrupt file must cost at most that store's
 * contents (reset to defaults), never the app. One owner for the policy,
 * attached by every preferences DataStore delegate in data/ (the
 * delegate-per-file rule pins where the DELEGATE lives, not the handler).
 */
internal fun reportingReplaceCorruptionHandler(
    store: String,
): ReplaceFileCorruptionHandler<Preferences> =
    ReplaceFileCorruptionHandler { e: CorruptionException ->
        CrashReporter.report(e, "$store datastore corrupted; reset to defaults")
        emptyPreferences()
    }
