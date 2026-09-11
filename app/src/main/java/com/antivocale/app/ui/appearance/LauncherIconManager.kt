package com.antivocale.app.ui.appearance

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import com.antivocale.app.R
import com.antivocale.app.util.ComponentAliasSync
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Launcher-icon variants: Default plus derei's six concepts (GH #61,
 * TASK-473), each a launcher activity-alias in the manifest. The recolor
 * set (TASK-392's Teal/Ink/Amber) was retired with the TASK-473 rework:
 * a user still pinned to a removed alias is healed back to Default at
 * startup ([LauncherIconManager.healIfNoAliasEnabled]); the read path
 * alone would show Default in Settings while the launcher showed nothing. The derei concepts
 * are glyph-only raster foreground layers ([foregroundRes], WebP re-exported
 * from the proposal tiles, maintainer decision 2026-09-11 favoring fidelity
 * to the shared artwork over the earlier vector redraws) over the shared
 * gradient drawable; the monochrome layer reuses the foreground's alpha, as
 * Default does; Default keeps the original color-plus-foreground adaptive
 * icon. The alias component name must keep
 * the Launcher<Variant> pattern, pinned by tests.
 *
 * The alias component names are a pinned contract mirrored by the
 * manifest activity-alias literals (LauncherIconManifestTest) and by
 * [com.antivocale.app.data.ShareTargetManager]'s separate share-target alias set;
 * the two sets must never overlap.
 */
enum class LauncherIconVariant(
    val aliasComponentName: String,
    @StringRes val nameRes: Int,
    @DrawableRes val foregroundRes: Int? = null,
) {
    DEFAULT(
        aliasComponentName = "com.antivocale.app.LauncherDefault",
        nameRes = R.string.app_icon_variant_default,
    ),
    WAVECUT(
        aliasComponentName = "com.antivocale.app.LauncherWavecut",
        nameRes = R.string.app_icon_variant_wavecut,
        foregroundRes = R.mipmap.ic_launcher_fg_derei_wavecut,
    ),
    CROSSED(
        aliasComponentName = "com.antivocale.app.LauncherCrossed",
        nameRes = R.string.app_icon_variant_crossed,
        foregroundRes = R.mipmap.ic_launcher_fg_derei_crossed,
    ),
    TEXTBLOCK(
        aliasComponentName = "com.antivocale.app.LauncherTextblock",
        nameRes = R.string.app_icon_variant_textblock,
        foregroundRes = R.mipmap.ic_launcher_fg_derei_textblock,
    ),
    MONOGRAM(
        aliasComponentName = "com.antivocale.app.LauncherMonogram",
        nameRes = R.string.app_icon_variant_monogram,
        foregroundRes = R.mipmap.ic_launcher_fg_derei_monogram,
    ),
    CAPSULE(
        aliasComponentName = "com.antivocale.app.LauncherCapsule",
        nameRes = R.string.app_icon_variant_capsule,
        foregroundRes = R.mipmap.ic_launcher_fg_derei_capsule,
    ),
    MUTEBAR(
        aliasComponentName = "com.antivocale.app.LauncherMutebar",
        nameRes = R.string.app_icon_variant_mutebar,
        foregroundRes = R.mipmap.ic_launcher_fg_derei_mutebar,
    ),
}

/**
 * Switches the launcher-icon aliases via [PackageManager.setComponentEnabledSetting]
 * (writes go through [ComponentAliasSync]), the same mechanism
 * [com.antivocale.app.data.ShareTargetManager] uses for share targets. The
 * PackageManager component state is the single source of truth for the current
 * variant: no preference is kept, because the component state already survives
 * app updates (an update re-reads the persisted per-component overrides as long
 * as the aliases still exist in the new manifest).
 */
@Singleton
class LauncherIconManager @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    companion object {
        private const val TAG = "LauncherIconManager"
    }

    /**
     * Current variant derived from component state. COMPONENT_ENABLED_STATE_DEFAULT
     * means the manifest-declared state (enabled for Default, disabled for the
     * concept aliases), which is exactly the fresh-install condition. If nothing is
     * enabled (unknown state), Default wins; if more than one is enabled (drift),
     * the first in canonical order wins deterministically.
     */
    fun current(): LauncherIconVariant =
        LauncherIconVariant.entries.firstOrNull(::isEnabled) ?: LauncherIconVariant.DEFAULT

    /**
     * [current] as the alias component, for consumers that anchor other
     * surfaces to the enabled launcher activity (the dynamic share
     * shortcuts): they must not re-derive the alias-componentName contract.
     */
    fun currentComponentName(): ComponentName = current().componentName()

    /**
     * Startup safety net for retired variants (TASK-473): a user who had a
     * removed alias selected also carries an explicit DISABLED write for
     * Default (select() always disabled the others), so after the update NO
     * alias is enabled and the app would vanish from the launcher. When
     * nothing is effectively enabled, re-select Default. Free when not needed:
     * a converged re-select performs zero writes.
     */
    fun healIfNoAliasEnabled() {
        if (LauncherIconVariant.entries.none(::isEnabled)) select(LauncherIconVariant.DEFAULT)
    }

    /**
     * Enables the chosen alias and disables every other one; self-heals drift.
     * Enable-first ordering: the target is enabled before any other alias is
     * disabled, so every intermediate state keeps at least one enabled launcher
     * alias and the app never disappears from the home screen mid-sequence
     * (worst case two are briefly enabled; [current] resolves that drift
     * deterministically). Only aliases whose effective state differs from the
     * desired one are written, so converging re-selects perform no IPC at all.
     */
    fun select(variant: LauncherIconVariant) {
        if (!isEnabled(variant)) {
            setAliasEnabled(variant, enabled = true)
        }
        LauncherIconVariant.entries
            .filter { it != variant }
            .forEach { if (isEnabled(it)) setAliasEnabled(it, enabled = false) }
    }

    private fun isEnabled(variant: LauncherIconVariant): Boolean =
        when (context.packageManager.getComponentEnabledSetting(variant.componentName())) {
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED -> true
            PackageManager.COMPONENT_ENABLED_STATE_DEFAULT -> variant == LauncherIconVariant.DEFAULT
            else -> false
        }

    private fun setAliasEnabled(variant: LauncherIconVariant, enabled: Boolean) {
        ComponentAliasSync.setEnabled(context, variant.aliasComponentName, enabled, TAG)
    }

    private fun LauncherIconVariant.componentName(): ComponentName =
        ComponentName(context, aliasComponentName)
}
