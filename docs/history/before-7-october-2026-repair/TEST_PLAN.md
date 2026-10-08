# TEST PLAN — Scheduled Power Menu Restriction

Every result is recorded in `docs/TEST_RESULTS.md` using exactly one of **PASS**, **FAIL**,
**BLOCKED**, or **NOT TESTED**. No result is inferred from a successful build, a passing unit
test, or an emulator run (brief section 5). Emulator evidence is preliminary and is labelled as
such; it is never recorded as a physical PASS.

Two test surfaces exist:

| Surface | Status |
|---|---|
| `spm-test-36` emulator (android-36, google_apis, x86_64) | available; preliminary evidence only |
| Dedicated physical Android 16 phone | **not attached** — all physical items are BLOCKED / NOT TESTED |

---

## 1. Automated checks

Run from the project root with the environment from `docs/ENVIRONMENT.md` section 8.

```powershell
.\gradlew.bat :app:testDebugUnitTest          # JVM unit tests
.\gradlew.bat :app:lintDebug                  # Android lint, abortOnError = true
.\gradlew.bat :app:assembleDebug              # debug APK
.\gradlew.bat :app:assembleDebug -Pspm.appId=com.example.spstest   # non-default applicationId
```

Expected: exit 0 for each. The exact commands and their observed output are recorded in
`docs/TEST_RESULTS.md`; "tests passed" without the command and count is not acceptable.

### What the unit tests must cover (brief section 26)

`ScheduleCalculatorTest` (19 cases): same-day and overnight membership; exact start included and
exact end excluded; disabled schedule; equal times rejected; out-of-range minutes rejected;
upcoming boundaries strictly after now; bounded scan reporting an error instead of looping;
month/year rollover; leap day; the same current instant judged in two zones; the zone provider
read fresh rather than cached; DST gap resolution; ambiguous start taking the earlier instant;
ambiguous end taking the later instant; a window collapsed by a gap being skipped rather than
becoming all-day protection; no fixed 24-hour assumption across a DST transition; an overnight
interval across a transition keeping a sane duration; next-transition reporting; the ordinary
`NORMAL` resolution case.

`ProtectionCoordinatorTest` (24 cases): missing ownership never arms or restricts; PINNED is not
LOCKED and never reports protection; missing exact capability prevents arming and prevents a new
restriction; end-alarm scheduling failure prevents restriction; an old start event uses current
settings; a newer disabled revision wins over a queued end alarm; no protection claim when the
runtime session is absent after boot; keyguard locked reports waiting-for-unlock; an interrupted
arm journal has a safe recovery route and never completes activation; arming is refused while
recovery is unresolved; arm succeeds end to end and restricts only inside the interval; arm
outside the interval leaves the menu allowed; a success path refreshes observations without
resubmitting unchanged setters; a schedule edit during an armed session reinstalls the plan and
reconciles immediately; an invalid edit is refused before the saved schedule changes; allowlist
edits disarm first and require an explicit resume; concurrent edit and alarm delivery serialize
and reach a consistent state; recovery is not starved by repeated status refreshes; a time-zone
change re-evaluates membership; restricting in the temporary test requires the release timer
first; the temporary test can restrict and then allow again; an interrupted temporary test is
recovered rather than resumed.

`RecoveryManagerTest` (10 cases), `DevicePolicyControllerTest` (12), `LockTaskMasksTest` (10),
`AlarmActionsTest` (8), `ProtectionSettingsTest` (9), `DiagnosticCodecTest` (9).

### Instrumented tests

`androidTest` asserts public API state on a provisioned managed device or emulator
(`app/src/androidTest/.../PlatformStateInstrumentedTest.kt`). It checks the device-protected
versus credential-protected storage placement, that alarm identities derive from the real
application ID, that the declared permission set is minimal, and that the receivers carry the
expected `exported` and permission attributes.

It is **not** a substitute for the manual physical power-menu and call-workflow observations, and
its results are recorded separately from them. Note also that a passing instrumented test is
still only an API observation: nothing in it proves the Power Off / Restart dialog is absent.

**Two practical requirements, learned by running them:**

1. **The device must be disarmed.** With `mLockTaskModeState=LOCKED`, the instrumentation runner
   cannot bring up its own activity and `:app:connectedDebugAndroidTest` hangs indefinitely —
   the test APK is not even installed. Release the session with **Restore Normal Device Mode**
   first.
2. **Running the instrumentation directly is more reliable than AGP's orchestration here**, and
   it produces readable output:

   ```powershell
   & $ADB -s $SERIAL install -r -t app\build\outputs\apk\debug\app-debug.apk
   & $ADB -s $SERIAL install -r -t app\build\outputs\apk\androidTest\debug\app-debug-androidTest.apk
   & $ADB -s $SERIAL shell am instrument -w -e package com.example.shutdownprotection `
       com.example.shutdownprotection.test/androidx.test.runner.AndroidJUnitRunner
   ```

   Expect `OK (9 tests)`. Compiling the androidTest sources is **not** sufficient: the first
   instrumented run failed on a stale assertion that compilation accepted happily.

## 2. Gate A — environment, provisioning, and recovery

Scope: skeleton, admin receiver, ownership checks, diagnostics, baseline capture, recovery
controls. Nothing restrictive is applied in this gate.

| # | Step | PASS requires |
|---|---|---|
| A1 | Create `spm-test-36` and boot it to an unlocked state; verify acceleration | AVD exists, boots, `adb devices` shows it |
| A2 | `adb install -t app-debug.apk` | Install succeeds; the `-t` flag is required because the debug APK is `testOnly` |
| A3 | Before provisioning, confirm the app reports **Device Owner: Required** | App state and `dpm list-owners` agree that there is no owner |
| A4 | `dpm set-device-owner com.example.shutdownprotection/.admin.ShutdownProtectionAdminReceiver` | Ownership appears in `dpm list-owners` **and** in the app's own state |
| A5 | Demonstrate the admin removal escape route **with no restrictive policy applied** | `dpm remove-active-admin` succeeds; the power menu is re-checked afterwards (never assumed) |
| A6 | Re-provision for Gate B | Ownership present again |
| A7 | Confirm **Restore Normal Device Mode** is visible and cannot be hidden behind a valid-schedule requirement | Button present on the main and POC screens with an invalid/disabled schedule |

Record: APK build ID, device model, OS/API, build fingerprint, and the exact working commands.

**A build alone is not a PASS for Gate A.** Actual provisioning and a verified escape route are
required.

## 3. Gate B — smallest power-menu proof of concept

Do not implement or exercise the full schedule interface here. Use the debug POC controls:

```text
Start managed session
Allow power menu
Restrict power menu
Restore Normal Device Mode
```

| # | Step |
|---|---|
| B1 | Capture the policy baseline and allowlist only this app |
| B2 | Apply the mask permitting global actions; enter real Lock Task Mode |
| B3 | Verify the runtime state is `LOCK_TASK_MODE_LOCKED`, **not** `LOCK_TASK_MODE_PINNED` |
| B4 | Record the device's actual global-actions gesture (some devices use a power/volume combination, or assign long-press to an assistant) |
| B5 | Confirm the menu **is present** under the permissive mask |
| B6 | Before applying the restrictive mask: verify exact capability and submit a minimal temporary release broadcast for five minutes later plus a distinct inexact fallback. If the release calls fail, do **not** restrict |
| B7 | Apply the restrictive mask; repeat the same gesture on the home/app and keyguard surfaces as practical; confirm the normal global actions dialog does **not** appear |
| B8 | Restore the permissive mask and confirm the menu returns |
| B9 | Run full recovery; confirm the managed session ends and baseline behaviour returns |
| B10 | Repeat the toggle/recovery cycle at least three times without accumulating broken state |

Do **not** select Power Off during ordinary toggle testing. Hardware forced-restart checks are
separate controlled tests on the dedicated device, run only after the recovery route is ready.

**PASS requires real observed suppression, real observed restoration, and a working recovery.**
If the dialog remains available under the restrictive mask, document which UI/path appears and
stop the gated application build — do not compensate by intercepting keys or hiding System UI.

## 4. Gate C — usability and product fit

Add the default launcher and selected applications, enable the intended feature mask, and keep
the managed session active in both states. Build a matrix with columns: device/build, function,
menu-allowed result, menu-restricted result, steps/evidence, limitation, severity.

Test each item separately:

```text
Home button and gesture navigation
Overview / Recents
Switching between allowlisted apps
Launching a non-allowlisted app
Notification icons, shade, and notification actions
Quick Settings
Incoming ordinary calls and in-call UI
Outgoing ordinary calls
SMS app
WhatsApp or selected messaging app
Browser
Camera and camera launched from another app
Clock alarms, alarm UI, dismiss and snooze
Settings subsections required for daily use
Secure screen lock
PIN/password unlock
Biometric unlock
Permission prompts
Browser/account authentication handoffs
File/document picker and share sheet
Rotation, split-screen behaviour, and system gestures where relevant
Reachability of recovery controls
```

Quick Settings being unavailable is the documented expected limitation: test it and report it,
but it is not a defect to fix through prohibited techniques. Never disable an already configured
screen lock to make a test pass, and never call emergency services during testing.

The gate result must state whether the demonstrated restrictions are acceptable for a
dedicated-device MVP. If an ordinary unrestricted personal-phone experience is required, report
that requirement as unmet rather than polishing an architecture that already fails it.

## 5. Gate D — scheduling, state integrity, and idle behaviour

Entry criterion: Gates A–C pass for the intended MVP scope.

Run in this order:

1. a screen-on convenience test beginning a few minutes ahead and ending several minutes later;
2. controlled screen-off / idle tests with events **at least 20 minutes apart**;
3. the real 02:00–05:00 interval, or an equivalent three-hour interval.

For each transition collect: desired boundary, submitted alarm, delivery time, policy readback,
runtime state, and manually observed menu behaviour. Verify the **60-second observed transition
threshold** and record any missed or delayed event. The 60-second figure is a project test
threshold, not a guarantee supplied by Android; if it fails, report the measured delay and do
not silently widen the threshold.

Cases:

| # | Case |
|---|---|
| D1 | Screen on and app foreground |
| D2 | Another allowlisted app foreground |
| D3 | Screen off and locked |
| D4 | Controlled Doze |
| D5 | Charging and not charging |
| D6 | Protection app removed from Recents |
| D7 | App process killed without force-stop, using an explicitly documented test method |
| D8 | Force-stop separately, if possible on the managed device — do not confuse it with ordinary process death |
| D9 | Editing times before an old alarm arrives |
| D10 | Disabling before an old alarm arrives |
| D11 | Duplicate and deliberately delayed alarm delivery in controlled tests |
| D12 | Time changed forward beyond the end |
| D13 | Time changed backward into the interval |
| D14 | Time zone changed while currently protected |
| D15 | Overnight interval crossing midnight |
| D16 | Scheduling API failure injected before arming and while replacing a plan |
| D17 | Settings/logging failure injected during release |
| D18 | Exact capability unavailable/lost, including real loss only if applicable |
| D19 | Fallback delivery behaviour following a missing primary release |

**PASS requires** enabled and disabled behaviour, stale-event safety, ordinary process-death
recovery, and measured idle transitions on the supported configuration.

Two items in this gate are **not established by this baseline** and must not be recorded as
PASS merely because nothing crashed:

- **Force-stop.** A force-stopped app receives no broadcasts and runs no code, so no timing
  target can be met. Record the measured outcome and state plainly that a hard bounded release
  is not provided.
- **Exact-alarm permission loss / revocation.** The architecture does not establish a maximum
  release delay after revocation. Record whether the capability genuinely changed and whether
  delivery occurred, and do not present a pass on this gate as evidence of a bounded release.

## 6. Gate E — boot and update recovery

| # | Case |
|---|---|
| E1 | Reboot outside the protected interval, before unlock |
| E2 | Reboot inside the protected interval, before unlock |
| E3 | First unlock with the protection app unopened |
| E4 | Open the app and explicitly resume after unlock |
| E5 | Reboot across a start boundary |
| E6 | Reboot across an end boundary |
| E7 | Reboot while `recoveryRequired` is set |
| E8 | Application update while enabled |
| E9 | Temporary debug test active at reboot/update |

For each, report: desired state, observed runtime lock state, effective policy, submitted alarms,
physical menu behaviour, whether user action was required, and the delay.

**Baseline PASS means:** the saved schedule is usable, alarms are restored, inactive runtime
protection is reported honestly, no stale restrictive state is mistaken for protection, recovery
remains possible, and explicit resume works.

Two stronger items are separate acceptance items and are **not** PASS merely because Direct Boot
ran or feature flags were restored:

- **Automatic unattended post-reboot re-entry** — PASS only if correct protection is observed
  without opening the app or resuming.
- **Protection before the first unlock** — PASS only on observation.

If either fails or remains untested, the final report must state that the original unattended
recovery objective is incomplete. The baseline application can still be delivered as a limited
prototype.

## 7. Evidence rules

- Record the device model and Android build fingerprint for every observation. One Pixel result
  never establishes all Android 16 devices.
- Distinguish "API readback says restricted" from "the menu was observed to be absent". Only the
  second is power-menu evidence.
- Record measured delays in seconds, not as "fast" or "fine".
- A missing test surface is `BLOCKED`/`NOT TESTED`, never a `FAIL`.
- Never record a hypothetical result, and never convert a blocked test into an assumed pass.
