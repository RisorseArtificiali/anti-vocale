package com.antivocale.app.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.antivocale.app.R
import com.antivocale.app.transcription.TranscriptionLanguagePolicy
import com.antivocale.app.util.LanguageNames
import com.antivocale.app.util.LocaleManager

/**
 * Transcription-language dropdown sentinel (TASK-457): "auto" is the
 * model-side detection choice. A stored "system" (the pre-457 untouched
 * default) resolves identically now that the app-locale pinning is gone, so
 * it renders with the same label instead of a second sentinel entry.
 */
val transcriptionSentinelLabels = mapOf(
    TranscriptionLanguagePolicy.PREF_AUTO to R.string.transcription_language_auto,
)

/**
 * TASK-546 AC3: one label resolver for every surface that renders
 * transcription-language options (the Settings card and the History chip's
 * re-run picker), hoisted from SettingsTab so the two cannot drift: sentinels
 * resolve through [transcriptionSentinelLabels] (the phone sentinel formats
 * with the resolved language's NAME, TASK-547 AC#2: a raw ISO code reads as
 * noise when every sibling row shows a native name), offered codes through
 * the picker's native display names.
 */
@Composable
fun languageOptionLabel(
    code: String,
    sentinelLabels: Map<String, Int>,
    options: Map<String, LanguageOption>,
): String {
    if (code == TranscriptionLanguagePolicy.PREF_PHONE) {
        val phone = LocaleManager.phoneLanguage(LocalContext.current)
        return stringResource(
            R.string.language_phone_option,
            phone?.let { LanguageNames.nativeLanguageName(it) } ?: "",
        )
    }
    sentinelLabels[code]?.let { return stringResource(it) }
    return options[code]?.displayName
        ?: LanguageNames.nativeLanguageName(code)
}
