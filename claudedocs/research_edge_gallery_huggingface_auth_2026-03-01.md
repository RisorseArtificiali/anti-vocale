# Research: Google Edge Gallery HuggingFace Authentication UX

## Executive Summary

Google's Edge Gallery app achieves its smooth HuggingFace authentication through the **OpenID AppAuth-Android library** combined with **Chrome Custom Tabs**. The "brief browser swap" UX occurs because Chrome Custom Tabs shares session cookies with the Chrome browser - if the user has previously authenticated with HuggingFace in Chrome, the OAuth flow completes automatically without requiring re-login.

---

## Key Findings

### 1. Technology Stack

| Component | Technology |
|-----------|------------|
| OAuth Library | `net.openid.appauth` (OpenID AppAuth-Android) |
| Browser | Chrome Custom Tabs (via AppAuth) |
| Token Storage | DataStore (encrypted) |
| OAuth Flow | Authorization Code Flow with PKCE |

### 2. How the Smooth UX Works

```
┌─────────────────────────────────────────────────────────────────────────┐
│                    EDGE GALLERY AUTH FLOW                               │
├─────────────────────────────────────────────────────────────────────────┤
│                                                                         │
│  1. User taps "Download" on a gated model                              │
│           ↓                                                             │
│  2. App checks if token exists and is valid                            │
│           ↓                                                             │
│  3. If no valid token → Launch Chrome Custom Tab                       │
│           ↓                                                             │
│  4. Chrome checks existing HuggingFace session cookies                 │
│           ↓                                                             │
│  ┌─────────────────────────────────────────────────────────────────┐   │
│  │  IF user already logged in to HF in Chrome:                     │   │
│  │    → OAuth auto-approves (user sees brief flash)                │   │
│  │    → Redirect back to app with auth code                        │   │
│  │                                                                 │   │
│  │  IF user NOT logged in:                                         │   │
│  │    → User sees HuggingFace login page                          │   │
│  │    → After login → OAuth approves → Redirect back              │   │
│  └─────────────────────────────────────────────────────────────────┘   │
│           ↓                                                             │
│  5. App exchanges auth code for tokens                                 │
│           ↓                                                             │
│  6. Tokens stored securely in DataStore                                │
│           ↓                                                             │
│  7. Model download proceeds with Bearer token                          │
│                                                                         │
└─────────────────────────────────────────────────────────────────────────┘
```

### 3. Core Implementation Details

#### A. Project Configuration (`ProjectConfig.kt`)

```kotlin
object ProjectConfig {
  // Hugging Face Client ID (from HF OAuth App settings)
  const val clientId = "YOUR_CLIENT_ID"

  // Redirect URI - scheme must match manifestPlaceholders in build.gradle.kts
  const val redirectUri = "com.google.ai.edge.gallery://oauth2callback"

  // OAuth 2.0 Endpoints
  private const val authEndpoint = "https://huggingface.co/oauth/authorize"
  private const val tokenEndpoint = "https://huggingface.co/oauth/token"

  val authServiceConfig = AuthorizationServiceConfiguration(
    authEndpoint.toUri(),
    tokenEndpoint.toUri(),
  )
}
```

#### B. Authorization Request (`ModelManagerViewModel.kt`)

```kotlin
val authService = AuthorizationService(context)

fun getAuthorizationRequest(): AuthorizationRequest {
  return AuthorizationRequest.Builder(
    ProjectConfig.authServiceConfig,
    ProjectConfig.clientId,
    ResponseTypeValues.CODE,  // Authorization Code Flow
    ProjectConfig.redirectUri.toUri(),
  )
    .setScope("read-repos")  // Scope for reading models
    .build()
}
```

#### C. Handling Auth Result

```kotlin
fun handleAuthResult(result: ActivityResult, onTokenRequested: (TokenRequestResult) -> Unit) {
  val response = AuthorizationResponse.fromIntent(result.data!!)
  val exception = AuthorizationException.fromIntent(result.data!!)

  when {
    response?.authorizationCode != null -> {
      // Exchange auth code for tokens
      authService.performTokenRequest(response.createTokenExchangeRequest()) {
        tokenResponse, tokenEx ->
        if (tokenResponse != null) {
          saveAccessToken(
            accessToken = tokenResponse.accessToken!!,
            refreshToken = tokenResponse.refreshToken!!,
            expiresAt = tokenResponse.accessTokenExpirationTime!!,
          )
          curAccessToken = tokenResponse.accessToken!!
          onTokenRequested(TokenRequestResult(status = TokenRequestResultType.SUCCEEDED))
        }
      }
    }
    exception != null -> {
      // Handle error
    }
  }
}
```

#### D. Build Configuration (`build.gradle.kts`)

```kotlin
android {
  defaultConfig {
    // Needed for HuggingFace auth workflows
    manifestPlaceholders["appAuthRedirectScheme"] = "com.google.ai.edge.gallery"
  }
}

dependencies {
  implementation(libs.openid.appauth)  // net.openid.appauth:appauth
}
```

#### E. AndroidManifest.xml

```xml
<activity
    android:name=".MainActivity"
    android:exported="true"
    android:launchMode="singleTop">

    <!-- Deep link for OAuth callback -->
    <intent-filter>
        <action android:name="android.intent.action.VIEW" />
        <category android:name="android.intent.category.DEFAULT" />
        <category android:name="android.intent.category.BROWSABLE" />
        <data android:scheme="com.google.ai.edge.gallery" />
    </intent-filter>
</activity>
```

#### F. Using Token for Downloads

```kotlin
// In DownloadWorker.kt
val connection = url.openConnection() as HttpURLConnection
if (accessToken != null) {
  connection.setRequestProperty("Authorization", "Bearer $accessToken")
}
```

---

## Why the UX is So Smooth

### The Secret: Chrome Session Cookie Sharing

1. **Chrome Custom Tabs share cookies** with the Chrome browser app
2. **First-time users** see the full HuggingFace login page
3. **Returning users** (who've logged into HF in Chrome before) get:
   - Instant OAuth approval (HF recognizes the session)
   - Brief flash of browser (redirect happening)
   - Immediate return to app with token

### Session Persistence

Once authenticated:
- **Access Token**: Stored in DataStore (encrypted)
- **Refresh Token**: Stored for automatic token renewal
- **Expiration Time**: Tracked for proactive refresh

---

## Implementation Checklist for Replication

### Step 1: Set up HuggingFace OAuth App

1. Go to https://huggingface.co/settings/oauth/apps
2. Click "New OAuth Application"
3. Configure:
   - **Application name**: Your app name
   - **Homepage URL**: Your app's website
   - **Redirect URI**: `your.app.scheme://oauth2callback`
   - **Scopes**: `read-repos` (for model downloads)
4. Copy **Client ID** and **Client Secret**

### Step 2: Add AppAuth Dependency

```kotlin
// build.gradle.kts
dependencies {
  implementation("net.openid.appauth:appauth:0.11.1")
}
```

### Step 3: Configure Build

```kotlin
// app/build.gradle.kts
android {
  defaultConfig {
    manifestPlaceholders["appAuthRedirectScheme"] = "your.app.scheme"
  }
}
```

### Step 4: Add Deep Link Intent Filter

```xml
<!-- AndroidManifest.xml -->
<activity android:name=".MainActivity" android:launchMode="singleTop">
  <intent-filter>
    <action android:name="android.intent.action.VIEW" />
    <category android:name="android.intent.category.DEFAULT" />
    <category android:name="android.intent.category.BROWSABLE" />
    <data android:scheme="your.app.scheme" />
  </intent-filter>
</activity>
```

### Step 5: Implement OAuth Flow

```kotlin
class AuthManager(context: Context) {
  private val authService = AuthorizationService(context)

  fun startAuthFlow(activity: Activity, launcher: ActivityResultLauncher<Intent>) {
    val authRequest = AuthorizationRequest.Builder(
      authServiceConfig,
      clientId,
      ResponseTypeValues.CODE,
      redirectUri.toUri()
    )
      .setScope("read-repos")
      .setPrompt("consent")  // Optional: force consent screen
      .build()

    val authIntent = authService.getAuthorizationRequestIntent(authRequest)
    launcher.launch(authIntent)
  }

  fun handleAuthResult(result: ActivityResult): String? {
    val response = AuthorizationResponse.fromIntent(result.data!!)

    if (response?.authorizationCode != null) {
      // Exchange code for tokens
      authService.performTokenRequest(response.createTokenExchangeRequest()) {
        tokenResponse, _ ->
        if (tokenResponse != null) {
          return tokenResponse.accessToken
        }
      }
    }
    return null
  }
}
```

---

## Alternative: New Auth Tab API (Chrome 137+)

For even smoother UX, consider the new **Auth Tab** API (Chrome 137+):

```kotlin
// Requires androidx.browser:browser:1.9.0+
private val launcher = AuthTabIntent.registerActivityResultLauncher(
  this, this::handleAuthResult
)

fun launchAuth() {
  val authTabIntent = AuthTabIntent.Builder().build()
  authTabIntent.launch(
    launcher,
    Uri.parse("https://huggingface.co/oauth/authorize?..."),
    "your.app.scheme"  // Custom scheme redirect
  )
}
```

**Benefits**:
- Stripped-down UI (no minimize, bookmarks, etc.)
- Direct callback (no intent filter needed for custom schemes)
- Automatic fallback to Chrome Custom Tabs on older browsers

---

## Key Takeaways

1. **Use AppAuth-Android** - It handles Chrome Custom Tabs automatically
2. **Session cookies are key** - Users logged into HF in Chrome get instant auth
3. **Store tokens securely** - Use DataStore with encryption
4. **Refresh tokens** - Implement automatic token refresh before expiration
5. **Deep link scheme** - Must match between build.gradle.kts and HuggingFace OAuth app settings

---

## Sources

- [Google AI Edge Gallery GitHub](https://github.com/google-ai-edge/gallery)
- [AppAuth-Android](https://github.com/openid/AppAuth-Android)
- [HuggingFace OAuth Documentation](https://huggingface.co/docs/hub/oauth)
- [Chrome Custom Tabs Auth Guide](https://developer.chrome.com/docs/android/custom-tabs/guide-auth-tab)
- [Android SSO with Chrome Custom Tabs](https://developer.android.com/work/guide)

---

## Related Files in Edge Gallery

- `Android/src/app/build.gradle.kts` - Build config with redirect scheme
- `Android/src/app/src/main/java/com/google/ai/edge/gallery/common/ProjectConfig.kt` - OAuth endpoints
- `Android/src/app/src/main/java/com/google/ai/edge/gallery/ui/modelmanager/ModelManagerViewModel.kt` - Auth flow
- `Android/src/app/src/main/java/com/google/ai/edge/gallery/worker/DownloadWorker.kt` - Token usage
- `Android/src/app/src/main/AndroidManifest.xml` - Deep link intent filter
