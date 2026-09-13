package com.antivocale.app.ui

/**
 * TASK-506: the single cap every transcript-render surface shares (the
 * History card blocks and the PiP live view). A monster transcript
 * (repetition-loop class) measured at full intrinsic height exceeds
 * Compose's representable Constraints (262142px) and bricks the list;
 * the copy/share actions still carry the FULL text.
 */
internal const val MAX_RENDERED_TRANSCRIPT_CHARS = 30_000
