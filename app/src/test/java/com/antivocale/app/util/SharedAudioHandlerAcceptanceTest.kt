package com.antivocale.app.util

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.antivocale.app.R
import java.io.ByteArrayInputStream
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * GH #18: the copy path accepts ANY shared file; MediaExtractor/MediaCodec
 * validate the format at decode time (typed NoDecoder/NoAudioTrack errors).
 * These tests pin that acceptance (the containers the retired whitelist
 * used to reject, and content with no identifier at all) plus the sealed
 * CopyResult shape and its message mapping.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class SharedAudioHandlerAcceptanceTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    /**
     * Robolectric's registerInputStream hands out the SAME instance on every
     * openInputStream call, but the handler opens the stream twice for
     * unidentifiable content (magic-byte sniff, then the copy), and a real
     * provider serves a fresh stream each time. Resetting on close (the
     * sniff's .use) replays the content for the copy; the copy's own close
     * resets again harmlessly.
     */
    private class ReplayOnCloseStream(content: ByteArray) : ByteArrayInputStream(content) {
        override fun close() {
            pos = 0
        }
    }

    private fun bytes(vararg ints: Int): ByteArray = ByteArray(ints.size) { ints[it].toByte() }

    private fun share(uri: Uri, mimeType: String?, vararg ints: Int, sniffFirst: Boolean = false): SharedAudioHandler.CopyResult {
        val stream = if (sniffFirst) ReplayOnCloseStream(bytes(*ints)) else ByteArrayInputStream(bytes(*ints))
        shadowOf(context.contentResolver).registerInputStream(uri, stream)
        return SharedAudioHandler.copyToAppStorage(context, uri, mimeType)
    }

    @Test
    fun `accepts a container the retired whitelist rejected (mka)`() {
        val result = share(
            Uri.parse("content://media.provider/recordings/meeting.mka"),
            "application/octet-stream",
            0x1A, 0x45, 0xDF, 0xA3, 0x42, 0x82, 0x88, 0x6D, 0x61, 0x74, 0x72, 0x6F, 0x73, 0x6B, 0x61, 0x00)

        assertTrue("expected Success, got $result", result is SharedAudioHandler.CopyResult.Success)
        assertEquals("mka", (result as SharedAudioHandler.CopyResult.Success).path.substringAfterLast('.'))
        assertTrue(File(result.path).length() > 0)
    }

    @Test
    fun `accepts an mpeg transport stream (ts)`() {
        val result = share(
            Uri.parse("content://media.provider/recordings/capture.ts"),
            "video/mp2t",
            0x47, 0x40, 0x00, 0x10, 0x00, 0x00, 0x00, 0x01)

        assertTrue("expected Success, got $result", result is SharedAudioHandler.CopyResult.Success)
        assertEquals("ts", (result as SharedAudioHandler.CopyResult.Success).path.substringAfterLast('.'))
    }

    @Test
    fun `accepts unidentifiable content under a generic name (bin)`() {
        // No MIME signal, no path extension, no sniffable magic: the file is
        // still copied (the decoder validates later) and only the local file
        // name falls back to the generic-binary suffix.
        val result = share(
            Uri.parse("content://recorder.provider/items/42"),
            "application/octet-stream",
            0x00, 0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, sniffFirst = true)

        assertTrue("expected Success, got $result", result is SharedAudioHandler.CopyResult.Success)
        assertEquals("bin", (result as SharedAudioHandler.CopyResult.Success).path.substringAfterLast('.'))
    }

    @Test
    fun `CopyResult carries exactly the copy-level outcomes (no format rejection)`() {
        // GH #18 pin: format support is decided by the decoder, not the copy,
        // so no UnsupportedFormat/UnknownFormat variant may come back.
        val variants = SharedAudioHandler.CopyResult::class.sealedSubclasses
            .mapNotNull { it.simpleName }
            .toSet()
        assertEquals(setOf("Success", "Unreadable", "OutOfSpace"), variants)
    }

    @Test
    fun `Unreadable and OutOfSpace map to their localized messages`() {
        assertEquals(
            context.getString(R.string.failed_to_process_audio),
            SharedAudioHandler.CopyResult.Unreadable.userMessage(context))
        assertEquals(
            context.getString(R.string.error_storage_full, 42),
            SharedAudioHandler.CopyResult.OutOfSpace(42).userMessage(context))
    }

    @Test(expected = IllegalStateException::class)
    fun `Success carries no user message`() {
        SharedAudioHandler.CopyResult.Success("/tmp/x.wav").userMessage(context)
    }
}
