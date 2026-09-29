package com.antivocale.app.transcription

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * TASK-720: the streaming-vs-offline discriminator for plain zipformers,
 * verified against REAL exports both ways (see the task notes): streaming
 * encoders carry decode_chunk_len and comment "streaming zipformer2";
 * offline ones carry neither key and comment "non-streaming zipformer2".
 * The fixtures below are minimal protobuf metadata_props blobs (tag 0x0a
 * key, tag 0x12 value, length-prefixed) in the exact shape
 * [SherpaBackend.onnxMetadataValueBytes] parses.
 */
class ZipformerStreamingDiscriminatorTest {

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
}
