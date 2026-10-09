# Android 10+ Compatibility edition

This is a separate Simple app, minimum Android 10 / API 29, package `com.example.shutdownprotection.compatibility`. It contains no managed-device implementation or Device Admin receivers. No factory reset, enrollment, root or running Shizuku is required.

## What is and is not complete

Current Compatibility 0.4.5 also includes the shared Reno shutdown-password cancellation physically tested in Simple 0.4.4. The port has passed desktop tests but still needs its own Oppo/Poco phone checks. Poco retains ordinary menu dismissal only. See [0.4.5 verification and safe setup](COMPATIBILITY_RENO_AUTH.md).

The older-version APK and Simple interface are built for Android 10+. Framework tests execute application/activity launch, edition isolation and explicit Back trials on API 29 through 36. These checks are simulated Android framework execution, not phone observations.

Compatibility 0.4.2 recognizes the tested Reno CPH2825 / Android 16 / build `CPH2825_16.0.10.501(EX01)` profile and the Poco X3 Pro M2102J20SG / Android 13 / Xiaomi / display `TKQ1.221013.002 test-keys` / incremental `V14.0.3.0.TJUMIXM` profile. Both require the observed English menu. Other firmware stays inactive. Changing minimum SDK cannot create a correct OEM detector. See [0.4.2 results and step-by-step setup](POCO_COMPATIBILITY_RELEASE.md) for actual checks, the initial failed trial and remaining physical checks.

Unknown phones can use two optional checks in Tools: read-only System UI event observation for 45 seconds and one owner-requested Back after 20 seconds. Neither learns a profile, enables a schedule or proves automatic protection. Events are bounded to eight records in memory containing model, API, System UI class and window ID. No screen text, node children or screenshots are collected or uploaded. Stop, interruption and disconnect cancel pending Back; schedule edits invalidate it; a late callback is discarded. The service stays approved after a normal test ends.

## Historical 0.4.1 Poco X3 Pro / Android 13 checks

The following records the initial 0.4.1 investigation. Its statement that automatic protection was unavailable describes that older version. The 0.4.2 detector and successful automatic actual-phone tests are recorded separately above.

8 October 2026: the connected spare phone reported model M2102J20SG, Android 13 / API 33 and MIUI build V14.0.3.0.TJUMIXM. Simple 0.4.1 was refused with INSTALL_FAILED_OLDER_SDK because it requires API 36. Compatibility 0.4.1 installed and opened successfully. Its Accessibility service was approved by the owner and independently listed as enabled and bound by Android.

During the owner-requested observation, the app recorded a System UI window event with class android.app.Dialog. A read-only window-manager check identified the visible power menu as MiuiGlobalActions. The Accessibility window had a null title. These observations establish event delivery, not automatic recognition or dismissal. Matching every System UI android.app.Dialog would be too broad. Automatic protection on this Poco remains unavailable pending a sufficiently specific profile and physical validation.

The owner subsequently confirmed that the explicit one-Back trial closed the menu after approximately 20 seconds. After that trial, Android still listed the Compatibility service as enabled and bound with no crashed service, and the visible app showed both temporary countdowns at zero. This proves a manually requested dismissal on this firmware. It does not prove an automatic schedule, repeated automatic detection, locked/background behavior or long-term service availability. No reset, data clearing, enrollment, reboot or shutdown was performed. The Reno was not connected or changed.

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

### Avoid interfering with Accessibility while checking it

Do not run the stock `uiautomator dump` during a service-lifetime, observation or dismissal test. Android [UiAutomation](https://developer.android.com/reference/android/app/UiAutomation#FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES) suppresses other Accessibility services by default unless FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES is supplied. The [stock wrapper](https://android.googlesource.com/platform/prebuilts/fullsdk/sources/android-31/+/refs/heads/main/com/android/uiautomator/core/UiAutomationShellWrapper.java) connects without that flag.

The Poco investigation exposed this interference: screen-text inspection coincided with a disconnected app status, while screenshots and Android's direct Accessibility state showed the service connected. In this app, a service reconnect intentionally cancels temporary tests and observation. Such inspection can therefore invalidate a test. Use direct `dumpsys accessibility`, screenshots and the owner's observation instead, or a separately validated inspector that explicitly preserves existing services.

Earlier disconnected readings collected during stock hierarchy inspection cannot by themselves establish an OEM service failure. Actual owner-observed service disablement and manufacturer blocking notices remain separate evidence. Do not assume every disconnect is caused by this inspector either.

Android’s [build variants](https://developer.android.com/build/build-variants) support different minimum SDKs and source sets. [AccessibilityService](https://developer.android.com/reference/android/accessibilityservice/AccessibilityService) exposes global Back and window events on versions covered here. Availability of an API does not establish OEM window identity or reliability. Android’s [AOSP GlobalActionsDialogLite source](https://android.googlesource.com/platform/frameworks/base/+/aae87b9485629fd876903bbabba6443bae21fc65/packages/SystemUI/src/com/android/systemui/globalactions/GlobalActionsDialogLite.java) illustrates another implementation; the observed OPPO class is manufacturer-specific. This is why support needs per-device evidence.
