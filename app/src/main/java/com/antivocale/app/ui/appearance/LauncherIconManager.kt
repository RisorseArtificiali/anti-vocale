package com.antivocale.app.ui.appearance

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import androidx.annotation.ColorRes
import androidx.annotation.StringRes
import com.antivocale.app.R
import com.antivocale.app.util.ComponentAliasSync
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Curated launcher-icon variants (TASK-392, GH #86), Telegram-style. Each variant
 * is a launcher activity-alias in the manifest and differs only by its adaptive-icon
 * background color; foreground and monochrome layers are shared with the default
 * icon. v2 artwork (derei's GH #61 proposals) slots in as new foreground assets plus
 * one adaptive XML per variant, with no change to this class beyond a new entry
 * (alias component name must keep the Launcher<Variant> pattern, pinned by tests).
 *
 * The alias component names are a pinned contract mirrored by the
 * manifest activity-alias literals (LauncherIconManifestTest) and by
 * [com.antivocale.app.data.ShareTargetManager]'s separate share-target alias set;
 * the two sets must never overlap.
 */
enum class LauncherIconVariant(
    val aliasComponentName: String,
    @ColorRes val backgroundRes: Int,
    @StringRes val nameRes: Int,
) {
    DEFAULT(
        aliasComponentName = "com.antivocale.app.LauncherDefault",
        backgroundRes = R.color.launcher_icon_default,
        nameRes = R.string.app_icon_variant_default,
    ),
    TEAL(
        aliasComponentName = "com.antivocale.app.LauncherTeal",
        backgroundRes = R.color.launcher_icon_teal,
        nameRes = R.string.app_icon_variant_teal,
    ),
    INK(
        aliasComponentName = "com.antivocale.app.LauncherInk",
        backgroundRes = R.color.launcher_icon_ink,
        nameRes = R.string.app_icon_variant_ink,
    ),
    AMBER(
        aliasComponentName = "com.antivocale.app.LauncherAmber",
        backgroundRes = R.color.launcher_icon_amber,
        nameRes = R.string.app_icon_variant_amber,
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
     * recolors), which is exactly the fresh-install condition. If nothing is
     * enabled (unknown state), Default wins; if more than one is enabled (drift),
     * the first in canonical order wins deterministically.
     */
    fun current(): LauncherIconVariant =
        LauncherIconVariant.entries.firstOrNull(::isEnabled) ?: LauncherIconVariant.DEFAULT

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
