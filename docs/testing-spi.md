# Testing SPI (debug builds)

The test SPI is a broadcast receiver that lets an agent, a script, or CI read and write the app's testable state directly: preferences, the active model, and the imported external-model records. It replaces the 30 to 40 adb UI-driving calls a device-test session otherwise burns on opening the app, navigating to Settings, dumping the UI tree, and tapping toggles (TASK-409). It is not a product feature and never ships to users.

## Security model

Three gates keep this out of the release attack surface:

1. **Debug-only registration.** The receiver is declared exclusively in the build-type overlay `app/src/debug/AndroidManifest.xml`. AGP merges that overlay only into the debug variants of both flavors (`playStoreDebug`, `fdroidDebug`); release merges read `main` plus the flavor source set, so `assemblePlayStoreRelease` and `assembleFdroidRelease` contain neither the receiver nor the `TEST_SPI` intent filter.
2. **Runtime guard.** `TestSpiReceiver` returns immediately unless `BuildConfig.DEBUG`, so even a hypothetical manual registration in a release build does nothing.
3. **No permission.** The broadcast is exported with no permission requirement. On a device running a debug build, any app could read or flip the app's preferences through it. That is acceptable only because debug builds are never distributed; it is exactly the trade you accept when you install a debug APK.

The op engine (`TestSpiOps`, in `app/src/main/java/com/antivocale/app/testing/`) is referenced only by the debug receiver, so R8 strips it from both minified release flavors; only debug builds carry its code.

This SPI is deliberately separate from the production exported receivers (`PROCESS_REQUEST`, `PRELOAD_MODEL`), whose security posture is reviewed in [`docs/research/2026-09-03_tasker-receiver-security-analysis.md`](research/2026-09-03_tasker-receiver-security-analysis.md). That memo also flags the anti-pattern this design avoids: `BenchmarkActivity` is described as "debug-only" but registered in the main manifest, so it ships in release builds with preference-clobbering extras. A debug-only component belongs in the debug source set, not in a comment.

## Action and extras

```text
Action: com.antivocale.app.TEST_SPI   (string extras, one op per broadcast)
  op     get | set | records | help   (missing or unknown op answers with help)
  key    one of the set keys below    (op=set)
  value  the new value                (op=set)
  entry  catalog entry id             (op=set, only for key=sherpa_path)
```

| Op | Extras | Response |
|---|---|---|
| `get` | none | JSON object: `vadEnabled`, `threadCount`, `inferenceProvider`, `transcriptionLanguage`, `transcriptionBackend`, `activeModelPath` (saved path of the current backend: the record's `dir` for `external:` ids, the generic preference for `llm`, the keyed sherpa preference for catalog ids), and `paths` mapping every catalog id plus `llm` to its saved path (or `null`) |
| `set` | `key`, `value`, plus `entry` for `sherpa_path` | confirmation JSON echoing `key`/`value` (`entry` too when used), or an error object with `error` and the full `supportedKeys` list |
| `records` | none | JSON array of the imported external models; each element is the record's persisted JSON plus the derived `backendId`. All records are listed, including dangling ones whose directory no longer exists, because dangling state is precisely what a debugging session needs to see |
| `help` | none | the op list, the set keys, the usage line, and the `PROCESS_REQUEST` pointer |

Set keys and value formats:

| Key | Writes | Value |
|---|---|---|
| `vad` | `saveVadEnabled` | `true` or `false` (strict; anything else is an error) |
| `threads` | `saveThreadCount` | integer (for example `4`) |
| `provider` | `saveInferenceProvider` | `auto`, `nnapi`, `cpu` (the settings dropdown's exact set; anything else is rejected because the app would silently run it as CPU) |
| `backend` | `saveTranscriptionBackend` | a catalog id (`sherpa-onnx`, `whisper`, `qwen3-asr`, `nemotron-streaming`, `gigaam`), `llm`, or `external:<record id>`; unknown ids are rejected without writing |
| `language` | `saveTranscriptionLanguage` | BCP-47 tag, `system`, or `auto` |
| `model_path` | `saveModelPath` | path (llm backend's model file) |
| `sherpa_path` | `saveSherpaModelPath(entry, path)` | path, with `entry=<catalog id>` naming which backend's keyed preference is written |

Paths are written as given and not validated against the filesystem. A test that writes a bogus path and then transcribes will fail at model load; set paths that came out of `op=get` or `op=records`, or a real download directory.

## Reading responses

Every response is a single line of JSON, delivered on two channels:

1. **resultData, the primary channel (device-verified 2026-09-03, Realme RMX3853 / Android 16):** with the explicit-component form below, the JSON comes back INLINE in the `am broadcast` output itself (`Broadcast completed: result=-1, data="{...}"`). One call, no second round-trip.
2. **logcat, the fallback channel.** Every response is also `Log.i("TestSpi", json)`: `adb shell logcat -d -s TestSpi:I`.

## Always use the explicit component (-n), and launch once after install

Two delivery rules learned on the real device (2026-09-03):

- **Implicit shell broadcasts are NOT delivered on this device.** A bare `am broadcast -a com.antivocale.app.TEST_SPI` completes silently with `result=0` and the receiver never runs; the same broadcast with `-n` is delivered. Verified as device behavior, not SPI behavior: an implicit `PROCESS_REQUEST` (a receiver that has worked for months) was also silently dropped in the same session, while its explicit form ran. Prefix every SPI call with the component:

```bash
adb shell am broadcast -a com.antivocale.app.TEST_SPI \
  -n com.antivocale.app.debug/com.antivocale.app.receiver.TestSpiReceiver --es op get
```

- **After a fresh install the app sits in the stopped state and receives nothing**, even with `-n`. Launch it once (`am start -n com.antivocale.app.debug/com.antivocale.app.MainActivity`) before the first SPI call of a session.

Debug builds install as `com.antivocale.app.debug` (the `applicationIdSuffix`), alongside the user's Play Store app; the `TEST_SPI` action exists only in the debug install.

## Ready-to-paste commands

Read the whole testable state in one call:

```bash
adb shell am broadcast -a com.antivocale.app.TEST_SPI -n com.antivocale.app.debug/com.antivocale.app.receiver.TestSpiReceiver --es op get
```

Toggle VAD (the classic multi-tap time sink):

```bash
adb shell am broadcast -a com.antivocale.app.TEST_SPI -n com.antivocale.app.debug/com.antivocale.app.receiver.TestSpiReceiver --es op set --es key vad --es value true
adb shell am broadcast -a com.antivocale.app.TEST_SPI -n com.antivocale.app.debug/com.antivocale.app.receiver.TestSpiReceiver --es op set --es key vad --es value false
```

Switch the active backend and language:

```bash
adb shell am broadcast -a com.antivocale.app.TEST_SPI -n com.antivocale.app.debug/com.antivocale.app.receiver.TestSpiReceiver --es op set --es key backend --es value whisper
adb shell am broadcast -a com.antivocale.app.TEST_SPI -n com.antivocale.app.debug/com.antivocale.app.receiver.TestSpiReceiver --es op set --es key language --es value it
```

Threads and inference provider:

```bash
adb shell am broadcast -a com.antivocale.app.TEST_SPI -n com.antivocale.app.debug/com.antivocale.app.receiver.TestSpiReceiver --es op set --es key threads --es value 4
adb shell am broadcast -a com.antivocale.app.TEST_SPI -n com.antivocale.app.debug/com.antivocale.app.receiver.TestSpiReceiver --es op set --es key provider --es value nnapi
```

Saved model paths (llm uses the generic preference; sherpa catalog backends need `entry`):

```bash
adb shell am broadcast -a com.antivocale.app.TEST_SPI -n com.antivocale.app.debug/com.antivocale.app.receiver.TestSpiReceiver --es op set --es key model_path --es value /data/local/tmp/gemma.taskml
adb shell am broadcast -a com.antivocale.app.TEST_SPI -n com.antivocale.app.debug/com.antivocale.app.receiver.TestSpiReceiver --es op set --es key sherpa_path --es entry sherpa-onnx --es value /storage/emulated/0/Android/data/com.antivocale.app.debug/files/models/sherpa-onnx
```

Imported external models, and the help text:

```bash
adb shell am broadcast -a com.antivocale.app.TEST_SPI -n com.antivocale.app.debug/com.antivocale.app.receiver.TestSpiReceiver --es op records
adb shell am broadcast -a com.antivocale.app.TEST_SPI -n com.antivocale.app.debug/com.antivocale.app.receiver.TestSpiReceiver --es op help
```

## Triggering transcription

Transcription is deliberately not an op of this SPI: it already has a production broadcast, `TaskerRequestReceiver` on `com.antivocale.app.PROCESS_REQUEST`:

```bash
adb shell am broadcast -a com.antivocale.app.PROCESS_REQUEST \
  -n com.antivocale.app.debug/com.antivocale.app.receiver.TaskerRequestReceiver \
  --es request_type audio \
  --es file_path /data/local/tmp/test_voice.wav \
  --es task_id spi-test \
  --es backend_id whisper
```

`backend_id` is optional (one-shot override; defaults to the saved preference). The transcript comes back on the `net.dinglisch.android.tasker.ACTION_TASKER_INTENT` reply broadcast and in the result notification. The path must be readable by the app's uid: the app declares no storage permissions (removed with `READ_MEDIA_AUDIO` in commit `b54303d`), so shared-storage paths like WhatsApp's media directory are unreadable and fail in preprocessing. Push audio into the app's own sandbox instead (the recipe the device playbook already uses):

```bash
adb push test_voice.wav /data/local/tmp/test_voice.wav
adb shell run-as com.antivocale.app.debug cp /data/local/tmp/test_voice.wav files/test_voice.wav
adb shell am broadcast -a com.antivocale.app.PROCESS_REQUEST \
  -n com.antivocale.app.debug/com.antivocale.app.receiver.TaskerRequestReceiver \
  --es request_type audio \
  --es file_path /data/user/0/com.antivocale.app.debug/files/test_voice.wav \
  --es task_id spi-test
```

The readable-set analysis is in the security memo linked above.
