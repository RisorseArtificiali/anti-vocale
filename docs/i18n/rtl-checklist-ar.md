# RTL checklist for values-ar (iw as the proven reference)

The app already ships one RTL locale (iw). Arabic reuses the same ground;
verify each point on device with the app locale set to Arabic before sign-off.

## Layout

- Every alignment uses start/end, never left/right (grep the Compose code:
  `Alignment.Start`, `padding(start=`, `AbsoluteAlignment` only where
  mirrored intentionally, e.g. progress bars).
- Icons with directional meaning (back arrow, "next" chevrons, swipe
  affordances) auto-mirror via `Icons.AutoMirrored.*`; verify the back
  arrows in the seven settings sub-pages and the History nav arrows.
- Any canvas/drawn graphics do NOT auto-mirror: the audio waveform and the
  PiP progress bar keep LTR direction (correct: time axis), the swipe
  reveal icon must mirror.

## Text

- Arabic text with embedded Latin (model names, file names, "GB", "MB")
  relies on the bidi algorithm; check the model cards (name + size line)
  and the download confirmations where a number meets Arabic text.
- The seven sub-page headers and the tab labels render at the same
  baseline; Arabic diacritics can increase line height slightly - check
  the sub-page header does not clip.
- Plurals: all SIX categories (see plurals-ar.md).
- Apostrophes in Arabic text must be escaped (\\') exactly like the
  it/fr lesson from TASK-741.

## Numbers and dates

- Western digits are fine for ar (both forms acceptable); keep the
  positional %1$d arguments in the same order unless the sentence
  demands otherwise.
- Duration/timestamp formatting uses the same formatters as every other
  locale; spot-check the History rows and the folder-watch card.

## Verification pass (device)

1. Settings search with Arabic query (the search-as-you-type on the
   Settings tab).
2. The seven settings sub-pages (back header + actions).
3. A full transcription flow: result notification expanded (subtext),
   Copy action, the History row.
4. The Models tab: catalog cards, the Advanced/external group, a
   download progress notification.
