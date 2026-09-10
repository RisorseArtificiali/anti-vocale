package com.antivocale.app.ui.tabs

import com.antivocale.app.data.PreferencesManager
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * TASK-470: the enum is the single authority for the swipe-mode vocabulary.
 * These pins keep the persisted values, the SPI's accepted set, and the
 * resolution fallback from drifting apart (a third mode added to the enum
 * must consciously update every derived site, not silently mis-execute).
 */
class SwipeActionModeTest {

    @Test
    fun `the persisted vocabulary is exactly the enum names`() {
        assertEquals(SwipeActionMode.NAMES, PreferencesManager.SWIPE_ACTION_MODES)
        assertEquals(listOf("REVEAL", "IMMEDIATE_DELETE"), SwipeActionMode.NAMES)
    }

    @Test
    fun `the default preference resolves to REVEAL`() {
        assertEquals(SwipeActionMode.REVEAL, SwipeActionMode.from(PreferencesManager.DEFAULT_SWIPE_ACTION_MODE))
    }

    @Test
    fun `unknown stored values fall back to the conservative action`() {
        // The pre-enum bug class in reverse: an unrecognized value must NOT
        // resolve to immediate-delete (destructive) but to reveal.
        assertEquals(SwipeActionMode.REVEAL, SwipeActionMode.from("COPY"))
        assertEquals(SwipeActionMode.REVEAL, SwipeActionMode.from(""))
    }
}
