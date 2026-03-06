package com.antivocale.app.util

/**
 * Utility class for extracting package names from Android ComponentInfo strings.
 *
 * This class is separated from BroadcastReceiver to allow unit testing
 * without Android framework dependencies.
 */
object ComponentInfoParser {

    /**
     * Extract package name from ComponentInfo string.
     *
     * Input format: "ComponentInfo{com.whatsapp/com.whatsapp.ShareActivity}"
     * Output: "com.whatsapp"
     *
     * Handles malformed input gracefully by returning null.
     *
     * @param componentInfo The ComponentInfo string from EXTRA_CHOSEN_COMPONENT
     * @return The package name, or null if extraction fails
     */
    fun extractPackageName(componentInfo: String): String? {
        // Validate input
        if (componentInfo.isBlank()) {
            return null
        }

        // Validate basic format
        if (!componentInfo.startsWith("ComponentInfo{")) {
            return null
        }

        // Remove "ComponentInfo{" prefix
        val withoutPrefix = componentInfo.substringAfter("ComponentInfo{", "")

        // Check if there's a slash (separating package from activity)
        if (!withoutPrefix.contains("/")) {
            return null
        }

        // Extract package name (everything before the first "/")
        val packageName = withoutPrefix.substringBefore("/").substringBefore("}")

        // Validate package name format (should contain at least one dot, no spaces)
        if (packageName.contains(".") && packageName.isNotBlank() && !packageName.contains(" ")) {
            return packageName
        }

        return null
    }
}
