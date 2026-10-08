# Gate A + Gate B Implementation Plan — Scheduled Power Menu Restriction

> **STATUS: SUPERSEDED IN PART — read this before using anything below.**
>
> This plan was written before the API surface was verified. Two of its API claims are **wrong**
> and have been corrected in `docs/API_FACTS.md` (extracted from platform 36's `android.jar` with
> `javap`):
>
> 1. **Task 3, Step 3b** states "API 36 defines NO lock-task policy-identifier constant". That is
>    true only of `DevicePolicyManager`'s own constants. `DevicePolicyIdentifiers.LOCK_TASK_POLICY
>    = "lockTask"` **does** exist. Whether that string is what the callbacks actually deliver
>    remains unverified, so the implementation still handles the identifier opaquely — which is
>    the correct behaviour either way.
> 2. **Task 3, Step 3b** calls the result codes `RESULT_CODE_*`. The real prefix is **`RESULT_*`**
>    (`RESULT_POLICY_SET`, `RESULT_FAILURE_CONFLICTING_ADMIN_POLICY`, `RESULT_POLICY_CLEARED`,
>    `RESULT_FAILURE_STORAGE_LIMIT_REACHED`, `RESULT_FAILURE_HARDWARE_LIMITATION`,
>    `RESULT_FAILURE_UNKNOWN`). The values quoted in the plan were right; the names were not, and
>    code using `RESULT_CODE_*` would not compile.
>
> Additionally, the arm/recovery work described here has since been implemented, and the actual
> results — including what is BLOCKED and why — are in `docs/TEST_RESULTS.md`. Where this plan and
> that document disagree, **`docs/TEST_RESULTS.md` is authoritative.**
>
> The plan is retained because its task decomposition and review-focus reasoning are still useful.

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Deliver a recoverable API-36 proof of concept that enters real Lock Task Mode, suppresses/restores the power menu on demand, and always recovers — passing Gates A and B on the `spm-test-36` emulator, with physical-device observations recorded as BLOCKED until a phone exists.

**Architecture:** Single `app` module; Compose screens never touch `DevicePolicyManager` — `MainViewModel` → `ProtectionCoordinator`/`RecoveryManager` → `DevicePolicyController`. Gate A+B scope has no scheduler: manual POC controls plus a 5-minute temporary release timer only.

**Tech Stack:** Kotlin 2.0.21, Gradle 8.13, AGP 8.13.2, compile/min/target SDK 36, Compose + Material 3, coroutines, DataStore, DevicePolicyManager, AlarmManager. (Pinned from proven `Linksi` combo — see `docs/ENVIRONMENT.md`.)

**Spec:** `docs/Agent-Brief-v2.0.md` (Brief v2.0). Environment facts: `docs/ENVIRONMENT.md`. API fact-sheet: pending subagent research (lock-task/policy/alarm signatures at API 36) — reconcile Task 2–4 interfaces with it before implementing.

## Global Constraints

- Public documented Android APIs only — no root, Accessibility Service, key interception, hidden API, reflection, shell invocation, Shizuku, file modification, vendor bypass.
- ADB is dev-provisioning/diagnostics/recovery only; the app never invokes it.
- No analytics, ads, network, or personal-content collection in the MVP.
- Product name `Scheduled Power Menu Restriction`; explanatory text verbatim: “Restricts the normal Android Power Off and Restart menu during your selected hours on a managed device. Hardware forced restart, manufacturer emergency functions, battery loss, and every possible source of shutdown are outside this feature's guarantee.” Never claim impossibility of power-off, anti-theft, or total prevention.
- Application ID `com.example.shutdownprotection`, overridable via one documented Gradle property; action strings/component names derived from actual applicationId.
- Recovery implemented and verified BEFORE the first restrictive test (Task 4 before Task 6).
- Results use only PASS / FAIL / BLOCKED / NOT TESTED; no invented observations; emulator evidence is preliminary.
- Never provision a personal phone; no factory reset without explicit per-device authorization.
- Second-situation label is “Power menu allowed — managed session active”, never “normal device mode”.

## Review Focus

1. **Emulator menu ≠ physical menu.** An emulator “Restricted” observation must never be recorded as a physical PASS — Gate B stays BLOCKED for the physical phone even when the emulator passes. (Pinned by Task 6 evidence-table test.)
2. **PINNED mistaken for LOCKED.** `LOCK_TASK_MODE_PINNED` (screen pinning) must fail entry; only `LOCK_TASK_MODE_LOCKED` counts. (Pinned by Task 3 controller test.)
3. **Crash during the 5-minute POC leaves restriction without a timer.** A durable temp-test marker must force recovery (not resume) on next start/boot. (Pinned by Task 6 marker test.)
4. **`dpm remove-active-admin` may not restore every policy on every build.** Escape-route verification must re-check power-menu behavior AFTER removal, not assume it. (Pinned by Task 5 procedure step.)
5. **PendingIntent extras don't distinguish identities.** START/END temp-release identities need distinct request codes/URIs + `FLAG_IMMUTABLE`; revision travels as metadata only. (Pinned by Task 6 alarm-identity test.)

---

### Task 1: Gradle project skeleton (API 36, pinned versions)

**Files:**
- Create: `settings.gradle.kts`, `build.gradle.kts` (root), `app/build.gradle.kts`, `gradle.properties`, `gradle/wrapper/gradle-wrapper.properties` (Gradle 8.13), `local.properties` (sdk.dir → Linksi toolchain SDK; git-ignored)
- Test: `./gradlew :app:assembleDebug` exit 0

**Interfaces:**
- Consumes: ENVIRONMENT.md pinned versions
- Produces: `:app` module with `applicationId "com.example.shutdownprotection"` + `appIdOverride` Gradle-property mechanism; `compileSdk/minSdk/targetSdk = 36`; Compose+M3+DataStore+coroutines dependencies (exact versions resolved once, then pinned in `gradle.properties`/version catalog and recorded in ARCHITECTURE.md)

- [ ] **Step 1: Generate wrapper and settings.** Create the files above; `gradle.properties` documents `spm.appId` override (default `com.example.shutdownprotection`) and how `app/build.gradle.kts` derives `applicationId` from it.
- [ ] **Step 2: Run debug build.** Run: `.\gradlew.bat :app:assembleDebug` with `JAVA_HOME=E:\Deepseek\Linksi\toolchain\jdk-17`. Expected: BUILD SUCCESSFUL, APK under `app\build\outputs\apk\debug\`.
- [ ] **Step 3: Record build identity.** Record Gradle/AGP/Kotlin versions, APK checksum into `docs/TEST_RESULTS.md` skeleton. Commit: `git` only if a repo is initialized for this folder (do not nest inside Linksi's repo).

### Task 2: Admin receiver, manifest, ownership status screen

**Files:**
- Create: `app/src/main/java/com/example/shutdownprotection/admin/ShutdownProtectionAdminReceiver.kt`, `app/src/main/res/xml/device_admin_receiver.xml` (empty/minimal `uses-policies`), `app/src/main/.../ui/MainActivity.kt`, `MainScreen.kt` (status only for now)
- Modify: `app/src/main/AndroidManifest.xml`
- Test: unit test `OwnershipStatusTest` (fake controller → Ready/Required/Lost mapping)

**Interfaces:**
- Consumes: API fact-sheet (manifest flags, `BIND_DEVICE_ADMIN`, `DEVICE_ADMIN_ENABLED`)
- Produces: `ShutdownProtectionAdminReceiver : DeviceAdminReceiver`; `DevicePolicyController.isDeviceOwner(): Boolean` (backed by `isDeviceOwnerApp(packageName)`); status UI state `DeviceOwnerState { READY, REQUIRED, LOST }`

- [ ] **Step 1: Write failing ownership test.** `app/src/test/.../OwnershipStatusTest.kt`: fake owner true→READY, false→REQUIRED, owner-lost-after-ready→LOST.
- [ ] **Step 2: Run, expect FAIL.** Run: `.\gradlew.bat :app:testDebugUnitTest --tests "*OwnershipStatusTest*"`. Expected: FAIL (no implementation).
- [ ] **Step 3: Implement receiver + manifest + status.** Receiver declares `BIND_DEVICE_ADMIN`, `DEVICE_ADMIN_ENABLED`, `android.app.device_admin` metadata; manifest declares `RECEIVE_BOOT_COMPLETED`, `SCHEDULE_EXACT_ALARM` only (no `USE_EXACT_ALARM`); minimal `uses-policies`. Compose status screen shows Device Owner state + the required “Device Owner provisioning is required…” text when absent.
- [ ] **Step 4: Run tests + lint.** Run: `.\gradlew.bat :app:testDebugUnitTest :app:lintDebug`. Expected: PASS, no errors.

### Task 3: DevicePolicyController + baseline capture + settings

**Files:**
- Create: `admin/DevicePolicyController.kt`, `admin/LockTaskPolicyUpdateReceiver.kt` (callbacks log/record + readback for now; full §18 handling at Gate D), `data/ProtectionSettings.kt`, `data/PolicyBaseline.kt`, `data/SettingsRepository.kt` (DataStore, single instance)
- Test: `DevicePolicyControllerTest` (fakes), `ProtectionSettingsTest` (validation: minutes 0..1439, equal-times reject)

**Interfaces:**
- Consumes: Task 2 receiver/admin identity
- Produces: `isDeviceOwner() / isLockTaskPermitted(pkg) / readLockTaskFeatures() / readLockTaskPackages() / readRuntimeLockTaskState() / applyAllowedPackages(set) / applyFeatures(int) / captureBaseline(): PolicyBaseline`; `PolicyOperationResult { SUBMITTED, READBACK_MISMATCH, FAILED(exception) }` — never unconditional success; `ProtectionSettings` exactly per Brief §7; `recoveryRequired` durable flag

- [ ] **Step 1: Write failing tests.** PINNED≠LOCKED mapping test; `applyFeatures` readback-mismatch test (fake applies nothing → result is not success); settings validation tests (equal times → “Start and end must be different.”, out-of-range reject).
- [ ] **Step 2: Run, expect FAIL.** Run: `.\gradlew.bat :app:testDebugUnitTest --tests "*DevicePolicyControllerTest*" --tests "*ProtectionSettingsTest*"`. Expected: FAIL.
- [ ] **Step 3: Implement.** Protected mask = SYSTEM_INFO|NOTIFICATIONS|HOME|OVERVIEW|KEYGUARD (47); allowed mask = protected|GLOBAL_ACTIONS (63) — verified API-36 values (GLOBAL_ACTIONS=16). Do NOT set BLOCK_ACTIVITY_START_IN_TASK (64). `captureBaseline()` reads current packages+features BEFORE first mutation, persisted via DataStore, never overwritten by already-modified values. `isLockTaskAllowed` must NOT exist (use real `isLockTaskPermitted`).
- [ ] **Step 3b: Opaque policy-identifier rule (API fact-sheet finding, 2026-10-06).** API 36 defines NO lock-task policy-identifier constant (only `POLICY_DISABLE_CAMERA` / `POLICY_DISABLE_SCREEN_CAPTURE` exist). `LockTaskPolicyUpdateReceiver` must handle `policyIdentifier` opaquely — log identifier + `PolicyUpdateResult.getResultCode()` (0=set, 1=conflict, 2=cleared, 3=storage-limit, 4=hardware-limitation, -1=unknown) + `TargetUser`, then refresh readback. Never compare against an invented `POLICY_LOCK_TASK` string. `getLockTaskPackages()` returns `String[]` — wrap to Set with null/empty defense. `setLockTaskPackages` declares `throws SecurityException` — arm/recovery paths catch it.
- [ ] **Step 4: Run full unit suite.** Run: `.\gradlew.bat :app:testDebugUnitTest`. Expected: all PASS.

### Task 4: Recovery first — RecoveryManager + Restore button + escape docs

**Files:**
- Create: `protection/RecoveryManager.kt`, `protection/ProtectionState.kt` (runtime states incl. RECOVERING/RECOVERY_FAILED), `ui/PocScreen.kt` (Restore button only at this stage)
- Modify: `AndroidManifest.xml` (debug-only `android:testOnly="true"` override via `src/debug/`), `docs/RECOVERY.md`
- Test: `RecoveryManagerTest` (idempotency: run twice → same verified end state, no duplicate sessions; settings-write-failure still attempts policy release)

**Interfaces:**
- Consumes: Task 3 controller + baseline
- Produces: `RecoveryManager.recover(reason): RecoveryResult`; “Restore Normal Device Mode” button visible on every management screen without requiring a valid schedule; `docs/RECOVERY.md` with exact `adb -s <SERIAL> install -t … / dpm set-device-owner <APP_ID>/<ADMIN_CLASS> / dpm list-owners / dpm remove-active-admin …` commands (placeholders marked, filled at Gate A run)

- [ ] **Step 1: Write failing recovery tests.** Idempotent double-recover test; disabled-wins-over-queued-restrict test; storage-failure-still-releases test.
- [ ] **Step 2: Run, expect FAIL.** Run: `.\gradlew.bat :app:testDebugUnitTest --tests "*RecoveryManagerTest*"`. Expected: FAIL.
- [ ] **Step 3: Implement idempotent recovery.** Sequence per Brief §10 (serialize → durably disable+revision+recoveryRequired → cancel owned alarms → permissive mask → stop session via allowlist removal → verify ended → restore baseline → clear flag only on verified cleanup → success display only after verification). Bounded inexact retry (≤3, ≥20 min apart, release-only) planned as metadata, wired fully at Gate D.
- [ ] **Step 4: Run + verify merged debug manifest.** Run tests; run `.\gradlew.bat :app:processDebugManifest` and confirm `testOnly="true"` present in debug, ABSENT in release.
- [ ] **Step 5: Commit.**

### Task 5: Gate A run — provisioning + escape on fresh `spm-test-36` AVD

**Files:**
- Create: `docs/TEST_RESULTS.md` (Gate A section: APK id, device/build fingerprint, commands, observations)
- Test: the procedure itself; status NOT TESTED → PASS/FAIL/BLOCKED per step

**Interfaces:**
- Consumes: Tasks 1–4 (installable debug APK with recovery UI)
- Produces: Gate A verdict + filled-in RECOVERY.md commands + re-provisioned AVD ready for Gate B

- [ ] **Step 1: Create fresh AVD.** Create `spm-test-36` from `system-images/android-36/google_apis/x86_64` (no Google accounts, snapshot enabled); record exact `avdmanager`/emulator commands; verify acceleration; boot to unlocked state.
- [ ] **Step 2: Install, confirm unowned state.** `adb -s <SERIAL> install -t <DEBUG_APK>`; app shows “provisioning required”; `dpm list-owners` shows none.
- [ ] **Step 3: Provision.** `dpm set-device-owner com.example.shutdownprotection/.admin.ShutdownProtectionAdminReceiver` (actual class path); app shows READY; diagnostics confirm.
- [ ] **Step 4: Escape without restriction.** `dpm remove-active-admin …`; confirm Restore button state, re-check power-menu behavior AFTER removal (Review Focus #4); record.
- [ ] **Step 5: Re-provision + verdict.** Re-provision for Gate B; write PASS/FAIL per Gate A step with fingerprints; recovery button visible and schedule-independent — mandatory for PASS.

### Task 6: Gate B — manual POC toggle + observed suppression/restoration

**Files:**
- Create: `protection/ProtectionCoordinator.kt` (minimal: manual-mode reconcile only), `ui/PocScreen.kt` (4 debug buttons), temp-release alarm receiver + inexact fallback (release-only → RecoveryManager, never enables)
- Test: `PocReleaseTest` (disable cancels timers; reboot/update marker forces recovery; alarm identities distinct with FLAG_IMMUTABLE)

**Interfaces:**
- Consumes: Tasks 3–5
- Produces: Debug-only controls Start/Allow/Restrict/Restore; 5-minute exact temp-release + distinct inexact fallback submitted BEFORE restrictive mask, verified install or no restriction; durable temp-test marker; POC status shows requested/effective mask + LOCKED state (never the production “daily enabled” label)

- [ ] **Step 1: Write failing POC tests.** Marker-forces-recovery test; disable-cancels-timers test; stale-revision-alarm-is-no-op test.
- [ ] **Step 2: Run, expect FAIL.** Run: `.\gradlew.bat :app:testDebugUnitTest --tests "*PocReleaseTest*"`. Expected: FAIL.
- [ ] **Step 3: Implement manual toggle.** Baseline capture → allowlist app → permissive mask → `startLockTask()` from foreground → verify `LOCK_TASK_MODE_LOCKED` (PINNED = failure) → record invoking gesture → confirm menu present → submit temp-release+fallback → restrictive mask → gesture on home/app/keyguard → menu absent → permissive → menu returns → full recovery → baseline returns. Never select Power Off during toggle tests.
- [ ] **Step 4: Run 3 toggle/recovery cycles on `spm-test-36`.** Record per-cycle evidence; no accumulated broken state; TEST_RESULTS Gate B emulator section.
- [ ] **Step 5: Gate B verdict.** Emulator observations recorded as preliminary; physical-phone Gate B explicitly BLOCKED (no device). If dialog remains available under restrictive mask: stop, document UI path, do not compensate with prohibited techniques.

### Task 7: Gate A/B unit-integration sweep + docs

**Files:**
- Modify: `README.md`, `docs/ARCHITECTURE.md` (state model, always-active-session choice, storage, alarm identities, rollback, policy verification, permission/exported-component table), `docs/TEST_PLAN.md`, `docs/COMPATIBILITY_MATRIX.md`, `docs/PRODUCTION_FEASIBILITY.md` (stub: DPC enrollment research deferred)
- Test: full `./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug` green; one non-default applicationId build (`-Pspm.appId=…test`) installs.

- [ ] **Step 1: Run full gate.** Run: `.\gradlew.bat :app:testDebugUnitTest :app:lintDebug :app:assembleDebug`. Expected: exit 0.
- [ ] **Step 2: Non-default appId build.** Run: `.\gradlew.bat :app:assembleDebug -Pspm.appId=com.example.spstest`. Expected: builds; derived action/component names correct (verify manifest merger).
- [ ] **Step 3: Write final response block.** Result classification + all 12 required fields per Brief §28 (honest BLOCKED items, no hypothetical compatibility).

## Later stages (gated — entry criteria, not tasks yet)

- **Gate C (usability matrix):** enters only after Gate B emulator PASS. Needs physical phone for call/SMS/biometric rows; emulator covers navigation/recents/shade only. Unacceptable usability → report, stop; no silent architecture switch.
- **Gate D (scheduler):** enters only after A–C pass for MVP scope. Pure `ScheduleCalculator` (injected clock/zone, DST gap/overlap rules) + unit battery per §26, durable settings + serialized `reconcile(trigger)`, exact START/END + inexact RELEASE_FALLBACK + RECOVERY_RETRY identities, 60-second threshold measurements, stale-event/process-death/edit tests.
- **Gate E (boot/update):** enters after Gate D. Direct-Boot minimal state, unlock reconciliation, explicit resume, update migration; unattended re-entry and pre-unlock protection are SEPARATE claims, PASS only on observation.
- **Final screens + production research:** after Gate E baseline; Play/enrollment feasibility from primary docs only.
