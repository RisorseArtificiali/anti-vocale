package com.antivocale.app.data

import com.antivocale.app.data.TranscriptionCalibrator.CalibrationProfile
import java.io.File
import io.mockk.mockk
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * TASK-658 / GH #120: the measured-accuracy artifact and its join onto the
 * PerformanceStatsDialog profiles. Parser tests (valid/corrupt/empty), the
 * display-value precision rule, the (modelId, variant) join including the
 * external uuid-suffix form, and a bundled-asset guard that re-reads the
 * shipped files from disk (the BundledModelCatalogTest pattern).
 */
class ModelAccuracyTest {

    private val validAsset = """
        {
          "schemaVersion": 1,
          "generatedFrom": "scripts/generate-accuracy-artifact.py",
          "measurements": [
            {
              "modelId": "sherpa-onnx",
              "variant": "parakeet-tdt-0.6b-v3-smoothquant",
              "language": "it",
              "metric": "WER",
              "value": 5.4,
              "corpus": "internal real-message set",
              "date": "2026-08"
            },
            {
              "modelId": "external",
              "variant": "Omnilingual-ASR-300M-int8-multilingual-experimental-Apache-2.0",
              "language": "tr",
              "metric": "CER",
              "value": 6.5,
              "corpus": "FLEURS-10",
              "date": "2026-09"
            }
          ]
        }
    """.trimIndent()

    // --- decode ---

    @Test
    fun `decode parses a valid artifact into typed rows`() {
        val rows = ModelAccuracy.decode(validAsset)
        assertEquals(2, rows.size)
        val first = rows[0]
        assertEquals("sherpa-onnx", first.modelId)
        assertEquals("parakeet-tdt-0.6b-v3-smoothquant", first.variant)
        assertEquals("it", first.language)
        assertEquals("WER", first.metric)
        assertEquals(5.4, first.value, 0.0)
        assertEquals("internal real-message set", first.corpus)
        assertEquals("2026-08", first.date)
    }

    @Test
    fun `decode returns empty for corrupt json`() {
        assertTrue(ModelAccuracy.decode("{ not json at all").isEmpty())
    }

    @Test
    fun `decode returns empty for null and blank input`() {
        assertTrue(ModelAccuracy.decode(null).isEmpty())
        assertTrue(ModelAccuracy.decode("").isEmpty())
        assertTrue(ModelAccuracy.decode("   ").isEmpty())
    }

    @Test
    fun `decode skips rows missing identity fields or a numeric value`() {
        val rows = ModelAccuracy.decode(
            """
            {
              "measurements": [
                { "modelId": "", "variant": "v", "language": "it", "metric": "WER", "value": 1.0 },
                { "modelId": "whisper", "variant": "", "language": "it", "metric": "WER", "value": 1.0 },
                { "modelId": "whisper", "variant": "v", "language": "", "metric": "WER", "value": 1.0 },
                { "modelId": "whisper", "variant": "v", "language": "it", "metric": "", "value": 1.0 },
                { "modelId": "whisper", "variant": "v", "language": "it", "metric": "WER" },
                "not an object",
                { "modelId": "whisper", "variant": "v", "language": "it", "metric": "WER", "value": 2.0 }
              ]
            }
            """.trimIndent()
        )
        assertEquals(1, rows.size)
        assertEquals(2.0, rows[0].value, 0.0)
    }

    // --- display value (the shown number must equal the measured one) ---

    @Test
    fun `displayValue keeps measured precision without padding`() {
        assertEquals("5.4", measurement(value = 5.4).displayValue)
        assertEquals("7.65", measurement(value = 7.65).displayValue)
        assertEquals("3", measurement(value = 3.0).displayValue)
    }

    // --- the dialog join: rowsFor ---

    private fun measurement(
        modelId: String = "sherpa-onnx",
        variant: String = "parakeet-tdt-0.6b-v3-smoothquant",
        language: String = "it",
        value: Double = 5.4,
    ) = ModelAccuracy.Measurement(
        modelId = modelId,
        variant = variant,
        language = language,
        metric = "WER",
        value = value,
        corpus = "internal real-message set",
        date = "2026-08",
    )

    private fun profile(key: String, name: String = key) =
        CalibrationProfile(modelId = key, displayName = name, msPerSecondOfAudio = 100f, sampleCount = 3)

    @Test
    fun `rowsFor joins built-in profiles by exact backend id and dirName`() {
        val measurements = listOf(
            measurement(language = "it"),
            measurement(language = "pl"),
            measurement(modelId = "whisper", variant = "sherpa-onnx-whisper-small", language = "tr"),
        )
        val groups = ModelAccuracy.rowsFor(
            listOf(profile("sherpa-onnx__parakeet-tdt-0.6b-v3-smoothquant", "Parakeet")),
            measurements,
        )
        assertEquals(1, groups.size)
        assertEquals("Parakeet", groups[0].first.displayName)
        assertEquals(listOf("it", "pl"), groups[0].second.map { it.language })
    }

    @Test
    fun `rowsFor omits profiles without measurements`() {
        val groups = ModelAccuracy.rowsFor(
            listOf(
                profile("sherpa-onnx__parakeet-tdt-0.6b-v3-smoothquant"),
                profile("gigaam__gigaam-v3"),
            ),
            listOf(measurement(language = "it")),
        )
        assertEquals(1, groups.size)
        assertEquals("sherpa-onnx", groups[0].first.modelId.substringBefore("__"))
    }

    @Test
    fun `rowsFor never prefix-matches built-in variants`() {
        val groups = ModelAccuracy.rowsFor(
            // A dirName that merely starts like the parakeet variant is a
            // different model; only external imports carry suffixes.
            listOf(profile("sherpa-onnx__parakeet-tdt-0.6b-v3-smoothquant-extra")),
            listOf(measurement()),
        )
        assertTrue(groups.isEmpty())
    }

    @Test
    fun `rowsFor joins external profiles through the uuid dir suffix`() {
        val measurements = listOf(
            measurement(
                modelId = ModelAccuracy.EXTERNAL_MODEL_ID,
                variant = "Omnilingual-ASR-300M-int8-multilingual-experimental-Apache-2.0",
                language = "he",
            ),
        )
        val dir = "Omnilingual-ASR-300M-int8-multilingual-experimental-Apache-2.0-9f3ab2"
        val groups = ModelAccuracy.rowsFor(
            listOf(profile("external:1a2b3c__$dir", "Omnilingual")),
            measurements,
        )
        assertEquals(1, groups.size)
        assertEquals("he", groups[0].second.single().language)
    }

    @Test
    fun `rowsFor leaves legacy keys without a variant unmatched`() {
        val groups = ModelAccuracy.rowsFor(
            listOf(profile("sherpa-onnx")),
            listOf(measurement()),
        )
        assertTrue(groups.isEmpty())
    }

    @Test
    fun `rowsFor does not match external rows against built-in backends`() {
        val groups = ModelAccuracy.rowsFor(
            listOf(profile("sherpa-onnx__Omnilingual-ASR-300M-int8-multilingual-experimental-Apache-2.0")),
            listOf(
                measurement(
                    modelId = ModelAccuracy.EXTERNAL_MODEL_ID,
                    variant = "Omnilingual-ASR-300M-int8-multilingual-experimental-Apache-2.0",
                ),
            ),
        )
        assertTrue(groups.isEmpty())
    }

    // --- bundled asset guard (reads the shipped files from disk) ---

    private fun repoFile(relative: String): File {
        val moduleRelative = File(relative)
        val rootRelative = File("app/$relative")
        return when {
            moduleRelative.exists() -> moduleRelative
            rootRelative.exists() -> rootRelative
            else -> throw IllegalStateException("Cannot locate $relative from ${File(".").absolutePath}")
        }
    }

    /**
     * Mirror of ExternalModelImporter.sanitizeDirName. If the importer's rule
     * ever changes, this fails together with the dialog join and the Python
     * mirror in scripts/generate-accuracy-artifact.py, forcing a coordinated
     * update instead of silently unrenderable rows.
     */
    private fun sanitizeDirName(name: String): String =
        name.replace(Regex("[^A-Za-z0-9._-]"), "-")
            .replace(Regex("-+"), "-")
            .trim('-', '.')
            .ifBlank { "model" }

    @Test
    fun `bundled asset decodes and every row is well-formed`() {
        val rows = ModelAccuracy.decode(
            repoFile("src/main/assets/model-accuracy.json").readText()
        )
        assertTrue("bundled model-accuracy.json decoded empty", rows.isNotEmpty())
        rows.forEach { m ->
            assertTrue("modelId blank in $m", m.modelId.isNotBlank())
            assertTrue("variant blank in $m", m.variant.isNotBlank())
            assertTrue("language not a code in $m", Regex("[a-z]{2}").matches(m.language))
            assertTrue("metric neither WER nor CER in $m", m.metric == "WER" || m.metric == "CER")
            assertTrue("value not finite non-negative in $m", m.value >= 0 && !m.value.isNaN() && !m.value.isInfinite())
            assertTrue("corpus blank in $m", m.corpus.isNotBlank())
            assertTrue("date not YYYY-MM in $m", Regex("\\d{4}-\\d{2}").matches(m.date))
        }
    }

    @Test
    fun `the sanitize mirror equals the importer rule over catalog entry names`() {
        // TASK-658 review F2: the tripwire the generator script cannot give.
        // The Kotlin rule is the authority; this mirror must track it or the
        // artifact's external rows silently stop rendering.
        val importer = ExternalModelImporter(
            store = mockk(relaxed = true),
            filesRoot = { java.io.File("/tmp/unused") },
        )
        val names = repoFile("src/main/assets/external-catalog")
            .listFiles { f -> f.name.endsWith(".json") && !f.name.startsWith("index") }
            .orEmpty()
            .map { JSONObject(it.readText()).getString("name") }
        assertTrue("no external catalog entries found", names.isNotEmpty())
        names.forEach { n ->
            assertEquals("mirror drifted from the importer rule on '$n'",
                importer.sanitizeDirName(n), sanitizeDirName(n))
        }
    }

    @Test
    fun `bundled external variants match importer-sanitized catalog entry names`() {
        val externalVariants = repoFile("src/main/assets/external-catalog")
            .listFiles { f -> f.name.endsWith(".json") && !f.name.startsWith("index") }
            .orEmpty()
            .map { JSONObject(it.readText()).getString("name") }
            .map(::sanitizeDirName)
            .toSet()
        assertTrue("no external catalog entries found", externalVariants.isNotEmpty())
        val bundled = ModelAccuracy.decode(repoFile("src/main/assets/model-accuracy.json").readText())
        bundled.filter { it.modelId == ModelAccuracy.EXTERNAL_MODEL_ID }.forEach { m ->
            assertTrue(
                "external variant ${m.variant} matches no catalog entry after sanitization; " +
                    "the dialog join can never render it",
                m.variant in externalVariants,
            )
        }
    }
}
