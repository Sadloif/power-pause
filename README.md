# Power Pause

Schedule when your ordinary Android power menu is allowed.

Power Pause closes the ordinary Power off / Restart menu during your daily hours. The menu can appear briefly. It uses an Accessibility service, needs no running Shizuku, and does not require a factory reset for its Simple edition.

**Start with Simple. Advanced contains experimental Device Owner tools intended for dedicated managed devices.**

## Download the right edition

Download an APK from [Releases](https://github.com/Sadloif/power-pause/releases/latest).

| | Simple — recommended | Advanced — experimental |
|---|---|---|
| Daily Accessibility schedule | Yes | Yes |
| Automatic four-digit 24-hour input | Yes | Yes |
| Stop and 60-second trial | Yes | Yes |
| Managed-device tools in the interface | Absent | Present, with caution and confirmation |
| Device Admin and managed alarm receivers | Absent | Present |
| Managed startup wiring | Not initialized | Available |
| Intended use | The tested everyday-phone workflow | Deliberate managed-device testing and recovery |

Both editions use the same package ID and release certificate. Install one edition at a time. Updating from the previous app or switching between these supplied APKs preserves saved hours and Accessibility approval; check the current status afterwards. Do not uninstall first if you want to keep saved data. **Do not switch editions while this app is Device Owner or a managed session is active.** Simple has no managed recovery tools; restore and plan management removal separately before considering a switch.

The APKs are signed local release builds, not Play Store production releases. Signing keys are not published. Independently built or CI APKs use a different certificate and cannot update these APKs in place.

## Compatibility and honest limits

- Android 16 / API 36 is required. The current APK cannot install on Android 12.
- Accessibility dismissal is deliberately limited to the physically tested OPPO Reno 15 5G, model CPH2825, display build `CPH2825_16.0.10.501(EX01)` and the observed English menu.
- No blanket compatibility is claimed for other phones, firmware or languages.
- ColorOS needs this app's own Phone Manager Allowlist entry. Android/ColorOS can still disconnect or disable Accessibility; the app reports unavailability and does not re-enable itself.
- Hardware forced restart, emergency functions, battery loss and other shutdown paths remain outside the guarantee. This app does not make a phone impossible to turn off.
- Long-duration, reboot/Doze and a fully observed wall-clock boundary sequence still need separate physical testing.

## Set up Simple

1. Install `Power-Pause-Simple-0.4.0.apk`. A fresh installation starts paused; an update keeps existing settings.
2. Open **Setup → Open Phone Manager**. In **Viruses & risks → Block suspicious app activities → More options → Allowlist**, add **Power Pause**. Keep the main blocking switch on.
3. Open **Setup → Open Accessibility settings** and enable **Power Pause** under downloaded services. Return and confirm the service is connected.
4. In **Schedule**, enter different Start and End times. Use the 24-hour clock: `1430` becomes `14:30`; `0230` becomes `02:30`. A later start than end means overnight.
5. Tap **Save and enable schedule**. The status distinguishes active hours from waiting for the next daily window.
6. **Stop protection now** pauses dismissal without turning off Accessibility. Expiry also leaves the service enabled. The explicit service-off control is in **Tools**.
7. For an optional trial, pause the schedule and use **Tools → Test for 60 seconds**. Open the menu briefly and release power; never select Power off or Restart during a test. Confirm closure, then confirm the menu stays open after expiry.

More setup detail and physical evidence: [No-reset mode](docs/NO_RESET_MODE.md).

## Caution: Advanced managed tools

**Do not experiment with managed settings on your everyday phone.** These are the original Device Owner / lock-task tools. On an enrolled device they can restrict available apps and system controls. Standard Device Owner enrollment normally requires a new-device setup or factory reset. Merely opening the tools does not enroll, reset or erase a phone, but it stops the Accessibility schedule.

Managed power-menu behaviour has **not** been verified on a physical device. Passing desktop tests is not evidence of actual suppression. Use the Advanced edition only for intentional testing or recovery on a dedicated compatible managed device. Read [Advanced mode and each option](docs/ADVANCED_MODE.md) and [Recovery](docs/RECOVERY.md) first. “Restore Normal Device Mode” restores app-applied managed rules; it is not a factory-reset button and does not remove Device Owner enrollment.

Historical provisioning commands are development records, not instructions for your personal phone. Hardware forced restart remains outside either edition's guarantee.

## Build and verify both editions

Requirements: Android SDK platform/build-tools 36, JDK 17 for Android compilation, JDK 21 for API 36 Robolectric, and the checked-in Gradle wrapper. Set `ANDROID_HOME` to your SDK; set `JAVA_HOME` to JDK 17. On Windows use `gradlew.bat` instead of `./gradlew`.

```sh
./gradlew :app:testSimpleDebugUnitTest :app:testAdvancedDebugUnitTest -Pspm.testJavaHome=/path/to/jdk-21
./gradlew :app:lintSimpleRelease :app:lintAdvancedRelease
./gradlew :app:assembleSimpleRelease :app:assembleAdvancedRelease
```

For locally signed updates, set `DEBUG_KEYSTORE_PATH` to your existing private keystore. Optional `DEBUG_KEYSTORE_PASSWORD`, `DEBUG_KEY_ALIAS`, and `DEBUG_KEY_PASSWORD` override standard debug-key defaults. The default application ID is `com.example.shutdownprotection`; `-Pspm.appId=...` overrides it. Changing the ID creates another app with separate data and approvals.

Simple executes the no-reset gate/service tests and edition-isolation checks. Advanced executes the complete managed and no-reset regression suite. The isolation tests inspect actual merged receivers/permissions and prove Simple startup/activity do not initialize managed wiring, even when a test shadow reports Device Owner.

CI builds both editions and uploads test APKs. Those debug/test-only artifacts are for developer verification; use the signed release assets for ordinary installation. The repository contains source, tests, Gradle wrapper and documentation. Caches, keys, credentials, screenshots, device data and local build outputs are excluded.

## Documentation

- [Advanced tools: caution, benefits and options](docs/ADVANCED_MODE.md)
- [Accessibility setup, physical evidence and limits](docs/NO_RESET_MODE.md)
- [Architecture](docs/ARCHITECTURE.md)
- [Managed recovery](docs/RECOVERY.md)
- [Edition build and verification record](docs/EDITIONS_RELEASE.md)
- [Earlier repair results](docs/REPAIR_RESULTS.md)

Earlier reports are preserved under `docs/history`; they describe older builds and do not override the current compatibility limits or cautions.
