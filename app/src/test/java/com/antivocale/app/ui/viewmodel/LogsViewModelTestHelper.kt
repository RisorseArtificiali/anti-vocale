package com.antivocale.app.ui.viewmodel

import com.antivocale.app.data.PreferencesManager
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf

fun stubPreferencesManager(): PreferencesManager = mockk(relaxed = true) {
    every { swipeActionMode } returns flowOf("delete")
    every { partialTranscriptionText } returns flowOf(null)
    every { groupLogsByConversation } returns flowOf(true)
    every { vadEnabled } returns flowOf(false)
    every { vadAdvisoryDismissed } returns flowOf(false)
}
