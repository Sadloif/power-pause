# Power Pause Compatibility 0.4.2 — POCO X3 Pro

8 October 2026. This Compatibility update adds a separate automatic menu detector for the connected POCO X3 Pro. The existing Simple 0.4.1 Reno APK and Advanced 0.4.1 APK are retained unchanged.

## Exact supported profiles

The new profile requires Compatibility edition, model M2102J20SG, Android 13 / API 33, manufacturer Xiaomi, display build TKQ1.221013.002 test-keys and incremental build V14.0.3.0.TJUMIXM. It matches the observed English menu labels. Another build, language or menu layout does not automatically qualify. The existing exact Reno CPH2825 / Android 16 profile remains available; Simple keeps its Android 16 minimum and Reno profile.

Compatibility installs on Android 10 / API 29 and later. That minimum does not promise automatic recognition on every phone. Unverified firmware stays inactive, with optional diagnostic checks in Tools.

## Detector and safety boundaries

An event must be WINDOW_STATE_CHANGED from com.android.systemui with class android.app.Dialog. Its positive window ID must identify an active, focused SYSTEM window, a null Accessibility title and a System UI FrameLayout root. Those generic fields alone never authorize a dismissal.

The app then checks exactly nine nodes: FrameLayout → LinearLayout → FrameLayout with android:id/content → FrameLayout with five leaf Views. All belong to System UI. Leaf descriptions must be Back, Aeroplane, Silent, Reboot and Power off, in the captured order. Extra/missing nodes, different classes, IDs, descriptions or package names refuse the action. It reads only this bounded layout and accessibility descriptions, not general screen text. It does not click any node or choose a power-menu option. The sole dismissal action is GLOBAL_ACTION_BACK, at most once per registered menu window.

Only Compatibility's service XML includes retrieve-interactive-windows, report-view-IDs and include-not-important-views. The first physical automatic trial failed because the diagnostic capture included structural layout nodes while the initial app XML omitted them. A direct comparison showed seven nodes under flags 80, versus nine with the extra layout flag. The correction uses flags 82; Android's live enabled-service configuration independently confirmed 82. A regression loads the merged manifest's real AccessibilityServiceInfo and verifies all required flag bits.

Stop and expiry keep Accessibility enabled. Stop, disconnect, edits and expired clocks prevent stale work. An automatic trial cancels a pending manual Back/observation; the manual trial cannot start while the automatic trial runs. Simple and Compatibility still contain no managed application/container, admin receivers, policy implementation or managed alarm/boot components. No enrollment, root, running Shizuku, reset or data clearing is involved.

## Executed checks

| Check | Result |
|---|---|
| Simple regression tests | 25; zero failures/errors/skips |
| Compatibility regression tests | 78; zero failures/errors/skips |
| Advanced regression tests | 264; zero failures/errors/skips |
| Release lint | Zero errors in all three editions; warnings remain |
| New APK identity | Compatibility package, version 0.4.2/code 7, min API 29, target API 36 |
| Signing | Same existing certificate as Compatibility 0.4.1; in-place upgrade |
| Actual APK inspection | Managed implementation classes/packages absent; bitmap icon and edition name present |
| Poco installation and Accessibility | Installed without clearing data; service enabled and bound |
| Manual one-Back trial in 0.4.1 | Owner confirmed menu closed after about 20 seconds |
| Corrected 0.4.2 automatic dismissal | Three foreground openings closed automatically on the physical Poco |
| Background / locked | Two openings after Home and two with secure keyguard showing closed automatically |
| Unrelated System UI | Quick-settings panel stayed open during the trial |
| Expiry / service lifetime | A menu opened after expiry stayed visible; Accessibility remained enabled and bound |
| Physical power-button repetition / Stop / clock boundary | Owner confirmation pending |

The repeated, background, locked and expiry checks above opened the normal menu through Android's GLOBAL_ACTION_POWER_DIALOG on the actual phone; focus readback confirmed that the app closed it, and a screenshot confirmed the first closure. That helper opens the dialog and never selects any option. An earlier attempt to automate the app's controls through a separate helper was killed and did not start a trial; it is not counted as a failed detector test. The successful trials were started through the app's visible control after checking that its own activity was focused.

The owner's physical power-button repetition, Stop and an actual wall-clock schedule boundary must still be confirmed. Desktop tests do not substitute for those observations. Long-term service availability, reboot/Doze and every possible menu state remain unverified.

## Set up on the tested Poco

1. Install Power-Pause-Compatibility-0.4.2.apk. Keep an existing installation and update it; do not uninstall or clear data first.
2. Open Setup → Open Accessibility settings. Under downloaded services, enable only Power Pause Compatibility. Keep other apps' services unchanged. Return and confirm the large card says Accessibility connected.
3. A fresh installation is Paused. Enabling Accessibility alone does not start a schedule. If the card says Phone not supported, your model/build differs from the tested profile; do not bypass that check.
4. In Schedule, use the 24-hour clock. Type 1430 for 14:30 or 0230 for 02:30. Choose different Start and End values. A start later than the end means the window continues overnight.
5. Tap Save and enable schedule. During those daily hours, the ordinary power menu should close after it appears. Outside the window it stays available. Android's normal emergency and hardware restart functions are outside the app's control.
6. Stop protection now pauses dismissal and retains Accessibility approval. The separate Turn off Accessibility service control explicitly revokes the running service; ordinary Stop does not.
7. To test briefly, pause the schedule and use Tools → Test for 60 seconds. Briefly open the menu and release power. Never choose Power off or Restart during testing. After the minute, verify the menu stays visible and the connection card remains connected.
8. If Android disconnects the service, protection is unavailable. Reconnect it in Accessibility settings and check the app. This app does not silently re-enable itself or guarantee permanent service availability.

Use screenshots, direct Android service state and owner observation for physical tests. Stock uiautomator dump can suppress existing Accessibility services and invalidate a trial; see ANDROID10_COMPATIBILITY.md.
