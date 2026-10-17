package com.antivocale.app.data

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.antivocale.app.util.TreeUris
import io.mockk.every
import io.mockk.just
import io.mockk.mockkObject
import io.mockk.runs
import io.mockk.unmockkObject
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * TASK-826: the grant-acceptance policy, extracted from the Settings
 * picker callback so the debug SPI grant activity runs the same sequence.
 * The framework-touching helpers (persistable take/release, folder
 * validation) are object-mocked; the POLICY under test is the real
 * acceptGrant: the take-validate-release-old-save order, the wrong-folder
 * slot giveback, and the take-fail refusal. The device trial owns the
 * real DocumentsUI pass.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class VoiceNoteTreeGrantTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val fake = FakePreferencesManager()

    @After
    fun tearDown() {
        unmockkObject(TreeUris)
        unmockkObject(VoiceNoteTreeGrant)
    }

    private fun armStubs(
        take: Boolean = true,
        looksLikeTree: Boolean = true,
    ): VoiceNoteTreeGrant {
        mockkObject(TreeUris)
        mockkObject(VoiceNoteTreeGrant)
        every { TreeUris.takePersistableReadGrant(context, any()) } returns take
        every { TreeUris.releasePersistableReadGrant(context, any()) } just runs
        every { VoiceNoteTreeGrant.looksLikeVoiceNotesTree(context, any()) } returns looksLikeTree
        return VoiceNoteTreeGrant(context, fake)
    }

    @Test
    fun `a valid pick takes the grant releases the old slot and saves`() = runBlocking {
        fake._voiceNoteTreeUri.value = "content://tree/old"
        val grant = armStubs()

        val outcome = grant.acceptGrant(Uri.parse("content://tree/new"))

        val saved = outcome as? VoiceNoteTreeGrant.GrantOutcome.Saved
        assertTrue("expected Saved, got $outcome", saved != null)
        assertEquals("content://tree/new", saved?.uriString)
        assertEquals("content://tree/new", fake._voiceNoteTreeUri.value)
        // The replacement's old slot is given back BEFORE the overwrite
        // (the platform caps persisted grants; review F9's leak class).
        verify(exactly = 1) { TreeUris.releasePersistableReadGrant(context, Uri.parse("content://tree/old")) }
    }

    @Test
    fun `a wrong folder is refused and the taken slot given back`() = runBlocking {
        fake._voiceNoteTreeUri.value = "content://tree/old"
        val grant = armStubs(looksLikeTree = false)

        val outcome = grant.acceptGrant(Uri.parse("content://tree/wrong"))

        assertEquals(VoiceNoteTreeGrant.GrantOutcome.WrongFolder, outcome)
        // Nothing saved (the old preference survives) and the freshly
        // taken grant is released, not leaked onto the cap.
        assertEquals("content://tree/old", fake._voiceNoteTreeUri.value)
        verify(exactly = 1) { TreeUris.releasePersistableReadGrant(context, Uri.parse("content://tree/wrong")) }
        verify(exactly = 0) { TreeUris.releasePersistableReadGrant(context, Uri.parse("content://tree/old")) }
    }

    @Test
    fun `a non-persistable take saves and releases nothing`() = runBlocking {
        fake._voiceNoteTreeUri.value = "content://tree/old"
        val grant = armStubs(take = false)

        val outcome = grant.acceptGrant(Uri.parse("content://tree/oem"))

        assertEquals(VoiceNoteTreeGrant.GrantOutcome.TakeFailed, outcome)
        assertEquals("content://tree/old", fake._voiceNoteTreeUri.value)
        verify(exactly = 0) { TreeUris.releasePersistableReadGrant(context, any()) }
    }
}
