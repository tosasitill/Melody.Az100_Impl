# Repository Guidelines

## Project Structure

This repository contains one Android module, `app/`. Kotlin hooks and Airoha protocol code live in `app/src/main/java/com/tosasitill/az100/`; the native LE Audio patch is `app/src/main/cpp/az100_patch.c` with its checked-in `app/src/main/jniLibs/arm64-v8a/libaz100patch.so`. Strings and arrays are in `app/src/main/res/values/`, and `app/src/main/resources/META-INF/xposed/java_init.list` currently registers `YukiHookEntry` through the YukiHookAPI libxposed runtime. `KavaRef` selects members; `YukiHookAdapter` bridges `HookRuntime` callbacks to its hook DSL. This migration is not currently build- or runtime-verified; `ModernXposedEntry` remains in source as the prior libxposed implementation.

`YukiHookEntry` declares two scopes in source and uses the beta12 annotation processor to generate the actual libxposed entry; the current local build passes. Runtime loading and device behavior still require separate LSPosed validation:

- `com.oplus.melody`: volume tile, ANC, and battery.
- `com.android.bluetooth`: LE Audio transport, UUID splice, native gate, BASS null fix, and the direct `connectLeAudio()` path.

`ModernXposedEntry` is the historical libxposed entry and still references the old `HookRuntime`, `Prefs`, and logging APIs. Do not treat either entry's presence in source as proof that the current APK loads.

Read `README.md` for current runtime behavior and `docs-audio-connect.md` for the RACE frames. Dated reports under the repository root are evidence logs; `README.md` wins when they disagree. JVM tests under `app/src/test/java` cover protocol parsing, hook callback semantics, connection gates, and handoff state.

## Build and Test

Use JDK 21 and an installed Gradle/Android SDK. This repository does not include a Gradle Wrapper. The current YukiHookAPI beta12 migration builds locally with AGP 9.1.0, Kotlin 2.4.21, KSP 2.3.9, and compile SDK 37. The local Maven repository is currently hard-coded in `settings.gradle`, so another checkout needs that artifact path or a portable repository configuration.

- `gradle :app:assembleDebug` builds `app/build/outputs/apk/debug/app-debug.apk`.
- `gradle :app:assembleRelease` builds the release APK.
- `gradle :app:testDebugUnitTest` runs the JVM regression tests; the current run passes all 48 tests.

JDK 25/26 previously failed in `JdkImageTransform`; use JDK 21.

For Melody-only changes, when a buildable APK is available, install the module, enable it in LSPosed, force-stop `com.oplus.melody`, and inspect `adb logcat -s Az100LSPosed:V AZ100:V`. For Bluetooth changes, the target process must load a new module instance; do not assume APK installation or a Bluetooth toggle does that automatically. The detailed trace file `/data/misc/bluetooth/az100.log` is conditional on diagnosis being enabled and may be absent or stale. Use current-process logcat / framework logs to confirm the loaded version; do not remotely run device probes for this documentation task.

Do not broaden LSPosed scope to SystemUI, Device Space, or Technics Audio Connect.

Add JVM tests under `app/src/test/java` (named `*Test`) for parsers or state logic. Document device-only checks that cannot be automated.

### Device Validation Restrictions

On 2026-10-14, the user reported a black screen and inability to reboot after an assistant-created temporary `app_process`/DEX AudioTrack test. Do not repeat that testing approach.

- Never create, push, or execute temporary on-device test programs, DEX files, helper APKs, or standalone Java/native probes. In particular, do not use `app_process` or independent AudioTrack/MediaPlayer probes to validate audio.
- Do not remotely start synthetic audio, test tones, playback, or stress tests on the phone. Audio playback and listening checks must be performed by the user through their normal apps.
- Keep assistant-driven audio validation to offline JVM tests and read-only inspection of existing device logs and system state. An active route or increasing playback frame count does not prove working audio or device stability.
- If the user reports a black screen, hang, or failed reboot, stop all device-side testing and record the failure. Do not launch more probes or restart processes to continue validation.

## Style and Boundaries

Follow the existing Kotlin style targeting JVM 17: four-space indentation, braces on the same line, `UpperCamelCase` class names, `lowerCamelCase` methods, and `UPPER_SNAKE_CASE` constants. Keep package names under `com.tosasitill.az100`; no formatter or linter is configured.

Keep the two runtime boundaries separate. Melody hooks must not depend on the Bluetooth-process classes being loaded, and Bluetooth hooks must not open the Airoha SPP link. Do not add a dependency on Technics Audio Connect. The YukiHookAPI migration uses its generated libxposed entry and YukiHookPreferences for hook-side reads; the module settings activity publishes changes through the libxposed remote preferences service, with private storage as its local copy. Do not restore world-readable files or legacy `assets/xposed_init` entry points.

`DIAGNOSE` only controls detailed trace logging. Do not put a behavior hook behind it. `SPLICE_LE_AUDIO_UUIDS` must remain installed when diagnosis is off. Production builds must keep the adb experiment console disabled; do not add a resident polling loop for diagnostics.

For static APK/DEX inspection, prefer ASC (`droidasc`); use apktool/jadx for resources or smali and Frida for runtime behavior. Do not scan the whole disk for APKs; the local Audio Connect artifact is `audioconnect-4.4.0.apk` in the repository root.

Never run bulk enumeration — no `find` sweeps over the filesystem, no loops over a class/jar/dex listing, no mass `javap` passes, no whole-corpus greps. Name the one class, method, symbol, or artifact you need and inspect that directly. Bulk scans are slow, noisy, and have twice been the reason a task stalled.

## Commits and Pull Requests

The Git history uses concise, action-focused summaries (for example, `避免连接期间反复建立 SPP`). Keep changes scoped and describe user-visible behavior, build/test results, and the Android device/ROM plus LSPosed version used for hardware validation. Include screenshots for UI changes and redact Bluetooth addresses or other device-specific data from logs.
