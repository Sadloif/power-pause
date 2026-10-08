# RECOVERY — Scheduled Power Menu Restriction

Repaired per guide **R10**. This document previously presented an emulator-specific
provisioning-flag workaround as if it were a general phone enrollment procedure, and implied that
in-app recovery removes device management. Both were wrong and are corrected here.

> **Desktop verification does not certify primary-phone safety or actual power-menu behaviour.**
> This document is a procedure, not a guarantee, and it is not authorization to enroll, restrict or
> reset any device.

---

## 1. Four separate actions — do not conflate them

These are four different things. Completing one proves nothing about the others.

| # | Action | What it does | What it does **not** do |
|---|---|---|---|
| 1 | **Install the APK** | Puts the app on the device | Does **not** grant any policy capability. Ordinary Device Administrator access is not enough either |
| 2 | **Enroll as Device Owner** | Gives the app device-wide management authority | Does **not** start a managed session or restrict anything by itself |
| 3 | **Recover normal controls / end the session** (the in-app button) | Ends the app's managed Lock Task session, restores the saved original policy, and disables the app's intent | **Does NOT remove Device Owner management.** The app remains Device Owner afterwards |
| 4 | **Remove Device Owner management** | Takes away the app's management authority entirely | Is a **debug/test-only escape route** here (see §4). It is not an ordinary uninstall guarantee |

**In particular:** pressing **Restore Normal Device Mode** leaves Device Owner management installed.
The device is back to normal *controls*, but the app is still the Device Owner. Removing the app
entirely is a separate, deliberate step.

## 2. In-app recovery — the primary route for action 3

**Restore Normal Device Mode** is present on every screen where protection can be managed (main,
POC, schedule, allowed applications, setup, and diagnostics). It:

- works outside the protected interval as well as inside it,
- does not require network access, a subscription, or a valid schedule,
- does not require the daily preference to be enabled.

It runs the idempotent sequence from brief section 10, repaired per guide R03:

1. inhibit new restriction **first**, before any lengthy cleanup
2. durably set `enabled=false`, advance the revision, set `recoveryRequired=true`
3. cancel every app-owned alarm, including the recovery retry and the temporary-test timer
4. request a mask including global actions and **verify it by readback** (finite retry budget)
5. stop the session through documented task ownership and by removing the app-configured
   lock-task packages
6. verify the runtime state is `LOCK_TASK_MODE_NONE` — unknown is **not** treated as released, and
   `PINNED` is reported as still-restricted rather than as a normal state
7. restore the saved baseline and **read back both** the feature mask and the package list,
   comparing them to the baseline
8. commit the required cleanup fields in **one atomic transaction** and check the result
9. clear the in-process safety brake only after everything above verified

`verified = true` means **all** of those hold. Ending the session alone does not produce
"baseline restored". If the stored baseline is missing or belongs to a different installation,
recovery releases what it can and explicitly reports that original-policy restoration could not be
confirmed — it never manufactures a baseline and claims a restore.

If release fails and Device Owner access remains, one separate **inexact, release-only** retry is
submitted at least 20 minutes later, capped at three automatic attempts per incident. It can never
arm a session or apply a restrictive mask.

## 3. Supported enrollment prerequisites — action 2

Provisioning a custom DPC as Device Owner requires the device to be in a state that permits it.
**Check every prerequisite before attempting anything. If any is not satisfied, STOP.** Do not
alter setup flags, remove accounts, or reset anything automatically.

| Prerequisite | How to check | If it fails |
|---|---|---|
| The device is a **disposable test device**, not the owner's daily-use phone, and contains no irreplaceable data | Ask the owner explicitly | **STOP.** Do not proceed |
| No other Device Owner or Profile Owner is present | `adb shell dpm list-owners` | **STOP.** Never remove someone else's management |
| The device is factory-fresh or otherwise unprovisioned | `adb shell settings get global device_provisioned` | **STOP** and ask the owner how they want to proceed. A used device generally cannot become Device Owner without a factory reset, which needs explicit per-device authorization for data destruction |
| No accounts are present | `adb shell dumpsys account` (look for the `Accounts:` count) | **STOP.** Do not remove accounts |
| The app is installed from a **debug** build carrying `android:testOnly="true"` | see §4 | **STOP** — without the flag the escape route in §4 does not exist |

A **work profile** is not a substitute. The power menu is a device-global surface, and the brief
requires that a work profile must not be assumed to behave like the supported Device Owner
deployment.

## 4. Development escape route — action 4 (debug/test-only)

The debug build carries `android:testOnly="true"` from `app/src/debug/AndroidManifest.xml`. That
flag is what makes `adb install -t` and `dpm remove-active-admin` work on an owner app.

**This is a debug/test-only escape route.** It depends on (a) working, authorized ADB access and
(b) the packaged flag being present. It is **not** an ordinary release-uninstall guarantee: the
release build intentionally lacks `testOnly`, and a Device Owner app generally cannot be
uninstalled normally. Production enrollment and removal remain separate feasibility work.

### 4.1 Verify the packaged manifests before relying on it

```powershell
# testOnly must be present in the DEBUG merged manifest
.\gradlew.bat :app:processDebugMainManifest
Select-String -Path app\build\intermediates\merged_manifest\debug\processDebugMainManifest\AndroidManifest.xml `
  -Pattern 'testOnly'

# and absent from RELEASE
.\gradlew.bat :app:processReleaseMainManifest
Select-String -Path app\build\intermediates\merged_manifest\release\processReleaseMainManifest\AndroidManifest.xml `
  -Pattern 'testOnly'    # expect no output
```

Also confirm it inside the actual packaged APK, because a source manifest alone does not prove the
packaged one:

```powershell
& "$SDK\build-tools\36.0.0\aapt2.exe" dump xmltree --file AndroidManifest.xml app-debug.apk |
  Select-String 'testOnly'
```

### 4.2 The commands

```powershell
$ADB    = "E:\Deepseek\Linksi\toolchain\android-sdk\platform-tools\adb.exe"
$SERIAL = "<TEST_DEVICE_SERIAL>"          # from: & $ADB devices
$APP_ID = "com.example.shutdownprotection"
$ADMIN  = "com.example.shutdownprotection/.admin.ShutdownProtectionAdminReceiver"

# 1. Install the DEBUG test-only APK
& $ADB -s $SERIAL install -t app\build\outputs\apk\debug\app-debug.apk

# 2. Inspect ownership BEFORE provisioning or removing anything
& $ADB -s $SERIAL shell dpm list-owners

# 3. Enroll (only if every prerequisite in section 3 is satisfied)
& $ADB -s $SERIAL shell dpm set-device-owner $ADMIN

# 4. Remove management (debug/test-only escape route)
& $ADB -s $SERIAL shell dpm remove-active-admin $ADMIN
```

After step 4, do **not** assume the device is fully normal. Confirm the runtime lock-task state is
no longer `LOCKED`, and then re-check the power menu itself on that specific device and build — an
OEM can leave policy behind.

## 5. Emulator-only provisioning note (historical evidence, not a phone procedure)

> **Do not apply this to a physical phone.** It is recorded because it is what was actually done on
> the disposable emulator used for Gate A, and it is evidence rather than advice.

On the `android-36 google_apis` emulator image, the first `dpm set-device-owner` attempt failed with
`Not allowed to set the device owner because there are already some accounts on the device`, even
though `dumpsys account` reported `Accounts: 0`. The actual blocker was the provisioning flag. On
that emulator only, clearing the flags let enrollment succeed:

```text
adb shell settings put global device_provisioned 0
adb shell settings put secure user_setup_complete 0
adb shell dpm set-device-owner <APP_ID>/<ADMIN_RECEIVER_CLASS>
adb shell settings put global device_provisioned 1
adb shell settings put secure user_setup_complete 1
```

This changes the device's setup state. On a real phone it can make the setup wizard re-appear and
is not an approved enrollment method. See `docs/TEST_RESULTS.md` §4.1 for the full record.

## 6. Emulator launch and clean-recovery path

The AVD used for this project is **`spm-test-36`**, and it deliberately does **not** live in the
default `%USERPROFILE%\.android\avd` location — it lives inside the project:

```text
_working\avd\spm-test-36.ini
_working\avd\spm-test-36.avd\config.ini
```

`ANDROID_AVD_HOME` must point at `_working\avd`. The emulator's own home directories must also be
redirected into the project, because its default `%USERPROFILE%\.android` is outside the writable
workspace and it otherwise fails repeatedly with `error: 5` on
`emu-last-feature-flags.protobuf.lock`.

```powershell
$SDK = "E:\Deepseek\Linksi\toolchain\android-sdk"
$W   = "E:\Deepseek\Projects\Scheduled-Power-Menu-Restriction\_working"

$env:ANDROID_EMULATOR_HOME = "$W\emulator-home"
$env:ANDROID_USER_HOME     = "$W\android-user"
$env:ANDROID_AVD_HOME      = "$W\avd"
$env:ANDROID_SDK_ROOT      = $SDK
$env:ANDROID_HOME          = $SDK

Start-Process -FilePath "$SDK\emulator\emulator.exe" `
  -ArgumentList @("-avd","spm-test-36","-no-window","-no-audio","-no-boot-anim",
                  "-no-snapshot","-gpu","swiftshader_indirect","-no-metrics","-port","5554") `
  -RedirectStandardOutput "$W\emu-out.log" -RedirectStandardError "$W\emu-err.log" `
  -PassThru -WindowStyle Hidden
```

The AVD was created by hand because `avdmanager create avd` fails on this machine with
`Error: "emulator" package must be installed!` (the SDK has no `emulator/package.xml`). Creating
that metadata file would mean modifying the shared donor SDK, so it was not done.

Clean recovery, in order of preference:

```powershell
& "$SDK\emulator\emulator.exe" -avd spm-test-36 -snapshot spm-clean -no-snapshot-save  # preferred
& "$SDK\emulator\emulator.exe" -avd spm-test-36 -wipe-data                            # fresh boot
& $ADB -s $SERIAL uninstall $APP_ID                                                  # remove the app
```

## 7. Factory reset

A factory reset is a **last-resort destructive action requiring explicit authorization for that
specific device and data destruction**. It is never an automatic recovery step and is not part of
any procedure above. No factory reset has been performed for this project.

## 8. Test record template

Use this for any authorized device test. Leave a field blank rather than guessing.

```text
Device model:                       ____________________
Serial:                             ____________________
Android version / API:              ____________________
Build fingerprint:                  ____________________
APK path / SHA-256 / size:          ____________________
Debug build (testOnly present?):    ____________________
Work profile or full device owner?  ____________________

ACTION 1 - install
  install -t result:                ____________________
ACTION 2 - enroll
  prerequisites all satisfied?      ____________________
  dpm list-owners BEFORE:           ____________________
  dpm set-device-owner result:      ____________________
  dpm list-owners AFTER:            ____________________
ACTION 3 - recover normal controls
  in-app Restore result:            ____________________
  runtime lock-task state after:    ____________________
  dpm list-owners after:            ____ (still an owner? this is expected) ____
  power menu re-checked AFTER:      ____________________
ACTION 4 - remove management
  dpm remove-active-admin result:   ____________________
  dpm list-owners AFTER:            ____________________
  runtime lock-task state AFTER:    ____________________
  power menu re-checked AFTER:      ____________________
  app uninstall result:             ____________________

Physical power-menu observation:    NOT OBSERVED / observed (describe)
Notes / failures:                   ____________________
```

## 9. What is deliberately kept available

- USB debugging stays enabled on the development device. No USB or debugging restriction is
  introduced by this app.
- The device's secure screen lock is never disabled to make a test pass (brief section 11.9).
- The protection app is never removed from the allowlist during a normal armed session without a
  defined recovery route (brief section 11.10).
- No deprecated or unsupported ownership-removal mechanism is used. Production enrollment and
  removal remain separate feasibility work.
