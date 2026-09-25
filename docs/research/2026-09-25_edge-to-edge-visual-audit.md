# Android 15 edge-to-edge visual audit (TASK-467)

Date: 2026-09-25 night. Device: RMX3853 (Android 16, beyond the enforced change), debug build, History tab.

## Code audit (questions 1 and 2, answered)

enableEdgeToEdge() is called in MainActivity.onCreate (MainActivity.kt:89, the androidx.activity API, the supported path). A full grep of app/src/main finds ZERO uses of setStatusBarColor, setNavigationBarColor, FLAG_TRANSLUCENT_STATUS, systemUiVisibility, or windowLightStatusBar in our sources. The two deprecated setters the Play console flagged (obfuscated cu2.z/w41.a) are dependency-internal: no code of ours calls them, so the mapping retrace remains a TASK-466 curiosity, not a risk.

## Visual audit (question 3, captured)

Light mode (cmd uimode night no) and dark mode (night yes), History with the search bar and tab row visible:

- STATUS BAR: correct contrast in both modes (dark icons over the light surface, light over dark); enableEdgeToEdge's automatic appearance handling is working.
- CONTENT INSETS: the search field and tabs render below the status area with proper insets; nothing draws under the status text and nothing is clipped at the top.
- NAV AREA: the gesture bar region is clean in both modes; list content scrolls behind it without contrast loss on the bottom row captions.

## Keyboard window (captured after the shell outage, dark mode)

With the History search focused and text typed, the IME insets are handled: the search field with the query stays fully visible above the keyboard, results render between them, and the status bar keeps correct contrast. No clipping, no covered controls.

## Verdict (question 4)

NO IMPACT found: the app is already on the Android 15+ edge-to-edge model (the modern API, no legacy flags), and the captures (light, dark, keyboard) show correct insets and contrast on every examined surface. Nothing to route into TASK-466.

Honest caveat: an open DIALOG was not captured (the phone returned to active use mid-pass; the phone is not to be driven while in use). Nothing in the code audit suggests dialogs behave differently: Compose dialogs handle insets by construction, and the codebase has no custom dialog window flags.
