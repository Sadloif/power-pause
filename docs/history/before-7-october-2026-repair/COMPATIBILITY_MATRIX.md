# COMPATIBILITY MATRIX — Scheduled Power Menu Restriction

Required by brief section 28.8: every tested device and workflow, separated rather than
aggregated. Brief section 27 is explicit that **one Pixel result does not establish all
Android 16 devices**, so nothing here is generalised beyond the row it appears in.

Columns follow brief section 23: device/build, function, menu-allowed result, menu-restricted
result, steps/evidence, limitation, severity.

---

## 1. Devices

| Device | OS / API | Build fingerprint | Role | Provisioned as Device Owner | Overall |
|---|---|---|---|---|---|
| `sdk_gphone64_x86_64` (emulator, AVD `spm-test-36`) | Android 16 / API 36 | `google/sdk_gphone64_x86_64/emu64xa:16/BE2A.250530.026.F3/13894323:userdebug/dev-keys` | Preliminary emulator surface only | Yes (verified, see `docs/TEST_RESULTS.md` §4) | **Preliminary** — Gate A PASS, Gate B BLOCKED |
| **OPPO CPH2825** (physical) | Android 16 / API 36, build type `user` | `OPPO/CPH2825/OP62B1L1:16/BP2A.250605.015.B.R4T2.4c442a7-271a2b9-276405c` — full value: `OPPO/CPH2825/OP62B1L1:16/BP2A.250605.015/B.R4T2.4c442a7-271a2b9-276405c:user/release-keys` | **Read-only inspection only** | **No — deliberately not provisioned** | **NOT TESTED** |
| Dedicated physical Android 16 phone (disposable) | unknown | not recorded | Intended primary surface | Not attached | **NOT TESTED** |
| Any Samsung / other OEM | unknown | not recorded | Out of scope until tested | — | **NOT TESTED** |

No second *test* device was available, so no cross-device compatibility claim is made anywhere in
this project.

### On the OPPO CPH2825 — inspection only, and why

A physical OPPO was attached once. It was **inspected read-only and then left untouched**: nothing
was installed, no setting was written, no policy was changed, and it was **not** enrolled. It is
the owner's daily-use phone — four Google accounts, WhatsApp and other live data — and the brief
forbids provisioning a primary personal phone (§4.5). `dpm set-device-owner` would also have failed
on it as-is, and the only way past that on an **emulator** is clearing `device_provisioned` /
`user_setup_complete` — which is **emulator-only historical evidence, not a phone procedure**, and
is an invasive change not to be made on a live phone without explicit authorization for that
specific device. See `docs/RECOVERY.md` §3 for the supported enrollment prerequisites.

What the read-only inspection did establish, as a genuine "other OEM" data point:

| Observation | Value |
|---|---|
| No pre-existing device management | `dpm list-owners` → `no owners` (nothing of anyone else's to disturb) |
| Accounts present | 8+, including four Google accounts |
| Provisioning state | `device_provisioned=1`, `user_setup_complete=1` — fully set up |
| Long-press-power behaviour | `assistant` is assigned, the same as the emulator |
| OEM-specific power handling | ColorOS keys present: `incall_power_button_behavior`, `oppo_power_button_ends_alarm_clock`, `oplus_*` |
| Exact-alarm special access | app-op at `default` for a sample app — not granted by default |

This is recorded as an **environment finding**, not as a compatibility result. No workflow was
tested on it, and no result from it may be used to claim OEM compatibility.

## 2. Why the emulator row stops at "preliminary"

The emulator cannot exercise the product's central behaviour. On this GMS image the
global-actions gesture is pinned to the Google Assistant by a configuration overlay, and the
usual override does not take effect even across a reboot; disabling the assistant makes the
gesture do nothing rather than falling back to the power menu. The full probe log is in
`docs/TEST_RESULTS.md` §5.2.

So the emulator row can confirm **policy and runtime state** but cannot confirm **menu
behaviour**. The two are kept in separate tables below and never merged.

## 3. Emulator — verified API and runtime state

These are public-API readbacks, not menu observations. Each is marked with the command used.

| Function | Result | Evidence |
|---|---|---|
| Device Owner provisioning | **PASS** | `dpm set-device-owner` → `Success`; `dpm list-owners` → `1 owner … DeviceOwner,Affiliated` |
| Missing-ownership reporting before provisioning | **PASS** | `dpm list-owners` → `no owners`; app reported `Device Owner: Required` |
| Ownership reporting after removal | **PASS** | App reported `Device Owner: Lost` (not `Required`) once ownership had existed — the intended distinction |
| Ownership survives reboot | **PASS** | `dpm list-owners` after `adb reboot` still listed the app |
| Real Lock Task Mode entered | **PASS** | `dumpsys activity activities` → `mLockTaskModeState=LOCKED` (never `PINNED`) |
| Permissive mask applied | **PASS** | `dumpsys device_policy` → `LockTaskPolicy {mPackages= com.example.shutdownprotection; mFlags= 63}` |
| Restrictive mask applied | **PASS** | `dumpsys device_policy` → `LockTaskPolicy {… mFlags= 47}` |
| Allowlist scoped to this app only | **PASS** | `mPackages= com.example.shutdownprotection` |
| Exact release alarm submitted | **PASS** | `dumpsys alarm` → `action.END_PROTECTION`, `RTC_WAKEUP`, `exactAllowReason=permission` |
| Distinct **inexact** fallback submitted | **PASS** | `dumpsys alarm` → `action.RELEASE_FALLBACK`, `window=+4m29s998ms` (a real delivery window) |
| Scheduled next start submitted | **PASS** | `dumpsys alarm` → `action.START_PROTECTION`, `origWhen=2026-10-07 02:00:00.000` |
| Full recovery ends the session | **PASS** | `mLockTaskModeState=NONE` afterwards |
| Recovery cancels every app-owned alarm | **PASS** | no pending `Alarm{…shutdownprotection` entries afterwards |
| Recovery clears the app's policy | **PASS** | no `LockTaskPolicy` for this admin afterwards (baseline was empty) |
| Recovery reports success only after verification | **PASS** | POC screen → `DISARMED: Normal device mode restored` |
| Exact-alarm capability evaluated, not assumed | **PASS** | reported `Unavailable` until the app-op was granted, then `Available` |
| Settings store is device-protected | **PASS** (after fix) | see `docs/TEST_RESULTS.md` §7 defect 1 and §8 |
| Notification permission denied | **NOT TESTED** | no status notification is implemented in the MVP; denial is therefore not exercised |

## 4. Emulator — menu behaviour

| Function | Menu-allowed result | Menu-restricted result | Evidence | Severity |
|---|---|---|---|---|
| Normal Android global actions dialog | **BLOCKED** | **BLOCKED** | The dialog cannot be invoked on this image (gesture pinned to the Assistant) | Blocking for Gate B |

The gesture as configured on this build, which the brief requires to be recorded: **long-press
Power is assigned to the Google Assistant**, not to the power menu. Logcat:
`WindowManager: powerLongPress: mLongPressOnPowerBehavior=5`.

## 5. Physical device — every workflow: NOT TESTED

The matrix below is the brief's section 23 list. It is reproduced in full so nothing is
silently omitted, and every row is **NOT TESTED** because no physical device was attached and
the emulator cannot invoke the menu at all.

| Function | Menu-allowed | Menu-restricted | Steps / evidence | Limitation | Severity |
|---|---|---|---|---|---|
| Home button and gesture navigation | NOT TESTED | NOT TESTED | — | — | unknown |
| Overview / Recents | NOT TESTED | NOT TESTED | — | — | unknown |
| Switching between allowlisted apps | NOT TESTED | NOT TESTED | — | — | unknown |
| Launching a non-allowlisted app | NOT TESTED | NOT TESTED | — | — | unknown |
| Notification icons, shade, notification actions | NOT TESTED | NOT TESTED | — | — | unknown |
| Quick Settings | NOT TESTED | NOT TESTED | — | Expected to be **unavailable** under the documented notifications feature; recorded as a known limitation, not a defect to fix with prohibited techniques | expected limitation |
| Incoming ordinary calls and in-call UI | NOT TESTED | NOT TESTED | — | Requires a physical device with a SIM | unknown |
| Outgoing ordinary calls | NOT TESTED | NOT TESTED | — | Requires a physical device with a SIM | unknown |
| SMS app | NOT TESTED | NOT TESTED | — | Requires a physical device with a SIM | unknown |
| WhatsApp or selected messaging app | NOT TESTED | NOT TESTED | — | Requires a physical device and an account | unknown |
| Browser | NOT TESTED | NOT TESTED | — | — | unknown |
| Camera and camera launched from another app | NOT TESTED | NOT TESTED | — | Camera handoff is a distinct compatibility case | unknown |
| Clock alarms, alarm UI, dismiss and snooze | NOT TESTED | NOT TESTED | — | Interacts with the same `AlarmManager` surface this app uses | unknown |
| Settings subsections required for daily use | NOT TESTED | NOT TESTED | — | — | unknown |
| Secure screen lock | NOT TESTED | NOT TESTED | — | The device's screen lock is never disabled to make a test pass | unknown |
| PIN / password unlock | NOT TESTED | NOT TESTED | — | — | unknown |
| Biometric unlock | NOT TESTED | NOT TESTED | — | Requires a physical device | unknown |
| Permission prompts | NOT TESTED | NOT TESTED | — | A distinct compatibility case under Lock Task | unknown |
| Browser / account authentication handoffs | NOT TESTED | NOT TESTED | — | A distinct compatibility case | unknown |
| File / document picker and share sheet | NOT TESTED | NOT TESTED | — | A distinct compatibility case | unknown |
| Rotation, split-screen, system gestures | NOT TESTED | NOT TESTED | — | — | unknown |
| Reachability of recovery controls | **PASS** (emulator, UI only) | **PASS** (emulator, UI only) | `uiautomator dump` lists `Restore Normal Device Mode` with the schedule disabled | Reachability verified in the UI only; not verified while a restricted session is active on a physical device | low |

### Gate C verdict

**NOT TESTED.** Brief section 23 requires an explicit statement of whether the demonstrated
restrictions are acceptable for a dedicated-device MVP. That statement cannot be made, because
no restriction has been demonstrated on a device where the menu can be observed. The brief's
instruction here is unambiguous: *do not spend later phases polishing an architecture that
already fails an essential use-case requirement* — so this is reported as unmet rather than
estimated.

## 6. Alarm and scheduling behaviour per configuration

| Case | Result |
|---|---|
| Exact alarm available (app-op granted) | **PASS** — primary boundaries submitted with `exactAllowReason=permission` |
| Exact alarm unavailable (app-op default) | **PASS** — the app refused to arm and reported `EXACT_SCHEDULING_UNAVAILABLE` rather than restricting without a release path |
| Inexact fallback is genuinely inexact | **PASS** — observed with a delivery window, so it does not require exact-alarm permission |
| Idle / Doze transition timing | **NOT TESTED** — no transition has been measured on any device; the 60-second project threshold is therefore unverified |
| Force-stop behaviour | **NOT TESTED** |
| Permission revocation after arming | **NOT TESTED** — real revocation could not be produced on this build |
| Overnight interval across midnight (device) | **NOT TESTED** — covered by unit tests only |
| DST gap / overlap (device) | **NOT TESTED** — covered by unit tests only |

## 7. Boot and update behaviour

| Case | Result |
|---|---|
| Ownership survives reboot | **PASS** (prerequisite only) |
| Schedule restored after reboot | **NOT TESTED** |
| Explicit post-unlock resume | **NOT TESTED** |
| Automatic unattended post-reboot re-entry | **NOT TESTED** — a separate objective; must not be reported as met |
| Protection before the first unlock | **NOT TESTED** — a separate objective; must not be reported as met |
| Application update while enabled | **NOT TESTED** |

## 8. How to extend this matrix

1. Add one **device** row per model and build fingerprint actually tested. Never merge two
   models into one row, and never infer one model's behaviour from another's.
2. Keep the "verified API and runtime state" table and the "menu behaviour" table separate. An
   API readback is not menu evidence, and the two must never be combined into a single PASS.
3. Fill every physical row from direct observation, with the exact steps and the measured delay
   where timing is relevant. Leave a row `NOT TESTED` rather than estimating it.
4. Record the device's actual global-actions gesture for that model — the brief notes some
   devices use a power/volume combination or assign long-press to an assistant, and this
   emulator is a live example of the latter.
