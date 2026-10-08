# Scheduled Power Menu Restriction

An Android 16 (API 36) Device Policy Controller that restricts the normal Android
**Power Off / Restart** menu during a chosen daily interval on a managed device.

> Restricts the normal Android Power Off and Restart menu during your selected hours on a
> managed device. Hardware forced restart, manufacturer emergency functions, battery loss, and
> every possible source of shutdown are outside this feature's guarantee.

That sentence is the honest scope of the feature. This app does **not** make a phone impossible
to turn off, is not anti-theft protection, and does not prevent every source of shutdown.

---

## What it is, and what it is not

- It **is** a Device Policy Controller. It needs **Device Owner** provisioning on a dedicated
  device. Installing the APK, or granting ordinary Device Administrator access, does **not**
  enable the feature.
- It **is not** an app that gains this capability from an install and a permission prompt.
- It uses only public, documented Android APIs: `DevicePolicyManager` lock-task policy,
  `AlarmManager`, `DataStore`, and Compose. No root, Accessibility Service, power-key
  interception, hidden API, System UI reflection, runtime shell command, Shizuku, system-file
  modification, or vendor privilege bypass.
- There is **no network permission at all**: no analytics, no advertising, no telemetry.

## How it works

While armed, the app holds a real **Lock Task Mode** session for the whole armed period, and
schedule boundaries change only whether global actions are permitted:

| Situation | Behaviour |
|---|---|
| Disarmed | App restriction removed, managed session exited, the captured policy baseline restored |
| Armed, outside protected hours | **Power menu allowed — managed session active** |
| Armed, inside protected hours | Global actions restricted, once every prerequisite is verified |

Two masks are used, built from the platform constants:

```kotlin
protectedFeatures = SYSTEM_INFO | NOTIFICATIONS | HOME | OVERVIEW | KEYGUARD   // 47
allowedFeatures   = protectedFeatures | GLOBAL_ACTIONS                        // 63
```

The device's own original configuration is captured as a **baseline before the first policy
mutation** and restored on full disarm. `allowedFeatures` is never treated as the baseline.

## Limitations you should read before using it

1. **Quick Settings is expected to remain unavailable** under the documented notifications
   feature. This is a known limitation of the Lock Task architecture and is not restored by
   adding unrelated flags.
2. **Some application workflows are restricted** while the session is active: permission
   dialogs, authentication handoffs, share sheets, document pickers, and camera handoffs are
   separate compatibility cases. Allowlisting a package is not proof that every workflow
   involving it works.
3. **Hardware forced restart, recovery mode, battery removal, OS updates, and every OEM
   shutdown path are outside the guarantee.** So is a hard bounded release after a force-stop or
   after exact-alarm permission is revoked — this architecture does not establish that.
4. **A reboot does not resume protection by itself.** The baseline MVP restores the schedule and
   reports honestly, but the user must unlock the device, open the app, and explicitly resume
   the managed session. Automatic unattended re-entry and protection before the first unlock are
   **separate objectives that are not met by this baseline**.
5. **The 60-second transition figure used in testing is a project test threshold, not a
   guarantee supplied by Android.** The configured time expresses the desired boundary, not a
   zero-latency promise.
6. **This is a dedicated-device feature.** It is not an ordinary unrestricted personal-phone
   experience, and it is not presented as one.
7. **Device behavior is not claimed until it is observed on that device and build.** The app
   shows "Device behavior not yet validated" until a manual observation is recorded. A
   successful build, a passing unit test, or an emulator run is not that observation.

## Schedule semantics

The interval repeats every calendar day in the device's **current** time zone
(`ZoneId.systemDefault()`), and is *not* pinned to the zone it was created in. Changing the
time zone re-evaluates membership immediately.

- start < end → the interval starts and ends on the same local day
- start > end → the interval starts on the day and ends on the **next** local day (crosses
  midnight)
- start == end → rejected with "Start and end must be different." It is never inferred as a
  zero-hour or 24-hour interval
- membership is `startInstant <= now < endInstant`, so the start instant is included and the end
  instant is excluded

### Daylight-saving rules

Boundaries are rebuilt from local calendar dates through the zone's own rules; a fixed 24 hours
is never added to an epoch timestamp.

1. **Ordinary local time** — use its sole valid offset.
2. **Nonexistent local time** (spring-forward gap) — use the **first valid instant at or after
   the gap's end**. A nonexistent clock time is never invented. The gap's end is located by
   walking real zone transitions.
3. **Ambiguous start time** (clock overlap) — choose the **earlier** instant.
4. **Ambiguous end time** (clock overlap) — choose the **later** instant.
5. If those rules **collapse** the interval so `endInstant <= startInstant`, that day's interval
   is **skipped and the reason recorded**. It is never turned into all-day protection.
6. Membership is evaluated against the intervals anchored on **yesterday and today**, so an
   overnight interval that began yesterday is still recognised during an overlap.

The Diagnostics screen shows the actual next start/end with its time zone.

## Project layout

```
app/src/main/java/com/example/shutdownprotection/
  admin/        DevicePolicyGateway, DevicePolicyController, admin receiver, policy receiver
  protection/   ProtectionState, seams, ProtectionCoordinator, RecoveryManager
  scheduling/   ScheduleCalculator, ScheduleManager, AlarmActions, alarm + system receivers
  data/         ProtectionSettings, SettingsRepository, PolicyBaseline, DiagnosticsRepository
  ui/           MainActivity, MainViewModel, MainScreen, Setup/Schedule/AllowedApps/
                Diagnostics/Poc screens
app/src/main/res/xml/device_admin_receiver.xml, data_extraction_rules.xml
app/src/debug/AndroidManifest.xml     debug-only android:testOnly="true"
app/src/test/                          JVM unit tests
app/src/androidTest/                   instrumented public-API tests
docs/                                  ENVIRONMENT, API_FACTS, ARCHITECTURE, TEST_PLAN,
                                       TEST_RESULTS, RECOVERY, COMPATIBILITY_MATRIX,
                                       PRODUCTION_FEASIBILITY
```

## Building

The build is pinned to Gradle 8.13, AGP 8.13.2, Kotlin 2.0.21, compileSdk/minSdk/targetSdk 36.
No dynamic versions are used.

```powershell
$env:JAVA_HOME           = "E:\Deepseek\Linksi\toolchain\jdk-17"
$env:ANDROID_HOME        = "E:\Deepseek\Linksi\toolchain\android-sdk"
$env:GRADLE_USER_HOME    = "E:\Deepseek\Projects\Scheduled-Power-Menu-Restriction\_working\gradle-home"
$env:DEBUG_KEYSTORE_PATH = "E:\Deepseek\Projects\Scheduled-Power-Menu-Restriction\_working\debug.keystore"
Set-Location "E:\Deepseek\Projects\Scheduled-Power-Menu-Restriction"

.\gradlew.bat :app:testDebugUnitTest     # JVM unit tests
.\gradlew.bat :app:lintDebug             # lint (abortOnError = true)
.\gradlew.bat :app:assembleDebug         # debug APK
```

### Overriding the application ID

One documented Gradle property. The Kotlin namespace stays
`com.example.shutdownprotection`; only the built `applicationId` changes, and every action
string, extra key, and component name is derived from the actual ID at runtime.

```powershell
.\gradlew.bat :app:assembleDebug -Pspm.appId=com.example.spstest
```

## Provisioning (development)

### Four separate actions — do not conflate them

| # | Action | What it does **not** do |
|---|---|---|
| 1 | **Install the APK** | Does not grant any policy capability. Ordinary Device Administrator access is not enough |
| 2 | **Enroll as Device Owner** | Does not start a session or restrict anything by itself |
| 3 | **Restore Normal Device Mode** (in-app) | Ends the managed session and restores the original policy — but **does NOT remove Device Owner management** |
| 4 | **Remove Device Owner management** | A **debug/test-only** escape route here, not an ordinary uninstall guarantee |

The debug build carries `android:testOnly="true"` from `app/src/debug/AndroidManifest.xml`, which
is what makes `adb install -t` and `dpm remove-active-admin` work on an owner app. It is absent
from the release manifest, so action 4 does **not** exist for a release build.

```powershell
$ADB    = "E:\Deepseek\Linksi\toolchain\android-sdk\platform-tools\adb.exe"
$SERIAL = "<TEST_DEVICE_SERIAL>"
$ADMIN  = "com.example.shutdownprotection/.admin.ShutdownProtectionAdminReceiver"

# Inspect first: never remove someone else's device management.
& $ADB -s $SERIAL shell dpm list-owners

# 1 + 2: install the DEBUG test-only APK, then enroll
& $ADB -s $SERIAL install -t app\build\outputs\apk\debug\app-debug.apk
& $ADB -s $SERIAL shell dpm set-device-owner $ADMIN
& $ADB -s $SERIAL shell dpm list-owners

# 4: remove management (debug/test-only escape route)
& $ADB -s $SERIAL shell dpm remove-active-admin $ADMIN
```

**Enrollment prerequisites must be checked, not worked around.** `dpm set-device-owner` refuses to
run when the device already has an owner, has accounts set up, or is already provisioned. If a
prerequisite fails, **stop** — do not clear setup flags, remove accounts, or reset anything
automatically. The full prerequisite list and the four-action model are in `docs/RECOVERY.md` §1–§3.

> The `device_provisioned` / `user_setup_complete` sequence recorded in `docs/TEST_RESULTS.md` §4.1
> is **emulator-only historical evidence**. It must not be applied to a physical phone.

## Using the app

1. **Setup and prerequisites** — confirm Android version, Device Owner state, admin receiver
   identity, default launcher, runtime state, exact-alarm capability, and recovery readiness.
2. **Change start / end** — set the daily interval in 24-hour `HH:mm`. An edit that changes
   current protection says so immediately.
3. **Manage allowed applications** — pick the launcher and the apps the device needs. Saving
   fully disarms first and requires an explicit resume, so tasks are never changed silently
   beneath a running session.
4. **Enable / resume managed session** — the first enable shows the practical limitations,
   including the unavailable Quick Settings, and the enable button is part of that disclosure.
5. **Restore Normal Device Mode** — always present, works inside or outside the interval, and
   needs no network, subscription, or valid schedule.
6. **Diagnostics** — settings revision, requested/effective masks, package list, runtime state,
   boot/unlock state, submitted alarm times, policy results, recovery status, and a bounded
   event log (1,000 records or 1 MiB) with a deliberate local export.
7. **Development test controls** (debug builds only) — the manual Gate B proof-of-concept:
   start a managed session, allow or restrict the power menu by hand, and restore. Restricting
   requires the temporary release timer to be submitted first; if that fails, the restrictive
   mask is not applied.

## Tests

```powershell
.\gradlew.bat :app:testDebugUnitTest
```

The JVM suite covers schedule arithmetic (including DST gaps, overlaps, a gap-collapsed window,
leap day, year rollover, and zone changes) and coordinator/recovery behaviour (missing ownership,
PINNED versus LOCKED, missing exact capability, scheduling failure, stale events, a newer
disabled revision, an absent session after boot, an interrupted arm journal, concurrent edit and
alarm, and interrupted temporary tests). See `docs/TEST_PLAN.md` for the full plan and
`docs/TEST_RESULTS.md` for what was actually observed.

## Documentation

| File | Contents |
|---|---|
| `docs/ENVIRONMENT.md` | Toolchain, pinned versions, test surfaces, and what is blocked |
| `docs/API_FACTS.md` | Platform 36 API signatures and constant values verified from `android.jar` |
| `docs/ARCHITECTURE.md` | State model, storage, event handling, alarm identities, rollback, policy verification, permission and exported-component inventory |
| `docs/RECOVERY.md` | In-app recovery, development escape route, emulator reset path |
| `docs/TEST_PLAN.md` | Gates A–E, the case list, and the evidence rules |
| `docs/TEST_RESULTS.md` | What was actually observed, with device fingerprints and honest labels |
| `docs/COMPATIBILITY_MATRIX.md` | Per-device and per-workflow results |
| `docs/PRODUCTION_FEASIBILITY.md` | Device Owner enrollment, deployment audience, and why APK installation is insufficient |
