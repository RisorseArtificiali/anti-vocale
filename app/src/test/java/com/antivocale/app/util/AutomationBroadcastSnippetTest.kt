package com.antivocale.app.util

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * TASK-275: the copyable automation command behind the Settings > Advanced
 * "Automation guide" card. The release-package form must stay byte-identical
 * to the ADB snippet in docs/TASKER_GUIDE.md (the card promises "the exact
 * PROCESS_REQUEST form from the guide"), so the guide itself is the oracle:
 * editing the doc without the builder (or vice versa) fails here. The
 * guide-reading probes mirror StringResourceParityTest's path resolution.
 */
class AutomationBroadcastSnippetTest {

    private fun guideFile(): File {
        val moduleRelative = File("docs/TASKER_GUIDE.md")
        val rootRelative = File("../docs/TASKER_GUIDE.md")
        return when {
            moduleRelative.exists() -> moduleRelative
            rootRelative.exists() -> rootRelative
            else -> throw IllegalStateException(
                "Cannot locate docs/TASKER_GUIDE.md from ${File(".").absolutePath}")
        }
    }

    @Test
    fun `release-package command matches the guide snippet verbatim`() {
        val command = AutomationBroadcastSnippet.adbTextRequest("com.antivocale.app")
        assertTrue(
            "The copied command drifted from docs/TASKER_GUIDE.md; update the builder or the " +
                "guide so the two stay byte-identical.\nCopied command:\n$command",
            guideFile().readText().contains(command))
    }

    @Test
    fun `debug application id fills the component package only`() {
        // The manifest action is a literal (com.antivocale.app.PROCESS_REQUEST)
        // and never changes with the debug applicationIdSuffix; only the
        // explicit component's package does (implicit shell broadcasts are
        // silently dropped on some devices, hence the -n form).
        val command = AutomationBroadcastSnippet.adbTextRequest("com.antivocale.app.debug")
        assertTrue(
            "  -n com.antivocale.app.debug/com.antivocale.app.receiver.TaskerRequestReceiver \\" in
                command)
        assertTrue("-a com.antivocale.app.PROCESS_REQUEST" in command)
        assertFalse("com.antivocale.app.debug/com.antivocale.app.PROCESS_REQUEST" in command)
    }

    @Test
    fun `the command is a single pasteable shell invocation`() {
        // Six lines, every non-final one closed with the shell continuation.
        val lines = AutomationBroadcastSnippet.adbTextRequest("com.antivocale.app").lines()
        assertEquals(6, lines.size)
        assertTrue(lines.dropLast(1).all { it.endsWith("\\") })
        assertFalse(lines.last().endsWith("\\"))
        assertEquals("adb shell am broadcast \\", lines.first())
    }
}
