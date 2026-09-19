package com.antivocale.app.audio

import android.content.Context
import com.antivocale.app.R
import com.antivocale.app.audio.AudioPreprocessor.PreprocessingError

/**
 * Localized user-facing text for PreprocessingError (TASK-396 OOM precedent:
 * the notification and the broadcast reply must carry localized advice, not
 * the sealed class's English).
 */
object PreprocessingErrorMessages {
    fun localize(context: Context, error: PreprocessingError): String = when (error) {
        is PreprocessingError.DurationTooLong -> when (error.path) {
            AudioDurationPolicy.DecodePath.STREAMING ->
                context.getString(R.string.error_audio_too_long_streaming)
            AudioDurationPolicy.DecodePath.WHOLE_FILE_PCM ->
                context.getString(R.string.error_audio_too_long_whole_file, error.ceilingSeconds / 60)
        }
        is PreprocessingError.FileTooLarge -> context.getString(R.string.error_file_too_large)
        is PreprocessingError.FileNotFound -> context.getString(R.string.error_file_not_found)
        is PreprocessingError.InvalidFormat -> context.getString(R.string.error_invalid_format)
        is PreprocessingError.NoAudioTrack -> context.getString(R.string.error_no_audio_track)
        is PreprocessingError.NoDecoder -> context.getString(R.string.error_no_decoder, noDecoderDetail(error))
        is PreprocessingError.DurationUnknown -> context.getString(R.string.error_duration_unknown)
        is PreprocessingError.ConversionFailed -> context.getString(R.string.error_conversion_failed, error.reason)
        is PreprocessingError.ChunkFailed -> context.getString(R.string.error_chunk_failed, error.chunkIndex, error.reason)
    }

    /**
     * GH #18: the NoDecoder detail names the container when the source path
     * carried a clean extension (".ts"), then the track MIME, the reason
     * ("audio/vnd.dts"). Both tokens are technical identifiers composed here,
     * in code, so word order stays locale-independent; [PreprocessingError.NoDecoder.format]
     * is constructor-sanitized ("" when absent), in which case only the MIME
     * is named.
     */
    internal fun noDecoderDetail(error: PreprocessingError.NoDecoder): String =
        if (error.format.isEmpty()) error.mime else ".${error.format} (${error.mime})"
}
