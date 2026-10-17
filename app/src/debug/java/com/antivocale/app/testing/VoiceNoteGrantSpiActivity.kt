package com.antivocale.app.testing

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import com.antivocale.app.data.VoiceNoteTreeGrant
import com.antivocale.app.receiver.TestSpiReceiver
import com.antivocale.app.util.CrashReporter
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.launch

/**
 * TASK-826: the SPI's grant arm, debug builds only. Started by
 * TestSpiReceiver's op=voice_note_grant_open, it fires the SAME
 * OpenDocumentTree picker the Settings grant card uses (initial URI
 * included) and feeds the result through the shared acceptance policy
 * ([VoiceNoteTreeGrant.acceptGrant]: take, validate, release-old, save),
 * so the USE-THIS-FOLDER confirmation is the single manual gesture in an
 * otherwise scripted voice-note trial. No UI of its own: transparent,
 * finishes as soon as the outcome is known, and answers on the TestSpi
 * logcat channel like every other SPI op.
 */
@AndroidEntryPoint
class VoiceNoteGrantSpiActivity : ComponentActivity() {

    @Inject lateinit var treeGrant: VoiceNoteTreeGrant

    // lifecycleScope (Main-immediate; the activity coroutine idiom here,
    // unlike the receiver's hand-built receive window) plus acceptGrant's
    // own withContext(IO): the binder work stays off main, finish() and
    // the log land on main, and destroy cancels a mid-acceptance run.
    private val picker = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri == null) {
            // The user backed out or DocumentsUI failed: nothing taken,
            // nothing saved; the trial script sees the unchanged state.
            TestSpiReceiver.logSpi(OP, "canceled")
            finish()
            return@registerForActivityResult
        }
        lifecycleScope.launch {
            // The outcome line plus finish() must survive an acceptance
            // throw too, or the transparent activity would linger with no
            // answer on the channel (a throw surface of nearly zero, but
            // the script contract is the point of this class).
            // Cancellation (destroy mid-acceptance) rethrows, the house
            // discipline: it is lifecycle control flow, not a failure to
            // report or log as error.
            val outcome = try {
                treeGrant.acceptGrant(uri)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                CrashReporter.report(e, "VoiceNoteGrantSpiActivity")
                null
            }
            TestSpiReceiver.logSpi(
                OP,
                when (outcome) {
                    is VoiceNoteTreeGrant.GrantOutcome.Saved -> "saved ${outcome.uriString}"
                    VoiceNoteTreeGrant.GrantOutcome.TakeFailed -> "take_failed"
                    VoiceNoteTreeGrant.GrantOutcome.WrongFolder -> "wrong_folder"
                    // Unreachable outside a caught throw: the script sees
                    // the error line and checks voice_note_state.
                    null -> "error"
                })
            // The save fires the coordinator's own collector (the URI is
            // part of its combine), so no poke is needed here.
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Recreation (rotation, process death while the picker is up) must
        // NOT fire a second DocumentsUI on top of the first: the activity
        // result is re-delivered to the new instance. First launch only.
        if (savedInstanceState == null) {
            // The receiver already acked launched:true; a picker that
            // cannot start at all answers on the channel instead of
            // crashing onCreate (the Settings site's guard shape).
            try {
                picker.launch(VoiceNoteTreeGrant.pickerInitialUri())
            } catch (e: android.content.ActivityNotFoundException) {
                TestSpiReceiver.logSpi(OP, "error")
                finish()
            }
        }
    }

    private companion object {
        val OP = TestSpiReceiver.OP_VOICE_NOTE_GRANT_OPEN
    }
}
