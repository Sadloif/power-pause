# Android 10+ Compatibility edition — installation preview

This is a separate Simple app, minimum Android 10 / API 29, package `com.example.shutdownprotection.compatibility`. It contains no managed-device implementation or Device Admin receivers. No factory reset, enrollment, root or running Shizuku is required.

## What is and is not complete

The older-version APK and Simple interface are built for Android 10+. Framework tests execute application/activity launch, edition isolation and explicit Back trials on API 29 through 36. These checks are simulated Android framework execution, not phone observations.

Automatic menu recognition remains restricted to the physically tested Reno CPH2825, Android 16, exact build `CPH2825_16.0.10.501(EX01)` and observed English menu. On Android 10–15 or another firmware, automatic schedule and the 60-second automatic trial remain unavailable. Changing minimum SDK cannot create a correct OEM menu detector. The Compatibility title and status explicitly disclose this limitation.

Unknown phones can use two optional checks in Tools: read-only System UI event observation for 45 seconds and one owner-requested Back after 20 seconds. Neither learns a profile, enables a schedule or proves automatic protection. Events are bounded to eight records in memory containing model, API, System UI class and window ID. No screen text, node children or screenshots are collected or uploaded. Stop, interruption and disconnect cancel pending Back; schedule edits invalidate it; a late callback is discarded. The service stays approved after a normal test ends.

## Poco X3 Pro / Android 12: next physical test

1. Use the spare phone. Keep the main Reno’s Simple installation unchanged.
2. Install `Power-Pause-Compatibility-0.4.1.apk`. Open it and confirm Compatibility preview and paused state.
3. In Accessibility settings, enable only Power Pause Compatibility. Leave unrelated services/settings alone. If another Power Pause service is enabled, switch that service off before this test.
4. Return to Setup. Unknown firmware may report unsupported automatic protection even while Android has connected the service; this is expected. A connected service enables the optional Tools checks.
5. In Tools, tap Observe only for 45 seconds. Briefly open the normal power menu, release power and leave the menu visible for about ten seconds. Do not choose Power off or Restart. Dismiss normally with Back. Record the class/window details from the app after returning. Observation sends no action.
6. Tap One Back after 20 seconds. While at least ten seconds remain, open the ordinary power menu and release power. Do not select any menu option. Observe whether it closes once at the end. If the menu is not open, Back may leave the current screen. Cancel checks and stop cancels the request.
7. Confirm the normal menu remains available afterwards and Accessibility remains enabled. Report any manufacturer blocking notice, the full build, menu language and observation result.
8. Repeat the single Back check only after the first result is understood. Check background/locked behavior separately. No reset or shutdown is part of this procedure.
9. Before adding automatic support, a developer must implement an exact firmware/event/window profile and prove that unrelated System UI dialogs remain untouched. Then perform repeated menu openings, Stop/expiry, background/locked and service lifetime tests. Do not enable broad matching merely on package `com.android.systemui`.

## Platform evidence

Android’s [build variants](https://developer.android.com/build/build-variants) support different minimum SDKs and source sets. [AccessibilityService](https://developer.android.com/reference/android/accessibilityservice/AccessibilityService) exposes global Back and window events on versions covered here. Availability of an API does not establish OEM window identity or reliability. Android’s [AOSP GlobalActionsDialogLite source](https://android.googlesource.com/platform/frameworks/base/+/aae87b9485629fd876903bbabba6443bae21fc65/packages/SystemUI/src/com/android/systemui/globalactions/GlobalActionsDialogLite.java) illustrates another implementation; the observed OPPO class is manufacturer-specific. This is why support needs per-device evidence.
