# Transcript editing in History: design exploration (TASK-653, android-hilfe request)

Date: 2026-09-25. Origin: the first campaign review (android-hilfe, forwarded by the maintainer) asked for an edit function on the result text so small ASR errors can be corrected. This note records the code-verified facts and the recommended first slice; it is the exploration deliverable, not the implementation.

## Verified facts (every claim with its source)

1. SEARCH re-indexes for free: History search is a query-time SQL LIKE over `result` (LogDao.searchAll, app/src/main/java/com/antivocale/app/data/local/LogDao.kt:59). Whatever text the row displays is what search finds; an edit needs no index work.
2. TIMED EXPORTS ARE THE CRUX: TXT export, copy, and share flow from the transcript string; SRT and VTT build from the persisted `segments` (TimedSegment cues), not from the text (TranscriptFileSaver.saveAuto passes transcript and segments separately; SubtitleFormatter.resolveExport composes timed formats from the cues). Editing the text cannot re-map onto the original ASR timing: cues carry the decode's own wording.
3. PROVENANCE has a precedent: the row already keeps `rawTranscript` (the pre-punctuation original, schema v5, the TASK-276 pattern). A `editedResult: String?` column (null = never edited) mirrors that pattern exactly; displayed text = editedResult ?: result.
4. DELIVERY-TIME SURFACES are unaffected: the Tasker reply (EXTRA_RESULT_TEXT), the notification, and SAF auto-save all write at delivery time, before any edit could exist.
5. The AI-disclaimer signature signs whatever text is exported (TranscriptSignature.apply over the export content). An edited transcript is mixed authorship: the base is still machine output, so the signature stays by default; the user can already disable it.

## Recommended first slice

- Column `editedResult` (schema v13), written by a plain edit dialog on the expanded SUCCESS audio row (the request class is correcting ASR errors, not a text editor; a single-line-of-defense dialog with save/discard).
- Undo is free: restoring means clearing editedResult; the original `result` is never overwritten.
- All text surfaces read editedResult ?: result: History row, copy, share, TXT export, search. Zero special cases.
- The TASK-545 report email gains one line when editedResult != null ("result was user-edited"), so misattribution reports stay answerable.

## The one decision for the maintainer: timed exports after an edit

Options, with my recommendation:

- (a) RECOMMENDED: segments stay immutable; SRT/VTT continue to export the ORIGINAL decode's cues; the FAQ and the export share sheet note it ("timed exports reflect the original decode"). Honest, zero risk, no timing lie.
- (b) On edit, drop the segments (row loses SRT/VTT fidelity, exports fall back to untimed). Consistent but destroys value the user asked for.
- (c) Re-map edits onto cues by text alignment. A research project for marginal gain; not v1.

## Explicitly out of v1

Editing on partial rows (isPartial) is allowed by the same mechanism but the banner already explains the state; no separate handling. Batch editing, versioning, or diff views: no request, no slice.
