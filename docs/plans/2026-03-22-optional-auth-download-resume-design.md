# Optional Auth + Download Resume/Retry

**Date**: 2026-03-22
**Inspired by**: Google AI Edge Gallery 1.0.10 "Streamlined Gemma model downloads — no HuggingFace login required"

## Problem

Anti-Vocale requires a HuggingFace token for every model download, even though the Gemma LiteRT models on `google/gemma-3n-*-litert-lm` and `litert-community/gemma-4-*-litert-lm` are publicly accessible. Additionally, downloads don't support resume on failure, so a 3GB download interrupted at 90% must restart from scratch.

## Solution

### 1. Optional Auth (pre-flight check)

**Flow**:
1. HEAD request to the HuggingFace resolve URL (no auth header)
2. HTTP 200 → download directly, no token needed
3. HTTP 401/403 → return `DownloadError.AuthRequired`, ViewModel navigates to Settings
4. Network error → return `DownloadError.NetworkError`

**Changes**:
- `ModelDownloader.downloadModel()`: `tokenManager` param becomes optional (`= null`)
- New private method `checkPublicAccess(variant)` that does a HEAD request
- Download request only adds `Authorization` header if a token is provided
- New `DownloadError.AuthRequired(message)` subclass for gated-model fallback
- `ModelViewModel.startDownload()`: no longer blocks on missing token; handles `AuthRequired` by showing snackbar with navigation to Settings

**Unchanged**: `HuggingFaceTokenManager`, `HuggingFaceAuthManager`, `HuggingFaceApiClient`, `HuggingFaceOAuthConfig` — all stay for gated models.

### 2. Download Resume (HTTP Range)

**Approach**: Mirror Google's `DownloadWorker` pattern:
- On download start, check if `{fileName}.tmp` exists with content
- If yes: send `Range: bytes={alreadyDownloaded}-` header, open output in append mode
- Force `Accept-Encoding: identity` to prevent chunked/compressed transfer from breaking resume
- Parse `Content-Range` response header to get true start offset
- If server returns 200 (not 206), delete partial file and start fresh

**Switch from OkHttp to HttpURLConnection**: OkHttp handles range/resume opaquely. Using `HttpURLConnection` (like Google) gives us explicit control over range headers, append mode, and `Accept-Encoding`.

### 3. Retry with Exponential Backoff

**Policy**:
- Max 3 retries for transient errors (timeouts, connection reset, 5xx)
- No retry for 401/403/404 (auth/license/not-found)
- Backoff delays: 1s, 3s, 9s
- Resume support works across retries (partial file persists)

### 4. UI Impact

- ModelTab download button: starts immediately without checking token first
- If auth required: snackbar says "This model requires a HuggingFace account" with action to open Settings
- Partial download detection: if `.tmp` exists on app start, model shows as partially downloaded with resume option
- Settings tab: HuggingFace auth section stays, demoted slightly

## Files Changed

| File | Change |
|------|--------|
| `ModelDownloader.kt` | Optional auth, resume, retry, switch to HttpURLConnection |
| `ModelViewModel.kt` | Don't block on missing token, handle AuthRequired error |
| `ModelTab.kt` | Handle partial download state, auth-required error action |
| `SettingsTab.kt` | Minor: demote HF auth section prominence |
| `strings.xml` | New strings for auth-required, resume download |
