package com.antivocale.app.util

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context

/**
 * TASK-688: the single owner of the plain-text clipboard write. Every copy
 * in the app (notification actions, auto-copy in both result paths, the UI
 * copy buttons) routes through [copy]; a cross-site fix (clip-label
 * conventions, Android 13+ overlay behavior) lands here once.
 *
 * Contract:
 * - Takes the FINAL text. Exit-surface policies that annotate it (the
 *   TASK-647/650 transcript signature) stay at their single owners; this
 *   helper is signature-agnostic.
 * - Adds no toast. On Android 13+ the system shows its own copy overlay;
 *   each call site keeps whatever confirmation it already shows.
 * - Synchronous and thread-agnostic: callers keep the thread discipline
 *   they already had (UI threads call it directly, the Dispatchers.IO
 *   auto-copy paths call it inline and post their toasts to main).
 */
object ClipboardWriter {

    /**
     * Sets the primary clip to plain [text], labeled [label] (the
     * [ClipData.newPlainText] argument order). NEVER THROWS (review F1/F2/F3):
     * the write is a binder call that can fail on a giant clip
     * (TransactionTooLargeException, the TASK-506 repetition-loop class) or a
     * dead service, and the eight call sites' invariants demand a no-throw
     * owner: the notification Copy button must never crash a tap, and both
     * auto-copy paths must not lose the result notification on a failed copy.
     */
    fun copy(context: Context, label: CharSequence, text: CharSequence) {
        runCatching {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText(label, text))
        }.onFailure { android.util.Log.w("ClipboardWriter", "clipboard write failed", it) }
    }
}
