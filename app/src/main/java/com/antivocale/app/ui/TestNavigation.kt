package com.antivocale.app.ui

import kotlinx.coroutines.flow.MutableStateFlow

/**
 * The TEST_SPI navigation bridge: DEBUG-WRITE, shared-read. The TEST_SPI
 * receiver (debug source set) starts [com.antivocale.app.MainActivity] with
 * the [EXTRA_TEST_NAV] extra; MainActivity writes the raw destination token
 * into [pending]; [com.antivocale.app.ui.MainScreen] consumes it and routes
 * through [AppNavigation].
 *
 * The navigation vocabulary itself (Destination, NavRequest, key tables,
 * parse) lives in [AppNavigation]: production code depends on that, never on
 * this bridge (TASK-558: the vocabulary used to sit next to the debug token
 * and read as test-only tooling while release navigation depended on it).
 *
 * Release posture: the WRITE side (the intent-extra read in MainActivity) is
 * gated on BuildConfig.DEBUG and the receiver does not exist in release, so
 * nothing ever sets [pending] there. No manifest component, no receiver, no
 * attack surface.
 *
 * Debug-build caveat (review 2026-09-12): when a nav cold-starts the task,
 * Android stores the intent as the Recents base intent and a later
 * relaunch-from-Recents after a process death redelivers the extra (same
 * exposure as the pre-existing EXTRA_NAVIGATE_TO_MODEL_TAB, but nav can
 * reopen dialogs). Debug builds only; acceptable for a test tool.
 */
object TestNavigation {
    const val EXTRA_TEST_NAV = "com.antivocale.app.TEST_NAV"

    /** One-shot destination token; consumed (nulled) by MainScreen. */
    val pending = MutableStateFlow<String?>(null)
}
