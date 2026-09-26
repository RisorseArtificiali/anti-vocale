package com.antivocale.app.transcription

import com.antivocale.app.data.catalog.CatalogEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * TASK-413 / GH #68 defense 1: the explicit-policy invariant over the REAL
 * bundled catalog.
 *
 * [SherpaBackend.loadModel] scans every entry whose flags do NOT set
 * skipMetadataCheck, demanding [SherpaBackend.requiredMetadataKeys]. That
 * resolver prefers flags.metaKeys and otherwise dispatches on modelType,
 * where an unknown modelType silently inherits the else default
 * (vocab_size). That silent inheritance is how qwen3 shipped broken for five
 * releases: the same wrong belief (every encoder carries vocab_size) wrote
 * both the guard and its test. No bundled entry may rely on it anymore:
 * each entry must be covered by an EXPLICIT policy, either a non-empty
 * flags.metaKeys or a modelType named in the when arms of
 * [SherpaBackend.requiredTransducerMetadataKeys].
 *
 * The else arm itself is legitimate for EXTERNAL transducer imports
 * (a zipformer import with modelType "" must be allowed to fail the
 * vocab_size gate deliberately, with guidance, per the TASK-481 ground
 * truth); this test scopes the invariant to the bundled catalog, where
 * every modelType is known ahead of time and silence has no excuse.
 */
class BundledCatalogMetadataPolicyTest {

    /**
     * The modelTypes explicitly handled by the when arms of
     * requiredTransducerMetadataKeys. Extending the production when with a
     * new arm without adding it here leaves the new arm untested; extending
     * this set without a production arm fails the pinned-key-lists test
     * below. The two lists can only change together.
     */
    private val explicitModelTypes = setOf("nemo_transducer", "qwen3_asr")

    /**
     * Entries that skip the metadata scan entirely, with the recorded reason.
     * Adding a skip is a policy decision: name the entry here with why, or
     * the invariant test fails.
     */
    private val documentedSkips = mapOf(
        // Whisper exports carry n_mels/n_vocab style metadata, never
        // vocab_size: the scan would reject every loadable Whisper.
        "whisper" to "no vocab_size in Whisper exports (flags.skipMetadataCheck)",
    )

    /** Where an entry's metadata policy comes from. */
    private enum class Policy { EXPLICIT_META_KEYS, EXPLICIT_MODEL_TYPE, SKIPPED }

    @Test
    fun `every bundled entry the load path checks declares an explicit metadata policy`() {
        val problems = mutableListOf<String>()
        classifyCatalog().forEach { (entry, policy) ->
            if (policy == null && entry.flags.skipMetadataCheck) {
                problems.add(
                    "entry '${entry.id}' sets skipMetadataCheck without a documented reason: " +
                        "record it in documentedSkips or drop the flag (a scan skip is a policy " +
                        "decision, TASK-413/GH #68)."
                )
            } else if (policy == null) {
                problems.add(
                    "entry '${entry.id}' (modelType='${entry.modelType}') would SILENTLY inherit " +
                        "the else default ${SherpaBackend.requiredTransducerMetadataKeys(entry.modelType)} " +
                        "from requiredTransducerMetadataKeys. Declare flags.metaKeys for it in " +
                        "models_catalog.json, or add an explicit when arm for '${entry.modelType}' " +
                        "(and extend explicitModelTypes here with a pinned key-list test). " +
                        "TASK-413/GH #68: silent inheritance is how qwen3 shipped broken."
                )
            }
        }
        assertTrue(problems.joinToString("\n"), problems.isEmpty())
    }

    @Test
    fun `bundled entries are pinned to their policy classification`() {
        // Documentation as much as pin: why each shipped entry falls where it
        // does. A catalog edit that changes any row's classification fails
        // here and forces a conscious decision instead of a silent drift.
        val expected = mapOf(
            // Parakeet and GigaAM declare the full NeMo triple explicitly; their
            // modelType would resolve the same list via the nemo arm, but the
            // flag is the catalog-driven policy and the belt-and-braces proof.
            "sherpa-onnx" to Policy.EXPLICIT_META_KEYS,
            "gigaam" to Policy.EXPLICIT_META_KEYS,
            // Online Nemotron uses modelType "" (the else arm's territory for
            // external imports); the flag declaring [vocab_size] is what keeps
            // it from inheriting that arm by accident.
            "nemotron-streaming" to Policy.EXPLICIT_META_KEYS,
            // GH #68: the qwen3 loader reads no encoder metadata and the export
            // carries none; the explicit empty arm is the decision.
            "qwen3-asr" to Policy.EXPLICIT_MODEL_TYPE,
            // The only scan skip: Whisper exports never carry vocab_size.
            "whisper" to Policy.SKIPPED,
        )
        val classified = classifyCatalog().entries.associate { (entry, policy) -> entry.id to policy }
        assertEquals(
            "the classification must cover exactly the bundled catalog " +
                "(BundledModelCatalogTest pins the id set separately)",
            expected.keys,
            classified.keys,
        )
        expected.forEach { (id, policy) ->
            assertEquals("classification of '$id'", policy, classified.getValue(id))
        }
    }

    @Test
    fun `explicit modelType arms resolve the pinned key lists and else serves imports only`() {
        assertEquals(
            listOf("vocab_size", "subsampling_factor", "model_type"),
            SherpaBackend.requiredTransducerMetadataKeys("nemo_transducer"),
        )
        assertEquals(
            emptyList<String>(),
            SherpaBackend.requiredTransducerMetadataKeys("qwen3_asr"),
        )
        // The else arm exists for EXTERNAL transducer imports (zipformer with
        // modelType "", TASK-481) and must never be reached by a bundled
        // entry: the invariant test above enforces that on the real catalog.
        assertEquals(
            listOf("vocab_size"),
            SherpaBackend.requiredTransducerMetadataKeys(""),
        )
    }

    /**
     * Classifies every real catalog entry against the production filter
     * (skipMetadataCheck entries are SKIPPED) and the explicit-policy rule.
     * Null policy = uncovered = the regression class this suite exists to
     * catch.
     */
    private fun classifyCatalog(): Map<CatalogEntry, Policy?> =
        bundledCatalogEntriesForTest().associateWith { entry ->
            when {
                entry.flags.skipMetadataCheck ->
                    if (entry.id in documentedSkips) Policy.SKIPPED else null
                entry.flags.metaKeys.isNotEmpty() -> Policy.EXPLICIT_META_KEYS
                entry.modelType in explicitModelTypes -> Policy.EXPLICIT_MODEL_TYPE
                else -> null
            }
        }
}
