package com.antivocale.app.data

import android.util.Log
import org.json.JSONObject
import java.io.File
import java.io.OutputStream
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * TASK-742 (GH #124): the offline model-transfer bundle. A zip of one
 * model directory plus a manifest entry (name, per-file sha256 pins, the
 * app version and the bundle format version) that another device's
 * Anti-Vocale can import without any network. The manifest reuses the
 * CATALOG's pin vocabulary (name/sha256 per file) so the import side is
 * the existing validator, not a new trust mechanism.
 *
 * FORMAT (stable, versioned):
 *   manifest.json          { formatVersion, appVersion, name, family,
 *                            modelType, languages, files: {name: sha256},
 *                            streaming, options }
 *   files/<original name>  the model's files verbatim
 *
 * WHAT IT DOES NOT DO (deliberate):
 *  - no compression beyond zip STORE (the models are int8 ONNX, already
 *    dense; compressing a 640MB file burns minutes for ~2 percent);
 *  - no cross-ABI concerns (ONNX CPU models are ABI-independent);
 *  - no secrets (the manifest carries no tokens or endpoint data).
 */
object ModelBundleCodec {

    private const val TAG = "ModelBundleCodec"
    private const val FORMAT_VERSION = 1
    const val MANIFEST_ENTRY = "manifest.json"
    private const val FILES_PREFIX = "files/"
    private const val COPY_BUFFER = 128 * 1024

    /** Writes the bundle for [modelDir] to [out]; the caller owns closing. */
    fun export(modelDir: File, out: OutputStream, metadata: BundleMetadata) {
        val files = modelDir.listFiles()?.sortedBy { it.name }
            ?: throw IllegalArgumentException("empty model dir: $modelDir")
        ZipOutputStream(out).use { zip ->
            // NO_COMPRESSION deflate: zip STORED would need size+crc up
            // front (a full extra pass over up-to-640MB files), while
            // deflate level 0 stores bytes verbatim at the same format
            // cost as STORE - the models are dense int8 ONNX anyway.
            zip.setLevel(java.util.zip.Deflater.NO_COMPRESSION)
            val pins = JSONObject()
            files.filter { it.isFile }.forEach { file ->
                val digest = MessageDigest.getInstance("SHA-256")
                zip.putNextEntry(ZipEntry(FILES_PREFIX + file.name))
                file.inputStream().use { input ->
                    val buffer = ByteArray(COPY_BUFFER)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        digest.update(buffer, 0, read)
                        zip.write(buffer, 0, read)
                    }
                }
                zip.closeEntry()
                pins.put(file.name, digest.digest().joinToString("") { "%02x".format(it) })
            }
            val manifest = JSONObject()
                .put("formatVersion", FORMAT_VERSION)
                .put("appVersion", metadata.appVersion)
                .put("name", metadata.displayName)
                .put("family", metadata.family)
                .put("modelType", metadata.modelType)
                .put("languages", org.json.JSONArray(metadata.languages))
                .put("streaming", metadata.streaming)
                .put("options", JSONObject(metadata.options))
                .put("files", pins)
            zip.putNextEntry(ZipEntry(MANIFEST_ENTRY))
            zip.write(manifest.toString().toByteArray())
            zip.closeEntry()
        }
    }

    /**
     * Reads a bundle from [input] into [targetDir] (created), verifying
     * every file's sha256 against the manifest BEFORE any file is kept:
     * a mismatch or a formatVersion above ours aborts with the target
     * cleaned up. Returns the parsed manifest.
     */
    fun import(input: java.io.InputStream, targetDir: File): JSONObject {
        require(!targetDir.exists() || targetDir.listFiles()?.isEmpty() != false) {
            "target dir not empty: $targetDir"
        }
        targetDir.mkdirs()
        try {
            var manifest: JSONObject? = null
            val written = HashMap<String, File>()
            ZipInputStream(input).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    when {
                        entry.name == MANIFEST_ENTRY -> {
                            manifest = zip.readBytes().toString(Charsets.UTF_8).let(::JSONObject)
                        }
                        entry.name.startsWith(FILES_PREFIX) && !entry.isDirectory -> {
                            val name = entry.name.removePrefix(FILES_PREFIX)
                                .replace("..", "").replace('/', '_')
                            val target = File(targetDir, name)
                            File(target.parentFile, name).outputStream().use { out ->
                                val buffer = ByteArray(COPY_BUFFER)
                                while (true) {
                                    val read = zip.read(buffer)
                                    if (read < 0) break
                                    out.write(buffer, 0, read)
                                }
                            }
                            written[name] = target
                        }
                    }
                }
            }
            val m = manifest ?: throw IllegalArgumentException("no $MANIFEST_ENTRY in bundle")
            val format = m.optInt("formatVersion", -1)
            require(format in 1..FORMAT_VERSION) {
                "bundle format $format not supported (this build understands up to $FORMAT_VERSION)"
            }
            val pins = m.getJSONObject("files")
            for (name in pins.keys()) {
                val target = written[name]
                    ?: throw IllegalArgumentException("manifest lists '$name' but the bundle lacks it")
                val actual = sha256Of(target)
                require(actual == pins.getString(name)) {
                    "sha256 mismatch for $name (bundle corrupt or tampered)"
                }
            }
            // Reject files the manifest does not know (no free riders).
            for ((name, _) in written) {
                require(pins.has(name)) { "bundle file '$name' absent from the manifest" }
            }
            return m
        } catch (e: Exception) {
            targetDir.deleteRecursively()
            Log.w(TAG, "Bundle import failed; target cleaned", e)
            throw e
        }
    }

    fun sha256Of(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(COPY_BUFFER)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /**
     * TASK-742 review F2: the import-side record params, parsed HERE (one
     * owner of the manifest vocabulary; the VM stays a 4-liner like its
     * sibling import entries). An unknown family fails LOUDLY at parse
     * time, not as a confusing transducer-shaped copy error later.
     */
    fun recordParams(manifest: JSONObject): RecordParams {
        val familyName = manifest.optString("family").ifBlank { ModelFamily.TRANSDUCER.name }
        val family = ModelFamily.entries.firstOrNull { it.name == familyName }
            ?: throw IllegalArgumentException("unknown model family '$familyName' in bundle")
        val optionsJson = manifest.optJSONObject("options")
        val languagesJson = manifest.optJSONArray("languages")
        return RecordParams(
            displayName = manifest.optString("name").ifBlank { null },
            modelType = manifest.optString("modelType").ifBlank { null },
            family = family,
            options = optionsJson?.let { o -> buildMap { o.keys().forEach { k -> put(k, o.getString(k)) } } }
                ?: emptyMap(),
            languages = languagesJson?.let { a -> (0 until a.length()).map { a.getString(it) } }
                ?: emptyList(),
            streaming = manifest.optBoolean("streaming", false),
        )
    }

    data class RecordParams(
        val displayName: String?,
        val modelType: String?,
        val family: ModelFamily,
        val options: Map<String, String>,
        val languages: List<String>,
        val streaming: Boolean,
    )

    /** The export-side metadata, from the registry descriptor or record. */
    data class BundleMetadata(
        val displayName: String,
        val family: String,
        val modelType: String,
        val languages: List<String>,
        val streaming: Boolean,
        val options: Map<String, String>,
        val appVersion: String,
    )
}
