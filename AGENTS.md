# Repository Guidelines

## Project Structure

This repository contains one Android module, `app/`. Java hooks and Airoha protocol code live in `app/src/main/java/com/tosasitill/az100/`; the native LE Audio patch is `app/src/main/cpp/az100_patch.c` with its checked-in `app/src/main/jniLibs/arm64-v8a/libaz100patch.so`. Strings and arrays are in `app/src/main/res/values/`, and `app/src/main/assets/xposed_init` registers the LSPosed entry point.

`Az100Hook` loads two scopes:

- `com.oplus.melody`: volume tile, ANC, and battery.
- `com.android.bluetooth`: LE Audio transport, UUID splice, native gate, BASS null fix, and the direct `connectLeAudio()` path.

Read `README.md` for current runtime behavior and `docs-audio-connect.md` for the RACE frames. Dated reports under the repository root are evidence logs; `README.md` wins when they disagree. There are currently no test source directories.

## Build and Test

Use JDK 21 and an installed Gradle/Android SDK. JDK 25/26 fails in `JdkImageTransform`. This repository does not include a Gradle Wrapper.

- `gradle :app:assembleDebug` builds `app/build/outputs/apk/debug/app-debug.apk`.
- `gradle :app:assembleRelease` builds the release APK.
- `gradle :app:testDebugUnitTest` runs local unit tests when tests are added.

For Melody-only changes, install the module, enable it in LSPosed, force-stop `com.oplus.melody`, and inspect `adb logcat -s AZ100:V`. For Bluetooth changes, also scope `com.android.bluetooth` and restart that process; its reliable log is `/data/misc/bluetooth/az100.log`.

Do not broaden LSPosed scope to SystemUI, Device Space, or Technics Audio Connect.

Add JVM tests under `app/src/test/java` (named `*Test`) for parsers or state logic. Document device-only checks that cannot be automated.

## Style and Boundaries

Follow the existing Java 17 style: four-space indentation, braces on the same line, `UpperCamelCase` class names, `lowerCamelCase` methods, and `UPPER_SNAKE_CASE` constants. Keep package names under `com.tosasitill.az100`; no formatter or linter is configured.

Keep the two runtime boundaries separate. Melody hooks must not depend on the Bluetooth-process classes being loaded, and Bluetooth hooks must not open the Airoha SPP link. Do not add a dependency on Technics Audio Connect.

`DIAGNOSE` only controls logging. Do not put a behavior hook behind it. `SPLICE_LE_AUDIO_UUIDS` must remain installed when diagnosis is off.

For static APK/DEX inspection, prefer ASC (`droidasc`); use apktool/jadx for resources or smali and Frida for runtime behavior. Do not scan the whole disk for APKs; the local Audio Connect artifact is `audioconnect-4.4.0.apk` in the repository root.

Never run bulk enumeration — no `find` sweeps over the filesystem, no loops over a class/jar/dex listing, no mass `javap` passes, no whole-corpus greps. Name the one class, method, symbol, or artifact you need and inspect that directly. Bulk scans are slow, noisy, and have twice been the reason a task stalled.

## Commits and Pull Requests

The Git history uses concise, action-focused summaries (for example, `避免连接期间反复建立 SPP`). Keep changes scoped and describe user-visible behavior, build/test results, and the Android device/ROM plus LSPosed version used for hardware validation. Include screenshots for UI changes and redact Bluetooth addresses or other device-specific data from logs.
