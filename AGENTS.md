# Repository Guidelines

## Project Structure

This repository contains one Android module, `app/`. Java hooks and Airoha protocol code live in `app/src/main/java/com/tosasitill/az100/`; strings and arrays are in `app/src/main/res/values/`, and `app/src/main/assets/xposed_init` registers the LSPosed entry point. The manifest and Gradle files define module metadata and Android build settings. Read `README.md` for installation and runtime behavior, and `docs-audio-connect.md` for protocol background. There are currently no test source directories.

## Build and Test

Use JDK 17 and an installed Gradle/Android SDK; this repository does not include a Gradle Wrapper.

- `gradle :app:assembleDebug` builds the installable debug APK at `app/build/outputs/apk/debug/app-debug.apk`.
- `gradle :app:assembleRelease` builds the release APK.
- `gradle :app:testDebugUnitTest` runs local unit tests when tests are added.

For behavior changes, install the module, enable it in LSPosed with only `com.oplus.melody` in scope, force-stop that package, and inspect `adb logcat -s AZ100:V`. Add JVM tests under `app/src/test/java` (named `*Test`) for parsers or state logic; document device-only checks that cannot be automated.

## Style and Boundaries

Follow the existing Java 17 style: four-space indentation, braces on the same line, `UpperCamelCase` class names, `lowerCamelCase` methods, and `UPPER_SNAKE_CASE` constants. Keep package names under `com.tosasitill.az100`; no formatter or linter is configured. Preserve the module's narrow runtime boundary: hooks run in `com.oplus.melody` only. Do not broaden LSPosed scope to SystemUI or Device Space, or add a dependency on Technics Audio Connect.

For static APK/DEX inspection, prefer ASC (`droidasc`); use apktool/jadx for resources or smali and Frida for runtime behavior.

## Commits and Pull Requests

The Git history uses concise, action-focused summaries (for example, `避免连接期间反复建立 SPP`). Keep changes scoped and describe user-visible behavior, build/test results, and the Android device/ROM plus LSPosed version used for hardware validation. Include screenshots for UI changes and redact Bluetooth addresses or other device-specific data from logs.
