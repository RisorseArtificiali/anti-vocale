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
import com.antivocale.app.receiver.ModelShortcutActivity
import com.antivocale.app.receiver.ShareReceiverActivity
import com.antivocale.app.transcription.BackendDescriptor
import com.antivocale.app.transcription.BackendRegistry
import com.antivocale.app.transcription.variantAwareDisplayName
import com.antivocale.app.ui.appearance.LauncherIconManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
    private val launcherIconManager: LauncherIconManager,
    private val shortcutIconStore: ShortcutIconStore,
    private val recentUsage: suspend () -> List<RecentModelUse>,
) {
    companion object {
        private const val TAG = "ShareShortcutManager"

        /** Stable per-backend shortcut id; renaming one drops the launcher's persisted entry. */
        private const val SHORTCUT_ID_PREFIX = "share-"

        /** TASK-552: the switch entries' id prefix (a distinct stable namespace). */
        private const val SHORTCUT_ID_SWITCH_PREFIX = "model-"

        // Launcher dynamic-shortcut budgets are 4-5; 3 keeps every launcher under
        // its cap (the shade-cap lesson: less is more on crowded surfaces).
        /** TASK-393: share entries (the "Transcribe with" shortcuts). */
        private const val MAX_SHARE_SHORTCUTS = 3

        /** TASK-552: model-switch entries (the "switch to" shortcuts). */
        private const val MAX_MODEL_SHORTCUTS = 2

        /** TASK-552: the static shortcuts sharing the same long-press menu.
         *  CONSCIOUS DESIGN (range review): shares take the budget FIRST,
         *  switch entries get the remainder - with 2+ recent share-eligible
         *  models on a cap-4 launcher the switch entries starve to zero.
         *  Share-first is the deliberate priority (the share shortcuts are
         *  the older, higher-traffic surface); revisit only on evidence the
         *  switch entries are being missed. */
        private const val STATIC_SHORTCUT_COUNT = 2
    }

    private val shortcutManager: ShortcutManager? =
        context.getSystemService(ShortcutManager::class.java)

    /**
     * The anchor alias plus the (id, label, rank) triples of the last set the
     * manager successfully pushed. A refresh deriving the same value is a
     * no-op: identical anchor, ids, labels and ranks mean the launcher state
     * already matches, so the icon rasterization and the
     * [ShortcutManager.setDynamicShortcuts] IPC are skipped. The anchor is
     * part of the signature because a launcher-icon switch changes WHERE the
     * set must live even when the candidates are unchanged (2026-09-09
     * trial: the old signature skipped the republish and the enabled alias
     * kept an empty long-press menu). Only set after a successful push, so a
     * failed sync retries.
     */
    private data class ShortcutSetSignature(
        val anchorAlias: String,
        val shortcuts: List<Triple<String, String, Int>>,
        // TASK-490 review: the stored-icon state must be part of the
        // no-op signature, or a pick/reset republishes nothing and the
        // launcher keeps the stale icon until an unrelated signature
        // change. lastModified + length catch a re-pick over the same
        // backend, not just the pick's appearance.
        val iconTokens: List<String>,
    )

    private var lastSignature: ShortcutSetSignature? = null

    /** Serializes [refresh]'s derive-and-push across its eight call sites. */
    private val refreshMutex = Mutex()

    /**
     * One shortcut candidate after the eligibility checks, before the icon is
     * built: everything the signature compares plus what [buildShortcut] needs.
     */
    private data class ResolvedShortcut(
        val id: String,
        val label: String,
        val rank: Int,
        val descriptor: BackendDescriptor,
        /** TASK-552: a switch entry (the app-icon "make active" shortcut),
         *  not a share target: the intent targets the trampoline. */
        val switchOnly: Boolean = false,
    )

    /**
     * Re-derives the whole dynamic-shortcut set from current state. Called at
     * cold start, on foreground, after every completed transcription (the
     * recency source moves), and wherever model deletions/downloads change the
     * eligible set. [ShortcutManager.setDynamicShortcuts] replaces the previous
     * set atomically, so stale ids (deleted models, backends falling out of the
     * top [MAX_SHARE_SHORTCUTS]) disappear without a separate removal pass.
     * Shortcut sync is metadata-only: a failure is logged, never thrown.
     */
    suspend fun refresh() = withContext(Dispatchers.Default) {
        val manager = shortcutManager ?: return@withContext
        // Serialized on purpose: refresh has eight independent call sites, and
        // a refresh that read the anchor before a variant switch landing after
        // the switch's own push would re-anchor the set onto the now-disabled
        // alias, reproducing the empty-menu bug this class exists to prevent.
        // The signature check keeps the contended path cheap.
        refreshMutex.withLock {
            // The whole derivation is contained, not just the write: call sites run
            // on scopes without a CoroutineExceptionHandler (viewModelScope,
            // lifecycleScope), and a malformed label would throw from
            // ShortcutInfo.Builder before any IPC.
            runCatching {
                // TASK-552: the launcher alias anchors ONE dynamic set, so
                // share and switch entries are built together. The SHARE
                // half follows the advanced-sharing toggle; the SWITCH half
                // does not (it opens no share surface - losing it to that
                // toggle would silently disappear the feature). The dynamic
                // budget leaves room for the STATIC shortcuts sharing the
                // same long-press menu (maxShortcutCountPerActivity covers
                // static + dynamic shown entries).
                val dynamicBudget = (
                    manager.maxShortcutCountPerActivity - STATIC_SHORTCUT_COUNT
                    ).coerceAtLeast(0)
                val ranked = rankRecentBackends(recentUsage(), dynamicBudget)
                val shareCandidates = if (preferencesManager.advancedSharingEnabled.first()) {
                    ranked.take(minOf(MAX_SHARE_SHORTCUTS, dynamicBudget))
                        .mapIndexedNotNull { rank, backendId -> resolveCandidate(backendId, rank, switchOnly = false) }
                } else {
                    emptyList()
                }
                val shareIds = shareCandidates.map { it.descriptor.backendId }.toSet()
                // The remaining budget scans the WHOLE ranked list minus the
                // published share ids: a backend dropped from share candidacy
                // (its dir vanished) is still offered as a switch entry,
                // where the trampoline reports the failure loudly.
                val modelCandidates = ranked.filter { it !in shareIds }
                    .take(minOf(MAX_MODEL_SHORTCUTS, (dynamicBudget - shareCandidates.size).coerceAtLeast(0)))
                    .mapIndexedNotNull { i, backendId ->
                        resolveCandidate(backendId, shareCandidates.size + i, switchOnly = true)
                    }
                val candidates = shareCandidates + modelCandidates
                // The anchor is the ENABLED launcher alias: a shortcut is visible
                // only on the activity the launcher resolved, and the icon-variant
                // switcher enables a different alias component (TASK-392/473).
                // Anchoring to a fixed component orphaned the set after a variant
                // switch: the enabled alias had no shortcuts and the long-press
                // menu came up empty (device trial 2026-09-09). Ids are unique per
                // app, so the set is published once, on the current alias.
                val anchor = launcherIconManager.currentComponentName()
                val signature = ShortcutSetSignature(
                    anchorAlias = anchor.className,
                    shortcuts = candidates.map { Triple(it.id, it.label, it.rank) },
                    iconTokens = candidates.mapNotNull { c ->
                        shortcutIconStore.iconFile(c.descriptor.backendId)
                            ?.let { "${c.id}:${it.lastModified()}:${it.length()}" }
                    },
                )
                if (signature == lastSignature) return@runCatching
                manager.setDynamicShortcuts(candidates.map { buildShortcut(it, anchor) })
                lastSignature = signature
            }.onFailure { Log.w(TAG, "Dynamic share-shortcut sync failed", it) }
        }
    }

    /**
     * Resolves one shortcut candidate (share or switch), or null when the
     * backend must not get one: no registered descriptor (a stale usage row).
     * The SHARE eligibility additionally needs a share alias (external models
     * deliberately carry blank aliases; they share through the family chooser)
     * and a saved model path (the share tap dead-ends in the SAF picker with
     * no recovery). The SWITCH eligibility needs neither: switching a model
     * whose dir vanished fails loudly through the trampoline's notification,
     * never silently, and externals switch fine (the asymmetry is the
     * contract). The label is the variant-aware display name either way.
     */
    private suspend fun resolveCandidate(backendId: String, rank: Int, switchOnly: Boolean): ResolvedShortcut? {
        val descriptor = backendRegistry.byBackendId(backendId) ?: return null
        val modelPath = descriptor.modelPathFlow(preferencesManager).first()
        if (!switchOnly) {
            if (descriptor.shareAlias.isBlank()) return null
            if (modelPath.isNullOrBlank()) return null
        }
        return ResolvedShortcut(
            id = (if (switchOnly) SHORTCUT_ID_SWITCH_PREFIX else SHORTCUT_ID_PREFIX) + backendId,
            label = variantAwareDisplayName(context, descriptor, modelPath).ifBlank { backendId },
            rank = rank,
            descriptor = descriptor,
            switchOnly = switchOnly,
        )
    }

    private fun buildShortcut(resolved: ResolvedShortcut, anchor: ComponentName): ShortcutInfo {
        // Exactly the static alias flow: an explicit ACTION_SEND to the alias
        // component, which ShareReceiverActivity resolves back to this backend
        // via its intent component (no parallel backend-override contract). The
        // shortcut marker routes the (stream-less) tap into the SAF audio
        // picker instead of the "no audio" error path. A SWITCH entry
        // (TASK-552) instead targets the no-UI trampoline that activates the
        // model and confirms by notification.
        val intent = if (resolved.switchOnly) {
            // ShortcutInfo demands an action on every intent; the explicit
            // component is what actually routes it.
            Intent(Intent.ACTION_VIEW, null, context, ModelShortcutActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                putExtra(ModelShortcutActivity.EXTRA_BACKEND_ID, resolved.descriptor.backendId)
            }
        } else {
            Intent(Intent.ACTION_SEND).apply {
                component = ComponentName(context, resolved.descriptor.shareAlias)
                type = "audio/*"
                putExtra(ShareReceiverActivity.EXTRA_FROM_SHORTCUT, true)
            }
        }
        val color = ContextCompat.getColor(context, resolved.descriptor.accentColorRes)
        // TASK-490: the user's pick wins; an absent/undecodable file falls
        // back to the generated family icon (the push must never fail on a
        // broken pick). Decoding is off the main thread: refresh() shifts to
        // Dispatchers.Default before it gets here.
        val icon = shortcutIconStore.decode(resolved.descriptor.backendId)
            ?.let { ShareShortcutIcons.createAdaptiveIcon(it) }
            ?: ShareShortcutIcons.createAdaptiveIcon(resolved.label, color)
        return ShortcutInfo.Builder(context, resolved.id)
            .setActivity(anchor)
            .setShortLabel(resolved.label)
            .setLongLabel(
                if (resolved.switchOnly) {
                    // The switch entries say what they do; the share label
                    // would promise a picker that never opens.
                    context.getString(R.string.model_shortcut_switch_label, resolved.label)
                } else {
                    context.getString(R.string.share_shortcut_transcribe_with, resolved.label)
                })
            .setIcon(Icon.createWithAdaptiveBitmap(icon))
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

    /**
     * TASK-490: the user-picked image masked into the same adaptive canvas.
     * Aspect-preserving CENTER-CROP (the smaller dimension is scaled up to
     * cover the canvas, the overflow trimmed symmetrically): no distortion,
     * and the subject stays centered through the launcher's crop and
     * parallax, the same geometry discipline as the launcher icon assets.
     * The source is consumed read-only and never recycled here (the caller
     * owns its lifecycle).
     */
    fun createAdaptiveIcon(source: Bitmap): Bitmap {
        val scale = maxOf(
            CANVAS_SIZE_PX.toFloat() / source.width,
            CANVAS_SIZE_PX.toFloat() / source.height,
        )
        val scaledWidth = (source.width * scale).toInt().coerceAtLeast(CANVAS_SIZE_PX)
        val scaledHeight = (source.height * scale).toInt().coerceAtLeast(CANVAS_SIZE_PX)
        val scaled = Bitmap.createScaledBitmap(source, scaledWidth, scaledHeight, true)
        val left = (scaledWidth - CANVAS_SIZE_PX) / 2
        val top = (scaledHeight - CANVAS_SIZE_PX) / 2
        val cropped = Bitmap.createBitmap(scaled, left, top, CANVAS_SIZE_PX, CANVAS_SIZE_PX)
        if (cropped !== scaled) scaled.recycle()
        return cropped
    }
}
