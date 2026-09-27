package com.antivocale.app.util

/**
 * TASK-275: the copyable automation command behind the Settings > Advanced
 * "Automation guide" card. It mirrors the PROCESS_REQUEST text request from
 * docs/TASKER_GUIDE.md verbatim (the test pins the two together by reading
 * the guide from disk), with the broadcast component's package filled from
 * the runtime application id so a debug build (com.antivocale.app.debug,
 * the applicationIdSuffix) copies a command that actually targets it. The
 * action string is a manifest literal and never changes with the suffix.
 *
 * The Tasker profile XML export (.prf.xml) was evaluated for the same card
 * and deliberately NOT shipped (the task's recorded decision): Tasker's
 * arg-level XML schema is undocumented and version-drifted (the community
 * reference covers only the envelope and the numeric action codes, not the
 * positional arg0..arg9 of a Send Intent), so a hand-authored file imports
 * cleanly but can silently misconfigure the intent; and the minimal
 * "File Modified in the WhatsApp dir -> Send Intent" profile cannot work
 * without root anyway, because PROCESS_REQUEST rejects any file_path
 * outside the app's private staging dir. The explainer card plus this
 * snippet is the v1 deliverable.
 */
object AutomationBroadcastSnippet {

    const val TASKER_GUIDE_URL =
        "${FeedbackHelper.SOURCE_CODE_URL}/blob/main/docs/TASKER_GUIDE.md"

    // Simplify edge F2: the receiver's own const and class name, not hand
    // copies (a drifted copy compiles clean and the broadcast no-ops).
    private val RECEIVER_CLASS get() = com.antivocale.app.receiver.TaskerRequestReceiver::class.java.name
    private val REQUEST_ACTION get() = com.antivocale.app.receiver.TaskerRequestReceiver.ACTION_PROCESS_REQUEST

    /** The guide's ADB text request, with the component package filled in. */
    fun adbTextRequest(applicationId: String): String = listOf(
        "adb shell am broadcast \\",
        "  -n $applicationId/$RECEIVER_CLASS \\",
        "  -a $REQUEST_ACTION \\",
        "  --es request_type \"text\" \\",
        "  --es task_id \"test_001\" \\",
        "  --es prompt \"Hello, how are you?\"",
    ).joinToString("\n")
}
