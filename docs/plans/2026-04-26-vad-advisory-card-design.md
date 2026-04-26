# VAD Advisory Card Design

## Problem

When Parakeet TDT is the active model and VAD silence stripping is enabled, the user incurs unnecessary processing overhead. Parakeet processes full audio in a single pass with no chunk splitting — VAD provides no benefit and only adds latency.

## Solution

Show a dismissable advisory card at the top of the Log tab when Parakeet is active and VAD is on. Once dismissed, it never appears again.

## Design

### Component: VadAdvisoryCard

New Composable reusing the OnboardingTooltip pattern (AnimatedVisibility + Card) with an amber/warning tint instead of onboarding blue.

```
@Composable
fun VadAdvisoryCard(onDismiss: () -> Unit)
```

- Icon: `Icons.Outlined.Info`
- Title: "VAD is not needed with Parakeet"
- Body: "Silence stripping adds processing overhead without improving results for this model. You can turn it off in Settings."
- Dismiss button: "Got it"

### State: PreferencesManager

Single boolean preference:

```kotlin
val vadAdvisoryDismissed: Flow<Boolean>
suspend fun setVadAdvisoryDismissed(dismissed: Boolean)
```

Key: `"vad_advisory_dismissed"`, default `false`.

### Placement: LogsTab

At the top of the LazyColumn as a conditional `item {}`. Shown when all three conditions are true:
- `activeBackendId == "sherpa-onnx"`
- `vadEnabled == true`
- `vadAdvisoryDismissed == false`

### Data Flow

```
LogsTabViewModel
  ├── activeBackendId (from TranscriptionBackendManager)
  ├── vadEnabled (from PreferencesManager)
  └── vadAdvisoryDismissed (from PreferencesManager)
  → combined into showVadAdvisory: StateFlow<Boolean>
```

### String Resources

English and Italian strings for title, description, and dismiss button.

## Files to Modify

1. `app/src/main/java/com/antivocale/app/ui/components/VadAdvisoryCard.kt` (new)
2. `app/src/main/java/com/antivocale/app/ui/tabs/LogsTab.kt`
3. `app/src/main/java/com/antivocale/app/data/PreferencesManager.kt`
4. `app/src/main/res/values/strings.xml`
5. `app/src/main/res/values-it/strings.xml`
