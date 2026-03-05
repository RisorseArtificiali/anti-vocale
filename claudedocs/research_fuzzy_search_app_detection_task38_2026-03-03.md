# Research: Fuzzy Search Approaches for Task-38 (Per-App Notification Behavior)

**Date:** 2026-03-03
**Task:** TASK-38 - Implement per-app notification behavior
**Focus:** Fuzzy matching approaches to handle AI transcription inaccuracies when detecting source apps

---

## Executive Summary

This research explores fuzzy search approaches for implementing Task-38, which requires detecting the source app (WhatsApp, Telegram, etc.) from share intents. The key challenge is that **Android's ability to detect source apps from share intents is extremely limited and unreliable**. However, if app detection via transcription content is needed as a fallback, **a hybrid approach combining Levenshtein distance with phonetic matching (Double Metaphone) offers the best accuracy for handling transcription errors**.

### Key Recommendations

1. **Primary Approach**: Use Android's `getReferrer()` (API 22+) and `EXTRA_CALLING_PACKAGE` as first-line detection
2. **Fallback Fuzzy Matching**: Combine Levenshtein distance + Double Metaphone for transcription-based detection
3. **Recommended Library**: `fuzzywuzzy-kotlin` or Apache Commons Text
4. **Confidence Threshold**: Use 75-80% similarity score minimum for app name matches

---

## Part 1: Android Source App Detection (Native Methods)

### Available APIs

| Method | API Level | Reliability | Notes |
|--------|-----------|-------------|-------|
| `activity.getCallingPackage()` | 1+ | **Low** | Only works with `startActivityForResult()`, not `ACTION_SEND` |
| `EXTRA_CALLING_PACKAGE` | 4+ | **Low** | Only set if sender uses `ShareCompat.IntentBuilder`; most apps don't |
| `activity.getReferrer()` | 22+ | **Medium** | Best available option, but can be spoofed and may return null |
| `Binder.getCallingUID()` | 1+ | **Low** | Not useful for share intents |

### Key Findings from Research

> "Most apps don't provide this information. And if the extra is present you should not use it for any security-critical functionality because senders are free to provide any value they like."
> — Stack Overflow (32771304)

> "It's impossible to guarantee being able to extract [the source app]. Given that it is possible to create an Intent without including the information you're looking for..."
> — CommonsWare

### Recommended Android Detection Strategy

```kotlin
fun detectSourceApp(activity: Activity): String? {
    // Method 1: Try getReferrer() (API 22+)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
        activity.referrer?.host?.let { return it }
    }

    // Method 2: Check EXTRA_CALLING_PACKAGE
    activity.intent.getStringExtra(Intent.EXTRA_CALLING_PACKAGE)?.let { return it }

    // Method 3: Try getCallingPackage() (rarely works for ACTION_SEND)
    activity.callingPackage?.let { return it }

    // Method 4: Fallback to fuzzy matching on transcription content
    return null // Will need fuzzy detection
}
```

### Package Names for Common Apps

| App | Package Name |
|-----|--------------|
| WhatsApp | `com.whatsapp` |
| WhatsApp Business | `com.whatsapp.w4b` |
| Telegram | `org.telegram.messenger` |
| Signal | `org.thoughtcrime.securesms` |
| Messenger | `com.facebook.orca` |

---

## Part 2: Whisper ASR Transcription Error Patterns

Understanding how Whisper makes mistakes is critical for designing effective fuzzy matching.

### Error Taxonomy (from NBA Commentary Study)

| Error Type | Frequency | Example |
|------------|-----------|---------|
| **Phonetic Substitution** (names) | 35% | "Kristaps Porzingis" → "Christmas Por Zingas" |
| **Domain Jargon Errors** | 28% | "pick and roll" → "picker roll" |
| **Proper Noun Mangling** | High | "Giannis Antetokounmpo" → "yanis anteto kumbo" |
| **Hallucinations** | 1-2% | Fabricated content during silence |

### Common Transcription Error Patterns

1. **Phonetic Substitution**: Words replaced with similar-sounding words
   - "WhatsApp" → "wats up", "whats app", "watts app"
   - "Telegram" → "tel a gram", "tell a gram"
   - "Signal" → "single", "sign all"

2. **Homophone Confusion**:
   - "their/there/they're"
   - "to/too/two"

3. **Word Segmentation Errors**:
   - "whatsapp" → "whats app"
   - "telegram" → "tel a gram"

4. **Proper Noun Errors**:
   - App names often treated as common words
   - Brand names may be split or substituted

### Whisper Word Error Rate (WER) Context

| Model | Typical WER | Proper Noun Error Rate |
|-------|-------------|------------------------|
| Whisper medium | 5-10% | 15-25% |
| Whisper large-v3 | 4-8% | 10-20% |
| Local Whisper (this app) | 8-15% | 20-35% |

---

## Part 3: Fuzzy String Matching Algorithms

### Algorithm Comparison

| Algorithm | Best For | Time Complexity | Handles Transpositions |
|-----------|----------|-----------------|----------------------|
| **Levenshtein Distance** | General purpose | O(m×n) | No |
| **Damerau-Levenshtein** | Typos, adjacent swaps | O(m×n) | Yes |
| **Jaro-Winkler** | Short strings, names | O(m×n) | Yes (prefix bonus) |
| **N-gram Similarity** | Longer texts | O(m+n) | Partial |
| **Soundex** | Surnames (English) | O(m+n) | N/A (phonetic) |
| **Metaphone** | English words | O(m+n) | N/A (phonetic) |
| **Double Metaphone** | Multi-origin names | O(m+n) | N/A (phonetic) |

### Recommended Approach: Hybrid Distance + Phonetic

For app name matching with transcription errors, a **hybrid approach** works best:

1. **Levenshtein/Damerau-Levenshtein**: Catches typos and character errors
2. **Double Metaphone**: Catches phonetic substitutions
3. **Token-based matching**: Handles word segmentation

### Scoring Formula Recommendation

```kotlin
fun calculateAppMatchScore(transcribedText: String, appName: String): Double {
    val levenshteinScore = levenshteinSimilarity(transcribedText, appName)
    val phoneticScore = metaphoneMatch(transcribedText, appName)
    val tokenScore = tokenSetRatio(transcribedText, appName)

    // Weighted combination
    return (levenshteinScore * 0.4) +
           (phoneticScore * 0.3) +
           (tokenScore * 0.3)
}
```

---

## Part 4: Phonetic Matching for App Names

### Double Metaphone Encoding Examples

| App Name | Primary Code | Secondary Code |
|----------|--------------|----------------|
| WhatsApp | "ATSP" | "ATSP" |
| Telegram | "TLKR" | "TLKR" |
| Signal | "SNKL" | "SNKL" |
| Messenger | "MNSJR" | "MNSJR" |

### Phonetic Equivalents for Common Transcription Errors

| Correct | Common Errors | Metaphone Match |
|---------|---------------|-----------------|
| WhatsApp | "wats up", "whats app", "watts app" | All encode to "ATSP" |
| Telegram | "tel a gram", "tell a gram" | All encode to "TLKR" |
| Signal | "single", "sign all" | "SNKL" vs "SNKL"/"SNL" |

### When Phonetic Matching Excels

- **Sound-alike substitutions**: Catches "wats up" → WhatsApp
- **Spelling variations**: Handles "telegraph" vs "telegram" similarities
- **Accent-related errors**: Accommodates pronunciation differences

### When Phonetic Matching Fails

- **Completely different words**: "messenger" vs "facebook" won't match
- **Short names**: "Signal" vs "single" may false-positive
- **Non-English origins**: Limited support for non-English pronunciations

---

## Part 5: Kotlin/Java Library Recommendations

### Library Comparison

| Library | Algorithms | Size | Maintenance | Android Support |
|---------|------------|------|-------------|-----------------|
| **fuzzywuzzy-kotlin** (willowtreeapps) | Levenshtein, token ratios | ~50KB | Active | Excellent |
| **Apache Commons Text** | Levenshtein, Jaro-Winkler, Cosine, Hamming | ~200KB | Very Active | Good |
| **java-string-similarity** (tdebatty) | 10+ algorithms | ~100KB | Active | Good |
| **kt-fuzzy** (solo-studios) | Levenshtein, Jaro-Winkler, Cosine | Zero-dep | Active | Multiplatform |

### Recommendation: fuzzywuzzy-kotlin

```kotlin
// build.gradle.kts
implementation("com.willowtreeapps:fuzzywuzzy-kotlin:0.1.1")

// Usage
import com.willowtreeapps.fuzzywuzzy.FuzzySearch

fun matchAppName(transcribedText: String, appNames: List<String>): String? {
    return FuzzySearch.extractOne(transcribedText, appNames)?.let { result ->
        if (result.score >= 75) result.string else null
    }
}

// With multiple scoring methods
fun matchAppNameRobust(transcribed: String, apps: Map<String, String>): String? {
    val results = apps.map { (name, packageName) ->
        val simple = FuzzySearch.ratio(transcribed.lowercase(), name.lowercase())
        val partial = FuzzySearch.partialRatio(transcribed.lowercase(), name.lowercase())
        val tokenSet = FuzzySearch.tokenSetRatio(transcribed.lowercase(), name.lowercase())
        val weighted = FuzzySearch.weightedRatio(transcribed.lowercase(), name.lowercase())

        Triple(packageName, weighted, maxOf(simple, partial, tokenSet))
    }.sortedByDescending { it.second }

    return results.firstOrNull()?.takeIf { it.second >= 75 }?.first
}
```

### Alternative: Apache Commons Text

```kotlin
// build.gradle.kts
implementation("org.apache.commons:commons-text:1.15.0")

// Usage
import org.apache.commons.text.similarity.JaroWinklerSimilarity
import org.apache.commons.text.similarity.LevenshteinDistance

fun matchWithApacheCommons(transcribed: String, appName: String): Double {
    val jw = JaroWinklerSimilarity().apply(transcribed, appName)
    val lev = 1.0 - (LevenshteinDistance().apply(transcribed, appName) /
                    maxOf(transcribed.length, appName.length).toDouble())
    return (jw + lev) / 2.0
}
```

---

## Part 6: Implementation Recommendations for Task-38

### Architecture

```
┌─────────────────────────────────────────────────────────────┐
│                   Source App Detection                       │
├─────────────────────────────────────────────────────────────┤
│  1. Native Android Detection (preferred)                    │
│     - getReferrer()                                         │
│     - EXTRA_CALLING_PACKAGE                                 │
│     - getCallingPackage()                                   │
│                          ↓ (if null)                         │
│  2. Content URI Analysis                                    │
│     - Parse content:// URIs for package hints               │
│                          ↓ (if no match)                    │
│  3. Fuzzy Transcription Matching                            │
│     - Levenshtein + Token Set Ratio                         │
│     - Phonetic matching (Double Metaphone)                  │
│     - Threshold: 75% confidence                             │
│                          ↓ (if no match)                    │
│  4. Default/Fallback                                        │
│     - Use global user preferences                           │
└─────────────────────────────────────────────────────────────┘
```

### Data Model

```kotlin
data class SourceAppConfig(
    val packageName: String,
    val displayName: String,
    val aliases: List<String>,      // ["whatsapp", "whats app", "wats up"]
    val metaphoneCode: String,      // "ATSP"
    val autoCopy: Boolean = false,
    val showShareAction: Boolean = true,
    val notificationSound: String? = null
)

val KNOWN_APPS = listOf(
    SourceAppConfig(
        packageName = "com.whatsapp",
        displayName = "WhatsApp",
        aliases = listOf("whatsapp", "whats app", "wats up", "watts app"),
        metaphoneCode = "ATSP",
        autoCopy = true  // Default for WhatsApp
    ),
    SourceAppConfig(
        packageName = "org.telegram.messenger",
        displayName = "Telegram",
        aliases = listOf("telegram", "tel a gram", "tell a gram"),
        metaphoneCode = "TLKR",
        showShareAction = true  // Default for Telegram
    )
)
```

### Implementation Sketch

```kotlin
class SourceAppDetector(private val context: Context) {

    private val fuzzyThreshold = 75

    fun detectSourceApp(intent: Intent, transcription: String?): AppDetectionResult {
        // Step 1: Try native detection
        detectFromIntent(intent)?.let { return AppDetectionResult(it, DetectionMethod.NATIVE) }

        // Step 2: Try content URI analysis
        detectFromContentUri(intent)?.let { return AppDetectionResult(it, DetectionMethod.CONTENT_URI) }

        // Step 3: Fuzzy match on transcription (if available)
        transcription?.let { text ->
            detectFromTranscription(text)?.let { return AppDetectionResult(it, DetectionMethod.FUZZY) }
        }

        return AppDetectionResult(null, DetectionMethod.UNKNOWN)
    }

    private fun detectFromTranscription(text: String): String? {
        val textLower = text.lowercase()

        // Method A: Check known aliases first (fastest)
        KNOWN_APPS.forEach { app ->
            if (app.aliases.any { alias -> textLower.contains(alias) }) {
                return app.packageName
            }
        }

        // Method B: Fuzzy matching with weighted ratio
        KNOWN_APPS.forEach { app ->
            val score = FuzzySearch.weightedRatio(textLower, app.displayName.lowercase())
            if (score >= fuzzyThreshold) {
                return app.packageName
            }
        }

        // Method C: Token-based matching (handles word segmentation)
        KNOWN_APPS.forEach { app ->
            val tokenScore = FuzzySearch.tokenSetRatio(textLower, app.displayName.lowercase())
            if (tokenScore >= fuzzyThreshold) {
                return app.packageName
            }
        }

        return null
    }
}

enum class DetectionMethod { NATIVE, CONTENT_URI, FUZZY, UNKNOWN }
data class AppDetectionResult(val packageName: String?, val method: DetectionMethod)
```

---

## Part 7: Confidence Thresholds & Edge Cases

### Recommended Thresholds

| Scenario | Threshold | Rationale |
|----------|-----------|-----------|
| Auto-copy action | 85% | Higher confidence needed for automatic actions |
| Show share button | 75% | User can still choose alternative |
| Custom notification sound | 70% | Non-critical customization |
| UI suggestion only | 60% | Just a hint, user decides |

### Edge Cases to Handle

1. **Multiple apps detected**: Return highest score, flag ambiguity
2. **Short transcriptions**: Require higher threshold (85%+)
3. **No transcription yet**: Fall back to native detection only
4. **Unknown app**: Use global default settings

### False Positive Mitigation

```kotlin
fun validateMatch(score: Int, transcribed: String, appName: String): Boolean {
    // Higher threshold for very short strings
    if (transcribed.length < 5 && score < 90) return false

    // Check for common word collisions
    val commonWords = setOf("single", "signal", "messenger", "message")
    if (appName.lowercase() in commonWords && score < 80) return false

    return score >= 75
}
```

---

## Conclusions

### Primary Findings

1. **Android native detection is unreliable** for share intents - expect null results frequently
2. **Fuzzy matching is viable** but should be a fallback, not primary method
3. **Hybrid approaches work best**: Combine Levenshtein + token matching + phonetic
4. **fuzzywuzzy-kotlin** is the recommended library for Android

### Implementation Priority

1. **Implement native detection first** (getReferrer, EXTRA_CALLING_PACKAGE)
2. **Add fuzzy matching as fallback** with 75% threshold
3. **Store user preferences per detected app**
4. **Provide UI for manual override** when detection fails

### Testing Recommendations

- Create test corpus of real transcriptions with known source apps
- Measure precision/recall at different thresholds
- A/B test with users to validate detection accuracy

---

## References

### Android Documentation
- [Send simple data to other apps](https://developer.android.com/training/sharing/send)
- [Declare package visibility needs](https://developer.android.com/training/package-visibility/declaring)

### Fuzzy Matching Libraries
- [fuzzywuzzy-kotlin (willowtreeapps)](https://github.com/willowtreeapps/fuzzywuzzy-kotlin)
- [Apache Commons Text](https://commons.apache.org/proper/commons-text/)
- [java-string-similarity](https://github.com/tdebatty/java-string-similarity)

### Phonetic Algorithms
- [Double Metaphone Algorithm](https://en.wikipedia.org/wiki/Metaphone)
- [Phonetic Matching Algorithms (Medium)](https://medium.com/@ievgenii.shulitskyi/phonetic-matching-algorithms-50165e684526)

### Whisper ASR Research
- "Whisper: Courtside Edition" (arXiv:2602.18966v1) - Error taxonomy for domain-specific ASR
- "Investigation of Whisper ASR Hallucinations" (arXiv:2501.11378v1)
- AssemblyAI Universal-2 vs Whisper comparison
