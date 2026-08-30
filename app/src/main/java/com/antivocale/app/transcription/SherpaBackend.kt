package com.antivocale.app.transcription

import android.content.Context
import android.util.Log
import androidx.annotation.VisibleForTesting
import com.antivocale.app.data.catalog.BundledCatalog
import com.antivocale.app.data.catalog.CatalogEntry
import com.antivocale.app.data.catalog.CatalogVariant
import com.antivocale.app.data.download.CatalogModelValidator
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineQwen3AsrModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineStream
import com.k2fsa.sherpa.onnx.OfflineTransducerModelConfig
import com.k2fsa.sherpa.onnx.OfflineWhisperModelConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineStream
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import com.antivocale.app.data.PreferencesManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.withContext
import java.io.File

/**
 * ONE catalog-driven transcription backend for every built-in sherpa-onnx model.
 *
 * Everything that used to be per-model backend code is now catalog data:
 *  - the model family (offline vs online recognizer) from the entry `runtime`,
 *  - the native config shape from the entry `modelType`
 *    (`nemo_transducer` → OfflineTransducer, `whisper` → OfflineWhisper,
 *    `qwen3_asr` → OfflineQwen3Asr, `""` + online → OnlineTransducer),
 *  - the file roles (encoder/decoder/joiner/tokens/conv_frontend/tokenizer) from
 *    the entry's variant file names,
 *  - the tuning knobs (tail silence padding, Whisper tailPaddings, Qwen3
 *    blankPenalty/maxNewTokens, chunk duration) from the entry `flags`.
 *
 * The five built-ins are five instances of this class, one per catalog entry
 * (see [BuiltInBackendIds] and the DI module).
 */
class SherpaBackend(
    val entryId: String,
) : TranscriptionBackend {

    companion object {
        private const val TAG = "SherpaBackend"

        // Required model files for a nemo_transducer (Parakeet TDT) model.
        val REQUIRED_MODEL_FILES = listOf(
            "encoder.int8.onnx",
            "decoder.int8.onnx",
            "joiner.int8.onnx",
            "tokens.txt"
        )

        // Canonical role names, resolved BY PREFIX, not by list position: reordering
        // REQUIRED_MODEL_FILES must never silently repoint a role. The external-model
        // importer copies/renames sources onto these names and the external engine
        // loads them, so both resolve the roles here rather than in parallel copies.
        val CANONICAL_ENCODER = REQUIRED_MODEL_FILES.first { it.startsWith("encoder") }
        val CANONICAL_DECODER = REQUIRED_MODEL_FILES.first { it.startsWith("decoder") }
        val CANONICAL_JOINER = REQUIRED_MODEL_FILES.first { it.startsWith("joiner") }
        val CANONICAL_TOKENS = REQUIRED_MODEL_FILES.first { it.startsWith("tokens") }

        /**
         * Metadata keys a transducer encoder must carry for [modelType], shared by the
         * external-model importer (import-time validation) and the external engine
         * (load-time validation) so the two cannot drift: vocab_size for every family
         * except qwen3_asr, whose loader and export carry no encoder metadata; the nemo
         * loader's subsampling_factor + model_type only for the nemo family (a zipformer
         * import with modelType "" does not carry them and must not be rejected for
         * their absence).
         */
        fun requiredTransducerMetadataKeys(modelType: String): List<String> = when (modelType) {
            "nemo_transducer" -> listOf("vocab_size", "subsampling_factor", "model_type")
            // GH #68: the qwen3 loader reads no encoder metadata (vocab lives in the
            // tokenizer dir) and the published export carries none, so any required
            // key would reject a loadable model.
            "qwen3_asr" -> emptyList()
            else -> listOf("vocab_size")
        }

        /**
         * Metadata keys a built-in catalog entry's encoder must carry: the entry's
         * flags.metaKeys when declared (per-model override — Parakeet/GigaAM (nemo) need
         * all three, Nemotron online carries only vocab_size), else the modelType default.
         * Catalog-driven so a tuned key list lives in the catalog, not in each backend.
         */
        fun requiredMetadataKeys(entry: CatalogEntry): List<String> =
            entry.flags.metaKeys.ifEmpty { requiredTransducerMetadataKeys(entry.modelType) }

        private const val ONNX_METADATA_SCAN_LIMIT: Long = 2L * 1024 * 1024

        /**
         * Returns the metadata keys from [requiredKeys] that are NOT present in [file].
         *
         * ONNX stores metadata_props (protobuf key-value pairs); for large models they land near
         * the END of the file, so we scan the last [maxScanBytes] once and test every key against
         * the same buffer. Empty/missing file returns every key as missing.
         *
         * This prevents native crashes: sherpa-onnx calls exit(255) when required metadata
         * (e.g. vocab_size) is missing, killing the process with no catchable exception.
         */
        fun missingOnnxMetadata(
            file: File,
            requiredKeys: List<String>,
            maxScanBytes: Long = ONNX_METADATA_SCAN_LIMIT
        ): List<String> {
            if (requiredKeys.isEmpty()) return emptyList()
            // Treat unreadable as "all metadata missing" so the caller shows a clear
            // ModelLoadError instead of letting an IOException propagate uncaught.
            val data = readTail(file, maxScanBytes) ?: return requiredKeys
            return missingOnnxMetadataKeys(data, requiredKeys)
        }

        /**
         * Key-presence check plus one value lookup in a SINGLE tail read: returns the
         * missing required keys together with the value of [valueKey] (null when
         * [valueKey] is null, or when the key/file is absent or unreadable). Serves
         * callers that run both [missingOnnxMetadata] and [onnxMetadataValue] on the
         * same file (importer registration, external engine init) without reading the
         * 2MB tail twice.
         */
        fun missingOnnxMetadataAndValue(
            file: File,
            requiredKeys: List<String>,
            valueKey: String?,
            maxScanBytes: Long = ONNX_METADATA_SCAN_LIMIT
        ): Pair<List<String>, String?> {
            val data = readTail(file, maxScanBytes) ?: return requiredKeys to null
            val value = valueKey?.let { onnxMetadataValueBytes(data, it) }
            return missingOnnxMetadataKeys(data, requiredKeys) to value
        }

        /**
         * Reads the last [maxScanBytes] of [file]. Null when the file is empty/missing
         * or unreadable (the sentinel each caller picks for its own error handling).
         */
        private fun readTail(file: File, maxScanBytes: Long): ByteArray? {
            if (!file.exists() || file.length() == 0L) return null
            val fileSize = file.length()
            val scanStart = maxOf(0L, fileSize - maxScanBytes)
            val data = ByteArray((fileSize - scanStart).toInt())
            return try {
                java.io.RandomAccessFile(file, "r").use { raf ->
                    raf.seek(scanStart)
                    raf.readFully(data)
                }
                data
            } catch (e: java.io.IOException) {
                null
            }
        }

        /**
         * Returns the metadata keys from [requiredKeys] whose UTF-8 bytes do not appear as a
         * contiguous subsequence in [data]. Pure (no I/O) so it can be unit-tested directly.
         */
        @JvmStatic
        @VisibleForTesting(otherwise = VisibleForTesting.PRIVATE)
        internal fun missingOnnxMetadataKeys(data: ByteArray, requiredKeys: List<String>): List<String> {
            return requiredKeys.filter { key ->
                val needle = key.toByteArray(Charsets.UTF_8)
                needle.isNotEmpty() && !containsSubsequence(data, needle)
            }
        }

        /**
         * Returns the VALUE of metadata prop [key] in [file], or null when absent/unreadable.
         *
         * ONNX metadata_props are protobuf StringStringEntryProto pairs (field 1 = key,
         * field 2 = value, both length-prefixed). The scan reads the file tail once (same
         * window as [missingOnnxMetadata]), locates the key bytes, and parses the following
         * length-delimited value (tag 0x12 + varint length). Key occurrences not followed by
         * a value tag are skipped, so stray key text cannot fool the parser.
         */
        fun onnxMetadataValue(
            file: File,
            key: String,
            maxScanBytes: Long = ONNX_METADATA_SCAN_LIMIT
        ): String? {
            val data = readTail(file, maxScanBytes) ?: return null
            return onnxMetadataValueBytes(data, key)
        }

        /** Pure (no I/O) value parser behind [onnxMetadataValue], unit-testable directly. */
        @JvmStatic
        @VisibleForTesting(otherwise = VisibleForTesting.PRIVATE)
        internal fun onnxMetadataValueBytes(data: ByteArray, key: String): String? {
            val needle = key.toByteArray(Charsets.UTF_8)
            if (needle.isEmpty()) return null
            var i = indexOfSubsequence(data, needle)
            while (i >= 0) {
                val p = parseLengthPrefixedValue(data, i + needle.size)
                if (p != null) return p
                i = indexOfSubsequence(data, needle, fromIndex = i + 1)
            }
            return null
        }

        /**
         * Parses a protobuf length-delimited field value at [start]: tag 0x12 (field 2,
         * wire type 2), varint length, then that many UTF-8 bytes. Null when [start] does
         * not hold that shape (the key occurrence is not a metadata entry).
         */
        private fun parseLengthPrefixedValue(data: ByteArray, start: Int): String? {
            if (start >= data.size || data[start] != 0x12.toByte()) return null
            var p = start + 1
            var len = 0L
            var shift = 0
            while (p < data.size) {
                val b = data[p++].toInt() and 0xFF
                len = len or ((b and 0x7F).toLong() shl shift)
                if (b and 0x80 == 0) break
                shift += 7
                if (shift > 35) return null
            }
            if (len < 1 || len > data.size - p) return null
            return String(data, p, len.toInt(), Charsets.UTF_8)
        }

        /**
         * Index of the first occurrence of [needle] as a contiguous byte subsequence of
         * [haystack][0..[length]) at or after [fromIndex], -1 when absent. The single
         * byte-scanner definition: the metadata checks here and the importer's
         * split-ONNX sidecar scan both express their loops through it. [length] allows
         * scanning a prefix of a larger reused buffer.
         */
        @JvmStatic
        @VisibleForTesting(otherwise = VisibleForTesting.PRIVATE)
        internal fun indexOfSubsequence(
            haystack: ByteArray,
            needle: ByteArray,
            fromIndex: Int = 0,
            length: Int = haystack.size,
        ): Int {
            val lastStart = length - needle.size
            var i = maxOf(fromIndex, 0)
            while (i <= lastStart) {
                var j = 0
                while (j < needle.size && haystack[i + j] == needle[j]) j++
                if (j == needle.size) return i
                i++
            }
            return -1
        }

        /** Returns true if [haystack] contains [needle] as a contiguous byte subsequence. */
        @VisibleForTesting(otherwise = VisibleForTesting.PRIVATE)
        internal fun containsSubsequence(haystack: ByteArray, needle: ByteArray): Boolean =
            indexOfSubsequence(haystack, needle) >= 0

        /**
         * The file roles of a catalog [variant] resolved FROM THE CATALOG FILE NAMES
         * (contains-matching, so GigaAM's `gigaam_v3_e2e_rnnt_encoder_int8.onnx` and
         * Whisper's `turbo-encoder.int8.onnx` both resolve). No role is ever implied by
         * list position.
         */
        @VisibleForTesting
        internal fun resolveRoles(variant: CatalogVariant): Roles {
            val files = variant.files.map { it.name }
            fun firstMatching(vararg markers: String): String? =
                files.firstOrNull { f -> markers.any { f.contains(it) } }
            val tokenizerDir = files.firstOrNull { it.contains("/") }?.substringBefore("/")
            return Roles(
                encoder = firstMatching("encoder") ?: error(
                    "entry variant '${variant.name}' has no encoder file (files=$files)"),
                decoder = firstMatching("decoder") ?: error(
                    "entry variant '${variant.name}' has no decoder file (files=$files)"),
                joiner = firstMatching("joiner", "joint"),
                tokens = firstMatching("tokens"),
                convFrontend = firstMatching("conv_frontend"),
                tokenizerDir = tokenizerDir,
            )
        }

        /** The roles every modelType requires to be present in [Roles]. */
        @VisibleForTesting
        internal fun requiredRoleNames(modelType: String): List<String> = when (modelType) {
            "nemo_transducer" -> listOf("encoder", "decoder", "joiner", "tokens")
            "whisper" -> listOf("encoder", "decoder", "tokens")
            "qwen3_asr" -> listOf("convFrontend", "encoder", "decoder", "tokenizerDir")
            "" -> listOf("encoder", "decoder", "joiner", "tokens")
            else -> throw IllegalArgumentException("unsupported catalog modelType '$modelType'")
        }
    }

    /** The file roles a catalog variant declares; built from the variant's file names. */
    data class Roles(
        val encoder: String,
        val decoder: String,
        val joiner: String? = null,
        val tokens: String? = null,
        val convFrontend: String? = null,
        val tokenizerDir: String? = null,
    ) {
        fun requireRole(name: String): String = when (name) {
            "encoder" -> encoder
            "decoder" -> decoder
            "joiner" -> joiner ?: error("role '$name' not present")
            "tokens" -> tokens ?: error("role '$name' not present")
            "convFrontend" -> convFrontend ?: error("role '$name' not present")
            "tokenizerDir" -> tokenizerDir ?: error("role '$name' not present")
            else -> error("unknown role '$name'")
        }
    }

    private val entry: CatalogEntry
        get() = requireNotNull(BundledCatalog.byId(entryId)) { "catalog missing entry '$entryId'" }

    override val id: String = entryId
    override val displayName: String = entryId
    override val supportsAudio: Boolean = true
    override val supportsText: Boolean = false

    /** Catalog `flags.chunkDurationSeconds` (>0 chunks long audio, 0 = whole clip in one pass). */
    override val maxChunkDurationSeconds: Int?
        get() = BundledCatalog.byId(entryId)?.flags?.chunkDurationSeconds?.takeIf { it > 0 }

    // @Volatile: the idle-unload timer frees these on Dispatchers.Default while
    // transcriptions start on Dispatchers.IO; a stale non-null read after an
    // unload would be a use-after-free on the native handle.
    @Volatile private var offlineRecognizer: OfflineRecognizer? = null
    @Volatile private var onlineRecognizer: OnlineRecognizer? = null
    private var modelDir: String? = null
    private var isInitialized = false
    private var language: String = "auto"

    /**
     * Idle-unload timer (TASK-344 / issue #42): the ORT arena under a loaded
     * sherpa session never shrinks (~2.3GB retained after a long clip), and
     * release() provably frees it, so the keep-alive timeout now really
     * unloads this backend when idle. Fires only when no work is in flight.
     */
    private val keepAliveScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val keepAlive = NativeKeepAlive(
        scope = keepAliveScope,
        tag = TAG,
        defaultTimeoutMinutes = PreferencesManager.DEFAULT_KEEP_ALIVE_TIMEOUT,
        onIdleUnload = { runCatching { unload() } },
    )
    private val onAutoUnloadCallback = java.util.concurrent.atomic.AtomicReference<(() -> Unit)?>(null)

    /**
     * Shared tail-pad silence (TASK-340 Fix 1b): chunked transcription used to build a fresh
     * `samples + FloatArray(tail)` copy per chunk; a second acceptWaveform of this one shared
     * zero-filled buffer (64KB for 1s at 16kHz) achieves the same final-token flush with no
     * per-chunk allocation. Never write into the returned array.
     */
    private val tailSilence = TailSilenceBuffer()

    private val isStreaming: Boolean get() = BundledCatalog.byId(entryId)?.isStreaming == true

    override suspend fun initialize(context: Context, config: BackendConfig): Result<Unit> {
        val sherpaConfig = config as? BackendConfig.SherpaOnnxConfig
            ?: return Result.failure(IllegalArgumentException("Invalid config type for SherpaBackend"))

        if (isInitialized) {
            Log.w(TAG, "Already initialized, returning success")
            return Result.success(Unit)
        }

        val entry = entry
        val modelDirectory = sherpaConfig.modelDir
        Log.i(TAG, "Initializing '${entry.id}' (${if (entry.isStreaming) "online" else "offline"}," +
            " modelType='${entry.modelType}') with model dir: $modelDirectory")

        val dir = File(modelDirectory)
        if (!dir.exists() || !dir.isDirectory) {
            return Result.failure(TranscriptionException.ModelLoadError("directory not found: $modelDirectory"))
        }

        // Resolve which catalog variant lives in the dir (multi-variant entries) so the
        // file roles and any single-language forcing (Whisper Distil) come from the
        // actual installed variant, not the default.
        val variant = entry.variants.firstOrNull { it.dirName == dir.name } ?: entry.defaultVariant
        val fileNames = variant.files.map { it.name }

        // Completeness: every catalog file must be present (ONNX via .size sidecar).
        if (!CatalogModelValidator.isValidModelDir(dir, fileNames)) {
            return Result.failure(TranscriptionException.ModelLoadError(
                "missing files in $modelDirectory: ${fileNames.joinToString()}"
            ))
        }
        val roles = resolveRoles(variant)
        // Guard against catalog variants whose file names can't resolve a required role
        // (a catalog bug surfaces here at load time, not in the native call).
        val requiredRolesMissing = requiredRoleNames(entry.modelType).filter { role ->
            val value = when (role) {
                "encoder" -> roles.encoder
                "decoder" -> roles.decoder
                "joiner" -> roles.joiner
                "tokens" -> roles.tokens
                "convFrontend" -> roles.convFrontend
                "tokenizerDir" -> roles.tokenizerDir
                else -> null
            }
            value.isNullOrBlank()
        }
        if (requiredRolesMissing.isNotEmpty()) {
            return Result.failure(TranscriptionException.ModelLoadError(
                "catalog entry '${entry.id}' cannot resolve required roles for modelType " +
                    "'${entry.modelType}': $requiredRolesMissing (files=$fileNames)"
            ))
        }

        return withContext(Dispatchers.IO) {
            // Pre-native validation: sherpa-onnx calls exit(255) when the encoder is missing
            // critical metadata, killing the process with no catchable exception. Whisper
            // models carry no vocab_size metadata, so the catalog skips the scan for it.
            if (!entry.flags.skipMetadataCheck) {
                val encoderFile = File(dir, roles.encoder)
                val missingMeta = missingOnnxMetadata(encoderFile, requiredMetadataKeys(entry))
                if (missingMeta.isNotEmpty()) {
                    Log.e(TAG, "Encoder missing required ONNX metadata: $missingMeta")
                    return@withContext Result.failure(TranscriptionException.ModelLoadError(
                        "model file is missing required metadata ($missingMeta). " +
                            "The model may be corrupt or an incompatible export. Try re-downloading it."
                    ))
                }
            }

            try {
                if (entry.isStreaming) {
                    initOnline(dir, sherpaConfig, entry, variant, roles)
                } else {
                    initOffline(dir, sherpaConfig, entry, variant, roles)
                }
                modelDir = modelDirectory
                language = sherpaConfig.language.ifBlank { "auto" }
                isInitialized = true
                keepAlive.start()
                Log.i(TAG, "'${entry.id}' initialized successfully")
                Result.success(Unit)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to initialize '${entry.id}'", e)
                Result.failure(TranscriptionException.ModelLoadError(e.message ?: "unknown", e))
            } catch (e: Error) {
                // Catch native errors (UnsatisfiedLinkError, etc.)
                Log.e(TAG, "Native error initializing '${entry.id}'", e)
                Result.failure(TranscriptionException.NativeError(e.message ?: "unknown", e))
            }
        }
    }

    /** Builds the OfflineRecognizer for an offline entry, dispatching on the catalog modelType. */
    private fun initOffline(
        dir: File,
        sherpaConfig: BackendConfig.SherpaOnnxConfig,
        entry: CatalogEntry,
        variant: CatalogVariant,
        roles: Roles,
    ) {
        val modelConfig = when (entry.modelType) {
            "nemo_transducer" -> OfflineModelConfig(
                transducer = OfflineTransducerModelConfig(
                    encoder = File(dir, roles.encoder).absolutePath,
                    decoder = File(dir, roles.decoder).absolutePath,
                    joiner = File(dir, requireNotNull(roles.joiner)).absolutePath,
                ),
                tokens = File(dir, requireNotNull(roles.tokens)).absolutePath,
                modelType = entry.modelType,
                numThreads = sherpaConfig.numThreads,
                debug = false,
                provider = sherpaConfig.provider,
            )

            "whisper" -> OfflineModelConfig(
                whisper = OfflineWhisperModelConfig(
                    encoder = File(dir, roles.encoder).absolutePath,
                    decoder = File(dir, roles.decoder).absolutePath,
                    // A single-language variant (Whisper Distil-IT) forces its language.
                    language = forcedLanguage(entry, variant, sherpaConfig.language),
                    task = "transcribe",
                    tailPaddings = entry.flags.whisperTailPaddings,
                ),
                tokens = File(dir, requireNotNull(roles.tokens)).absolutePath,
                modelType = entry.modelType,
                numThreads = sherpaConfig.numThreads,
                debug = false,
                provider = sherpaConfig.provider,
            )

            "qwen3_asr" -> OfflineModelConfig(
                qwen3Asr = OfflineQwen3AsrModelConfig(
                    convFrontend = File(dir, requireNotNull(roles.convFrontend)).absolutePath,
                    encoder = File(dir, roles.encoder).absolutePath,
                    decoder = File(dir, roles.decoder).absolutePath,
                    tokenizer = File(dir, requireNotNull(roles.tokenizerDir)).absolutePath,
                    maxNewTokens = entry.flags.maxNewTokens,
                ),
                modelType = entry.modelType,
                numThreads = sherpaConfig.numThreads,
                debug = false,
                provider = sherpaConfig.provider,
            )

            else -> throw IllegalArgumentException("unsupported offline modelType '${entry.modelType}'")
        }

        val recognizerConfig = OfflineRecognizerConfig(
            modelConfig = modelConfig,
            featConfig = com.k2fsa.sherpa.onnx.FeatureConfig(
                sampleRate = 16000,
                featureDim = 80,
            ),
            decodingMethod = "greedy_search",
            blankPenalty = entry.flags.blankPenalty.toFloat(),
        )
        offlineRecognizer = OfflineRecognizer(config = recognizerConfig)
    }

    /** Builds the OnlineRecognizer for a streaming entry (Nemotron online transducer). */
    private fun initOnline(
        dir: File,
        sherpaConfig: BackendConfig.SherpaOnnxConfig,
        entry: CatalogEntry,
        variant: CatalogVariant,
        roles: Roles,
    ) {
        val transducerConfig = OnlineTransducerModelConfig(
            encoder = File(dir, roles.encoder).absolutePath,
            decoder = File(dir, roles.decoder).absolutePath,
            joiner = File(dir, requireNotNull(roles.joiner)).absolutePath,
        )
        val modelConfig = OnlineModelConfig(
            transducer = transducerConfig,
            tokens = File(dir, requireNotNull(roles.tokens)).absolutePath,
            numThreads = sherpaConfig.numThreads,
            debug = false,
            provider = sherpaConfig.provider,
            modelType = entry.modelType, // Nemotron online: empty modelType (NOT nemo_transducer)
        )
        val recognizerConfig = OnlineRecognizerConfig(
            modelConfig = modelConfig,
            featConfig = com.k2fsa.sherpa.onnx.FeatureConfig(
                sampleRate = 16000,
                featureDim = 80,
            ),
        )
        onlineRecognizer = OnlineRecognizer(config = recognizerConfig)
    }

    /** A variant with exactly one supported language forces it (Whisper Distil-IT → "it"). */
    private fun forcedLanguage(entry: CatalogEntry, variant: CatalogVariant, configLanguage: String): String {
        val langs = entry.languagesFor(variant)
        return if (langs.size == 1) langs.first() else configLanguage
    }

    override suspend fun transcribeAudio(
        samples: FloatArray,
        sampleRate: Int,
        prompt: String,
    ): Result<TranscriptionResult> {
        if (isStreaming) {
            // Batch interface over the streaming recognizer (same shape as the old Nemotron path).
            return transcribeAudioStreaming(samples, sampleRate, prompt) {}
        }

        // beginWork BEFORE reading the recognizer: claiming work first closes
        // the idle-unload race (a timer fire that already reserved the unload
        // still wins, but then this read sees the post-unload null).
        keepAlive.beginWork()
        try {
            val rec = offlineRecognizer
                ?: return Result.failure(TranscriptionException.NotInitialized())
            return withContext(Dispatchers.IO) {
            // Release the native OfflineStream on EVERY path (happy, exception, blank-result)
            // so the JNI handle is freed deterministically, not left to GC finalization.
            var stream: OfflineStream? = null
            try {
                Log.d(TAG, "Transcribing audio: ${samples.size} samples at ${sampleRate}Hz")

                stream = rec.createStream()
                stream.acceptWaveform(samples, sampleRate)
                // Append `tailPadSeconds` (catalog flag) of silence so trailing tokens
                // finalize correctly (benchmarked ~2% WER improvement on WhatsApp audio).
                // Second acceptWaveform of one shared zero buffer: no per-chunk copy
                // (TASK-340 Fix 1b). acceptWaveform appends, so this equals one padded array.
                val tailPad = entry.flags.tailPadSeconds
                if (tailPad > 0) {
                    stream.acceptWaveform(tailSilence.get((sampleRate * tailPad).toInt()), sampleRate)
                }
                rec.decode(stream)

                val result = rec.getResult(stream)
                val transcription = result.text
                val detectedLang = result.lang.ifBlank { null }

                Log.d(TAG, "Transcription complete: '${transcription.take(100)}...' (${transcription.length} chars)")

                if (transcription.isBlank()) {
                    Result.failure(TranscriptionException.NoTranscriptionProduced())
                } else {
                    // Keep the ORIGINAL samples length (not the padded one) for the duration calc.
                    val confidence = TranscriptionResult.computeConfidence(transcription, samples.size, sampleRate)
                    Result.success(TranscriptionResult(
                        text = transcription,
                        confidence = confidence,
                        detectedLanguage = detectedLang,
                    ))
                }
            } catch (e: Exception) {
                Log.e(TAG, "Transcription failed", e)
                Result.failure(TranscriptionException.NativeError(e.message ?: "unknown", e))
            } finally {
                stream?.release()
            }
            }
        } finally {
            keepAlive.endWork()
        }
    }

    override suspend fun transcribeAudioStreaming(
        samples: FloatArray,
        sampleRate: Int,
        prompt: String,
        onPartial: suspend (String) -> Unit,
    ): Result<TranscriptionResult> {
        if (!isStreaming) {
            // Offline entries: no progressive partials; the default batch path.
            return transcribeAudio(samples, sampleRate, prompt)
        }

        keepAlive.beginWork()
        try {
            val rec = onlineRecognizer
                ?: return Result.failure(TranscriptionException.NotInitialized())
            return withContext(Dispatchers.IO) {
            var stream: OnlineStream? = null
            try {
                Log.d(TAG, "Transcribing audio: ${samples.size} samples at ${sampleRate}Hz")

                // createStream(String) arg is HOTWORDS/contextual biasing, NOT language — passing a
                // non-empty value triggers contextual biasing, which sherpa aborts on (exit 255).
                stream = rec.createStream("")
                // AC #3: condition the multilingual model on the user's language ("auto" = auto-detect).
                stream.setOption("language", language)
                // Tail padding: a second acceptWaveform of one shared zero buffer so the
                // streaming encoder gets a complete final chunk to flush trailing tokens,
                // with no per-chunk padded copy (TASK-340 Fix 1b). The original length
                // stays the basis for the duration calc below.
                val tailPadSamples = (entry.flags.tailPadSeconds * sampleRate).toInt()
                stream.acceptWaveform(samples, sampleRate)
                if (tailPadSamples > 0) {
                    stream.acceptWaveform(tailSilence.get(tailPadSamples), sampleRate)
                }

                // Decode loop: drain the recognizer's internal buffer, emitting the growing
                // hypothesis after each pass so the UI can render text progressively.
                var lastEmitted = ""
                while (rec.isReady(stream)) {
                    rec.decode(stream)
                    val partial = rec.getResult(stream).text
                    if (partial.isNotBlank() && partial != lastEmitted) {
                        onPartial(partial)
                        lastEmitted = partial
                    }
                }

                stream.inputFinished()
                // Drain trailing hypotheses after end-of-input (a streaming transducer can hold
                // several final tokens until EOF; a single decode pass may not flush them all).
                while (rec.isReady(stream)) {
                    rec.decode(stream)
                }

                val result = rec.getResult(stream)
                val transcription = result.text
                if (transcription.isNotBlank() && transcription != lastEmitted) {
                    onPartial(transcription)
                }

                Log.d(TAG, "Transcription complete: '${transcription.take(100)}...' (${transcription.length} chars)")

                if (transcription.isBlank()) {
                    Result.failure(TranscriptionException.NoTranscriptionProduced())
                } else {
                    // OnlineRecognizerResult exposes no confidence/language fields.
                    val confidence = TranscriptionResult.computeConfidence(transcription, samples.size, sampleRate)
                    Result.success(TranscriptionResult(
                        text = transcription,
                        confidence = confidence,
                        detectedLanguage = null,
                    ))
                }
            } catch (e: Exception) {
                Log.e(TAG, "Transcription failed", e)
                Result.failure(TranscriptionException.NativeError(e.message ?: "unknown", e))
            } finally {
                stream?.release()
            }
            }
        } finally {
            keepAlive.endWork()
        }
    }

    override suspend fun generateText(prompt: String): Result<String> {
        return Result.failure(UnsupportedOperationException(
            "Text generation not supported by $entryId backend. Use for audio transcription only."
        ))
    }

    override fun isReady(): Boolean = isInitialized && (offlineRecognizer != null || onlineRecognizer != null)

    override fun isAudioSupported(): Boolean = true

    override fun unload() {
        Log.i(TAG, "Unloading '$entryId' backend")
        keepAlive.stop()
        offlineRecognizer?.release()
        offlineRecognizer = null
        onlineRecognizer?.release()
        onlineRecognizer = null
        modelDir = null
        language = "auto"
        isInitialized = false
        // Notify the manager/UI after the native memory is actually freed.
        onAutoUnloadCallback.get()?.invoke()
    }

    override fun setKeepAliveTimeout(minutes: Int) {
        keepAlive.setTimeout(minutes)
    }

    override fun setOnAutoUnloadCallback(callback: (() -> Unit)?) {
        onAutoUnloadCallback.set(callback)
    }

    override fun getModelPath(): String? = modelDir
}
/**
 * Lazily-allocated, shared all-zero silence buffer for tail padding (TASK-340 Fix 1b).
 * Sized per request and cached per size; feeding it as a second acceptWaveform call
 * replaces the per-chunk `samples + silence` full copy. The buffer must never be
 * written to: callers rely on it staying all zeros.
 */
internal class TailSilenceBuffer {
    // @Volatile: transcribeAudio may run on more than one coroutine when the
    // orchestrator's in-flight chunk limit exceeds 1 (serial in production since
    // TASK-406, but the limit is a test seam); the lazy slots must be visible
    // across coroutines. Contents are never written after allocation (all zeros),
    // so a rare duplicate allocation during a race is harmless; this just makes
    // the cache airtight.
    @Volatile private var oneSecond: FloatArray? = null
    @Volatile private var other: Pair<Int, FloatArray>? = null

    fun get(sampleCount: Int): FloatArray {
        if (sampleCount <= 0) return FloatArray(0)
        if (sampleCount == TAIL_PAD_SECONDS_1 * SAMPLE_RATE_16K) {
            return oneSecond ?: FloatArray(sampleCount).also { oneSecond = it }
        }
        other?.let { (size, buf) -> if (size == sampleCount) return buf }
        return FloatArray(sampleCount).also { other = sampleCount to it }
    }

    private companion object {
        const val SAMPLE_RATE_16K = 16000
        const val TAIL_PAD_SECONDS_1 = 1
    }
}
