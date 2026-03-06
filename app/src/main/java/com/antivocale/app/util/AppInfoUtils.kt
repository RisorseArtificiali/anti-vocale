package com.antivocale.app.util

import android.content.Context
import android.content.pm.PackageManager
import com.antivocale.app.R
import com.antivocale.app.data.PerAppPreferencesManager

/**
 * Utility for getting app information from package names.
 */
object AppInfoUtils {
    /**
     * Get the display name for an app package.
     *
     * @param context Application context
     * @param packageName Package name (e.g., "com.whatsapp")
     * @return Display name (e.g., "WhatsApp") or package name if not found
     */
    fun getAppName(context: Context, packageName: String?): String {
        if (packageName == null) return ""

        // Use common app names for better UX
        val commonNames = mapOf(
            PerAppPreferencesManager.WHATSAPP to "WhatsApp",
            PerAppPreferencesManager.TELEGRAM to "Telegram",
            PerAppPreferencesManager.SIGNAL to "Signal"
        )

        return commonNames[packageName] ?: try {
            val pm = context.packageManager
            val appInfo = pm.getApplicationInfo(packageName, 0)
            pm.getApplicationLabel(appInfo).toString()
        } catch (e: Exception) {
            packageName
        }
    }

    /**
     * Get the "Send to [App]" text for Share Back button.
     *
     * @param context Application context
     * @param packageName Package name
     * @return Localized string like "Send to WhatsApp"
     */
    fun getSendToText(context: Context, packageName: String?): String {
        if (packageName == null) return ""

        val appName = getAppName(context, packageName)
        return context.getString(R.string.send_to_app, appName)
    }
}
