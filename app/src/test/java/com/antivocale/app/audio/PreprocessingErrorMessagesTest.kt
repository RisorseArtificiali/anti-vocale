package com.antivocale.app.audio

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.antivocale.app.R
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * GH #18: NoDecoder reaches the user through the notification and the Tasker
 * reply via PreprocessingErrorMessages; the message must name the container
 * (when the path carried a clean extension) and the track MIME (the reason).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class PreprocessingErrorMessagesTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `NoDecoder names the format and the mime`() {
        val message = PreprocessingErrorMessages.localize(
            context, AudioPreprocessor.PreprocessingError.NoDecoder("ts", "audio/vnd.dts"))
        assertEquals(
            context.getString(R.string.error_no_decoder, ".ts (audio/vnd.dts)"),
            message)
    }

    @Test
    fun `NoDecoder without a clean extension names the mime alone`() {
        val message = PreprocessingErrorMessages.localize(
            context, AudioPreprocessor.PreprocessingError.NoDecoder("", "audio/mpeg"))
        assertEquals(
            context.getString(R.string.error_no_decoder, "audio/mpeg"),
            message)
    }
}
