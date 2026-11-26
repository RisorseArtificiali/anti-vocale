package com.antivocale.app.ui

/**
 * TASK-506 / TASK-1034: the shared transcript-render sizing constants.
 *
 * MAX_RENDERED_TRANSCRIPT_CHARS is the PANEL THRESHOLD for the History
 * surfaces, not a truncation: below it a card renders full height (the
 * short branch's own single node keeps its historical behavior); above
 * it the card renders the bounded, scrollable panel (position indicator,
 * reading-progress line). Since TASK-1034 the panel renders EVERY
 * character. The SAME constant is also a live render cap inside
 * PipTranscriptionView, scaled by fontScale and the app text step: a
 * different consumer, unchanged meaning there.
 *
 * MAX_RENDER_BLOCK_CHARS is the per-block CHAR ceiling of the panel
 * split, and MAX_RENDER_BLOCK_LINES the per-block LINE ceiling: the
 * 262142px Constraints limit the TASK-506 class breaches is LINE-count
 * driven (a newline-dense repetition loop), so the char bound alone is
 * not sufficient safety (code-review round).
 */
internal const val MAX_RENDERED_TRANSCRIPT_CHARS = 30_000
internal const val MAX_RENDER_BLOCK_CHARS = 4_000
internal const val MAX_RENDER_BLOCK_LINES = 600
