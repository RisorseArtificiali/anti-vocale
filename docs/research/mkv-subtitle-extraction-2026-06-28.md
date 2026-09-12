# Research: Extracting Embedded Subtitles from MKV on Android

**Date:** 2026-06-28 · **Command:** `/sc:research --depth exhaustive` · **Confidence:** High (~0.85)

## Executive summary

The framework `MediaExtractor` omits Matroska subtitle tracks **by design** — AOSP's `MatroskaExtractor` exposes only video/audio tracks, and no Android 14/15/16 release changed this. The cleanest, smallest, best-supported fix for Anti-Vocale is **`androidx.media3`'s `MatroskaExtractor` driven headless** (no `Player`, no `Surface`): a `media3-extractor` + `media3-container` dependency (~hundreds of KB of pure-Java/Kotlin, **no native `.so`**) parses the MKV EBML stream and yields each subtitle track's raw bytes via a `TrackOutput`, with `MatroskaExtractor.FLAG_EMIT_RAW_SUBTITLE_DATA` preserving the original SRT/ASS/SSA payload. Proven in the wild (androidx/media#1286). Avoids the ~10–40 MB native cost of FFmpeg. **Recommendation: media3 headless extractor as the MKV branch of `SubtitleExtractor`; do not add FFmpeg.**

## 1. Limitation confirmation

Android's framework `MediaExtractor` is a thin wrapper over AOSP's `libstagefright` Matroska extractor. The public `MediaFormat` container list has, for the platform's entire history, listed only **video + audio** under Matroska — no subtitle MIME. The on-device observation (only `video/avc` + `audio/mp4a-latm` surfaced on Android 16) is consistent across all shipped versions; **no Android 14/15/16 release note adds MKV subtitle support** to framework `MediaExtractor`. The omission is at the track-enumeration layer, so it hits the **entire Matroska subtitle family** (SRT/S_TEXT/UTF8, ASS/SSA, WebVTT, PGS bitmap). ⚠️ AOSP `MatroskaExtractor.cpp` source not directly loadable this session (Googlesource/cs.android.com blocked); inferred from on-device behavior + docs absence + the existence of the media3 workaround (issue #1286).

## 2. Options comparison

| Option | Feasibility | APK / native cost | Kotlin integration | Maturity | ASS/SSA? | Headless? |
|---|---|---|---|---|---|---|
| **(a) media3 `MatroskaExtractor` headless** | ✅ Excellent — pure-JVM, API 21+ | Small: a few hundred KB classes; **no native `.so`** | Medium — ExoPlayer-style `Extractor`/`TrackOutput` plumbing | ✅ Google-maintained, stable | ✅ (raw bytes) | ✅ Yes (#1286) |
| **(b) ffmpeg-kit / custom FFmpeg** | ✅ Trivial feature-wise | **~10–40 MB `.so` per ABI**; Full/GPL tier for libass | Low — one-line `FFmpegKit.executeAsync` | ⚠️ upstream archived Apr 2025; community fork active | ✅ (libass / srt mux) | ✅ Yes (CLI) |
| **(c) pure-JVM EBML parser (EBMLReader)** | ✅ Works | Tiny (~tens of KB) | Low–Medium | ❌ Unmaintained (v0.1.0, ~2016) | ✅ (emits ASS) | ✅ Yes |
| **(d) existing Android subtitle lib** | ⚠️ Most are sidecar parsers (SRT/VTT files), not MKV demuxers | Small | Low | Varies | Usually no | ✅ |
| **(e) do-nothing / ASR fallback** | ✅ Already implemented | 0 | 0 | n/a | n/a | n/a |

## 3. Per-option deep dive

### (a) media3 `MatroskaExtractor`, headless — **recommended**

**Killer fact:** media3 ships a public, framework-independent `MatroskaExtractor` (`androidx.media3.extractor.mkv`). Since **1.4.0-alpha01**, subtitle parsing happens *during extraction* (not rendering) — making headless subtitle extraction a first-class supported path, not a hack.

API surface:
- `MatroskaExtractor` with `FLAG_EMIT_RAW_SUBTITLE_DATA` (keep original bytes) or default (transcode to `APPLICATION_MEDIA3_CUES`).
- An `ExtractorOutput` whose `track(id, type)` returns a custom `TrackOutput` for `C.TRACK_TYPE_TEXT`; `sampleData`/`sampleMetadata` deliver the raw SRT/ASS payload per cue.
- Drive it with a `DefaultExtractorInput` over any `DataSource` (file, content URI) — **no `ExoPlayer`, no `Surface`, no UI**. Issue #1286 contains a complete working Kotlin snippet that dumps ASS subtitle bytes to disk.

Minimum deps: `media3-extractor` + transitive `media3-container` + `media3-datasource`. **Do NOT need** `media3-exoplayer`/`ui`/`session`. Pure Java/Kotlin, no native `.so`. ASS bytes include `[Script Info]`, `[V4+ Styles]`, `[Events]` — exactly what the app's text-stripping path consumes. PGS/VOBSUB (bitmap) surface as raw bytes to skip/OCR. License: Apache 2.0 (compatible).

### (b) ffmpeg-kit / custom FFmpeg — viable but heavy

Original `tanersener/ffmpeg-kit` **archived April 2025**; `mobile-ffmpeg` EOL before that. Community fork **`ffmpegkit-maintained/ffmpeg-kit`** is active, Android-only, SDK-35 / 16 KB-page aligned, on Maven Central (`dev.ffmpegkit-maintained:ffmpeg-kit-*`). Cost: `full`/`full-gpl` ~**30–40 MB `.so`/ABI** (libavcodec/format/filter/ass); `min` ~10–15 MB/ABI (no libass). ⚠️ Exact MB unverified for the fork's 8.1.x line — historical upstream values. FFmpeg 8.x caveat: `-ac N` breaks; use `aformat=channel_layouts=mono`. Detect via `ffprobe -show_streams`; dump via `-map 0:s:0 -c:s text|srt`. Not recommended over media3 for text subtitles (30 MB native for a niche input class).

### (c) pure-JVM EBML parser

`matthewn4444/EBMLReader` — only credible pure-Java MKV demuxer that does subtitles (SRT + ASS). But v0.1.0, "not complete," unmaintained for years. Bugs in container edge-cases (header compression, CRC-32, attachments) silently produce wrong text. Not recommended over Google-maintained media3. libmatroska/libebml via JNI is more work than reusing media3's already-shipped Java EBML reader.

### (d) existing Android subtitle libraries

Most are **sidecar** parsers (parse a `.srt`/`.vtt` you give them), not MKV demuxers. media3 already ships best-in-class SRT/WebVTT/TTML/SSA *parsers* (`SubtitleParser.Factory`) reusable even outside extraction.

### Bundled-dep reuse (Q5)

sherpa-onnx / LiteRT-LM / MediaPipe do **not** bundle an ffmpeg or Matroska demuxer — they expect decoded PCM. ⚠️ Not symbol-verified this session, but consistent with their documented API. **No reusable demuxer in the existing native stack.**

### Real-world incidence (Q6)

For shared-video / voice-message (WhatsApp, Telegram, Signal, RCS), the dominant container is **MP4** (H.264/AAC, sometimes HEVC), with `.mov`/`.3gp` secondary. MKV-with-embedded-text-subtitles is concentrated in **anime/film/TV rips** where ASS styling matters — a small minority of "transcribe this shared video" inputs. → Argues for the **lowest-cost** fix (media3, ~0 native MB), not FFmpeg (30–40 MB for a niche class).

### Newer Android APIs (Q7)

No evidence Android 14/15/16 added MKV subtitle support to framework `MediaExtractor`. The practical "new API" is **media3 itself** — Google's recommended replacement for direct `MediaExtractor` use.

## 4. Concrete recommendation for Anti-Vocale

**Adopt (a): media3 `MatroskaExtractor` headless.** Only option that closes the gap at near-zero size, first-party maintained, with a public reference implementation (#1286).

Implementation shape (research-level, not code):
1. Add `androidx.media3:media3-extractor` (Gradle pulls `media3-container`, `media3-datasource`). No `media3-exoplayer`.
2. R8/ProGuard keep rule for `androidx.media3.**` (consistent with existing native-JNI keep discipline).
3. In `SubtitleExtractor`, branch on container sniff: if Matroska, drive `MatroskaExtractor(FLAG_EMIT_RAW_SUBTITLE_DATA)` via `Extractor.init(ExtractorOutput)` + `extractor.read(input, holder)` until `RESULT_END_OF_INPUT`, collecting text-track `TrackOutput` bytes. Reuse existing timestamp-stripping on the SRT/ASS payload.
4. Gate behind the same path as MP4; keep ASR as universal fallback. Skip bitmap (PGS) gracefully.

**Effort:** ~1–2 days for a working MKV-text branch (API small; #1286 is a near-copy template) + half-day R8/device verify on a real MKV-with-ASS sample.
**Size:** classes-only, low single-digit MB pre-R8, sub-MB post-R8. ⚠️ **Confirm by building + measuring** (verify, don't assume).
**Risk:** Low. Main risk: unusual codec IDs (e.g. PGS bitmap) — skip gracefully. Apache 2.0, license-compatible.

**Fallback if media3 insufficient** (e.g. bitmap PGS via OCR, batch remux): switch to `ffmpegkit-maintained/ffmpeg-kit` `full` tier (~30 MB native). **Do not** use `mobile-ffmpeg` (EOL) or archived `arthenica/ffmpeg-kit`.

## Sources

1. `MatroskaExtractor` — androidx.media3 API ref. https://developer.android.com/reference/androidx/media3/extractor/mkv/MatroskaExtractor
2. `matthewn4444/EBMLReader` — pure-Java MKV subtitle parser. https://github.com/matthewn4444/EBMLReader
3. "Saying Goodbye to FFmpegKit" (tanersener, Jan 6 2025). https://tanersener.medium.com/saying-goodbye-to-ffmpegkit-33ae939767e1
4. Android `MediaExtractor` reference. https://developer.android.com/reference/android/media/MediaExtractor
5. libmatroska/libebml Android port notes. https://www.programmersought.com/article/62905458705/
6. `ffmpegkit-maintained/ffmpeg-kit` — community fork. https://github.com/ffmpegkit-maintained/ffmpeg-kit
7. Media3 ExoPlayer — Google's recommended stack. https://developer.android.com/media/media3/exoplayer
8. `mobile-ffmpeg` (EOL). https://tanersener.github.io/mobile-ffmpeg/
9. androidx/media **issue #1286** — headless media3-extractor subtitle export (working Kotlin, ASS). https://github.com/androidx/media/issues/1286
10. `ffmpegkit-maintained/ffmpeg-kit` releases (8.1.x tiers, FFmpeg 8.x caveats). https://github.com/ffmpegkit-maintained/ffmpeg-kit/releases
11. media3 release notes — subtitle parsing during extraction (1.4.0-alpha01+). https://developer.android.com/jetpack/androidx/releases/media3
12. Matroska codec mappings (official). https://www.matroska.org/technical/codec_specs.html
13. IETF cellar codec draft — subtitle codec IDs. https://datatracker.ietf.org/doc/html/draft-ietf-cellar-codec-13
14. androidx/media RELEASENOTES.md. https://github.com/androidx/media/blob/release/RELEASENOTES.md
15. `media3-exoplayer` Maven Central (Apache 2.0). https://mvnrepository.com/artifact/androidx.media3/media3-exoplayer/1.5.1
16. WhatsApp supported video formats. https://www.dropbox.com/resources/send-large-videos-on-whatsapp
17. Android media modules (Mainline) — codec, not extractor, scope. https://source.android.com/docs/core/media/media-modules

## Unverifiable claims (flagged)

- Exact AOSP `MatroskaExtractor.cpp` source confirming subtitle-track omission — source page blocked; inferred from on-device behavior + docs + issue #1286. High but not source-confirmed.
- Exact R8-shrunk size of `media3-extractor` subset in Anti-Vocale's APK — **must be measured by building**.
- Exact `.so` per-ABI size of the maintained ffmpeg-kit fork's `full`/`full-gpl` tiers — historical upstream value (~30–40 MB/ABI) cited directionally; not re-measured for 8.1.x.
