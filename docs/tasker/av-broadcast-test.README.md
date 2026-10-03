# AV Background Test (TASK-577)

Tasker task that sends the PROCESS_REQUEST broadcast to the DEBUG build
of Anti-Vocale from Tasker's own uid - the faithful background sender
the shell cannot simulate (shell is exempt from the FGS background
denial that exercises the trampoline).

## Import (one minute)

1. The file `av-broadcast-test.tsk.xml` is already in the phone's
   Download folder (pushed 2026-10-03). It also lives here in the repo.
2. Open Tasker -> TASKS tab -> **LONG-PRESS on the TASKS tab header**
   (the canonical import entry point, per the Tasker documentation; the
   + button is NOT it - corrected 2026-10-03 after the maintainer
   flagged it).
3. Pick **Import Task** (or Import, depending on version), then
   `av-broadcast-test.tsk.xml` from Download.
4. The task "AV Background Test" appears in the list. If Beginner Mode
   hides the long-press menu: Tasker's three-dot menu -> More ->
   Android Settings... no - disable Beginner Mode in Tasker's
   preferences first (the FAQ's note), then the long-press works.

## Run the experiment

1. Close Anti-Vocale from Recents (it must NOT be running).
2. In Tasker, TASKS tab, tap "AV Background Test" -> the play button.
3. Tell the agent "fatto": the logcat capture reads the outcome
   (direct start vs fallback notification vs trampoline).

The action inside: Send Intent ->
Action `com.antivocale.app.PROCESS_REQUEST`, Target Broadcast Receiver,
Package `com.antivocale.app.debug`,
Class `com.antivocale.app.debug.receiver.TaskerRequestReceiver`,
extras `request_type=audio`, `file_path=/data/data/com.antivocale.app.debug/files/shared_audio/two_speakers.wav`
(the 16s test clip already staged on the phone).
