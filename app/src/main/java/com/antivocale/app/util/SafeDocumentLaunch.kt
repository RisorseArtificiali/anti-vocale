package com.antivocale.app.util

import android.content.ActivityNotFoundException
import android.content.Context
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import android.util.Log
import com.antivocale.app.R

/**
 * TASK-1052: the shared guard for system-picker launches that can resolve
 * to nothing. On the device class without a documents UI (the Honor
 * BVL-N49 report: two separate Crashlytics fatals from one user in 24h,
 * the export-folder tree picker and the History browse FAB), the
 * ACTION_OPEN_DOCUMENT* family resolves to nothing and a bare
 * [ActivityResultLauncher.launch] crashes the app with
 * [ActivityNotFoundException]. The catch is the canonical defense: a
 * PackageManager pre-check is unreliable under API 30+ package visibility
 * without a `<queries>` declaration.
 *
 * Eleven sites ride this helper: the ten documents-UI launches
 * (OpenDocumentTree, OpenMultipleDocuments, OpenDocument, CreateDocument
 * across SettingsTab, ModelTab, HistoryTab, AutomationSettingsScreen) and
 * the shortcut-icon photo picker (PickVisualMedia rides here too: in the
 * PINNED androidx.activity its fallback chain ends on ACTION_OPEN_DOCUMENT,
 * so a no-documents-UI device throws the same way; code-review finding).
 * The flow-owner site in ShareReceiverActivity catches LOCALLY instead:
 * its endFlow slot must be accounted the moment the launch fails, which
 * the toast-and-return contract here cannot do.
 */
fun <I> launchDocumentPick(context: Context, launcher: ActivityResultLauncher<I>, input: I) {
    try {
        launcher.launch(input)
    } catch (e: ActivityNotFoundException) {
        Log.w("SafeDocumentLaunch", "No documents UI on this device; the picker cannot open", e)
        ToastCompat.show(context, R.string.error_no_document_picker, Toast.LENGTH_LONG)
    }
}
