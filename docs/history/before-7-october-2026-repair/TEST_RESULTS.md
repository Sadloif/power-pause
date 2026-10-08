# TEST RESULTS — Scheduled Power Menu Restriction

Every result below is one of **PASS**, **FAIL**, **BLOCKED**, or **NOT TESTED**.

Two rules were applied throughout:

- A successful build, a passing unit test, or an emulator result is **never** recorded as proof of
  physical-device behaviour (brief sections 5 and 27).
- A missing test surface is **BLOCKED**, not **FAIL** (brief section 4.9).

**Headline:** the implementation is complete and its automated checks pass. Gate A passed on the
emulator. The **central product behaviour — the power menu actually being suppressed and restored
— has not been observed anywhere yet**, because the only available emulator image pins the
power-menu gesture to the Google Assistant and no physical test phone is attached. That item is
BLOCKED, and the brief requires it to be stated rather than worked around.

---

## 1. Device under test

| Field | Value |
|---|---|
| Device | `sdk_gphone64_x86_64` (emulator) |
| Product / device | `sdk_gphone64_x86_64` / `emu64xa` |
| Android release | **16** |
| API level | **36** |
| Build fingerprint | `google/sdk_gphone64_x86_64/emu64xa:16/BE2A.250530.026.F3/13894323:userdebug/dev-keys` |
| Serial | `emulator-5554` |
| AVD | `spm-test-36` (hand-written under `_working/avd`; see §6.2) |
| Acceleration | WHPX, `emulator-check accel` → exit 0, "WHPX(10.0.26200) is installed and usable." |
| Physical test phone | **none attached** — see §5 |

## 2. Build identity

| Field | Value |
|---|---|
| Gradle / AGP / Kotlin | 8.13 / 8.13.2 / 2.0.21 (all pinned; no dynamic versions) |
| compileSdk / minSdk / targetSdk | 36 / 36 / 36 |
| Default applicationId | `com.example.shutdownprotection` |
| Debug APK | `app\build\outputs\apk\debug\app-debug.apk` |
| Debug APK size / SHA256 | 25,607,612 bytes / `A2CEF37D90F4BAE173773DE3116B7741373B5DBC7D911C48357E6855192FCA2E` (see §8) |

> **APK hashes are artifact identifiers, not source fingerprints.** Two builds from identical
> source produced different APK bytes and sizes (25,728,868 / `18618391…` versus the 25,607,612 /
> `A2CEF37D…` recorded above) because AGP embeds build metadata and incremental dex ordering
> varies. The source revision is what a re-verification should compare; the APK hash only
> identifies one specific built file. The identity above is the artifact left on disk.

## 3. Automated checks

Exact commands (environment from `docs/ENVIRONMENT.md` §8):

```powershell
.\gradlew.bat :app:testDebugUnitTest
.\gradlew.bat :app:lintDebug
.\gradlew.bat :app:assembleDebug
.\gradlew.bat :app:assembleDebug "-Pspm.appId=com.example.spstest"
```

| Check | Command | Observed | Result |
|---|---|---|---|
| Unit tests | `:app:testDebugUnitTest` | **112 tests, 0 failures, 0 errors, 0 skipped** | **PASS** |
| Lint | `:app:lintDebug` | `BUILD SUCCESSFUL`, no lint errors (`abortOnError = true`) | **PASS** |
| Debug APK | `:app:assembleDebug` | `BUILD SUCCESSFUL` | **PASS** |
| Non-default applicationId | `:app:assembleDebug "-Pspm.appId=com.example.spstest"` | `BUILD SUCCESSFUL`; `aapt2 dump badging` → `package: name='com.example.spstest'`, `minSdkVersion:'36'`, `targetSdkVersion:'36'` | **PASS** |
| Instrumented sources compile | `:app:compileDebugAndroidTestKotlin` / `:app:assembleDebugAndroidTest` | `BUILD SUCCESSFUL`; `app-debug-androidTest.apk` produced, 858,310 bytes | **PASS** |
| Instrumented tests run | `:app:connectedDebugAndroidTest` | see §8 | recorded in §8 |

> **Verification caveat that matters.** `:app:testDebugUnitTest` and `lintAnalyzeDebug` both
> reported `UP-TO-DATE` on a first invocation, which means a naive run reports success without
> executing anything. The counts below come from a run where the JUnit XML and the lint
> intermediates were deleted first, forcing genuine execution. Anyone re-verifying should do the
> same, or use `--rerun-tasks`, rather than trusting a green `UP-TO-DATE`.

Per-class unit-test results (parsed from the JUnit XML, not from console text):

| Test class | Tests | Failures |
|---|---|---|
| `protection.ProtectionCoordinatorTest` | 33 | 0 |
| `scheduling.ScheduleCalculatorTest` | 19 | 0 |
| `admin.DevicePolicyControllerTest` | 12 | 0 |
| `protection.RecoveryManagerTest` | 10 | 0 |
| `admin.LockTaskMasksTest` | 10 | 0 |
| `scheduling.AlarmActionsTest` | 10 | 0 |
| `data.ProtectionSettingsTest` | 9 | 0 |
| `data.DiagnosticCodecTest` | 9 | 0 |
| **Total** | **112** | **0** |

> One `:app:testDebugUnitTest` invocation reported `BUILD FAILED` with "Failed to delete some
> children… a process has files open". That was a build-directory race with a concurrently running
> verification build, not a test failure: the JUnit XML from that same run shows 112 tests with
> zero failures. The final clean run is recorded in §8.

### Debug-only `testOnly` override (brief section 10)

| Check | Evidence | Result |
|---|---|---|
| Present in `app/src/debug/AndroidManifest.xml` | file content | **PASS** |
| Absent from `app/src/main/AndroidManifest.xml` | file content | **PASS** |
| Present in the merged **debug** manifest | `app\build\intermediates\merged_manifest\debug\processDebugMainManifest\AndroidManifest.xml` contains `android:testOnly="true"` | **PASS** |
| **Absent** from the merged **release** manifest | `…\merged_manifest\release\processReleaseMainManifest\AndroidManifest.xml` → `testOnly` not present | **PASS** |
| Present inside the built debug APK | `aapt2 dump xmltree --file AndroidManifest.xml app-debug.apk` → `android:testOnly(0x01010272)=true` | **PASS** |

## 4. Gate A — environment, provisioning, and recovery

| # | Step | Observed evidence | Result |
|---|---|---|---|
| A1 | Create and boot the test AVD | `emulator -list-avds` → `spm-test-36`; `sys.boot_completed=1`; `adb devices` → `emulator-5554 device`; WHPX usable | **PASS** |
| A2 | Install the debug test-only APK | `adb install -t app-debug.apk` → `Performing Streamed Install` / `Success` | **PASS** |
| A3 | App correctly reports missing ownership **before** provisioning | `adb shell dpm list-owners` → `no owners`, and on a freshly installed app with no owner the main screen reads **`Device Owner` / `Required`** (captured with `uiautomator dump`: `text='Device Owner'`, `text='Required'`). See also A5: once ownership has existed and is then removed, the same row reads **`Lost`** instead — the intended distinction. | **PASS** |
| A4 | Provision the real receiver as Device Owner | `adb shell dpm set-device-owner com.example.shutdownprotection/.admin.ShutdownProtectionAdminReceiver` → `Success: Device owner set to package …`; `dpm list-owners` → `1 owner: User 0: admin=…,DeviceOwner,Affiliated`; `dumpsys device_policy` → `Device Owner:` with `testOnlyAdmin=true`; app showed **Device Owner: Ready** | **PASS** |
| A5 | Demonstrate the admin-removal escape route with **no** restrictive policy applied | lock-task state `mLockTaskModeState=NONE` before removal; `dpm remove-active-admin …` → `Success: Admin removed …`; `dpm list-owners` → `no owners`; package still installed; app then showed **Device Owner: Lost** | **PASS** (with the caveat below) |
| A6 | Re-provision for the next test | `dpm set-device-owner …` → `Success`; `dpm list-owners` → `1 owner … DeviceOwner,Affiliated` | **PASS** |
| A7 | Recovery control visible and not hidden behind a valid-schedule requirement | `uiautomator dump` on the main screen lists `Restore Normal Device Mode` while the schedule is **disabled** and the interval is not active | **PASS** |

**Caveat on A5, stated because it matters.** The decisive half of the escape-route check is
confirming that the **power menu returns** after `dpm remove-active-admin`. That cannot be done on
this emulator (§5.2), so the escape route is verified as *ownership removal and session state*
only, not as observed restoration of the menu. Brief section 21 requires a verified escape route
for a Gate A PASS; the provisioning and removal half passed, the menu half is blocked. Gate A is
therefore recorded as **PASS with one blocked sub-item**, not as a clean pass.

Ownership also survived a full `adb reboot` (`dpm list-owners` still reported the app as Device
Owner afterwards) — recorded here because it is a prerequisite for the Gate E work, not as a
Gate E result.

### 4.1 Provisioning detail worth recording

> **Emulator-only. This is historical evidence, not a phone procedure (guide R10).** It is recorded
> because it is what was actually done on the disposable `spm-test-36` emulator. It changes a
> device's setup state and must **not** be applied to a physical phone. The supported enrollment
> prerequisites for a real device are in `docs/RECOVERY.md` §3, and they are checked rather than
> worked around.

The first `dpm set-device-owner` attempt **failed**:

```text
java.lang.IllegalStateException: Not allowed to set the device owner because there are already
some accounts on the device.
```

`dumpsys account` reported `Accounts: 0`, so the message is misleading; the actual blocker was
the provisioning flag. The sequence that worked **on the emulator** was:

```text
adb shell settings put global device_provisioned 0
adb shell settings put secure user_setup_complete 0
adb shell dpm set-device-owner com.example.shutdownprotection/.admin.ShutdownProtectionAdminReceiver
```

**Do not carry this into physical-device instructions.** If a real device fails an enrollment
prerequisite, stop and ask the owner — do not alter setup flags, remove accounts, or reset
anything automatically.

## 5. Gate B — the central behaviour: BLOCKED

Gate B requires the normal Android global-actions dialog to be **observed** disappearing under
the restrictive mask and **observed** returning under the permissive mask.

**That observation was not obtained, on either surface.**

### 5.1 Physical phone — BLOCKED

No dedicated physical Android 16 phone was attached during this work. The brief forbids
provisioning a personal phone, and the intended device was offered but not available. Every
physical item is **BLOCKED / NOT TESTED**.

### 5.2 Emulator — BLOCKED, with the exact reason

The only system image available on this machine is `system-images;android-36;google_apis;x86_64`
(a **GMS** image). On that build the power-menu gesture is pinned to the Google Assistant and
the standard override does not take effect. Evidence:

| Probe | Observed |
|---|---|
| Long-press power on the home screen | Focus moves to `VoiceInteractionSession` / `com.google.android.googlequicksearchbox` — the **Assistant**, not global actions |
| `adb shell settings put secure power_button_long_press 1` (AOSP value for GLOBAL_ACTIONS) | Setting stored, but logcat still shows `WindowManager: powerLongPress: mLongPressOnPowerBehavior=5` (ASSISTANT) |
| Same setting re-checked **after a full reboot** | Still `mLongPressOnPowerBehavior=5` — the value comes from the GMS configuration overlay, not from the setting |
| `settings put secure assistant null` + `pm disable-user --user 0 com.google.android.googlequicksearchbox` | The gesture then does **nothing** (focus stays on the launcher); it does not fall back to global actions |
| Kernel-level `sendevent` power-key injection on `/dev/input/event1` and `/dev/input/event13` with proper `SYN_REPORT` framing, 4-second hold (root adbd) | Same `mLongPressOnPowerBehavior=5`; no very-long-press path |
| `/dev/input/event0` ("Power Button") | Triggered the camera double-press gesture instead |
| `sdkmanager --list` for another API 36 image | Only `system-images;android-36;google_apis;x86_64` is available; no AOSP/`default` image to fall back to |

Because the dialog cannot be invoked on this test surface, its suppression cannot be observed
here. This is a **missing test surface**, not evidence that the feature fails.

### 5.3 What *was* observed on the emulator — API and runtime state only

These are recorded explicitly as **API readback and runtime state**, never as power-menu
evidence. The app's own POC screen says the same thing in its own words.

| Observation | Command | Result |
|---|---|---|
| Real Lock Task Mode reached | `adb shell dumpsys activity activities` → `mLockTaskModeState=LOCKED` | **PASS** (not `PINNED`) |
| Permissive mask applied | `adb shell dumpsys device_policy` → `LockTaskPolicy {mPackages= com.example.shutdownprotection; mFlags= 63 }` | **PASS** — 63 = `allowedFeatures` |
| Restrictive mask applied | `adb shell dumpsys device_policy` → `LockTaskPolicy {… mFlags= 47 }` | **PASS** — 47 = `protectedFeatures` |
| Allowlist scoped to this app only | `mPackages= com.example.shutdownprotection` | **PASS** |
| App state after arming outside the interval | POC screen → `ARMED_POWER_MENU_ALLOWED` / "Power menu allowed - managed session active" | **PASS** |
| App state after the restrictive toggle | POC screen → `ARMED_POWER_MENU_RESTRICTED` | **PASS** |
| Exact release alarm submitted (primary) | `dumpsys alarm` → `tag=*walarm*:…action.END_PROTECTION`, `type=RTC_WAKEUP`, `exactAllowReason=permission`, `origWhen=2026-10-06 17:00:02.243` | **PASS** |
| Distinct **inexact** fallback submitted | `tag=*walarm*:…action.RELEASE_FALLBACK`, `origWhen=2026-10-06 17:01:02.243`, `window=+4m29s998ms` — a real delivery window, i.e. genuinely inexact | **PASS** |
| Scheduled next start submitted | `tag=*walarm*:…action.START_PROTECTION`, `origWhen=2026-10-07 02:00:00.000` | **PASS** |
| Recovery ended the session | `mLockTaskModeState=NONE` | **PASS** |
| Recovery cancelled every app-owned alarm | `dumpsys alarm` filtered to `Alarm{.*shutdownprotection` → **none pending** (the three remaining mentions are the alarm *history* section, not pending work) | **PASS** |
| Recovery cleared the app's policy | `dumpsys device_policy` → no `LockTaskPolicy` for this admin afterwards (the captured baseline was empty) | **PASS** |
| Recovery reported success only after verification | POC screen → `DISARMED: Normal device mode restored` | **PASS** |

> **These observations were taken with the pre-fix APK.** At that point the Gate B 5-minute
> release timer was installed on the daily `END_PROTECTION` identity, which is defect 7 in §7.
> The post-fix re-run, in which the timer uses its own identities, is recorded in §8.

**Gate B verdict: BLOCKED.** The API-level behaviour is correct and the plumbing works, but the
brief's PASS condition is *observed* menu suppression and restoration, which has not happened.

## 6. Environment findings worth recording

### 6.1 Exact-alarm capability is not automatic for a Device Owner

| Step | Observed |
|---|---|
| App provisioned as Device Owner, `SCHEDULE_EXACT_ALARM` declared | App reported **Exact scheduling: Unavailable** and refused to arm — the designed fail-safe |
| `adb shell cmd appops get com.example.shutdownprotection SCHEDULE_EXACT_ALARM` | `No operations.` / `Default mode: default` |
| `adb shell cmd appops set com.example.shutdownprotection SCHEDULE_EXACT_ALARM allow` | App then reported **Exact scheduling: Available — last verified 2026-10-06 16:37:30** |
| `cmd package resolve-activity -a android.settings.REQUEST_SCHEDULE_EXACT_ALARM -d package:…` | Resolves to `com.android.settings/.Settings$AlarmsAndRemindersAppActivity` — the documented intent the app now offers is valid |

Consequence recorded in `docs/PRODUCTION_FEASIBILITY.md` §5: a rollout must grant Alarms &
reminders access or verify a genuine per-model exemption.

### 6.2 The AVD had to be created by hand

`avdmanager create avd` failed with `Error: "emulator" package must be installed!` because this
SDK has no `emulator/package.xml`, even though the emulator binary is present and works.
Creating that metadata file would mean modifying the shared donor SDK, so the AVD was written by
hand under `_working/avd` with `ANDROID_AVD_HOME` pointed at it. The emulator also had to be
launched detached with `ANDROID_EMULATOR_HOME`/`ANDROID_USER_HOME` redirected into the project,
because its default `%USERPROFILE%\.android` is outside the writable workspace and it failed
with `error: 5` on `emu-last-feature-flags.protobuf.lock`.

## 7. Defects found during verification, and their fixes

These were found by inspecting the running device and by re-reading the code against the brief.
Each is recorded with what was wrong, because a results document that lists only successes is
not useful.

| # | Defect | How it was found | Fix |
|---|---|---|---|
| 1 | **The settings DataStore was landing in credential-protected storage.** `SettingsDataStores.deviceProtected()` used `deContext.preferencesDataStoreFile(name)`, but that extension resolves `applicationContext.filesDir`, and `createDeviceProtectedStorageContext()` still returns the credential-protected application context. Observed on the emulator: both files appeared under `/data/user/0/…` and `/data/user_de/0/com.example.shutdownprotection/` did not exist. This would have made **every pre-unlock Direct Boot read fail** — brief sections 7.8 and 17. | `adb shell find /data -name 'spm_*'` on the running device | File path built from `deContext.filesDir` directly; a `settingsFile()`/`diagnosticsFile()` accessor added, plus an instrumented test that asserts the placement |
| 2 | **The mask readback was blanked by an observation-only refresh.** `refreshObservationLocked()` did not pass `requestedFeatures`/`effectiveFeatures`, so the lock-task enter/exit callback overwrote the status and the POC screen's required "requested/effective mask" rows showed `unknown`. | `uiautomator dump` of the POC screen after arming | Requested mask derived from the same intent the reconciler uses; effective mask read from the platform |
| 3 | **The POC reported "Inside the protected interval" for a manual override.** Misleading: the schedule had not caused the restriction. | POC status dialog during the manual toggle | Override-specific wording, and the required "Power menu allowed — managed session active" phrase is preserved |
| 4 | **No corrective action on the exact-alarm error.** Brief section 15.3 requires the documented Alarms & reminders settings intent when capability is unavailable and the app is foreground; the card explained the problem but offered nothing. | Code read against §15.3 | Error card now has an "Open Alarms & reminders settings" action |
| 5 | **A modal status dialog appeared on every return to the app.** Poor UX and not required. | Emulator screenshot on relaunch | Routine successes are no longer announced on foreground; failures still are |
| 6 | Kotlin compile daemon cannot start (`AccessDeniedException` writing under `%LOCALAPPDATA%\kotlin\daemon`) | First build log | `kotlin.compiler.execution.strategy=in-process` pinned in `gradle.properties`; documented as a host constraint, not a version choice |

A second, independent audit of brief sections 12–20 against the source found ten further defects.
They are listed separately because they were found by adversarial review rather than by running
the app, and two of them were serious.

| # | Defect | Why it mattered | Fix |
|---|---|---|---|
| 7 | **The Gate B temporary release timer was inert *and* destroyed the daily release path.** `installTemporaryRelease` installed the timer on the daily `END_PROTECTION` identity (request code 1002). Its delivery ran the normal reconciler, and with a forced-restriction override the same instant resolves back to the restrictive mask — so the five-minute safety timer never released anything. Worse, it replaced the real daily END alarm while the stored receipt still claimed that plan was installed. | Directly broke the brief's §22 step 6 safety requirement, and left a device the app believed had a release plan with none. | The timer now uses its own debug-only identities (`TEMPORARY_TEST_RELEASE` 1005 / `TEMPORARY_TEST_FALLBACK` 1006) and its handler calls a new `releaseTemporaryTest()`, which runs `RecoveryManager` and can never enable protection. The daily identities and receipt are untouched, and the temporary install returns no receipt so it can never be persisted as the daily plan. |
| 8 | **Policy-conflict feedback loop.** Every failure result code routed to `reconcile(POLICY_CHANGED)`, which re-submitted the setter unconditionally; a readback mismatch therefore stayed `POLICY_PENDING` forever and each callback triggered another submission. | An unbounded submit → callback → submit loop on a managed device, and a permanent false "pending" state. | The coordinator remembers `(revision, mask, verified)`. An unchanged submission is never re-sent — the next pass reads back instead. If the readback still disagrees, that is a policy conflict: it invalidates the success display and takes the bounded recovery path (`POLICY_READBACK_MISMATCH`). |
| 9 | **Policy result code and observation time were never recorded.** `statusFromObservation` hardcoded both to `null`, so the diagnostics screen always said "not recorded" — brief §18 requires the code, time and revision. | A required diagnostic was permanently blank. | `recordPolicyResult(identifier, resultCode)` captures the code, time and revision; both policy callbacks call it, and diagnostics read it. |
| 10 | **The required second-situation label was never used.** `strings.xml` defined "Power menu allowed — managed session active" but no code referenced it; the README claimed it was used. | A brief §3.3 wording requirement was unmet, and the README overclaimed. | The label is now rendered on the main screen whenever the state is `ARMED_POWER_MENU_ALLOWED`. |
| 11 | **"Restricted" was displayed when the seven-condition claim failed** (as "Restricted (claim not verified)"). | Brief §19 permits the word only when all seven conditions hold. | The row now reads "Not verified" unless the claim passes. |
| 12 | **`userActionRequired` was computed eleven times and rendered nowhere**; `RECOVERY_FAILED` offered no action. | Brief §17/§19 require explaining that an explicit foreground resume is needed, and §19 item 8 requires a concrete corrective action. | The main screen renders it with a "Restore Normal Device Mode" action when recovery is incomplete. |
| 13 | **No schema migration handling**, and no test referencing an app update. | Brief §17 requires migration handling plus a test that active state does not silently change during an update. | New `SettingsSchema`: an older schema upgrades in one place preserving intent exactly, and a *newer* schema is a recovery condition rather than being silently reinterpreted. Six tests added. |
| 14 | **A timed-out pass published its status outside the lock**, so it could overwrite a newer correct status. | Stale status could win over fresh state. | The timeout is now applied inside the mutex on every entry point, so publishing is serialized. |
| 15 | **`RECOVERY_RETRY` used the 12-second arm budget**, not the 5-second reconcile budget; unknown-action receiver branches launched a coroutine after returning without `goAsync()`. | Wrong budget for a path that submits nothing, and a coroutine outliving the receiver's pending result. | Both fixed: the retry path uses the 5 s budget, and `goAsync()` is taken on every receiver path with `finish()` in a `finally`. |
| 16 | **The exact-alarm corrective action vanished on the next pass** (recovery leaves intent disabled, so the following reconcile reported plain `DISARMED` and the card disappeared), and the critical section polled for 3 s. | Brief §15.3 requires the corrective action to be available while the app is foreground. | The disabled branch now reports `EXACT_SCHEDULING_UNAVAILABLE` with its action whenever capability is missing, and the session-entry poll was reduced from 15 to 5 iterations. |

Also added while addressing the above: an `ALARM_DELIVERY` diagnostic that records the intended
boundary, the actual delivery time and the delta, which is what brief §20 asks for and what the
Gate D timing threshold will be measured from.

Defects 1–3 were fixed and are re-verified in §8. Defects 4–5 are UI changes verified by the
build and by the unit tests, with the on-device re-check noted in §8.

## 8. Re-verification after the fixes

The fixes in §7 were re-checked on the emulator with the rebuilt APK. Every row below is an
observation made **after** the fixes, not a restatement of the pre-fix run in §5.3.

Build identity for this round:

| Field | Value |
|---|---|
| Debug APK | `app\build\outputs\apk\debug\app-debug.apk` |
| Size | 25,728,868 bytes |
| SHA256 | `18618391E0B8677BEE1F1AF3E832DB5AD929E06E09E4781399BA56E402E92972` |
| Declared permissions | `RECEIVE_BOOT_COMPLETED`, `SCHEDULE_EXACT_ALARM`, plus the AndroidX-contributed signature-level `com.example.shutdownprotection.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION` |
| Confirmed absent | `USE_EXACT_ALARM`, `INTERNET`, `QUERY_ALL_PACKAGES`, `POST_NOTIFICATIONS` |

| Check | Observed | Result |
|---|---|---|
| Unit tests after the fixes | **112 tests, 0 failures, 0 errors, 0 skipped** | **PASS** |
| Lint after the fixes | `BUILD SUCCESSFUL`, no lint errors | **PASS** |
| androidTest sources compile | `app-debug-androidTest.apk` produced, 858,310 bytes | **PASS** |
| **Instrumented tests executed on the device** | `adb shell am instrument -w -e package com.example.shutdownprotection com.example.shutdownprotection.test/androidx.test.runner.AndroidJUnitRunner` → **`OK (9 tests)`** | **PASS** |
| Ownership survives a reinstall over the owner app | `adb install -r -t` → `Success`; `dpm list-owners` still reports the app as `DeviceOwner,Affiliated` | **PASS** |
| Main screen rows (§19 items 1–8) | `Device Owner: Ready`; `User preference: Disabled`; `Managed session: Not active`; `Power menu: Not verified`; `Schedule (24-hour): 02:00 – 05:00`; `Device time zone: Asia/Karachi`; `Next planned transition: Restriction starts 2026-10-07 02:00:00 (Asia/Karachi)`; `Exact scheduling: Available — last verified 2026-10-06 17:21:14` | **PASS** |
| Verbatim explanatory text on the main screen | The exact §1 sentence is rendered verbatim | **PASS** |
| Real Lock Task session reached | `mLockTaskModeState=LOCKED` | **PASS** |
| Permissive mask applied and read back | `LockTaskPolicy {mPackages= com.example.shutdownprotection; mFlags= 63}` | **PASS** |
| **Requested/effective mask readback now populated** (defect 2) | POC screen: `Requested mask 63 (SYSTEM_INFO \| NOTIFICATIONS \| HOME \| OVERVIEW \| GLOBAL_ACTIONS \| KEYGUARD)`, `Effective mask 63 (…)` — previously both read `unknown` | **PASS** |
| Restrictive mask applied and read back | `LockTaskPolicy {… mFlags= 47}` | **PASS** |
| POC status wording no longer claims the schedule caused it (defect 3) | `ARMED_POWER_MENU_RESTRICTED: Restricting the power menu by manual debug override; global actions are restricted` | **PASS** |
| **Required second-situation label rendered** (defect 10) | Main screen shows `Power menu allowed — managed session active` with `Managed session: Active` and `Power menu: Allowed` | **PASS** |
| **Recovery control on every management screen** (defect 4 / §10) | `Restore Normal Device Mode` present on the main screen, the POC screen, **Change start / end**, **Manage allowed applications**, **Setup and prerequisites**, and **Diagnostics** | **PASS** |
| **The temporary release timer uses its own identities and leaves the daily plan intact** (defect 7) | `dumpsys alarm` pending tags: `…action.TEMPORARY_TEST_RELEASE`, `…action.TEMPORARY_TEST_FALLBACK`, **and** the untouched daily `…action.START_PROTECTION`, `…action.END_PROTECTION`, `…action.RELEASE_FALLBACK` | **PASS** |
| **The temporary release timer actually releases** (defect 7 — the decisive re-check) | With the session restricted (`LOCKED`, `mFlags=47`), the timer was left to fire unattended. State polled every 20 s: `LOCKED`/`47` continuously until **17:27:17**, when it became `mLockTaskModeState=NONE` with no `LockTaskPolicy` at all. No pending alarms remained afterwards. | **PASS** |
| Alarm-delivery diagnostic recorded (§20) | An `ALARM_DELIVERY` record is present in the diagnostics store | **PASS** |

**What that last row does and does not prove.** It proves the Gate B safety timer is no longer
inert: a restricted session released itself, unattended, about five minutes after the restrictive
mask was applied, through `RecoveryManager`, and the daily schedule's alarms were never touched.
It does **not** prove anything about the power menu — no menu was invoked, and the observation is
policy and runtime state only. Gate B remains BLOCKED for the reason in §5.

The three defects the earlier audit found in the *policy callback* path (8, 9, 14) and the
schema/update work (13) are covered by unit tests rather than by device observation, because the
platform does not deliver a policy conflict on demand and cannot be made to report a schema from
the future. Those are recorded in §3's test table, not here.

### 8.1 Two operational findings about running these tests

Both cost real time here and are worth recording so the next person does not repeat them.

1. **An armed Lock Task session blocks the instrumentation runner.** With
   `mLockTaskModeState=LOCKED`, `:app:connectedDebugAndroidTest` hung indefinitely: the runner
   could not bring up its own activity, and the test APK was never even installed. The
   instrumented tests must be run **while the device is disarmed**. The session was released
   through the app's own `Restore Normal Device Mode` control before the successful run above —
   which incidentally re-demonstrated that the in-app recovery works.
2. **AGP's connected-test orchestration did not get as far as installing the test APK**, even
   once disarmed. Running the instrumentation directly is the reliable path on this machine, and
   it also gives readable output:

   ```powershell
   & $ADB -s $SERIAL install -r -t app\build\outputs\apk\debug\app-debug.apk
   & $ADB -s $SERIAL install -r -t app\build\outputs\apk\androidTest\debug\app-debug-androidTest.apk
   & $ADB -s $SERIAL shell am instrument -w -e package com.example.shutdownprotection `
       com.example.shutdownprotection.test/androidx.test.runner.AndroidJUnitRunner
   ```

3. **The first instrumented run failed, and the failure was genuine.** It reported
   `expected:<4> but was:<6>` for the alarm-identity count, because the test still asserted four
   identities after the two debug-only ones were added. That is exactly the kind of stale
   assertion a compile-only check cannot catch, and it is why the instrumented tests were run
   rather than merely compiled. Fixed and re-run: `OK (9 tests)`.

### 8.2 What the instrumented tests actually establish

On the real API 36 device, the nine passing tests verify: that the settings store resolves to
device-protected storage (`/data/user_de/`) and not credential-protected storage; that the
diagnostics store resolves to credential-protected storage; that the device-protected and
credential-protected files directories genuinely differ; that all six alarm identities are
derived from the application ID actually built into the APK, are mutually distinct, and round-trip
through `kindFor`; that the declared permission set is minimal and excludes `USE_EXACT_ALARM`,
`INTERNET`, `QUERY_ALL_PACKAGES` and the contact/camera/location permissions; that
`ProtectionAlarmReceiver` is not exported; that the admin receiver is exported and protected by
`BIND_DEVICE_ADMIN`; and that both documented policy actions resolve to
`LockTaskPolicyUpdateReceiver`.

None of that is power-menu evidence. It is public-API state, and it is recorded separately from
the menu observations for exactly that reason.

## 9. Gates C, D and E

| Gate | Status | Reason |
|---|---|---|
| Gate C — usability and product fit | **NOT TESTED** | Requires an accepted Gate B and a physical device for the call, SMS, biometric and keyguard rows |
| Gate D — scheduling, state integrity, idle behaviour | **NOT TESTED** | Entry criterion is Gates A–C passing for the MVP scope. The schedule *arithmetic* is covered by 19 unit tests, but no idle/Doze transition has been measured on any device |
| Gate E — boot and update recovery | **NOT TESTED** | Entry criterion is Gate D. Ownership surviving a reboot was observed, which is a prerequisite, not a Gate E result |

## 10. Result classification

**Prototype with blocked verification.**

The implementation exists, builds, and passes its automated checks. Gate A passed with real
provisioning and a verified escape route. The required physical/environment tests for the
central behaviour are **unavailable**, so the brief's "Verified baseline MVP" label cannot be
used.

Explicitly **not** established, and not claimed:

- Observed power-menu suppression and restoration on any device.
- Automatic unattended post-reboot re-entry (the original stronger objective).
- Protection before the first unlock.
- A hard bounded release after force-stop or exact-alarm revocation.
- Any OEM other than the one emulator image used here.
- Google Play availability or enrollment feasibility — `docs/PRODUCTION_FEASIBILITY.md` lists
  these as questions requiring primary documentation that could not be fetched.

### Exact next test instructions

**These require the owner to identify a disposable test device and explicitly authorize its use.**
They are not authorization to enroll, restrict or reset any phone, and they must not be run against
a daily-use phone. If any prerequisite fails, **stop** — do not alter setup flags, remove accounts,
or reset anything automatically (guide R10).

1. The owner identifies the exact spare Android 16 phone and authorizes its use. It must not be
   their daily-use phone and must not contain irreplaceable data. Record its model and build
   fingerprint and confirm `adb devices` shows it.
2. Work through the **enrollment prerequisites** in `docs/RECOVERY.md` §3 and confirm every one:
   disposable device, no other owner, unprovisioned, no accounts, and a **debug** build carrying
   `android:testOnly="true"` (verify it in the packaged APK, not just the source manifest). If any
   check fails, stop and ask the owner how they want to proceed.
3. `adb install -t app\build\outputs\apk\debug\app-debug.apk`, then `dpm list-owners`, then
   `dpm set-device-owner com.example.shutdownprotection/.admin.ShutdownProtectionAdminReceiver`.
   Do **not** clear `device_provisioned` / `user_setup_complete` on a phone — that sequence in §4.1
   is emulator-only historical evidence.
4. Before restricting anything, prove the **debug owner-removal route** works on that device
   (`dpm remove-active-admin`), then re-enroll through the same supported path.
5. Grant Alarms & reminders access (or record the exemption) and confirm the app shows
   **Exact scheduling: Available**.
6. Run Gate B as written in `docs/TEST_PLAN.md` §3: record the device's actual global-actions
   gesture **first**, confirm the menu appears under the permissive mask, confirm the temporary
   test saved its marker and submitted its fixed expiry, apply the restrictive mask, and confirm
   the dialog does not appear. Do not select Power Off during ordinary toggle testing.
7. Repeat the toggle/recovery cycle at least three times, including expiry recovery, then run full
   recovery and re-check the menu afterwards. Note that recovery ends the session but **leaves
   Device Owner management installed**; removing management is the separate step 4 above.
8. Record every result here with the measured delays, using the test record template in
   `docs/RECOVERY.md` §8, and keep the API-level observations in §5.3 clearly separated from the
   menu observations.
