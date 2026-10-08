# Power Pause

Schedule when your ordinary Android power menu is allowed.

Power Pause closes the ordinary Power off / Restart menu during your daily hours. The menu can appear briefly. It uses an Accessibility service, needs no running Shizuku, and does not require a factory reset for its Simple edition.

**Start with Simple. Advanced contains experimental Device Owner tools intended for dedicated managed devices.**

## Download the right edition

Download an APK from [Releases](https://github.com/Sadloif/power-pause/releases/latest).

| Edition | Android requirement | Package ID | Purpose |
|---|---|---|---|
| **Simple — recommended for the tested Reno** | Android 16 / API 36+ | `com.example.shutdownprotection` | Working Accessibility schedule, Stop and 60-second trial; managed code excluded |
| **Compatibility — preview for older Androids** | Android 10 / API 29+ | `com.example.shutdownprotection.compatibility` | Simple interface and optional device checks; unknown firmware cannot arm automatic dismissal |
| **Advanced — experimental** | Android 16 / API 36+ | `com.example.shutdownprotection.advanced` | Accessibility plus managed-device tools, with caution and confirmation |

All three can be installed together. **Enable only one edition’s Accessibility service at a time.** Different packages have separate hours, Accessibility approval and manufacturer Allowlist entries. Simple keeps the original package and certificate, so it updates the existing ordinary installation without uninstalling or discarding saved hours. Advanced and Compatibility are separate fresh installations and start paused.

In 0.4.0, Simple and Advanced shared the original package. Updating that ordinary installation with Simple 0.4.1 preserves its data; installing Advanced 0.4.1 creates a separate app. **An existing Device Owner installation must not be replaced by Simple or migrated merely by installing the new Advanced package.** Keep its existing managed recovery tools and plan any management migration separately on the enrolled test device.

The APKs are signed local release builds, not Play Store production releases. Signing keys are not published. Independently built or CI APKs use a different certificate and cannot update these APKs in place.

## Compatibility and honest limits

- Simple and Advanced require Android 16 / API 36. The separate Compatibility APK can install on Android 10 and later, including Android 12. Installation support is different from verified menu recognition.
- Accessibility dismissal is deliberately limited to the physically tested OPPO Reno 15 5G, model CPH2825, display build `CPH2825_16.0.10.501(EX01)` and the observed English menu.
- No blanket compatibility is claimed for other phones, firmware or languages.
- ColorOS needs this app's own Phone Manager Allowlist entry. Android/ColorOS can still disconnect or disable Accessibility; the app reports unavailability and does not re-enable itself.
- Hardware forced restart, emergency functions, battery loss and other shutdown paths remain outside the guarantee. This app does not make a phone impossible to turn off.
- Long-duration, reboot/Doze and a fully observed wall-clock boundary sequence still need separate physical testing.

## Set up Simple

1. Install `Power-Pause-Simple-0.4.1.apk`. A fresh installation starts paused; an update keeps existing settings.
2. Open **Setup → Open Phone Manager**. In **Viruses & risks → Block suspicious app activities → More options → Allowlist**, add **Power Pause Simple**. Keep the main blocking switch on.
3. Open **Setup → Open Accessibility settings** and enable **Power Pause Simple** under downloaded services. Return and confirm the service is connected.
4. In **Schedule**, enter different Start and End times. Use the 24-hour clock: `1430` becomes `14:30`; `0230` becomes `02:30`. A later start than end means overnight.
5. Tap **Save and enable schedule**. The status distinguishes active hours from waiting for the next daily window.
6. **Stop protection now** pauses dismissal without turning off Accessibility. Expiry also leaves the service enabled. The explicit service-off control is in **Tools**.
7. For an optional trial, pause the schedule and use **Tools → Test for 60 seconds**. Open the menu briefly and release power; never select Power off or Restart during a test. Confirm closure, then confirm the menu stays open after expiry.

More setup detail and physical evidence: [No-reset mode](docs/NO_RESET_MODE.md).

## Caution: Advanced managed tools

**Do not experiment with managed settings on your everyday phone.** These are the original Device Owner / lock-task tools. On an enrolled device they can restrict available apps and system controls. Standard Device Owner enrollment normally requires a new-device setup or factory reset. Merely opening the tools does not enroll, reset or erase a phone, but it stops the Accessibility schedule.

Managed power-menu behaviour has **not** been verified on a physical device. Passing desktop tests is not evidence of actual suppression. Use the Advanced edition only for intentional testing or recovery on a dedicated compatible managed device. Read [Advanced mode and each option](docs/ADVANCED_MODE.md) and [Recovery](docs/RECOVERY.md) first. “Restore Normal Device Mode” restores app-applied managed rules; it is not a factory-reset button and does not remove Device Owner enrollment.

Historical provisioning commands are development records, not instructions for your personal phone. Hardware forced restart remains outside either edition's guarantee.

## Build and verify all editions

Requirements: Android SDK platform/build-tools 36, JDK 17 for Android compilation, JDK 21 for API 36 Robolectric, and the checked-in Gradle wrapper. Set `ANDROID_HOME` to your SDK; set `JAVA_HOME` to JDK 17. On Windows use `gradlew.bat` instead of `./gradlew`.

```sh
./gradlew :app:testSimpleDebugUnitTest :app:testCompatibilityDebugUnitTest :app:testAdvancedDebugUnitTest -Pspm.testJavaHome=/path/to/jdk-21
./gradlew :app:lintSimpleRelease :app:lintCompatibilityRelease :app:lintAdvancedRelease
./gradlew :app:assembleSimpleRelease :app:assembleCompatibilityRelease :app:assembleAdvancedRelease
```

For locally signed updates, set `DEBUG_KEYSTORE_PATH` to your existing private keystore. Optional `DEBUG_KEYSTORE_PASSWORD`, `DEBUG_KEY_ALIAS`, and `DEBUG_KEY_PASSWORD` override standard debug-key defaults. The default application ID is `com.example.shutdownprotection`; `-Pspm.appId=...` overrides it. Changing the ID creates another app with separate data and approvals.

Simple executes the no-reset service/gate tests and edition-isolation checks. Compatibility also launches its actual application/activity on simulated API 29–36, verifies managed classes are absent, and tests the optional Back trial and cancellation on those versions. Advanced executes the complete managed and no-reset regression suite. Desktop framework tests do not prove physical menu recognition or manufacturer service lifetime.

CI builds all three editions and uploads test APKs. Those debug artifacts use CI signing and are for developer verification; use signed release assets for ordinary installation. Source, tests, launcher icon assets, Gradle wrapper and documentation are published. Caches, private keys, credentials, screenshots, device data and local build outputs are excluded.

The application name is declared explicitly per edition in Android’s manifest. Bitmap launcher/round icons at all standard densities provide a fallback for OEM launchers and App info. No launcher data clearing is required by the app.

## Documentation

- [Android 10+ compatibility preview and physical test guide](docs/ANDROID10_COMPATIBILITY.md)
- [Advanced tools: caution, benefits and options](docs/ADVANCED_MODE.md)
- [Accessibility setup, physical evidence and limits](docs/NO_RESET_MODE.md)
- [Architecture](docs/ARCHITECTURE.md)
- [Managed recovery](docs/RECOVERY.md)
- [Current separate-edition verification record](docs/SEPARATE_EDITIONS_RELEASE.md)
- [Historical 0.4.0 edition verification](docs/EDITIONS_RELEASE.md)
- [Earlier repair results](docs/REPAIR_RESULTS.md)

Earlier reports are preserved under `docs/history`; they describe older builds and do not override the current compatibility limits or cautions.
