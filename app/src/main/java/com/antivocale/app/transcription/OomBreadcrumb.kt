package com.antivocale.app.transcription

import android.content.Context
import android.util.Log
import com.antivocale.app.R
import com.antivocale.app.audio.MemoryReadings
import com.antivocale.app.data.PreferencesManager
import com.antivocale.app.manager.LlmManager
import com.antivocale.app.util.CrashReporter
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first

/**
 * One resident engine as the breadcrumb and the memory panel name it: the
 * registry backend id (externals carry their `external:<id>`) plus the
 * user-visible display name (TASK-679).
 */
data class ResidentEngine(
    val id: String,
    val displayName: String,
)

/**
 * TASK-679: the post-OOM breadcrumb. When a transcription dies on an
 * OOM-class failure ([TranscriptionOrchestrator]'s OutOfMemoryError catch and
 * the memory-class failure arms), one scrubbed line records every engine that
 * was resident at that moment plus the shape of the request that tipped the
 * device, so the next occurrence is attributable on-device (the TASK-631
 * calibration audit stalled exactly on this blind spot).
 *
 * Scrub contract (AC#3), enforced by construction: the [Snapshot] has NO
 * field transcript text can flow through, and every free-text token passes
 * [scrubToken] (path reduced to its last segment, whitespace collapsed,
 * length capped). The persisted line is capped at [MAX_LENGTH] characters.
 */
object OomBreadcrumb {

    /** Cap on the persisted line: the preference value stays small (AC#3). */
    const val MAX_LENGTH = 1024

    /** Cap per free-text token (ids, display names). */
    private const val TOKEN_CAP = 64

    private const val MB = 1024L * 1024L

    /** The scrubbed inputs of one breadcrumb line. */
    data class Snapshot(
        /** Exception class at the failure point ("OutOfMemoryError"). */
        val errorClass: String,
        /** Engines resident when the device tipped. */
        val residentEngines: List<ResidentEngine>,
        /** [MemoryReadings.availableRamBytes] at failure, null when unreadable. */
        val freeRamBytes: Long?,
        /** [MemoryReadings.totalRamBytes] at failure, null when unreadable. */
        val totalRamBytes: Long?,
        /** The backend the failing request was running ("the load that tipped"). */
        val requestBackendId: String?,
        /** Container-metadata audio duration in seconds, when already known. */
        val audioDurationSeconds: Double?,
        /** Effective-VAD approximation at failure time; null when unresolved. */
        val vadEnabled: Boolean?,
    )

    /** Pure builder: scrubbed inputs in, one capped key=value line out. */
    fun build(snapshot: Snapshot): String = buildString {
        append("oom error=").append(scrubToken(snapshot.errorClass))
        append(" residents=")
        if (snapshot.residentEngines.isEmpty()) {
            append("none")
        } else {
            snapshot.residentEngines.joinTo(this, ",") { engine ->
                "${scrubToken(engine.id)}(${scrubToken(engine.displayName)})"
            }
        }
        append(" ram=")
        if (snapshot.freeRamBytes != null && snapshot.totalRamBytes != null) {
            append("${snapshot.freeRamBytes / MB}/${snapshot.totalRamBytes / MB}MB")
        } else {
            append("unknown")
        }
        snapshot.requestBackendId?.let { append(" req=").append(scrubToken(it)) }
        snapshot.audioDurationSeconds?.let { append(" dur=").append("%.0fs".format(java.util.Locale.US, it)) }
        snapshot.vadEnabled?.let { append(" vad=").append(if (it) "on" else "off") }
    }.take(MAX_LENGTH)

    /**
     * The scrub applied to every free-text token: path separators keep only
     * the last segment (a model dir name, never a full path), whitespace
     * collapses to single spaces so the line stays one line, and the token is
     * capped so no field can smuggle bulk content.
     */
    internal fun scrubToken(raw: String): String {
        val flat = raw.replace(Regex("\\s+"), " ").trim()
        val lastSegment = flat.substringAfterLast('/').substringAfterLast('\\')
        return lastSegment.take(TOKEN_CAP)
    }
}

/**
 * TASK-679: captures and persists the breadcrumb, and owns the live residency
 * snapshot the Settings panel renders. One owner so the line written at
 * failure time and the panel the user later reads name engines through the
 * SAME derivation (AC#4: no second observability seam, persistence is a
 * synchronous SharedPreferences commit because the OOM catch site is the last
 * code that runs before the process may die).
 */
@Singleton
class OomBreadcrumbRecorder @Inject constructor(
    private val preferencesManager: PreferencesManager,
    private val backendManager: TranscriptionBackendManager,
    private val llmManager: LlmManager,
    private val backendRegistry: BackendRegistry,
) {
    companion object {
        private const val TAG = "OomBreadcrumbRecorder"
        private const val PREFS_NAME = "oom_breadcrumb"
        private const val KEY_LINE = "last_breadcrumb"
        private const val KEY_AT = "last_breadcrumb_at"
    }

    /**
     * Engines resident right now: the active transcription backend (the
     * manager's REAL id for externals, not the engine's "external"
     * placeholder) plus the LLM when [LlmManager] holds an engine. Display
     * names resolve through the registry derivation so a resident external is
     * named by its record, not the engine's generic label.
     */
    suspend fun residentEngines(context: Context): List<ResidentEngine> = buildList {
        backendManager.activeBackendId.value?.let { activeId ->
            add(ResidentEngine(activeId, displayNameFor(context, activeId)))
        }
        if (llmManager.isReady()) {
            add(ResidentEngine(
                LlmTranscriptionBackend.BACKEND_ID,
                context.getString(R.string.llm_backend_name)))
        }
    }

    private suspend fun displayNameFor(context: Context, backendId: String): String {
        // Review F9: the raw id is a better fallback than the engine's
        // generic "External model" placeholder (the id carries the family
        // and the record identity).
        val fallback = backendId
        return runCatching {
            val descriptor = backendRegistry.byBackendId(backendId) ?: return fallback
            variantAwareDisplayName(
                context, descriptor, descriptor.modelPathFlow(preferencesManager).first())
        }.getOrDefault(fallback)
    }

    /**
     * The catch-site entry. Never throws (every read is guarded: the heap may
     * be exhausted at the caller). Persists FIRST, then rides the existing
     * [CrashReporter] flow (playStore: Crashlytics non-fatal; fdroid: logcat),
     * each capture reported as-is: OOM-class failures are rare by construction
     * and a repeated refusal is itself the signal.
     */
    suspend fun record(
        context: Context,
        error: Throwable,
        requestBackendId: String?,
        audioDurationSeconds: Double?,
        vadEnabled: Boolean?,
    ) {
        val line = runCatching {
            OomBreadcrumb.build(
                OomBreadcrumb.Snapshot(
                    errorClass = error::class.simpleName ?: "unknown",
                    residentEngines = residentEngines(context),
                    freeRamBytes = MemoryReadings.availableRamBytes(context),
                    totalRamBytes = MemoryReadings.totalRamBytes(context),
                    requestBackendId = requestBackendId,
                    audioDurationSeconds = audioDurationSeconds,
                    vadEnabled = vadEnabled,
                ))
        }.getOrElse {
            Log.w(TAG, "Breadcrumb snapshot failed; nothing persisted", it)
            return
        }
        persist(context, line)
        CrashReporter.report(OomBreadcrumbReport(line), "Post-OOM breadcrumb")
    }

    /**
     * Synchronous on purpose ([SharedPreferences.Editor.commit], not apply):
     * the OOM catch is the last chance to write before the process dies, and
     * an async DataStore flush is exactly what an LMKD kill would drop.
     */
    private fun persist(context: Context, line: String) {
        runCatching {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_LINE, line)
                .putLong(KEY_AT, System.currentTimeMillis())
                .commit()
        }.onFailure { Log.w(TAG, "Breadcrumb persist failed", it) }
    }

    /** The last persisted breadcrumb line and its wall-clock time, or null when none. */
    fun last(context: Context): Pair<String, Long>? = runCatching {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val line = prefs.getString(KEY_LINE, null) ?: return null
        line to prefs.getLong(KEY_AT, 0L)
    }.getOrNull()

    /** The read-only panel state (TASK-679 AC#2): residents, RAM, breadcrumb. */
    suspend fun panelState(context: Context): MemoryDiagnosticsState {
        val lastCrumb = last(context)
        return MemoryDiagnosticsState.assemble(
            residentEngines = runCatching { residentEngines(context) }.getOrDefault(emptyList()),
            freeRamBytes = MemoryReadings.availableRamBytes(context),
            totalRamBytes = MemoryReadings.totalRamBytes(context),
            heapLimitBytes = MemoryReadings.maxHeapBytes(),
            lastBreadcrumb = lastCrumb?.first,
            lastBreadcrumbAtMs = lastCrumb?.second,
        )
    }

    /** Telemetry carrier rendered as a Crashlytics non-fatal record. */
    class OomBreadcrumbReport(line: String) :
        RuntimeException("OOM breadcrumb: $line")
}

/**
 * TASK-679: the state the Settings memory panel renders. Pure data plus pure
 * derived lines (the mapping is the unit-test target); the scrub contract
 * holds here too because every input is already a scrubbed field.
 */
data class MemoryDiagnosticsState(
    val residentEngines: List<ResidentEngine>,
    val freeRamBytes: Long?,
    val totalRamBytes: Long?,
    val heapLimitBytes: Long,
    val lastBreadcrumb: String?,
    val lastBreadcrumbAtMs: Long?,
) {
    companion object {
        private const val MB = 1024L * 1024L

        /** Pure assembly: normalizes inputs into the panel state. */
        fun assemble(
            residentEngines: List<ResidentEngine>,
            freeRamBytes: Long?,
            totalRamBytes: Long?,
            heapLimitBytes: Long,
            lastBreadcrumb: String?,
            lastBreadcrumbAtMs: Long?,
        ): MemoryDiagnosticsState = MemoryDiagnosticsState(
            // Stable identity order: one row per engine, duplicates by id
            // collapsed (the LLM and an active backend never share an id, but
            // a defensive distinct keeps the panel honest if that ever changes).
            residentEngines = residentEngines.distinctBy { it.id },
            freeRamBytes = freeRamBytes?.takeIf { it > 0 },
            totalRamBytes = totalRamBytes?.takeIf { it > 0 },
            heapLimitBytes = heapLimitBytes.takeIf { it > 0 } ?: 0L,
            lastBreadcrumb = lastBreadcrumb?.takeIf { it.isNotBlank() },
            lastBreadcrumbAtMs = lastBreadcrumbAtMs?.takeIf { it > 0 },
        )

        /** Pure byte-to-"NMB" formatter shared by the UI rows and the export. */
        fun mb(bytes: Long): String = "${bytes / MB}MB"
    }

    /** "id (display name)" per resident; display name first in the UI lines below. */
    fun residentLines(): List<String> = residentEngines.map {
        // Review F5: the fields are scrubbed of the line's own delimiters so
        // an adversarial import name cannot inject fake key=value structure.
        val name = it.displayName.filter { c -> c !in "()" }.trim()
        val id = it.id.filter { c -> c !in "()" }.trim()
        if (name.isBlank()) "$id" else "$name ($id)"
    }

    /** "812MB" / "3712MB" for the localized RAM row, null when unreadable. */
    fun freeRamMb(): String? = freeRamBytes?.let(::mb)
    fun totalRamMb(): String? = totalRamBytes?.let(::mb)

    /** "812MB free of 3712MB" (report-facing English), or null when unreadable. */
    fun ramLine(): String? =
        if (freeRamBytes != null && totalRamBytes != null) {
            "${mb(freeRamBytes)} free of ${mb(totalRamBytes)}"
        } else null

    /** The app's per-process heap ceiling ("256MB"). */
    fun heapLimitLine(): String = mb(heapLimitBytes)

    /**
     * The scrubbed export bundle the Copy button puts on the clipboard:
     * report-facing English like [com.antivocale.app.data.local.FailureContextJson.render],
     * built only from the scrubbed fields above (no transcript, no paths).
     */
    fun exportBundle(): String = buildString {
        appendLine("Anti-Vocale memory diagnostics")
        if (residentEngines.isEmpty()) {
            appendLine("resident: none")
        } else {
            residentEngines.forEach { appendLine("resident: ${it.displayName} (${it.id})") }
        }
        appendLine("ram: ${ramLine() ?: "unknown"}")
        appendLine("heap: ${heapLimitLine()}")
        appendLine("oom: ${lastBreadcrumb ?: "none"}")
    }
}
