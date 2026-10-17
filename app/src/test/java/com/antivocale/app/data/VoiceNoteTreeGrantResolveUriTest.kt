package com.antivocale.app.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * TASK-832: the caller-held-uri contract. The null arm (no live URI) must
 * return null WITHOUT touching the resolver, and the preference store is
 * no longer read at all (the coordinator's live value is the only source).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class VoiceNoteTreeGrantResolveUriTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `a null live uri answers null without resolving`() = runBlocking {
        // The house fixture, not a mock-of-the-fake: the null arm returns
        // before touching preferences at all (review 3a).
        val grant = VoiceNoteTreeGrant(context, FakePreferencesManager())
        assertNull(grant.resolve(treeUri = null, whatsappPath = "/w/PTT-1.opus"))
    }

    @Test
    fun `resolve uses the caller's uri, never the preference store`() = runBlocking {
        // Non-relaxed: any unexpected member access throws, which is the
        // strictness the assertion wants (review 3b).
        val prefs = mockk<FakePreferencesManager>()
        val grant = VoiceNoteTreeGrant(context, prefs)
        grant.resolve(treeUri = "content://tree/live", whatsappPath = "/gone/PTT-x.opus")
        // The property GET is the prerequisite of any store read, so its
        // absence covers the whole removed firstOrNull path.
        verify(exactly = 0) { prefs.voiceNoteTreeUri }
    }
}
