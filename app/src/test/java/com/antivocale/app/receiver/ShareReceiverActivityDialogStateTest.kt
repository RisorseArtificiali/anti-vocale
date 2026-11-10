package com.antivocale.app.receiver

import android.content.Intent
import com.antivocale.app.MainActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config

/**
 * The share flow's dialog state holder (the 2026-10-31 look-and-feel fix).
 * Scope, honestly: this file drives only the arms that are synchronous and
 * side-effect assertable without running the copy flow (the entries a batch
 * can pend at once, identity-true removal, the open and cancel arms'
 * endFlow). The reprocess and chooser-pick arms stay UNPINNED here: both
 * launch the copy flow, which the other ShareReceiverActivity suites cover
 * only at the routing level, not through the dialog arms. No Compose
 * composition: create() only, the surface never attaches.
 */
@RunWith(RobolectricTestRunner::class)
class ShareReceiverActivityDialogStateTest {

    /** The shortcut flow's stay-alive entry: no stream + marker opens the
     *  picker instead of dead-ending, so the activity is not finishing. */
    private fun stayAliveActivity(): ShareReceiverActivity =
        Robolectric.buildActivity(
            ShareReceiverActivity::class.java,
            Intent(Intent.ACTION_SEND).apply {
                component = android.content.ComponentName("com.antivocale.app", "com.antivocale.app.ShareWhisper")
                type = "audio/*"
                putExtra(ShareReceiverActivity.EXTRA_FROM_SHORTCUT, true)
            },
        )
            .create()
            .get()

    private fun duplicate(taskId: String) = ShareReceiverActivity.PendingDialog.Duplicate(
        modelName = "Whisper",
        existingTaskId = "existing_$taskId",
        taskId = taskId,
        localPath = "/data/voice.m4a",
        backendOverride = null,
        contentHash = "hash",
    )

    private fun chooser(taskId: String) = ShareReceiverActivity.PendingDialog.ExternalChooser(
        taskId = taskId,
        localPath = "/data/voice.m4a",
        records = emptyList(),
        contentHash = null,
    )

    @Test
    fun `batch pend several prompts and the open arm resolves its own entry`() {
        val activity = stayAliveActivity()
        assertFalse(activity.isFinishing)
        val first = duplicate("share_1")
        val second = duplicate("share_2")
        activity.pendingDialogs.add(first)
        activity.pendingDialogs.add(second)
        assertEquals(2, activity.pendingDialogs.size)

        activity.onDuplicateOpen(first)

        assertEquals(listOf(second), activity.pendingDialogs.toList())
        val started = Shadows.shadowOf(activity).nextStartedActivity
        assertEquals(MainActivity::class.java.name, started.component?.className)
        assertEquals(
            "existing_share_1",
            started.getStringExtra(MainActivity.EXTRA_HIGHLIGHT_TASK_ID),
        )
        // The open arm runs endFlow like its dialog callback did.
        assertTrue(activity.isFinishing)
    }

    @Test
    fun `chooser cancel drops its entry and accounts the slot`() {
        val activity = stayAliveActivity()
        val entry = chooser("share_1")
        activity.pendingDialogs.add(entry)

        activity.onExternalChooserCancel(entry)

        assertTrue(activity.pendingDialogs.isEmpty())
        // The cancel arm runs endFlow like the platform dialog's cancel listener did.
        assertTrue(activity.isFinishing)
    }
}
