package com.antivocale.app.data.download

import android.content.Context
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * TASK-986: the pin-override channel (maintainer-approved design, 2026-11-11).
 *
 * The built-in catalog pins model files by sha256, but the files live on
 * remotes we do not control (HuggingFace repos): when a publisher
 * re-uploads, every FRESH download fails its integrity check (GH #187)
 * until a release ships a re-pin. This channel lets the PROJECT heal a
 * drifted pin without a release: a JSON file in our own repo
 * (assets/pin-overrides.json, served from GitHub raw on the same path)
 * lists dir/file entries whose CURRENT upstream bytes the project has
 * verified and published as acceptable.
 *
 * SECURITY MODEL (the reason this is safe): an override is consulted only
 * AFTER a download fails its catalog pin, and accepts the file ONLY if its
 * ACTUAL hash equals one of the hashes the project published in
 * [ACCEPTABLE_HASHES]. So the channel accepts bytes the project vetted -
 * never arbitrary content, never attacker-chosen bytes unless the repo
 * itself is compromised (which already ships APKs and community entries).
 * The same trust root as the community catalog; no new attack surface.
 *
 * CONSULTATION IS LAZY: the fetch happens once per process, only after a
 * pin mismatch (zero cost on the happy path, no startup fetch). A fetch
 * failure degrades to today's behavior.
 *
 * PERSISTENCE: an accepted heal is recorded in a local JSON store
 * ([PinHeals]) so future verifications (including load-time re-verification,
 * which re-hashes against the CATALOG pin) consult the healed value without
 * any network. The heal store is the machine memory of what the channel
 * accepted.
 */
object PinOverrides {

    private const val RAW_URL =
        "https://raw.githubusercontent.com/RisorseArtificiali/anti-vocale/main/app/src/main/assets/pin-overrides.json"
    private const val BUNDLED_ASSET = "pin-overrides.json"

    /** data "dir/file" -> override. Null = nothing fetched yet. Internal set
     *  so JVM tests can seed the cache directly (same-module test source). */
    @Volatile internal var cache: Map<String, PinOverride>? = null

    /** Serializes the one fetch attempt per process (review round: losers
     *  of a CAS race would return with cache still null). */
    private val fetchLock = Any()

    /** Injectable for JVM tests: returns the raw JSON body or null. */
    @Volatile var fetcher: (suspend (String) -> String?)? = null

    data class PinOverride(
        val dir: String,
        val file: String,
        val acceptableHashes: List<String>,
        val fallbackUrl: String?,
    )

    /** Pure parser (JVM-testable): malformed entries are skipped, not fatal. */
    fun parse(body: String): Map<String, PinOverride> {
        val root = runCatching { JSONObject(body) }.getOrNull() ?: return emptyMap()
        val array = runCatching { root.getJSONArray("overrides") }.getOrNull() ?: return emptyMap()
        val out = mutableMapOf<String, PinOverride>()
        for (i in 0 until array.length()) {
            val o = runCatching { array.getJSONObject(i) }.getOrNull() ?: continue
            val dir = o.optString("dir", "").trim()
            val file = o.optString("file", "").trim()
            val hashes = runCatching {
                (0 until o.getJSONArray("acceptableHashes").length())
                    .map { o.getJSONArray("acceptableHashes").getString(it).trim().lowercase() }
                    .filter { it.length == 64 && it.all { c -> c.isDigit() || c in 'a'..'f' } }
            }.getOrDefault(emptyList())
            if (dir.isEmpty() || file.isEmpty() || hashes.isEmpty()) continue
            val fallback = o.optString("fallbackUrl", "").trim().ifEmpty { null }
            out["$dir/$file"] = PinOverride(dir, file, hashes, fallback)
        }
        return out
    }

    /** The bundled seed (ships in the APK; may list overrides authored at
     *  release time). Missing asset = empty map. */
    fun bundled(context: Context): Map<String, PinOverride> =
        runCatching {
            context.assets.open(BUNDLED_ASSET).bufferedReader().use { it.readText() }
        }.map(::parse).getOrDefault(emptyMap())

    /**
     * The lazy consultation: on the first call, merges the bundled seed with
     * one fetched copy from GitHub raw (raw main wins: it is the live
     * channel). Later calls reuse the cache. Network errors leave the
     * bundled seed (or empty) - never an exception.
     */
    private val fetchMutex = kotlinx.coroutines.sync.Mutex()

    suspend fun onDemand(bundledSeed: () -> Map<String, PinOverride> = { emptyMap() }) {
        // The cache check first (review round: the bundled asset is opened
        // and parsed only when the channel is actually being consulted).
        if (cache != null) return
        // Mutex (not synchronized): the fetch suspends, and concurrent
        // consultations BLOCK until the winner publishes its result - the
        // earlier CAS shape let losers return with cache==null, skipping
        // their fallback. A failed fetch caches the bundled seed (or empty):
        // degraded for this process, exactly today's behavior.
        fetchMutex.withLock {
            if (cache != null) return
            cache = runCatching { (fetcher ?: ::defaultFetch)(RAW_URL) }
                .getOrNull()?.let(::parse) ?: bundledSeed()
        }
    }

    private suspend fun defaultFetch(url: String): String? = withContext(Dispatchers.IO) {
        val c = java.net.URL(url).openConnection() as javax.net.ssl.HttpsURLConnection
        try {
            c.connectTimeout = 10_000
            c.readTimeout = 10_000
            runCatching { c.inputStream.bufferedReader().use { it.readText() } }.getOrNull()
        } finally {
            c.disconnect()
        }
    }

    /**
     * The verdict for a file whose CATALOG pin check FAILED: true when the
     * actual hash is project-published (override) or locally healed. Never
     * consults the network (call [onDemand] once first).
     */
    fun verdictFor(dir: String, file: String, actualSha: String): Boolean {
        val o = cache?.get("$dir/$file")
        if (o != null && actualSha.lowercase() in o.acceptableHashes) return true
        return PinHeals.healed(dir, file)?.equals(actualSha, ignoreCase = true) == true
    }

    /** Fallback URL for a file the upstream no longer serves (404 arm). */
    fun fallbackFor(dir: String, file: String): String? =
        cache?.get("$dir/$file")?.fallbackUrl
}

/**
 * The persisted half of the channel: dir/file -> the sha this device
 * accepted through the override channel. Consulted by load-time
 * re-verification (which re-hashes against the CATALOG pin) so a healed
 * model keeps loading with no network.
 */
object PinHeals {

    private const val STORE_FILE = "pin-heals.json"

    @Volatile private var healed: MutableMap<String, String>? = null
    private var storeFile: File? = null
    private val lock = Any()

    /** Idempotent; [filesDir] is the app's private storage root. */
    fun init(filesDir: File) {
        synchronized(lock) {
            if (healed != null) return
            val f = File(filesDir, STORE_FILE)
            storeFile = f
            // Review round: NO non-local return here - an early `return`
            // skipped `healed = map`, leaving the store uninitialized for
            // the process lifetime and silently dropping recorded heals.
            healed = runCatching {
                if (!f.exists()) return@runCatching mutableMapOf<String, String>()
                val root = JSONObject(f.readText())
                val out = mutableMapOf<String, String>()
                val keys = root.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    val v = root.optString(k, "")
                    if (v.isNotEmpty()) out[k] = v
                }
                out
            }.getOrDefault(mutableMapOf())
        }
    }

    fun healed(dir: String, file: String): String? =
        synchronized(lock) { healed?.get("$dir/$file") }

    /** Load-time overlay (TASK-986): healed pins REPLACE the catalog pin for
     *  the same dir/file, everything else passes through unchanged. */
    fun overlay(dirName: String, catalogPins: Map<String, String>): Map<String, String> =
        synchronized(lock) {
            val store = healed ?: return catalogPins
            val patched = catalogPins.toMutableMap()
            for ((key, sha) in store) {
                if (key.startsWith("$dirName/")) {
                    patched[key.substringAfter('/')] = sha
                }
            }
            patched
        }

    /** Test isolation: the store is process-wide and JVM tests share it.
     *  Forces a fresh store rooted at [filesDir] regardless of prior state. */
    internal fun resetForTest(filesDir: File) {
        synchronized(lock) {
            healed = null
            storeFile = File(filesDir, STORE_FILE)
        }
        init(filesDir)
    }

    fun recordHeal(dir: String, file: String, sha: String) {
        synchronized(lock) {
            val store = storeFile ?: return
            val map = healed ?: mutableMapOf()
            map["$dir/$file"] = sha
            healed = map
            runCatching {
                val tmp = File(store.parentFile, "$STORE_FILE.tmp")
                tmp.writeText(JSONObject(map).toString(2))
                if (!tmp.renameTo(store)) {
                    store.writeText(JSONObject(map).toString(2))
                    tmp.delete()
                }
            }
        }
    }
}
