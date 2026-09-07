package com.antivocale.app.data

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.Icon
import android.util.Log
import androidx.core.content.ContextCompat
import com.antivocale.app.R
import com.antivocale.app.receiver.ShareReceiverActivity
import com.antivocale.app.transcription.BackendDescriptor
import com.antivocale.app.transcription.BackendRegistry
import com.antivocale.app.transcription.variantAwareDisplayName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/** One usage observation from the model-recency source: a backend id and when it last ran. */
data class RecentModelUse(val backendId: String, val lastUsedAtMillis: Long)

/**
 * Orders backends by most recent use, one entry per distinct backend id (a
 * backend keeps the newest timestamp across its model variants) and capped at
 * [limit]. Pure so the ordering contract is testable without Android.
 * Tie-break is input order (groupBy + sortedByDescending are both stable), so
 * callers with a stable source get a deterministic result.
 */
internal fun rankRecentBackends(usage: List<RecentModelUse>, limit: Int): List<String> =
    usage
        .groupBy(RecentModelUse::backendId)
        .map { (backendId, uses) -> backendId to uses.maxOf(RecentModelUse::lastUsedAtMillis) }
        .sortedByDescending { (_, lastUsedAt) -> lastUsedAt }
        .take(limit)
        .map { (backendId, _) -> backendId }

/**
 * Keeps the launcher's dynamic long-press shortcuts ("Transcribe with &lt;model&gt;",
 * TASK-393 / GH #87) in sync with model usage. Dynamic shortcuts accept runtime
 * bitmaps, the one icon surface that is not baked into the APK, so each shortcut
 * carries a generated per-model icon ([ShareShortcutIcons]).
 *
 * Eligibility mirrors [ShareTargetManager]'s alias predicate (advanced sharing on
 * AND a saved model path) because the shortcut intent launches the same alias
 * component: tapping a shortcut whose alias ShareTargetManager disabled would
 * dead-end. The two classes stay separate on purpose: the component sync and
 * the shortcut set are different surfaces with different triggers.
 *
 * [refresh] shifts itself to [Dispatchers.Default] (the icon rasterization stays
 * off the main thread), so callers only need any coroutine scope; the dispatcher
 * contract lives here, not per call site.
 */
class ShareShortcutManager(
    private val context: Context,
    private val preferencesManager: PreferencesManager,
    private val backendRegistry: BackendRegistry,
    private val recentUsage: suspend () -> List<RecentModelUse>,
) {
    companion object {
        private const val TAG = "ShareShortcutManager"

        /** Stable per-backend shortcut id; renaming one drops the launcher's persisted entry. */
        private const val SHORTCUT_ID_PREFIX = "share-"

        // Launcher dynamic-shortcut budgets are 4-5; 3 keeps every launcher under
        // its cap (the shade-cap lesson: less is more on crowded surfaces).
        private const val MAX_SHORTCUTS = 3
    }

    private val shortcutManager: ShortcutManager? =
        context.getSystemService(ShortcutManager::class.java)

    /**
     * The (id, label, rank) triples of the last set the manager successfully
     * pushed. A refresh deriving the same triples is a no-op: identical ids,
     * labels and ranks mean the launcher state already matches, so the icon
     * rasterization and the [ShortcutManager.setDynamicShortcuts] IPC are
     * skipped. Only set after a successful push, so a failed sync retries.
     */
    private var lastSignature: List<Triple<String, String, Int>>? = null

    /**
     * One shortcut candidate after the eligibility checks, before the icon is
     * built: everything the signature compares plus what [buildShortcut] needs.
     */
    private data class ResolvedShortcut(
        val id: String,
        val label: String,
        val rank: Int,
        val descriptor: BackendDescriptor,
    )

    /**
     * Re-derives the whole dynamic-shortcut set from current state. Called at
     * cold start, on foreground, after every completed transcription (the
     * recency source moves), and wherever model deletions/downloads change the
     * eligible set. [ShortcutManager.setDynamicShortcuts] replaces the previous
     * set atomically, so stale ids (deleted models, backends falling out of the
     * top [MAX_SHORTCUTS]) disappear without a separate removal pass.
     * Shortcut sync is metadata-only: a failure is logged, never thrown.
     */
    suspend fun refresh() = withContext(Dispatchers.Default) {
        val manager = shortcutManager ?: return@withContext
        // The whole derivation is contained, not just the write: call sites run
        // on scopes without a CoroutineExceptionHandler (viewModelScope,
        // lifecycleScope), and a malformed label would throw from
        // ShortcutInfo.Builder before any IPC.
        runCatching {
            val candidates = if (!preferencesManager.advancedSharingEnabled.first()) {
                emptyList()
            } else {
                val cap = minOf(MAX_SHORTCUTS, manager.maxShortcutCountPerActivity)
                rankRecentBackends(recentUsage(), cap)
                    .mapIndexedNotNull { rank, backendId -> resolveShortcut(backendId, rank) }
            }
            val signature = candidates.map { Triple(it.id, it.label, it.rank) }
            if (signature == lastSignature) return@runCatching
            manager.setDynamicShortcuts(candidates.map(::buildShortcut))
            lastSignature = signature
        }.onFailure { Log.w(TAG, "Dynamic share-shortcut sync failed", it) }
    }

    /**
     * Resolves one shortcut candidate, or null when the backend must not get
     * one: no registered descriptor (stale usage row), no share alias (external
     * models deliberately carry blank aliases; they share through the family
     * chooser), or no saved model path (model deleted since it was last used).
     */
    private suspend fun resolveShortcut(backendId: String, rank: Int): ResolvedShortcut? {
        val descriptor = backendRegistry.byBackendId(backendId) ?: return null
        if (descriptor.shareAlias.isBlank()) return null
        val modelPath = descriptor.modelPathFlow(preferencesManager).first()
        if (modelPath.isNullOrBlank()) return null

        return ResolvedShortcut(
            id = SHORTCUT_ID_PREFIX + backendId,
            label = variantAwareDisplayName(context, descriptor, modelPath),
            rank = rank,
            descriptor = descriptor,
        )
    }

    private fun buildShortcut(resolved: ResolvedShortcut): ShortcutInfo {
        // Exactly the static alias flow: an explicit ACTION_SEND to the alias
        // component, which ShareReceiverActivity resolves back to this backend
        // via its intent component (no parallel backend-override contract). The
        // shortcut marker routes the (stream-less) tap into the SAF audio
        // picker instead of the "no audio" error path.
        val intent = Intent(Intent.ACTION_SEND).apply {
            component = ComponentName(context, resolved.descriptor.shareAlias)
            type = "audio/*"
            putExtra(ShareReceiverActivity.EXTRA_FROM_SHORTCUT, true)
        }
        val color = ContextCompat.getColor(context, resolved.descriptor.accentColorRes)
        return ShortcutInfo.Builder(context, resolved.id)
            .setShortLabel(resolved.label)
            .setLongLabel(context.getString(R.string.share_shortcut_transcribe_with, resolved.label))
            .setIcon(Icon.createWithAdaptiveBitmap(ShareShortcutIcons.createAdaptiveIcon(resolved.label, color)))
            .setIntent(intent)
            .setRank(resolved.rank)
            .build()
    }
}

/**
 * Generates the per-model shortcut icons: the backend's family color filling an
 * adaptive-icon canvas with the model initial centered in the safe zone.
 * Runtime bitmaps are legal for ShortcutManager icons (unlike launcher icons,
 * which the platform keeps curated-only); the launcher masks the canvas to its
 * own shape, so no circle needs drawing here.
 *
 * Pure (label + color in, Bitmap out) so the raster contract is unit-testable;
 * [com.antivocale.app.data.ShareShortcutManager] owns when and where it runs.
 */
internal object ShareShortcutIcons {

    /** 108dp adaptive-icon canvas rendered at 3x density; launchers scale as needed. */
    internal const val CANVAS_SIZE_PX = 324

    // Adaptive icons may crop or parallax up to 18dp per side, so the glyph
    // stays inside the inner 72dp safe zone (72/108 of the canvas).
    private const val SAFE_ZONE_FRACTION = 72f / 108f
    private const val TEXT_HEIGHT_FRACTION = 0.62f

    fun createAdaptiveIcon(modelLabel: String, backgroundColor: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(CANVAS_SIZE_PX, CANVAS_SIZE_PX, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(backgroundColor)
        val initial = initialFor(modelLabel) ?: return bitmap
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textAlign = Paint.Align.CENTER
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textSize = CANVAS_SIZE_PX * SAFE_ZONE_FRACTION * TEXT_HEIGHT_FRACTION
        }
        val center = CANVAS_SIZE_PX / 2f
        // The ascent/descent idiom centers the glyph's visual box on the canvas
        // center; a plain drawText would hang it on the baseline.
        canvas.drawText(
            initial, center, center - (paint.ascent() + paint.descent()) / 2f, paint)
        return bitmap
    }

    /** First letter of the label, uppercase; null when there is none (the icon stays the plain disc). */
    internal fun initialFor(label: String): String? =
        label.firstOrNull { it.isLetter() }?.uppercaseChar()?.toString()
}
