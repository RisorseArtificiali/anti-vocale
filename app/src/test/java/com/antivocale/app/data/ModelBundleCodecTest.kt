package com.antivocale.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * TASK-742 (GH #124): the transfer-bundle codec pins. Round-trip,
 * tamper rejection, format-version gate, no free-rider files, and the
 * cleanup-on-failure contract.
 */
class ModelBundleCodecTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun metadata(name: String = "My Model") = ModelBundleCodec.BundleMetadata(
        displayName = name,
        family = "WHISPER",
        modelType = "",
        languages = listOf("he"),
        streaming = false,
        options = mapOf("whisper.language" to "he"),
        appVersion = "1.14.0-SNAPSHOT",
    )

    private fun modelDir(vararg files: Pair<String, String>): File {
        val dir = tmp.newFolder()
        files.forEach { (name, content) -> File(dir, name).writeText(content) }
        return dir
    }

    @Test
    fun `round trip preserves files and manifest`() {
        val dir = modelDir("encoder.int8.onnx" to "enc-bytes", "tokens.txt" to "a\nb\nc")
        val out = ByteArrayOutputStream()
        ModelBundleCodec.export(dir, out, metadata())

        val target = File(tmp.newFolder(), "imported")
        val manifest = ModelBundleCodec.import(ByteArrayInputStream(out.toByteArray()), target)

        assertEquals("My Model", manifest.getString("name"))
        assertEquals("WHISPER", manifest.getString("family"))
        assertEquals(1, manifest.getInt("formatVersion"))
        assertEquals("he", manifest.getJSONArray("languages").getString(0))
        assertEquals("enc-bytes", File(target, "encoder.int8.onnx").readText())
        assertEquals("a\nb\nc", File(target, "tokens.txt").readText())
        // the pins match the actual content
        val pins = manifest.getJSONObject("files")
        assertEquals(ModelBundleCodec.sha256Of(File(target, "tokens.txt")), pins.getString("tokens.txt"))
    }

    @Test
    fun `a tampered file fails verification and cleans the target`() {
        val dir = modelDir("m.onnx" to "original")
        val out = ByteArrayOutputStream()
        ModelBundleCodec.export(dir, out, metadata())

        // flip one byte inside the zip payload (not the manifest): a simple
        // way is to tamper the source text before re-zipping manually - here
        // tamper the exported bytes at the file-content region.
        val bytes = out.toByteArray()
        val idx = String(bytes, Charsets.ISO_8859_1).indexOf("original")
        assertTrue("payload region found", idx > 0)
        bytes[idx] = 'X'.code.toByte()

        val target = File(tmp.newFolder(), "imported")
        try {
            ModelBundleCodec.import(ByteArrayInputStream(bytes), target)
            fail("tampered bundle must throw")
        } catch (e: Exception) {
            // the honest cleanup contract
            assertTrue("target cleaned: ${target.exists()}", !target.exists())
        }
    }

    @Test
    fun `a bundle with a free-rider file is rejected`() {
        val dir = modelDir("m.onnx" to "x")
        val out = ByteArrayOutputStream()
        ModelBundleCodec.export(dir, out, metadata())
        // re-zip with an extra entry the manifest does not know
        val extras = ByteArrayOutputStream()
        java.util.zip.ZipOutputStream(extras).use { zip ->
            java.util.zip.ZipInputStream(ByteArrayInputStream(out.toByteArray())).use { src ->
                var e = src.nextEntry
                while (e != null) {
                    zip.putNextEntry(java.util.zip.ZipEntry(e.name))
                    src.copyTo(zip)
                    zip.closeEntry()
                    e = src.nextEntry
                }
            }
            zip.putNextEntry(java.util.zip.ZipEntry("files/evil.txt"))
            zip.write("evil".toByteArray())
            zip.closeEntry()
        }
        val target = File(tmp.newFolder(), "imported")
        try {
            ModelBundleCodec.import(ByteArrayInputStream(extras.toByteArray()), target)
            fail("free rider must throw")
        } catch (e: Exception) {
            assertTrue(!target.exists())
        }
    }

    @Test
    fun `a newer format version is rejected without side effects`() {
        val dir = modelDir("m.onnx" to "x")
        val out = ByteArrayOutputStream()
        ModelBundleCodec.export(dir, out, metadata())
        // patch formatVersion to a future value
        val patched = String(out.toByteArray(), Charsets.ISO_8859_1)
            .replace("\"formatVersion\":1", "\"formatVersion\":99")
            .toByteArray(Charsets.ISO_8859_1)
        val target = File(tmp.newFolder(), "imported")
        try {
            ModelBundleCodec.import(ByteArrayInputStream(patched), target)
            fail("future format must throw")
        } catch (e: Exception) {
            assertTrue(!target.exists())
        }
    }
}
