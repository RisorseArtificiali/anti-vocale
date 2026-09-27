package com.antivocale.app.transcription.diarization

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.util.UUID

/**
 * TASK-670 (GH #83): one enrolled speaker identity. The [embedding] is the
 * titanet voiceprint extracted from the enrollment sample; it lives in the
 * app's private storage only and never leaves the device. [sampleSeconds]
 * is the enrollment clip's speech length, kept for the Settings row.
 */
data class SpeakerIdentity(
    val id: String,
    val name: String,
    val embedding: FloatArray,
    val sampleSeconds: Float,
)

/**
 * TASK-670 (GH #83): the voiceprint store. App-private, file-based, one
 * directory per person under filesDir/speaker_identities/<id>/ holding the
 * serialized identity (name + embedding + sample length) and the enrollment
 * clip as a 16 kHz mono WAV (the replay in "record, name, replay, delete").
 *
 * The store is deliberately protobuf-free: a versioned DataOutputStream
 * frame. Deleting a person removes their whole directory, voiceprint and
 * sample together ([delete] is the privacy decision's per-person removal
 * path). No contact-book integration by design.
 *
 * The whole feature sits behind the default-off speakerIdEnabled
 * preference; while that is off nothing constructs or reads this store.
 */
class SpeakerIdentityStore(private val baseDir: File) {

    companion object {
        private const val IDENTITY_FILE = "identity.bin"
        private const val SAMPLE_FILE = "sample.wav"

        /** Frame magic ("SPKI") + version; a file without them is skipped. */
        private const val MAGIC = 0x5350_4B49
        private const val VERSION = 1

        /**
         * Enrollment sample bounds: below the minimum titanet has too little
         * voice to embed reliably; above the maximum a clip adds nothing (the
         * extractor averages the whole buffer) and the stored WAV would bloat
         * the private dir.
         */
        const val MIN_ENROLL_SECONDS = 3f
        const val MAX_ENROLL_SECONDS = 30f

        fun dir(context: android.content.Context): File =
            File(context.filesDir, "speaker_identities")
    }

    /** All enrolled identities; a corrupt or foreign entry is skipped, never fatal. */
    @Synchronized
    fun list(): List<SpeakerIdentity> {
        val dirs = baseDir.listFiles { file -> file.isDirectory } ?: return emptyList()
        return dirs.mapNotNull { dir -> read(File(dir, IDENTITY_FILE)) }
            .sortedBy { it.name.lowercase() }
    }

    /**
     * Persists one person: the identity frame plus the WAV copy of the
     * enrollment samples (16 kHz mono), so replay and any future
     * re-extraction keep working after the picker's clip is gone.
     */
    @Synchronized
    fun save(
        name: String,
        embedding: FloatArray,
        sampleSeconds: Float,
        samples: FloatArray,
        sampleRate: Int,
    ): SpeakerIdentity {
        val id = UUID.randomUUID().toString()
        val personDir = File(baseDir, id)
        personDir.mkdirs()
        // Review R3 + simplify F1: the SAMPLE writes first through the shared
        // WavUtils writer (the third hand-rolled little-endian writer is
        // gone), the identity frame LAST: a failure mid-save leaves a dir
        // whose corrupt/missing identity.bin the listing's skip already
        // hides, instead of a ghost identity with a truncated sample.
        File(personDir, SAMPLE_FILE).writeBytes(
            com.antivocale.app.util.WavUtils.floatSamplesToWav(samples, sampleRate))
        DataOutputStream(File(personDir, IDENTITY_FILE).outputStream().buffered()).use { out ->
            out.writeInt(MAGIC)
            out.writeInt(VERSION)
            out.writeUTF(name)
            out.writeInt(embedding.size)
            for (value in embedding) out.writeFloat(value)
            out.writeFloat(sampleSeconds)
        }
        return SpeakerIdentity(id, name, embedding, sampleSeconds)
    }

    /** Per-person removal: the directory, voiceprint and sample, goes away entirely. */
    @Synchronized
    fun delete(id: String) {
        File(baseDir, id).deleteRecursively()
    }

    /** The enrollment clip for replay, when it still exists. */
    fun sampleFile(id: String): File? = File(File(baseDir, id), SAMPLE_FILE).takeIf { it.isFile }

    private fun read(file: File): SpeakerIdentity? = runCatching {
        DataInputStream(file.inputStream().buffered()).use { input ->
            val magic = input.readInt()
            val version = input.readInt()
            if (magic != MAGIC || version != VERSION) return null
            val name = input.readUTF()
            val dim = input.readInt()
            if (dim <= 0 || dim > MAX_EMBEDDING_DIM) return null
            val embedding = FloatArray(dim) { input.readFloat() }
            val sampleSeconds = input.readFloat()
            if (!sampleSeconds.isFinite() || sampleSeconds <= 0f) return null
            val id = file.parentFile?.name ?: return null
            SpeakerIdentity(id, name, embedding, sampleSeconds)
        }
    }.getOrNull()

    /** A sane ceiling so a corrupt length cannot ask for a huge allocation. */
    private val MAX_EMBEDDING_DIM = 4096
}
