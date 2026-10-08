# Repair environment — 7 October 2026

The original application was already built when this review began. Its earlier environment report is preserved verbatim in `history/before-7-october-2026-repair/ENVIRONMENT.md`. That report describes an earlier session and is not the current verification record.

The repair working directory is `E:\CodexData\work\scheduled-power-menu-repair\project`. The delivery directory is `E:\Deepseek\Projects\Scheduled-Power-Menu-Restriction`. Delivery occurs only after verification and a hash-checked backup. See `TEST_RESULTS.md` for executed results.

| Purpose | Location / version |
|---|---|
| Android compilation | Existing JDK 17: `E:\Deepseek\Linksi\toolchain\jdk-17` |
| API 36 Robolectric test process | Existing JDK 21.0.4: `C:\Program Files\JetBrains\PyCharm Community Edition 2024.2.4\jbr` |
| Android SDK | `E:\Deepseek\Linksi\toolchain\android-sdk` |
| Gradle / Android Gradle Plugin / Kotlin | 8.13 / 8.13.2 / 2.0.21 |
| compile / minimum / target Android API | 36 / 36 / 36 |
| App bytecode target / Robolectric | JVM 17 / 4.16.1 |
| DataStore Preferences | 1.1.7, production and tests |
| Signing key | Existing original `_working\debug.keystore`; preserved |

DataStore 1.1.7 is the current candidate dependency. Updating from 1.0.0 alone did not resolve the host disk tests: Diagnostic 8b still reproduced second-write rename errors. Inspected bytecode showed that plain JUnit reports Android API 0 and selects a legacy rename fallback. Under Robolectric API 36, the same production factory uses its supported Files.move path. Diagnostic 9 executed all 11 real disk tests with zero failures. Compatibility tests use a binary file written by the original serializer and close/reopen real stores. They do not fake persistence or delete the destination file to make updates pass. See [Android DataStore release notes](https://developer.android.com/jetpack/androidx/releases/datastore).

Compile with JDK 17 and pass the JDK 21 directory through `-Pspm.testJavaHome=<absolute JDK 21 home>` or `JAVA21_HOME`. Do not replace donor toolchains. Missing or unsuitable test runtimes produce an explicit error.

Verification tasks: `:app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest :app:processReleaseMainManifest`. Compiling the Android test APK does not run it on a device.

No phone installation, enrollment, activation, account removal, reset, management removal, or connected-device test is part of this repair.


