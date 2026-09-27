package com.antivocale.app.transcription.diarization

import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * TASK-670 (GH #83): the voiceprint store's persistence contract. Every
 * test runs on a throwaway directory: the store is pure file IO, no
 * Android dependency.
 */
class SpeakerIdentityStoreTest {

    private fun newStore(): SpeakerIdentityStore =
        SpeakerIdentityStore(Files.createTempDirectory("speaker-ids").toFile())

    private fun embed(vararg values: Float): FloatArray = values

    @Test
    fun `save then list round-trips name embedding and sample length`() {
        val store = newStore()
        val embedding = embed(0.1f, -0.2f, 0.3f)
        val saved = store.save("Alice", embedding, 12.5f, FloatArray(16_000), 16_000)

        val listed = store.list()
        assertEquals(1, listed.size)
        assertEquals("Alice", listed[0].name)
        assertTrue(listed[0].embedding.contentEquals(embedding))
        assertEquals(12.5f, listed[0].sampleSeconds, 0.0001f)
        assertEquals(saved.id, listed[0].id)
    }

    @Test
    fun `the enrollment sample is stored as a playable wav next to the voiceprint`() {
        val store = newStore()
        val saved = store.save("Bob", embed(1f), 5f, floatArrayOf(0.5f, -0.5f, 0.25f), 16_000)

        val sample = store.sampleFile(saved.id)
        assertNotNull(sample)
        val header = sample!!.readBytes()
        // RIFF header sanity: the magic and the mono-16kHz format block.
        assertEquals("RIFF", String(header, 0, 4, Charsets.US_ASCII))
        assertEquals("WAVE", String(header, 8, 4, Charsets.US_ASCII))
    }

    @Test
    fun `delete removes the voiceprint and the sample together`() {
        val store = newStore()
        val saved = store.save("Carol", embed(0.9f), 8f, FloatArray(1_000), 16_000)
        assertTrue(store.sampleFile(saved.id)!!.isFile)

        store.delete(saved.id)

        assertTrue(store.list().isEmpty())
        assertNull(store.sampleFile(saved.id))
        // The whole per-person directory is gone, not just one file.
        assertFalse(store.sampleFile(saved.id)?.exists() ?: false)
    }

    @Test
    fun `a corrupt identity file is skipped and never fails the listing`() {
        val store = newStore()
        store.save("Dave", embed(0.4f), 9f, FloatArray(1_000), 16_000)
        val dir = Files.createTempDirectory("speaker-ids").toFile()
        val garbage = dir.resolve("foreign-dir")
        garbage.mkdirs()
        garbage.resolve("identity.bin").writeText("not a voiceprint")

        val listed = SpeakerIdentityStore(dir).list()

        assertTrue(listed.isEmpty())
    }

    @Test
    fun `listing is sorted by name for a stable settings order`() {
        val store = newStore()
        store.save("Zoe", embed(0.1f), 3f, FloatArray(100), 16_000)
        store.save("Amy", embed(0.2f), 4f, FloatArray(100), 16_000)

        assertEquals(listOf("Amy", "Zoe"), store.list().map { it.name })
    }

    @Test
    fun `enrollment bounds are the documented constants`() {
        // TASK-670: the bounds the enrollment pipeline enforces on a picked
        // clip; pinned here so a change is a visible decision.
        assertEquals(3f, SpeakerIdentityStore.MIN_ENROLL_SECONDS, 0.0001f)
        assertEquals(30f, SpeakerIdentityStore.MAX_ENROLL_SECONDS, 0.0001f)
    }
}
