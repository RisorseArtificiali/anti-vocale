# Device-test playbook

Recipes for driving the app on a real device from adb. Companion to
`testing-spi.md` (the TEST_SPI broadcast seam): this file carries the
flows the SPI alone cannot reach. Conventions: debug package
`com.antivocale.app.debug`, every broadcast with `-n`, the app launched
once after any fresh install (SPI rule), preferences restored after use.

## Long-file transcription under a constrained memory budget (TASK-428)

The discriminating experiment the TASK-416 gate could not be: same
phone, 4GB-class budget, watching for the MemoryLimiter kill class
rather than the Java OOM class.

**Verified on the RMX3853 (Android 16, SDK 36, 2026-10-02): the
`am memory-limiter` subcommands DO NOT EXIST on this build.** The
research that named them (`docs/research/2026-09-01-android-heap-limits-adaptive-behavior.md`,
finding 3/5) describes the Android 17 surface. On this device today:

```
$ adb shell am memory-limiter status
Unknown command: memory-limiter
```

What Android 16 does expose (verified live):

- `cmd activity send-trim-memory <PROCESS> [level]` - sends a soft
  memory-pressure signal to one process. NOT a budget: the app is told
  to trim, nothing enforces a ceiling. Useful as a smoke input, useless
  for reproducing the kill class.
- `cmd activity memory-factor ...` - overrides the memory pressure
  factor used by the system's process-kill decisions.

Neither approximates `memory-limiter manual <pid> <limit>`.

**The recipe, when the device takes Android 17 (or on an enforcing
emulator):**

1. Start the long-file gate (the TASK-8-class real audio; push via
   `run-as cp` into `files/shared_audio/`, see testing-spi.md for the
   broadcast shape).
2. Read the app pid: `pidof com.antivocale.app.debug`.
3. Impose the 4GB-class budget:
   `adb shell am memory-limiter manual <pid> 4000000000` (bytes; also
   accepts `max`/`none` to lift).
4. Fire the transcription broadcast; watch for the discriminating
   outcome: NO MemoryLimiter kill (the row completes or fails with an
   honest error), and `logcat -s MemoryLimiter` stays silent on the
   enforced budget.
5. Lift the limit (`... manual <pid> none`) and confirm the next run
   is unconstrained.

Caveat carried from the research: the subcommands have no effect on a
device that does not impose the limits natively; verify with
`am memory-limiter status` before trusting a green run.
