# Dictation pill: Play-policy and feasibility analysis (TASK-676, GH #79)

Date: 2026-09-27. Status: analysis only, no implementation. The go/no-go is the maintainer's call.

Framing up front, because it matters to the policy reading: Anti-Vocale is a voice-message transcription app. A floating dictation pill would be an opt-in power-tool surface for existing users who already use the app's engines. It is not a keyboard, and it is not a pivot into the dictation-keyboard lane (Gboard voice typing and peers own that lane uncontested). This identity constraint rules out one of the four technical routes on its own, before any policy analysis.

## 1. Mechanism comparison

### Route A: SYSTEM_ALERT_WINDOW overlay (the literal "floating pill")

- Mechanism: `android.permission.SYSTEM_ALERT_WINDOW`, windows of type `WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY` shown on top of all other apps (https://developer.android.com/reference/android/Manifest.permission#SYSTEM_ALERT_WINDOW).
- Permission UX: not a runtime permission. Grant flows through the special app-access screen ("Display over other apps"), reached via `Settings.ACTION_MANAGE_OVERLAY_PERMISSION`. The user must leave the app and flip a system toggle; we cannot prompt-dialog our way to a grant.
- Play review burden: low-to-moderate and mostly self-certified. There is no surviving dedicated SAW declaration form for ordinary overlay UI (the old Play Console "Display over other apps" declaration, answer 9154286, is retired and returns "page can't be found" as of 2026-09-27). The binding obligations are the general policies: the Device and Network Abuse policy prohibits overlays used for clickjacking, deception, or interfering with other apps (https://support.google.com/googleplay/android-developer/answer/9888379), and the User Data policy's prominent-disclosure requirements apply if the overlay is used to collect content the user does not know about. A user-summoned, user-visible pill that only renders our own transcription output is squarely the benign case that Google Translate's Tap to Translate and many floating widgets already ship under.
- Committing into the focused field: NOT possible by injection. An overlay window cannot write into another app's text field. The only no-special-permission path is: the pill writes the committed utterance to the system clipboard (`ClipboardManager`, global by design, https://developer.android.com/develop/ui/views/touch-and-input/copy-paste) and the user pastes into the field. Writing the clipboard needs no permission; reading it is restricted on modern Android and triggers system toasts to the user. So the honest capability ceiling of this route is "live per-utterance text in a floating pill, one tap to copy, user pastes", not "auto-types into the focused field".
- Precedents on Play: Google Translate's Tap to Translate ships a floating overlay entry point as a mainstream Play feature (https://support.google.com/translate/answer/6142478); floating-widget utilities (translators, note pills, screen tools) are a large established category. None of these auto-type into other apps' fields; the ones that do are the accessibility route below.

### Route B: Bubbles API (same floating look, no SAW permission)

- Mechanism: `Notification.BubbleMetadata` on a notification, requires the app to publish a shortcut and the notification to be bubble-eligible; the bubble is a floating, expandable overlay managed entirely by the system (https://developer.android.com/develop/ui/views/notifications/bubbles, https://developer.android.com/reference/android/app/Notification.BubbleMetadata).
- Permission UX: no SYSTEM_ALERT_WINDOW needed; the system notification permission plus a shortcut is enough. This is the headline attraction.
- Play review burden: essentially none beyond normal notification policy. Bubbles are a first-class platform feature.
- Why it still fails the feature spec: bubbles are designed for conversations and are user-controlled. The pill cannot be a persistent always-on-top surface the app summons on demand; it exists only as a bubble on a notification the user expands, and expanding it opens OUR activity in a bubble window, which takes focus away from whatever field the user was typing in. Committing into another app's focused field is architecturally impossible while the bubble is the focused window. It also cannot behave like a durable dictation pill: dismissals, collapsed state, and position are the system's, not ours. As of the docs fetched 2026-09-27 the API is not marked deprecated, but bubble investment and OEM behavior vary; treating it as a stable power-tool surface is a gamble. Verdict: wrong shape for this feature, not a policy problem but a fit problem.

### Route C: AccessibilityService (the only route that can truly auto-commit)

- Mechanism: an AccessibilityService observing the window content, finding the focused editable field, and committing text via `ACTION_SET_TEXT` or the clipboard-plus-focus path, optionally dispatching gestures.
- Play review burden: the heaviest of any route. Per the Accessibility API policy (https://support.google.com/googleplay/android-developer/answer/10964491, fetched 2026-09-27):
  - Only services whose primary purpose is helping people with disabilities may declare `isAccessibilityTool="true"` and gain exemption from disclosure. The policy's own example of what does NOT qualify: "A general assistant that is voice-activated... that targets a large user population but would help users with motor impairments in some situations, would not qualify as an accessibility tool." That is a precise description of our dictation pill. We are not an accessibility tool.
  - Non-tools must pass the Permission Declaration Form with Google review and approval, and must show an in-app prominent disclosure meeting a strict checklist: displayed in normal usage, describes the data accessed through the AccessibilityService API, explains use and sharing, requires affirmative consent, and is a separate disclosure not bundled with other notices.
  - The policy adds a purpose-narrowness test ("all actions performed on a user's behalf are for a narrow and clearly understood purpose") and prohibits autonomous action chains; deterministic rule-based automation is allowed, so paste-on-utterance would likely pass that clause, but approval itself is discretionary and revocable, and any change in how the API is used requires re-filing.
- Verdict: technically the only route to the original vision (text appears in the focused field with no user paste), and the policy cost is disproportionate: a Play declaration form, review approval, a dedicated consent screen, and a standing review surface on an app whose core product is transcription and whose stake in this feature is "opt-in power tool, priority low". This is the honest no-go candidate per AC#4.

### Route D: IME (input method)

- Mechanism: ship an input method; a commit via `InputConnection.commitText` is native to the platform, no overlay permission, no accessibility declaration.
- Policy: IMEs are allowed on Play but carry their own sensitive-perception baggage (keyboard apps read everything typed), and more importantly this is the exact keyboard pivot the identity note forbids. Ruled out on identity, not policy.

### Summary table

| Route | Floating pill | Commit into focused field | Special permission | Play disclosure |
|---|---|---|---|---|
| A. SAW overlay | Yes, full control | No: clipboard + user paste | SAW via special app access | General policies; no surviving dedicated form |
| B. Bubbles | Partial: system-managed | No: expanded bubble steals focus | None | None specific, but wrong shape |
| C. Accessibility | Yes | Yes: ACTION_SET_TEXT | AccessibilityService + declaration form + Google approval | Prominent in-app disclosure, affirmative consent |
| D. IME | N/A (keyboard) | Yes, natively | IME enable | Identity rules it out |

## 2. Play-policy verdict per route

- Route A (SAW): ALLOWED for this use case. Overlay utility surfaces are an established Play category; our obligations are the generic ones: no deceptive or clickjacking overlay behavior (Device and Network Abuse, https://support.google.com/googleplay/android-developer/answer/9888379) and no undisclosed collection of other apps' content (User Data policy prominent-disclosure rules, https://support.google.com/googleplay/android-developer/answer/10964491 section on prominent disclosure). A pill that renders only our own transcription and only appears when the user summons it is the clean case.
- Route B (Bubbles): ALLOWED, but the API cannot deliver the feature (focus and lifecycle constraints above). Not a viable route regardless of policy.
- Route C (Accessibility): CONDITIONALLY allowed, gated on Play's Permission Declaration Form approval plus the full prominent-disclosure checklist (https://support.google.com/googleplay/android-developer/answer/10964491). Approval is discretionary; for a general-population voice assistant the isAccessibilityTool exemption is explicitly unavailable by the policy's own wording.
- Route D (IME): ALLOWED by policy, blocked by identity (this is the dictation-keyboard lane we explicitly do not enter).

## 3. Recommended shape for our identity

Go, but at the reduced capability Route A honestly supports:

- A strictly opt-in floating pill, off by default, enabled from Settings with the disclosure below shown at enable time (satisfies AC#3).
- The pill renders live per-utterance transcription from the engines the user already has, exactly like the in-app live view, just floating.
- Commit mechanics: a tap on the pill (or on an utterance) writes that text to the clipboard and shows a "copied, paste into your field" affordance. We do not touch other apps' fields, we do not read other apps' content, and the pill carries our app identity (icon/name) so it is never mistaken for system UI. This keeps us inside the benign overlay case with zero sensitive-permission declarations.
- Explicitly NOT in scope: accessibility-driven auto-typing, IME, reading any content that is not our own transcription output.
- It is a power tool layered on the transcription engine, not a new product surface: the settings entry, the disclosure, and the store description should all say so, which is also the strongest policy posture (the overlay is unambiguous in purpose).

Honest disclosure draft (English, shown in-app at enable time, affirmative consent required):

> "Floating dictation pill. When you turn this on, Anti-Vocale can show a small floating window over your other apps so you can see your dictation transcribed live, even while another app is in front. The pill displays only the transcription of audio you choose to record with it. It never reads the content of other apps and never types into them; when you tap a result it is copied to your clipboard for you to paste. You can turn the pill off at any time in Anti-Vocale settings, and revoking the 'Display over other apps' permission disables it completely."

If the pill ever grows the ambition of committing into other apps' fields, that is Route C territory and needs the Play declaration form, Google approval, and a standalone consent screen; it should be a new decision, not a silent extension of this one.

## 4. Go / no-go

- GO, conditional, on Route A (SAW + clipboard commit): low policy risk, no review gates beyond generic policy compliance, and the feature stays within our transcription identity. The accepted trade is the capability ceiling: no auto-commit, the user pastes.
- NO-GO on the full original vision (text commits itself into the focused field) via accessibility: the policy cost (declaration form, discretionary Google approval, dedicated consent flow, standing re-review on any behavior change) outweighs the value of a priority-low opt-in power tool on an app whose core is message transcription. If the maintainer judges auto-commit essential rather than optional, the honest verdict flips to no-go for the whole feature (AC#4), because every route that achieves auto-commit is either policy-heavy (accessibility) or identity-violating (IME).
- Route B (bubbles) is a no regardless: wrong tool, not a policy problem.

Signed-off decision pending: maintainer.

## Source list

- SYSTEM_ALERT_WINDOW permission, TYPE_APPLICATION_OVERLAY: https://developer.android.com/reference/android/Manifest.permission#SYSTEM_ALERT_WINDOW
- Accessibility API policy (isAccessibilityTool, declaration form, prominent disclosure checklist): https://support.google.com/googleplay/android-developer/answer/10964491
- Device and Network Abuse policy (overlay abuse / clickjacking): https://support.google.com/googleplay/android-developer/answer/9888379
- Bubbles guide and API reference: https://developer.android.com/develop/ui/views/notifications/bubbles and https://developer.android.com/reference/android/app/Notification.BubbleMetadata
- Clipboard framework (global ClipboardManager, copy/paste mechanics): https://developer.android.com/develop/ui/views/touch-and-input/copy-paste
- Google Translate floating Tap to Translate precedent: https://support.google.com/translate/answer/6142478
- Retired SAW declaration form (checked, now 404): https://support.google.com/googleplay/android-developer/answer/9154286
