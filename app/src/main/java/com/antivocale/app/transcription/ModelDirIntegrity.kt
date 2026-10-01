package com.antivocale.app.transcription

import com.antivocale.app.data.catalog.CatalogVariant
import com.antivocale.app.data.download.DownloadedModelIntegrity
import com.antivocale.app.data.download.HashVerifier
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * TASK-479 / GH #88: content-level model-dir validation at LOAD time.
 *
 * The existing checks catch incompleteness (presence, `.size` sidecar) but not
 * corruption: a file whose length matches the sidecar but whose bytes are
 * garbage (interrupted write, bit rot, a bad resume) reaches the native
 * OfflineRecognizer, which aborts the whole process with "Protobuf parsing
 * failed", and every retry re-aborts on the same file. This object adds the
 * content layer on top:
 *
 * - structural checks for every file (ONNX header byte, size floors), shared
 *   with the download-time pass ([DownloadedModelIntegrity]);
 * - SHA-256 verification for files the catalog pins (exact, catches same-length
 *   corruption; costs ~1-2 s per GB on device during a load that already takes
 *   seconds).
 *
 * TASK-482: the pin pass is shared with the external-model backend
 * ([verifyPins]) and its verdicts are cached per (path, length, lastModified)
 * so a re-initialize (keep-alive unload, backend switch, benchmark) does not
 * re-hash ~862MB on the request hot path; the boundary lives on
 * [PinVerdictCache].
 *
 * [verify] is pure I/O (no Android types) so the decision is unit-testable;
 * the caller owns the failure UX and the heal.
 */
object ModelDirIntegrity {

    /**
     * One file that failed verification, with the human-readable reason.
     * [unreadable] marks a READ failure (transient IO), not a corruption
     * verdict: callers must not route it into a heal that deletes the files
     * (TASK-482 review: a healthy dir deleted over an EIO).
     */
    data class Failure(val file: File, val reason: String, val unreadable: Boolean = false)

    /**
     * TASK-482: remembers which files already passed the SHA-pin check, one
     * entry per path holding the (length, lastModified, pin) descriptor it
     * verified (so a stats change or a different pin re-verifies, and no
     * superseded generation accumulates). CONSCIOUS BOUNDARY (the measured
     * decision the task asked for): same-length bit rot that also preserves
     * mtime after one clean verify stays undetected until the next process
     * start, exactly the marker-sidecar boundary the unpinned-file test below
     * documents; the alternative (re-hashing every load) costs 1.7-3.4s per
     * reload of the default model.
     */
    class PinVerdictCache {
        private val verified = ConcurrentHashMap<String, String>()

        private fun descriptor(f: File, pin: String) = "${f.length()}:${f.lastModified()}:$pin"

        fun isVerified(f: File, pin: String): Boolean =
            verified[f.path] == descriptor(f, pin)

        fun rememberVerified(f: File, pin: String) {
            verified[f.path] = descriptor(f, pin)
        }
    }

    /** Shared default: verdicts live once per process. */
    private val defaultPinCache = PinVerdictCache()

    /**
     * TASK-482: the ONE load gate: structural checks for every file plus
     * SHA-pin verification, as the external-model backend consumes it
     * (its records pin every file at import: server pin or
     * trust-on-first-use). Missing files are the completeness layer's job,
     * not a gate failure.
     */
    fun verify(
        dir: File,
        pins: Map<String, String>,
        pinCache: PinVerdictCache = defaultPinCache,
    ): List<Failure> {
        val structural = runCatching {
            DownloadedModelIntegrity.validate(dir)
        }.getOrElse { e ->
            // An unreadable dir at load time is a completeness failure, not a
            // crash out of the Result contract (review F5).
            return listOf(Failure(dir, "unreadable: ${e.message}", unreadable = true))
        }.map { Failure(it.file, it.reason, it.unreadable) }
        return (structural + verifyPins(dir, pins, pinCache)).distinctBy { it.file.path }
    }

    /**
     * Verifies [dir] against [variant]. Returns the failures (empty = healthy).
     * Files with extensions other than .onnx/.txt are not content-checked
     * here; their existence is the completeness layer's job
     * ([CatalogModelValidator]).
     */
    fun verify(
        dir: File,
        variant: CatalogVariant,
        verifyPins: Boolean = true,
        pinCache: PinVerdictCache = defaultPinCache,
    ): List<Failure> = verify(
        dir,
        if (verifyPins) {
            variant.files.filter { it.sha256 != null }.associate { it.name to it.sha256!! }
        } else {
            emptyMap()
        },
        pinCache,
    )

    /**
     * TASK-482 review: the gate's verdict, pre-split so every caller reads
     * the SAME readability semantics: [unreadable] must never route into a
     * heal that deletes files (a transient IO failure is not corruption);
     * [corrupt] is the content verdict the heals act on. Lives here, beside
     * the flag it splits on, so the two backends cannot diverge on it.
     */
    data class Verdict(val unreadable: List<Failure>, val corrupt: List<Failure>) {
        val anyFailure: Boolean get() = unreadable.isNotEmpty() || corrupt.isNotEmpty()
    }

    fun split(failures: List<Failure>): Verdict = Verdict(
        failures.filter { it.unreadable },
        failures.filterNot { it.unreadable },
    )

    /**
     * TASK-482: the SHA-pin half of the gate, exposed for the pin-map
     * contract test. Missing files are the completeness layer's job, not a
     * pin failure.
     */
    fun verifyPins(
        dir: File,
        pins: Map<String, String>,
        pinCache: PinVerdictCache = defaultPinCache,
    ): List<Failure> =
        pins.mapNotNull { (name, expected) ->
            val onDisk = File(dir, name)
            if (!onDisk.isFile) return@mapNotNull null
            if (pinCache.isVerified(onDisk, expected)) return@mapNotNull null
            val actual = runCatching { HashVerifier.sha256(onDisk) }.getOrNull()
                ?: return@mapNotNull Failure(onDisk, "unreadable", unreadable = true)
            if (!actual.equals(expected, ignoreCase = true)) {
                Failure(onDisk, "SHA-256 mismatch (pinned $expected)")
            } else {
                pinCache.rememberVerified(onDisk, expected)
                null
            }
        }
}
