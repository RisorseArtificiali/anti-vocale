package com.antivocale.app.ui.tabs

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.antivocale.app.R

/**
 * What swiping a History entry does (TASK-470, the ThemeType pattern): the
 * enum is the single authority for the Settings dropdown's options, the test
 * SPI's validation, and the LogsTab dispatch. Before it, the three sites
 * shared bare strings and an else-branch silently executed any unrecognized
 * value as immediate-delete.
 *
 * Persistence keeps the enum name (no migration); [from] resolves stored
 * strings, falling back to [REVEAL] for unknown values.
 */
enum class SwipeActionMode {
    REVEAL,
    IMMEDIATE_DELETE;

    companion object {
        /** The persisted vocabulary: also the SPI's accepted set. */
        val NAMES: List<String> = entries.map { it.name }

        /**
         * Resolves a stored preference. Unknown strings (hand-edited
         * DataStore, a removed future mode) resolve to [REVEAL]: the
         * conservative action, matching the pre-enum default.
         */
        fun from(value: String): SwipeActionMode =
            entries.firstOrNull { it.name == value } ?: REVEAL
    }
}

/** Localized dropdown label for a persisted mode string. */
@Composable
fun String.swipeActionLabel(): String = when (SwipeActionMode.from(this)) {
    SwipeActionMode.REVEAL -> stringResource(R.string.swipe_action_reveal)
    SwipeActionMode.IMMEDIATE_DELETE -> stringResource(R.string.swipe_action_immediate_delete)
}
