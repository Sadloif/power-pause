# Power Pause

An Android 16 (API 36) app with a daily power-menu schedule and two separate modes.

Version 0.3.0 provides **no-reset Accessibility menu dismissal** for the tested OPPO Reno 15,
four-digit 24-hour input (`0230` → `02:30`), and a service that stays enabled when dismissal ends.
The new interface separates Schedule, Setup and Tools, with a matching launcher icon and light/dark themes. A fresh installation opens this mode with protection disabled. It needs the owner's
Accessibility approval and a per-app ColorOS Phone Manager Allowlist entry, but no reset,
Device Owner enrollment or running Shizuku. Setup, results and remaining checks are in
[docs/NO_RESET_MODE.md](docs/NO_RESET_MODE.md). The separate probe passed repeated, background,
locked-screen and expiry trials; final app verification is recorded separately there.

The existing **Device Owner managed mode** remains separate. See `docs/REPAIR_RESULTS.md` and
`docs/TEST_RESULTS.md` for its October 7 verification and exact evidence. Physical behaviour
of that managed backend has not been established. Do not provision the owner's primary phone.
Earlier reports are preserved under `docs/history/before-7-october-2026-repair`.

> Restricts the normal Android Power Off and Restart menu during your selected hours on a
> managed device. Hardware forced restart, manufacturer emergency functions, battery loss, and
> every possible source of shutdown are outside this feature's guarantee.

That sentence is the honest scope of the feature. This app does **not** make a phone impossible
to turn off, is not anti-theft protection, and does not prevent every source of shutdown.

---

## The two modes

| Mode | Requirements | Behaviour |
|---|---|---|
| No-reset menu dismissal | Tested Reno firmware, manually enabled Accessibility service, this app in Phone Manager Allowlist | Closes the exact normal power menu during selected hours; may briefly appear; no OS power setting changed |
| Device Owner managed mode | Existing Device Owner enrollment on a dedicated device | Uses lock-task policy and the original managed recovery implementation |

The no-reset mode has its own configuration, service and Stop controls. It does not enter a
lock-task session or modify managed policy baselines. Reboot/Doze, other firmware/languages
and long-duration OEM behaviour remain unverified. Both modes have no network permission.

## Managed mode implementation

- The managed backend **is** a Device Policy Controller. It needs **Device Owner** provisioning on a dedicated
  device. Installing the APK, or granting ordinary Device Administrator access, does **not**
  enable the feature.
- Its managed-policy capability does not follow from ordinary installation or an admin prompt.
- The managed backend uses public Android APIs: `DevicePolicyManager` lock-task policy,
  `AlarmManager`, `DataStore`, and Compose. The separate no-reset mode uses Accessibility.
  No root, power-key
  interception, hidden API, System UI reflection, runtime shell command, Shizuku, system-file
  modification, or vendor privilege bypass.
- There is **no network permission at all**: no analytics, no advertising, no telemetry.

## How managed mode works

While armed, the app holds a real **Lock Task Mode** session for the whole armed period, and
schedule boundaries change only whether global actions are permitted:

| Situation | Behaviour |
|---|---|
| Disarmed | Managed session absent; clean existing policy preserved, or trusted original restored and cleanup verified |
| Armed, outside protected hours | **Power menu allowed — managed session active** |
| Armed, inside protected hours | Global actions restricted, once every prerequisite is verified |

Two masks are used, built from the platform constants:

```kotlin
protectedFeatures = SYSTEM_INFO | NOTIFICATIONS | HOME | OVERVIEW | KEYGUARD   // 47
allowedFeatures   = protectedFeatures | GLOBAL_ACTIONS                        // 63
```

The device's original configuration is captured before policy mutation. A completed session
retires its baseline; the next actual session captures the then-current policy. Ambiguous
legacy records are preserved rather than treated as proven originals. Release and original
restoration are separate outcomes. `allowedFeatures` is never invented as the original.

## Managed mode limitations

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

.\gradlew.bat :app:testDebugUnitTest '-Pspm.testJavaHome=C:\Program Files\JetBrains\PyCharm Community Edition 2024.2.4\jbr'
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

## Device testing and management

Installing the APK does not grant Device Owner authority. In-app Restore does not remove
Device Owner management. Enrollment, activation, management removal and data preservation
need a separately authorized dedicated test-device procedure. The primary phone must not
be used for this repair. Earlier emulator commands are historical evidence, not instructions
for a physical phone. Read `docs/RECOVERY.md` before preparing any future device test.

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
.\gradlew.bat :app:testDebugUnitTest '-Pspm.testJavaHome=C:\Program Files\JetBrains\PyCharm Community Edition 2024.2.4\jbr'
```

Android compilation uses JDK 17. API 36 Robolectric runs in a separate JDK 21 process.
DataStore 1.1.7 is shared by production and tests; disk tests use the actual factory under
API 36 and check real close/reopen persistence and the original serialized file format.

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
| `docs/RECOVERY.md` | Current recovery semantics, provenance, retry and device limits |
| `docs/TEST_PLAN.md` | Gates A–E, the case list, and the evidence rules |
| `docs/TEST_RESULTS.md` | Exact desktop evidence and unverified physical-device behavior |
| `docs/COMPATIBILITY_MATRIX.md` | Per-device and per-workflow results |
| `docs/PRODUCTION_FEASIBILITY.md` | Device Owner enrollment, deployment audience, and why APK installation is insufficient |


