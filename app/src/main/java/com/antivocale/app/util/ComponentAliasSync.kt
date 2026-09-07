package com.antivocale.app.util

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log

/**
 * Single write path for manifest component-alias toggling: the
 * setComponentEnabledSetting + DONT_KILL_APP + log-on-failure block shared by
 * [com.antivocale.app.data.ShareTargetManager] (share targets) and
 * [com.antivocale.app.ui.appearance.LauncherIconManager] (launcher variants).
 * DONT_KILL_APP because aliases are switched while the app runs; the write is
 * a binder call, safe (and preferable) off the main thread. A failure is
 * logged and swallowed: alias sync is idempotent and the next sync rewrites
 * the drifted state.
 */
object ComponentAliasSync {

    fun setEnabled(context: Context, className: String, enabled: Boolean, tag: String) {
        val state = if (enabled)
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED
        else
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        try {
            context.packageManager.setComponentEnabledSetting(
                ComponentName(context, className),
                state,
                PackageManager.DONT_KILL_APP,
            )
        } catch (e: Exception) {
            Log.e(tag, "Failed to sync $className", e)
        }
    }
}
