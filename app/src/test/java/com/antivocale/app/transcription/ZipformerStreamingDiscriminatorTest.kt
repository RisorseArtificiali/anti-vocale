package com.antivocale.app.transcription

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * TASK-720: the streaming-vs-offline discriminator for plain zipformers,
 * verified against REAL exports both ways (see the task notes): streaming
 * encoders carry decode_chunk_len and comment "streaming zipformer2";
 * offline ones carry neither key and comment "non-streaming zipformer2".
 * The fixtures below are minimal protobuf metadata_props blobs (tag 0x0a
 * key, tag 0x12 value, length-prefixed) in the exact shape
 * [SherpaBackend.onnxMetadataValueBytes] parses. The file-level half pins
 * the TASK-720 GATE ([TransducerSupport.streamingFlagMismatch]): routed by
 * export shape, the zipformer probe decides model_type-absent exports, a
 * NeMo export (model_type "EncDec*", the Fritter Streaming / bundled
 * Nemotron shape) discriminates on chunk_shift instead.
 */
class ZipformerStreamingDiscriminatorTest {

    @get:Rule
    val tmp = TemporaryFolder()

    /** One metadata_props entry: key + value as length-delimited protobuf. */
    private fun prop(key: String, value: String): ByteArray {
        val k = key.toByteArray(Charsets.UTF_8)
        val v = value.toByteArray(Charsets.UTF_8)
        return byteArrayOf(0x0a, k.size.toByte()) + k + byteArrayOf(0x12, v.size.toByte()) + v
    }

    private fun blob(vararg props: ByteArray): ByteArray =
        props.fold(ByteArray(0)) { acc, p -> acc + p }

    @Test
    fun `streaming graph is detected by decode_chunk_len alone`() {
        val data = blob(prop("model_type", "zipformer2"), prop("decode_chunk_len", "32"))
        assertEquals(true, SherpaBackend.zipformerGraphIsStreamingBytes(data))
    }

    @Test
    fun `streaming graph is detected by the comment`() {
        val data = blob(prop("comment", "streaming zipformer2"), prop("T", "45"))
        assertEquals(true, SherpaBackend.zipformerGraphIsStreamingBytes(data))
    }

    @Test
    fun `offline graph is detected by the comment, never by substring`() {
        // The trap: "non-streaming".contains("streaming") is true; the
        // classification must be startsWith, and decoy keys do not vote.
        val data = blob(prop("comment", "non-streaming zipformer2"), prop("model_author", "k2-fsa"))
        assertEquals(false, SherpaBackend.zipformerGraphIsStreamingBytes(data))
    }

    @Test
    fun `no comment and no chunk key is undeterminable (fail open)`() {
        val data = blob(prop("model_author", "k2-fsa"), prop("version", "1"))
        assertNull(SherpaBackend.zipformerGraphIsStreamingBytes(data))
    }

    @Test
    fun `empty buffer is undeterminable`() {
        assertNull(SherpaBackend.zipformerGraphIsStreamingBytes(ByteArray(0)))
    }

    // ==================== the gate (TransducerSupport) ====================
    // Routed by export shape: a NeMo export (model_type "EncDec*")
    // discriminates on chunk_shift (the TASK-968 refutation's live gate:
    // bundled Nemotron 112, Fritter streaming 56, offline Fritter none);
    // a model_type-absent export falls to the zipformer probe.

    /** Encoder file whose metadata tail carries [props] (the real export shape: plain bytes then props). */
    private fun encoderFile(vararg props: ByteArray): java.io.File =
        tmp.newFile().apply { writeBytes(ByteArray(2048) { if (it == 0) 8 else 1 } + blob(*props)) }

    /** The real Fritter Streaming artifact shape (model_type + chunk keys; the comment reads offline to a naive zipformer probe). */
    private fun nemoStreamingProps() = arrayOf(
        prop("model_type", "EncDecHybridRNNTCTCBPEModel"),
        prop("chunk_size_ms", "560"),
        prop("chunk_shift", "56"),
        prop("comment", "Only the transducer branch is exported"))

    @Test
    fun `streaming NeMo export under a streaming flag passes (TASK-968)`() {
        val encoder = encoderFile(*nemoStreamingProps())
        assertNull(TransducerSupport.streamingFlagMismatch(encoder, declaredStreaming = true))
    }

    @Test
    fun `streaming NeMo export under a non-streaming flag fires`() {
        val encoder = encoderFile(*nemoStreamingProps())
        assertNotNull(TransducerSupport.streamingFlagMismatch(encoder, declaredStreaming = false))
    }

    @Test
    fun `offline NeMo export under a streaming flag fires (finding 1)`() {
        // chunk_shift is the ONLY NeMo streaming marker: an EncDec encoder
        // without it is an offline export (the offline Fritter encoder has
        // none of the chunk keys), and streaming=true must die here, not as
        // the native abort at first decode.
        val encoder = encoderFile(
            prop("model_type", "EncDecHybridRNNTCTCBPEModel"),
            prop("comment", "Only the transducer branch is exported"))
        assertNotNull(TransducerSupport.streamingFlagMismatch(encoder, declaredStreaming = true))
    }

    @Test
    fun `offline NeMo export under a non-streaming flag passes`() {
        val encoder = encoderFile(
            prop("model_type", "EncDecRNNTBPEModel"),
            prop("vocab_size", "1024"))
        assertNull(TransducerSupport.streamingFlagMismatch(encoder, declaredStreaming = false))
    }

    @Test
    fun `gate still fires for a streaming zipformer under a non-streaming flag`() {
        // model_type present but NOT EncDec: still a zipformer-shaped export,
        // the probe applies and the mismatch must die here, not at decode.
        val encoder = encoderFile(
            prop("model_type", "zipformer2"),
            prop("decode_chunk_len", "32"))
        assertNotNull(TransducerSupport.streamingFlagMismatch(encoder, declaredStreaming = false))
    }

    @Test
    fun `gate still fires for an offline zipformer under a streaming flag`() {
        val encoder = encoderFile(prop("comment", "non-streaming zipformer2"))
        assertNotNull(TransducerSupport.streamingFlagMismatch(encoder, declaredStreaming = true))
    }

    @Test
    fun `model_type-absent export with a streaming comment falls to the zipformer probe and passes`() {
        // model_type absent (a k2 zipformer, or metadata beyond the scan
        // window): the NeMo path must NOT engage; the zipformer probe governs
        // and a streaming comment under a streaming flag is consistent.
        val encoder = encoderFile(prop("comment", "streaming zipformer2"))
        assertNull(TransducerSupport.streamingFlagMismatch(encoder, declaredStreaming = true))
    }
}
