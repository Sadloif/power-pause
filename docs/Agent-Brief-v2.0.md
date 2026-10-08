# Android 16 Scheduled Power Menu Restriction — Implementation Brief

Version: 2.0. Prepared 6 October 2026.

This document replaces the previous project plan. Give the entire document to the implementing agent. Implement the requirements in the stated order. Do not interpret API availability, a successful build, or an emulator result as proof of physical-device behavior.

## 1. Assignment and intended outcome

Build an Android application that lets the person managing a dedicated test device choose a daily protected interval, initially 02:00–05:00 in the device's current local time zone.

When the system is armed and protection is actually operating, restrict the normal Android global actions dialog containing Power Off and Restart. Outside the interval, permit that dialog. Use documented Android Device Policy APIs and real Lock Task Mode.

The first deliverable is a small, recoverable proof of concept. The full application is conditional on passing the gates in this document.

The application is a Device Policy Controller (DPC). It requires Device Owner provisioning for the supported MVP deployment. It is not an application that gains this capability through installation and an ordinary permission prompt.

Use the product name **Scheduled Power Menu Restriction**. The explanatory text must say:

> Restricts the normal Android Power Off and Restart menu during your selected hours on a managed device. Hardware forced restart, manufacturer emergency functions, battery loss, and every possible source of shutdown are outside this feature's guarantee.

Do not claim that the phone is physically impossible to turn off. Do not claim anti-theft protection or complete shutdown prevention.

## 2. Hard constraints

1. Target Android 16, API 36.
2. Use Kotlin, Gradle Kotlin DSL, Compose, Material 3, coroutines, DataStore, DevicePolicyManager, and AlarmManager.
3. Use public, documented Android APIs only.
4. No root, Accessibility Service, power-key interception, hidden API, System UI reflection, runtime shell command, Shizuku, system-file modification, or vendor privilege bypass.
5. ADB commands are allowed only for development provisioning, diagnostics, recovery, and explicitly documented test execution. The installed application must not invoke them.
6. No analytics, advertising, network communication, or personal-content collection in the MVP.
7. Activation requires a deliberate user action. First installation is disabled.
8. Recovery must be implemented and verified before the first restrictive test.
9. Real-device claims require observations on that device and OS build.
10. No invented test results. Use PASS, FAIL, BLOCKED, or NOT TESTED.
11. No production release or Play submission is part of this assignment.
12. Do not call emergency services during testing. Document emergency-call behavior from authoritative sources and controlled testing arrangements only.

## 3. Decisions already made

### 3.1 Android versions

For the MVP use:

```kotlin
compileSdk = 36
minSdk = 36
targetSdk = 36
```

This intentionally narrows the first implementation to Android 16. Supporting API 28–35 is a separate future task; do not add untested compatibility branches now. If the repository already has a justified lower minimum, explain the difference, guard every newer API, and test that support before claiming it.

### 3.2 Package name

Default application ID: `com.example.shutdownprotection`.

Allow override through one documented Gradle property. Derive internal action names, package-specific settings intents, and admin component names from the actual application ID/context rather than scattering hardcoded strings. Keep the Kotlin namespace stable unless a change is necessary. Test one non-default application ID.

### 3.3 MVP operating model

The initial architecture keeps Lock Task Mode active throughout an armed session. At schedule boundaries it changes whether global actions are permitted.

There are three distinct user-facing situations:

| Situation | Intended behavior |
| --- | --- |
| Disarmed | App restriction removed; exit its managed session and restore the policy baseline |
| Armed, outside protected hours | Global actions permitted; managed-session restrictions still apply |
| Armed, inside protected hours | Global actions restricted, if policy and runtime prerequisites are verified |

Do not label the second situation “normal device mode.” Label it “Power menu allowed — managed session active.”

Lock Task Mode is a dedicated-device mechanism. Quick Settings is expected to remain unavailable under the documented notifications feature. Record this as a known limitation; do not promise to restore it by adding unrelated flags.

### 3.4 Reboot behavior

Persist the user's enabled preference, but do not assume the runtime session survives a reboot. After reboot, restore scheduling and reconcile configuration. If the required locked session is not active, show `WAITING_FOR_SESSION` or `WAITING_FOR_UNLOCK`.

The supported baseline recovery route is: user unlocks the device, opens the app, and explicitly resumes the managed session. Automatic re-entry is a separate proof-of-concept investigation. It must use documented mechanisms, respect keyguard, and must never be represented as working before it is demonstrated.

This is an explicit limitation of the baseline MVP. The original goal of unattended reboot recovery is achieved only if the additional reboot gate passes.

### 3.5 Failure preference

Prefer restoring access to the power menu and ending the app's managed session when a detected error makes scheduled release unreliable. This is a best-effort recovery policy, not an Android guarantee that restoration happens immediately after every failure.

Do not leave the menu restricted merely to preserve an “enabled” indicator. For scheduled operation, do not enable a new restrictive state unless the restoration schedule has been created successfully. The manual proof of concept must have a tested recovery route and the minimal temporary release described in Gate B; it does not require the full daily scheduler yet.

## 4. Before writing implementation code

Perform these steps and report the result in `docs/ENVIRONMENT.md`:

1. Inspect repository instructions, existing code, and current modifications. Preserve unrelated work.
2. Determine whether Android SDK 36, a compatible JDK, Gradle, and ADB are available.
3. Use compatible stable tool versions, pin their actual versions, and explain the choices. Do not guess future dependency versions or use dynamic versions.
4. Check available Android 16 emulators and connected test devices.
5. Record whether a dedicated physical test phone is available. Never provision a primary personal phone as part of unattended testing.
6. Inspect Device Owner state before attempting provisioning. Do not remove someone else's device management.
7. Do not factory reset any device without explicit authorization for that specific device and data destruction.
8. Read the official references in section 29 for the exact APIs used. Verify current method signatures, component registration, and target-SDK requirements.
9. Separate missing tooling from unsupported behavior. A missing device is BLOCKED/NOT TESTED, not proof that the feature fails.

If a required test surface is unavailable, finish the implementation and documentation that can be safely completed for the current gate. Do not proceed to later gated features or fabricate verification. State precisely what must be tested next.

## 5. Required project organization

Use this structure, adapting package directories to the existing repository:

```text
app/src/main/.../
  admin/
    ShutdownProtectionAdminReceiver.kt
    DevicePolicyController.kt
    LockTaskPolicyUpdateReceiver.kt
  protection/
    ProtectionCoordinator.kt
    ProtectionState.kt
    RecoveryManager.kt
  scheduling/
    ScheduleCalculator.kt
    ScheduleManager.kt
    ProtectionAlarmReceiver.kt
    SystemEventReceiver.kt
  data/
    ProtectionSettings.kt
    SettingsRepository.kt
    PolicyBaseline.kt
    DiagnosticsRepository.kt
  ui/
    MainActivity.kt
    MainViewModel.kt
    MainScreen.kt
    SetupScreen.kt
    ScheduleScreen.kt
    AllowedAppsScreen.kt
    DiagnosticsScreen.kt
    PocScreen.kt

app/src/main/res/xml/device_admin_receiver.xml
app/src/test/.../
app/src/androidTest/.../
docs/
  ENVIRONMENT.md
  ARCHITECTURE.md
  TEST_PLAN.md
  TEST_RESULTS.md
  COMPATIBILITY_MATRIX.md
  RECOVERY.md
  PRODUCTION_FEASIBILITY.md
README.md
```

Compose screens must not call DevicePolicyManager directly. ViewModels invoke a coordinator; the coordinator uses policy, scheduling, settings, and diagnostics components. Schedule calculation must be pure Kotlin with an injected clock and time zone.

Do not introduce a background service simply to keep the process alive. Do not add WorkManager as the primary clock-boundary mechanism. Long-running diagnostic/export work can be separated from time-critical reconciliation.

## 6. Manifest and receiver requirements

1. Declare the DeviceAdminReceiver with the `android.permission.BIND_DEVICE_ADMIN` component permission, appropriate exported behavior, `android.app.device_admin` metadata, and the `DEVICE_ADMIN_ENABLED` action.
2. Create the referenced admin XML. Use an empty/minimal `uses-policies` declaration unless a documented operation actually requires a listed legacy policy. Do not declare wipe, password, camera, or unrelated policies.
3. Declare `RECEIVE_BOOT_COMPLETED` and `SCHEDULE_EXACT_ALARM`.
4. Do not declare `USE_EXACT_ALARM` for this feature in the MVP.
5. Register the PolicyUpdateReceiver for both documented policy-set-result and policy-changed actions. Protect it with `BIND_DEVICE_ADMIN`; override the policy callbacks, not its final `onReceive()`.
6. Protect internal scheduling receivers from arbitrary external invocation. Use explicit broadcast PendingIntents and `exported=false` for internal receivers.
7. Handle legitimate system events with suitable manifest/dynamic registrations for API 36. Accept only the specific known actions. Set exported/permission attributes according to the documented event source rather than copying a receiver declaration indiscriminately.
8. Mark only the components that genuinely support pre-unlock operation as `directBootAware=true`. Ensure all their dependencies are also usable before unlock.
9. Do not apply `android:lockTaskMode="if_whitelisted"` to every activity. The baseline implementation enters from the explicit foreground arm/resume flow. If this manifest mechanism is investigated for auto-re-entry, isolate it, explain its effects, and verify that it cannot re-enter after disarm.
10. Do not request contacts, SMS, calls, location, camera, microphone, Internet, or broad storage permissions merely to test other applications.
11. If a persistent user-visible status notification is implemented, use the required notification permission flow. Denial must not hide status in the main screen or remove recovery controls.
12. Disable automatic backup of active management/scheduling state, or define explicit backup exclusions. A restore onto another device must not silently activate protection or restore a stale policy baseline.

Document every declared permission and every exported component in `ARCHITECTURE.md`.

## 7. Persistent settings and observed state

Keep user intent separate from observed Android state. Use a model equivalent to:

```kotlin
data class ProtectionSettings(
    val schemaVersion: Int = 1,
    val enabled: Boolean = false,
    val startMinuteOfDay: Int = 120,
    val endMinuteOfDay: Int = 300,
    val revision: Long = 0,
    val allowedPackages: Set<String> = emptySet(),
    val recoveryRequired: Boolean = false
)
```

Requirements:

1. Validate minute values in `0..1439`.
2. Reject identical start and end times with “Start and end must be different.” Do not infer a zero-hour or 24-hour interval.
3. Increment `revision` when enabling, disabling, changing times, changing the allowlist, or invalidating existing scheduled work.
4. `enabled` is the persistent preference, not proof of protection.
5. `recoveryRequired` survives a crash during rollback; later startup must resume recovery before considering a new arm operation.
6. Capture and persist a `PolicyBaseline` before the first session policy mutation. Include the original lock-task package list and features, and any other policy changed by this app. Never overwrite it with the app's already-modified values on a later resume.
7. Keep a single DataStore instance for each file. Keep MVP receivers in the same process. Do not create competing writers or instances.
8. Use a DataStore file deliberately located in device-protected storage for the minimum settings needed by Direct Boot. Do not access a credential-protected DataStore before unlock.
9. Persist only configuration and limited diagnostics, not personal application content.
10. Treat unreadable/corrupt settings as a recovery condition. Default to disabled, try to release app-imposed restriction using documented operations, and display an error. Do not create a fresh enabled schedule silently.
11. Store diagnostic observations separately from durable user intent. A stored “Protected” value from yesterday is not today's runtime state.

Suggested runtime state values:

```text
NOT_DEVICE_OWNER
DISARMED
ARMING
ARMED_POWER_MENU_ALLOWED
ARMED_POWER_MENU_RESTRICTED
WAITING_FOR_UNLOCK
WAITING_FOR_SESSION
EXACT_SCHEDULING_UNAVAILABLE
POLICY_PENDING
CONFIGURATION_ERROR
RECOVERING
RECOVERY_FAILED
```

The screen may use simpler wording, but the internal distinction must exist.

## 8. DevicePolicyController responsibilities

Encapsulate all documented device policy operations and reads here. Provide equivalents of:

```kotlin
fun isDeviceOwner(): Boolean
fun isLockTaskPermitted(packageName: String): Boolean
fun readLockTaskFeatures(): Int
fun readLockTaskPackages(): Set<String>
fun readRuntimeLockTaskState(): Int
fun applyAllowedPackages(packages: Set<String>): PolicyOperationResult
fun applyFeatures(features: Int): PolicyOperationResult
fun captureBaseline(): PolicyBaseline
```

Do not return unconditional success because a setter returned without throwing. Distinguish request submitted, current readback, asynchronous policy result, and runtime state.

Use the actual public method `isLockTaskPermitted()`; do not accidentally implement a nonexistent Android method named `isLockTaskAllowed()`.

Use these initial masks:

```kotlin
val protectedFeatures =
    DevicePolicyManager.LOCK_TASK_FEATURE_SYSTEM_INFO or
    DevicePolicyManager.LOCK_TASK_FEATURE_NOTIFICATIONS or
    DevicePolicyManager.LOCK_TASK_FEATURE_HOME or
    DevicePolicyManager.LOCK_TASK_FEATURE_OVERVIEW or
    DevicePolicyManager.LOCK_TASK_FEATURE_KEYGUARD

val allowedFeatures = protectedFeatures or
    DevicePolicyManager.LOCK_TASK_FEATURE_GLOBAL_ACTIONS
```

Use `allowedFeatures` for armed hours when the menu is permitted. Use `protectedFeatures` only after all protection prerequisites pass. Restore the captured baseline for full disarm; do not confuse `allowedFeatures` with the original device configuration.

## 9. Entering, observing, and leaving a managed session

Entry requirements:

1. The user explicitly arms/resumes from an unlocked foreground activity.
2. Verify Device Owner status using `isDeviceOwnerApp(context.packageName)`.
3. Verify keyguard/unlock state. Do not attempt entry while keyguard is locked.
4. Verify settings are valid and no recovery is pending.
5. Verify exact scheduling capability and successfully prepare the upcoming release events.
6. Capture baseline if none exists for this session.
7. Configure the allowlist and a permissive initial mask with global actions enabled.
8. Verify policy readback and relevant results.
9. Call `startLockTask()` only when this package is permitted.
10. Confirm `ActivityManager.lockTaskModeState == ActivityManager.LOCK_TASK_MODE_LOCKED`. `LOCK_TASK_MODE_PINNED` is failure for this feature.
11. Persist enabled intent and invoke the shared reconciler with the latest configuration. Implement ordering/rollback so a crash after partial entry does not leave an unrecorded restricted session.

If entry fails, rollback and show the actual reason. Never mark the device protected after merely setting feature flags.

### Concrete crash-safe arm transaction

Use this ordering inside the serialized arm operation; this clarifies the persistence/rollback requirement above:

1. Read current settings and prerequisite state. Reject the operation if recovery is unresolved.
2. Read the original policy baseline before modifying it.
3. In a durable settings operation, store the baseline/session journal, set `enabled=false`, set `recoveryRequired=true`, and advance revision. The UI is ARMING. At this stage the journal means “clean up if interrupted,” not “start protection.”
4. Apply the permissive mask and selected allowlist, verifying each result. Prepare future alarms for this revision from the candidate schedule. Internal events are serialized and cannot interrupt this arm transaction.
5. Enter from the eligible foreground activity and verify real runtime LOCKED state. Global actions are still permitted.
6. Durably commit `enabled=true` and `recoveryRequired=false` without changing the revision used by the prepared alarm plan.
7. Reconcile immediately. Restriction can start only now, if the current interval and all prerequisites permit it.
8. Any failure before step 6 invokes recovery. Any startup/event that discovers the interrupted journal invokes recovery rather than completing an abandoned activation automatically.

Do not keep the receiver/coordinator lock held across a long UI operation. The activity-driven arm transaction must remain bounded; if a launch/lifecycle handoff is needed, model its intermediate state and resume only from that same explicit arm request. A background alarm must not complete a new session launch.

### Schedule edits during an armed session

1. Validate candidate times before touching the saved schedule.
2. Serialize with alarm/policy operations, and request a permissive mask before replacing an existing protected plan.
3. Persist the edited times and new revision atomically. Preserve enabled intent only while the operation has a valid managed session and can install its replacement release plan.
4. Install the new plan and reconcile immediately from the new settings. If installation or policy verification fails, disarm/recover.
5. Explain in the UI when an edit starts or ends restriction immediately. Do not wait for the next obsolete alarm.

For allowlist changes, fully disarm first, save the new selection, and require explicit foreground resume. This avoids silently changing tasks beneath an active session.

Observe `DeviceAdminReceiver.onLockTaskModeEntering()` and `onLockTaskModeExiting()`. Refresh runtime state whenever the app returns to foreground and whenever a policy/schedule event is handled. Do not recursively re-arm from an exit callback.

Do not claim to monitor every external state change continuously when the process is not running. Record when runtime state was last checked.

Exit is defined in section 10. Do not assume any newly created activity can call `stopLockTask()` for the original task.

## 10. Recovery implementation — must exist first

Add a prominent **Restore Normal Device Mode** button to the proof-of-concept screen and every production screen where the user can manage protection. It must work outside the protected interval as well as inside it, and must not require network access, a subscription, or the schedule to be valid.

Implement recovery as an idempotent sequence:

1. Serialize recovery against policy changes and alarm handling.
2. Durably set `enabled=false`, increment revision, and set `recoveryRequired=true` before attempting release.
3. Cancel all app-owned start, end, fallback, prior recovery-retry, and temporary-test alarms.
4. If still authorized, request a mask including global actions immediately; do not wait for a logging or export operation.
5. Stop the session through documented task ownership or DPC allowlist removal. For the dedicated MVP, temporarily removing all app-configured lock-task packages is an acceptable exit procedure; verify it on the device.
6. Verify the runtime locked session ended. Retry only in a bounded manner; no infinite policy loops.
7. Restore the saved baseline packages/features and any explicitly tracked app-changed policy. Do not touch unrelated management settings.
8. Re-read current state. Clear `recoveryRequired` only when cleanup is verified. Retain recovery information after a failure.
9. Display success only after verification. On failure, show **Recovery incomplete** with the failing step and the documented development recovery route.
10. A delayed alarm/callback must not re-enable restriction after this sequence. Latest disabled intent has precedence.

If settings storage fails during recovery, still attempt immediate policy release. Report the persistence failure and keep recovery unresolved rather than allowing it to prevent release attempts.

If release fails and Device Owner access remains, submit one separate inexact, release-only retry for at least 20 minutes later, where scheduling is available. Limit automatic attempts to three per recovery incident and retain the manual recovery route afterward. Persist the incident/attempt count where possible. This retry must never arm a session or apply a restrictive mask; it only attempts cleanup for the incident it belongs to. Cancel it after verified recovery. It is a best-effort opportunity, with no delivery deadline guarantee.

If durable disable cannot be written, inhibit new restriction in the current process and report that durable cleanup is unverified. Do not claim a persistence failure has been repaired by changing only an in-memory boolean.

### Development escape route

Use a debug-only manifest override with `android:testOnly="true"`. Verify the merged debug manifest before provisioning. Do not put this in the release manifest.

Document actual commands using the chosen application ID, receiver class, target serial, and user. Illustrative commands:

```text
adb -s <TEST_DEVICE_SERIAL> install -t <DEBUG_APK_PATH>
adb -s <TEST_DEVICE_SERIAL> shell dpm set-device-owner <APPLICATION_ID>/<ADMIN_RECEIVER_CLASS>
adb -s <TEST_DEVICE_SERIAL> shell dpm list-owners
adb -s <TEST_DEVICE_SERIAL> shell dpm remove-active-admin <APPLICATION_ID>/<ADMIN_RECEIVER_CLASS>
```

Replace placeholders in the tested procedure. A receiver in an `.admin` package needs its actual class name, not an assumed root-level receiver.

Test owner removal/recovery before a restrictive test, then re-provision the dedicated test device. Verify task state and power-menu behavior after recovery. Do not assume owner removal alone repairs every prior policy on every OEM.

Keep USB debugging available on the development device; do not introduce USB or debugging restrictions. For an emulator, document a verified clean snapshot/recovery path. A factory reset is a last-resort destructive action requiring explicit authorization, not an automatic recovery step.

## 11. Allowlist and everyday application behavior

1. Initially allowlist only the protection app for the minimal API test.
2. For the usability test, add the device's currently selected default launcher and chosen apps.
3. Resolve launcher/application identities on the actual device. Do not assume Pixel package names apply to Samsung or other manufacturers.
4. Show the package name and app label for user-selected entries.
5. Define appropriate package visibility queries. Do not add `QUERY_ALL_PACKAGES` without a justified requirement and a distribution assessment.
6. Handle missing, disabled, uninstalled, or changed packages explicitly. Do not silently pretend an invalid allowlist is active.
7. Treat Phone, in-call UI, permission dialogs, document pickers, authentication flows, camera handoffs, share flows, and default-app settings as separate compatibility cases.
8. Allowlisting a package is not proof that all workflows involving it work.
9. Do not disable an already configured screen lock to make the tests pass.
10. Never remove the protection app from the allowlist during a normal armed session without a defined recovery route.
11. Allowlist editing while armed must be coordinated. Apply a permissive state or disarm while changing the list; verify it and require a valid re-entry before restriction resumes.

If usability is unacceptable, document the outcome. Do not quietly switch to a scheduled-entry architecture and claim the same guarantee. A separate architecture investigation must demonstrate entry while another app is foreground, screen off, and after boot, including keyguard interaction.

## 12. One shared reconciliation algorithm

Create one `ProtectionCoordinator.reconcile(trigger)` entry point. Both alarm actions, startup, schedule edits, resume, relevant policy callbacks, boot, time changes, and time-zone changes must use it. There must not be separate contradictory start/end policy implementations.

Use a process-wide coordinator and serialization primitive such as a coroutine Mutex. Keep all components in the same process for the MVP. Keep the critical section short. Never hold a lock while waiting indefinitely for a policy callback that itself needs that lock.

The sequence is:

1. Read the latest durable settings; do not use stale Compose state or alarm extras as authority.
2. Read current Device Owner status and current runtime Lock Task state.
3. If recovery is pending, continue recovery and return its state.
4. If disabled, ensure stale work is canceled and app-imposed restriction is released. Never enter Lock Task Mode.
5. Validate the schedule and required package configuration.
6. Check current exact-alarm capability. If unavailable, take the detected-error recovery path. Do not newly restrict.
7. Recompute whether the current instant belongs to a valid protected interval using section 13.
8. Ensure the upcoming end/start/fallback scheduling plan exists for the current revision. If installation fails, recover rather than restrict.
9. If the required runtime session is absent, keep global actions permitted in the configured mask, report waiting/error status, and do not claim protection. Do not launch an activity automatically from an alarm in the baseline implementation.
10. If runtime state is truly locked and the current time is inside the interval, request `protectedFeatures`.
11. Otherwise request `allowedFeatures` while leaving the armed managed session running.
12. Read back effective features and packages. Update status from actual observations and latest callback information.
13. Write a small diagnostic event and ensure future alarms are scheduled. Logging failure must not prevent release.

Each invocation must be idempotent. Running the same invocation twice must not create duplicate alarm identities, additional sessions, or a protection toggle that differs from current settings.

Handle policy results without a feedback loop: a success callback should refresh observations; it must not re-submit unchanged setters repeatedly. A conflict/failure should invalidate the success display and enter bounded recovery. Correlate callback observations to current state; do not let an old success undo a newer disable/error.

Suggested triggers:

```text
USER_ENABLE
USER_DISABLE
USER_EDIT
APP_FOREGROUND
START_ALARM
END_ALARM
RELEASE_FALLBACK
BOOT_LOCKED
BOOT_UNLOCKED
USER_UNLOCKED
TIME_CHANGED
TIMEZONE_CHANGED
APP_UPDATED
EXACT_ACCESS_GRANTED
POLICY_CHANGED
LOCK_TASK_CHANGED
```

## 13. Schedule calculation — exact rules

The schedule repeats every calendar day in the device's **current** `ZoneId.systemDefault()`. It does not remain pinned to the time zone in which it was created. Refresh the zone after a time-zone change; do not cache it forever.

Use `java.time` and injected `Clock`/zone dependencies. Do not add a fixed 24 hours to an epoch timestamp to obtain the next day's local boundary.

For a local start date `D`, construct the interval:

- If start is before end: start on `D`, end on `D`.
- If start is after end: start on `D`, end on `D + 1 calendar day`.
- If start equals end: configuration invalid.

Convert the two local boundaries into instants using explicit time-zone rules:

1. Ordinary local time: use its sole valid offset.
2. Nonexistent local time during a daylight-saving jump: use the first valid instant at/after the gap's end, rather than inventing a nonexistent clock time.
3. Ambiguous start time during a clock overlap: choose the earlier instant.
4. Ambiguous end time during an overlap: choose the later instant.
5. If those rules collapse the interval so `endInstant <= startInstant`, skip that day's interval and record the reason. Do not turn it into all-day protection.
6. Membership is `startInstant <= now && now < endInstant`.

Evaluate relevant intervals anchored on yesterday and today to determine current membership; do not compare only local hour/minute values when an overlap exists.

For future alarms, calculate upcoming valid start/end instants over enough local dates to obtain both strictly future boundaries, initially through the next eight days. Treat inability to find valid future boundaries as an error, not an infinite loop.

Examples under an ordinary offset:

| Schedule | Current time | Desired restriction |
| --- | --- | --- |
| 02:00–05:00 | 01:59 | Off |
| 02:00–05:00 | 02:00 | On |
| 02:00–05:00 | 04:59 | On |
| 02:00–05:00 | 05:00 | Off |
| 23:00–06:00 | 22:59 | Off |
| 23:00–06:00 | 23:00 | On |
| 23:00–06:00 | 00:30 | On |
| 23:00–06:00 | 06:00 | Off |
| Any valid schedule, disabled | Any time | Off |

After a manual clock/time-zone change, reconcile immediately when the corresponding event is delivered and replace future alarms. Clock changes are accepted as schedule changes; preventing the user from changing time is outside the MVP.

Document the DST rules in the README and show the actual next start/end with time zone in diagnostics.

## 14. AlarmManager implementation

Use one-shot `RTC_WAKEUP` alarms with explicit broadcast PendingIntents and `setExactAndAllowWhileIdle()` for the primary boundaries.

Do not use repeating alarms. Do not use an in-process timer or `OnAlarmListener` as the persistent mechanism. Do not use `setAlarmClock()` merely to evade idle throttling for a background policy toggle.

Use four stable app-owned identities; the fourth is used only for failed cleanup:

```text
<applicationId>.action.START_PROTECTION
<applicationId>.action.END_PROTECTION
<applicationId>.action.RELEASE_FALLBACK
<applicationId>.action.RECOVERY_RETRY
```

Give them distinct request codes or stable URI identities, explicit receiver components, and `FLAG_IMMUTABLE`. Use appropriate update/cancel flags. PendingIntent equality does not include extras: do not rely on a revision extra alone to distinguish identities.

Include revision, planned boundary instant, and event kind as diagnostic/staleness metadata. A receiver must always read current settings and time before acting. Obsolete work may trigger safe reconciliation; it must never impose an obsolete requested state.

Handle `RECOVERY_RETRY` through RecoveryManager. Verify its incident identity against unresolved cleanup state, and never interpret it as a request to enable protection. In ordinary disabled state with no cleanup pending, it is a no-op.

Before entering restriction:

1. Calculate the next relevant end and start.
2. Confirm `canScheduleExactAlarms()`.
3. Install the end alarm first, then the next start, and the separate fallback.
4. Catch scheduling exceptions, including `SecurityException`.
5. Persist the scheduling receipt only after the calls succeed. A receipt records what was submitted, not a guarantee that Android will deliver it.
6. If any required primary scheduling step fails, remain permissive or recover; do not activate restriction.

Do not repeatedly replace an unchanged future alarm just because the UI recomposes. Reinstall when revision, computed boundary, boot generation, capability, or installation validity changes.

### Best-effort release fallback

Create a distinct **inexact** `setAndAllowWhileIdle(RTC_WAKEUP, ...)` reconciliation alarm for the scheduled end. This does not require exact-alarm permission and exists as a secondary attempt if primary release work is lost.

Its handler reads the latest configuration. If the current schedule is still protecting a newly configured interval, it must not blindly release based on its old event name. If exact capability has become unavailable, invoke the detected-error recovery policy.

This fallback is not an independent hard guarantee: it can be delayed or prevented by platform/OEM restrictions or force-stop. Never state that it guarantees release at the exact end or within a fixed maximum delay. Record actual delivery and whether it survives the relevant failure on each test device.

Keep fallback identities separate from exact alarms. Do not cancel a useful fallback during an unrelated status refresh. When replacing the plan, avoid unnecessarily leaving an already-restricted session without a submitted release path; use permissive recovery if replacement fails.

### Timing expectations

The configured time expresses the user's desired boundary, not a zero-latency guarantee. Measure actual request time, alarm delivery time, policy application time, and physical behavior separately.

For the standard supported-device acceptance test, the observed primary transition must occur within 60 seconds of the desired boundary. This is a project test threshold, not a guarantee supplied by Android. If it fails, report the measured delay and do not silently widen the threshold.

Android idle quotas and ordering can affect closely spaced events. Use screen-on short tests for development convenience, then use transitions at least 20 minutes apart for controlled idle testing and repeat the real three-hour interval. A short test cannot certify an overnight schedule.

## 15. Exact-alarm capability and loss of capability

1. Evaluate `canScheduleExactAlarms()` rather than inferring availability solely from installation, a permission declaration, or Device Owner status.
2. A managed device may have relevant exemptions; record actual capability and exemption information where observable. Do not pretend permission revocation is possible on an exempt device if the capability remains available.
3. When unavailable and the app is foreground, explain the requirement and provide the documented Alarms & Reminders settings intent for this application.
4. Do not arm from a pending request. Recheck after returning from the system screen.
5. Handle the documented exact-alarm access-granted broadcast by rechecking capability and reconciling current settings.
6. Do not rely on that broadcast to notify immediate revocation. Where revocation applies, the app can be stopped and its future exact alarms canceled before it can perform cleanup.
7. Recheck at foreground entry, every received schedule/system event, and before installing exact alarms.
8. On detected loss, persist disabled intent and run recovery. Show why the system was disarmed.
9. Test the inexact fallback after permission loss where the target device permits that experiment. Report whether the capability truly changed and whether delivery occurred.
10. If exact capability is always granted by an exemption on the supported Device Owner device, mark real revocation testing NOT APPLICABLE with evidence; test coordinator behavior with an injected unavailable capability separately. Do not present that injected test as a physical-device revocation test.

If the product requires a hard maximum release delay even after revocation, process stoppage, or force-stop, explicitly report that this architecture has not established that guarantee. Do not solve it by adding hidden APIs or an unsupported keep-alive process.

## 16. Broadcast execution and concurrency

`onReceive()` must validate the action, obtain `goAsync()` when asynchronous storage is needed, and delegate a short bounded operation to the app's coordinator scope. Finish the pending result in a `finally` block.

Requirements:

1. No untracked fire-and-forget coroutine after returning from `onReceive()`.
2. No waiting for user interaction or an activity launch inside a broadcast.
3. No network, full-log export, unbounded DataStore collection, or long package scan in the critical operation.
4. Use bounded storage reads and reconciliation. Target completion comfortably within the receiver time budget; initially use a five-second operation deadline with explicit timeout handling.
5. `goAsync()` is not unlimited execution time. Handle cancellation and finish promptly.
6. Never put the exact boundary in a WorkManager queue and claim precise execution.
7. Missing intent extras must not crash the receiver. Unknown actions must be ignored/logged safely.
8. A timeout or policy exception invalidates a success status. Attempt bounded release if safe; leave persistent recovery work unresolved for the next legitimate opportunity.
9. A foreground edit and a delivered alarm must serialize through the same coordinator. A newer revision wins.
10. Recovery must not be starved by continuous status refresh or callback delivery.

## 17. Boot, unlock, clock, and application-update handling

Handle these events using registrations appropriate to API 36:

```text
LOCKED_BOOT_COMPLETED
BOOT_COMPLETED
USER_UNLOCKED where appropriate for a running component
TIME_SET / documented ACTION_TIME_CHANGED
TIMEZONE_CHANGED
MY_PACKAGE_REPLACED
Exact-alarm access granted
```

For locked boot:

1. Read only device-protected minimum state.
2. Recompute today's intended interval and reinstall future scheduling when permitted.
3. Resume pending recovery if there is sufficient authorized access.
4. Read runtime state rather than assuming it survived.
5. Do not start a locked session while keyguard is locked.
6. Keep the baseline implementation permissive while the required session is absent; record waiting-for-unlock/session status.

After unlock/ordinary boot:

1. Reconcile again and record actual runtime state.
2. If explicit foreground resume is needed, explain that on the next app visit. If notifications are available, a user-initiated resume notification may be offered.
3. Do not claim unattended post-reboot protection when user action remains required.
4. Do not lose the user's schedule while waiting.

After app replacement, reconcile and reinstall the scheduling plan. Do not assume old alarms, class names, or stored schema are automatically valid. Add migration handling and a test that active state does not silently change during update.

### Optional automatic re-entry investigation

This investigation comes only after the baseline gates pass. Research DPC-specific activity-launch rules and any target-36 PendingIntent opt-ins. Do not assume ordinary background-activity restrictions apply identically to every Device Owner launch, and do not assume an exception guarantees safe locked-device entry.

Possible documented mechanisms may include an explicitly configured home activity or a carefully scoped allowlisted activity. Test the actual chosen route. A custom launcher changes product scope; do not silently replace the user's default launcher.

Test screen off, secure keyguard, another app foreground, process absence, and reboot before first unlock. If an automatic route cannot be demonstrated without violating constraints or impairing unlock, keep the baseline behavior and mark unattended recovery unmet.

## 18. Policy result handling

Implement `PolicyUpdateReceiver.onPolicySetResult()` and `onPolicyChanged()` for the relevant lock-task policy on API 36. Use the documented policy identifier constant and inspect actual result codes; do not infer success from a guessed string.

Record policy identifier, target user, result code, observation time, and current settings revision. Lock-task packages and features must be treated as related policy state.

Behavior:

- Success: read effective state and refresh status.
- Conflict/failure: show error, invalidate “restricted” status, and invoke bounded recovery where authorized.
- State changed externally: reconcile current intent against effective state.
- Late result: do not override a newer disable/recovery request.

Readback alone does not prove the physical menu is absent. Manual device evidence establishes that behavior. Missing callbacks are not grounds to hang receivers; expose pending/unknown policy confirmation and record what was observed.

If reliable continued callback delivery requires an additional documented admin service, research and justify it separately. Do not add a general foreground service as a substitute for understanding the policy callback lifecycle.

## 19. User interface requirements

The proof-of-concept screen comes first. The final Compose interface comes after the scheduler gates.

### Main screen

Show:

1. Device Owner: Ready / Required / Lost.
2. User preference: Enabled / Disabled.
3. Managed session: Active / Not active / Waiting for unlock.
4. Power menu: Restricted / Allowed / Not verified.
5. Schedule in an unambiguous 24-hour form; optional localized 12-hour display may be additional.
6. Device time zone and next planned transition.
7. Scheduling capability and last verification time.
8. Any error with a concrete corrective action.

Controls:

- Enable / resume managed session.
- Disable and restore normal device mode.
- Change start/end.
- Manage allowed applications.
- Diagnostics.
- Development test controls in debug builds only.

First-time enable must display the practical limitations, including unavailable Quick Settings and possible application workflow restrictions. Make the explicit enable button part of that disclosure. Do not repeatedly require confirmation for harmless edits, but ensure an edit that changes current protection is clearly reflected immediately.

The UI may display **Power menu restricted** only when all are true:

1. Enabled intent is current and valid.
2. Current instant belongs to a protected interval.
3. Device Owner access is present.
4. Runtime state is `LOCK_TASK_MODE_LOCKED`.
5. Effective policy matches the required mask/list sufficiently to implement the feature.
6. The required release plan has been submitted successfully for the current settings.
7. No unresolved policy/recovery failure exists.

If the physical behavior has never been validated on this device/build, add “Device behavior not yet validated” in setup/diagnostics; do not disguise API observations as a physical test result.

### Setup screen

Check Android API, Device Owner, admin receiver identity, default launcher, allowlist permission, runtime state, exact scheduling capability, relevant exemptions, recovery readiness, and supported-device test status.

If Device Owner is absent, show:

> Device Owner provisioning is required. Installing the APK or activating ordinary Device Administrator access does not enable this feature.

### Diagnostics screen

Show settings revision, requested/effective masks, package list, runtime state, boot/unlock state, next submitted alarm times, last actual deliveries/delays, policy results, and recovery status. Provide a local export via a standard user-selected destination/share flow; no automatic upload.

### Temporary test mode

Keep short-interval test mode in debug builds. It must have explicit activation and a submitted end/recovery path before restriction begins. Disable invalidates all temporary test events. Do not leave temporary overrides active after an app restart, update, or boot; recover them and require explicit reactivation.

## 20. Privacy, logging, and security

Use bounded local structured diagnostics. Record operational state, event kind, settings revision, intended/actual transition timestamps, policy result, runtime state, and exception class/message with sensitive data removed.

Do not record contacts, notifications' contents, messages, browsing content, location, photos, credentials, or activity contents. Application labels/package selections are configuration; do not turn them into a usage history.

Limit retained events, initially 1,000 records or 1 MiB, and provide clear/export controls. Export is deliberate and local. Failure to write a log must not block power-menu release.

Threat model: the feature intentionally controls a software menu on a voluntarily managed device. It does not prevent hardware reset, recovery-mode actions, battery depletion, all privileged system reboots, OS updates, or every OEM shutdown path. Manual user recovery is always available by design, so do not claim tamper resistance.

Inspect exported components and PendingIntent identities. Another installed app must not be able to send an arbitrary intent that arms protection or rewrites the allowlist. Do not add a hidden remote activation or backdoor.

## 21. Gate A — environment, provisioning, and recovery

Implement only the project skeleton, admin receiver, ownership checks, diagnostics, baseline capture, and recovery controls needed for this gate.

Steps:

1. Build and install the debug test-only APK on the dedicated test device/emulator.
2. Verify the app correctly reports missing ownership before provisioning.
3. Provision the actual receiver as Device Owner using the tested command.
4. Verify ownership through app state and development diagnostics.
5. Demonstrate admin removal/escape route without restrictive policy.
6. Re-provision if required for the next test.
7. Verify the recovery button is visible and cannot be hidden behind a valid schedule requirement.

PASS requires actual provisioning and a verified escape route. A build alone is insufficient. Record the APK build ID, device model, OS/API, build fingerprint, and the exact working commands.

## 22. Gate B — smallest power-menu proof of concept

Do not implement the full schedule interface yet.

Provide debug controls:

```text
Start managed session
Allow power menu
Restrict power menu
Restore Normal Device Mode
```

Run these steps on an unlocked device:

1. Capture policy baseline and allowlist the app.
2. Apply a mask permitting global actions; enter real Lock Task Mode.
3. Verify runtime state is LOCKED, not PINNED.
4. Open the menu using the actual device gesture that normally invokes global actions. Some devices use a power/volume combination or have long-press assigned to an assistant. Record the configured gesture.
5. Confirm the menu is present under the permissive mask.
6. Before applying the restrictive mask, verify exact capability and submit a minimal temporary release broadcast for five minutes later, plus a distinct inexact fallback. The release handler invokes RecoveryManager; it never enables protection. Persist a temporary-test marker so later startup can clean up an interrupted test. This small safety timer is implemented for Gate B; the recurring daily scheduler and its UI remain deferred to Gate D. If the release calls fail, do not restrict. Then apply the restrictive mask.
7. Repeat the same menu-invocation gesture on home/app and keyguard surfaces as practical. Confirm the normal global actions dialog does not appear.
8. Restore the permissive mask and confirm the menu returns.
9. Run full recovery, confirm the managed session ends, and confirm baseline behavior returns.
10. Repeat the toggle/recovery cycle at least three times without accumulating broken state.

The POC is an explicit temporary debug session, distinct from the persistent daily enabled preference. Disable/restore cancels its release timer after cleanup is verified. On reboot, app restart, or update, a temporary-test marker triggers recovery, not a resumed test. In the POC screen show the requested/effective mask and actual locked state; do not reuse a production “daily schedule enabled” success label for manual testing.

Do not select Power Off during ordinary toggle testing. Hardware forced restart checks are separate, controlled tests on the dedicated device after the recovery route is ready.

PASS requires real observed suppression/restoration and recovery. Emulator evidence is preliminary. For the intended physical phone, mark this gate BLOCKED until its physical test is done.

If the dialog remains available, document which UI/path appears and stop the gated application build. Do not compensate by intercepting keys or hiding System UI through unsupported mechanisms.

## 23. Gate C — usability and product fit

Add the default launcher and selected applications, enable the intended feature mask, and keep the managed session active during both menu-allowed and menu-restricted states.

Create a matrix with columns: device/build, function, menu-allowed result, menu-restricted result, steps/evidence, limitation, severity.

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
Rotation, split-screen behavior, and system gestures where relevant
Reachability of recovery controls
```

Quick Settings being unavailable is the documented expected limitation. Test and report it; it is not a defect to fix through prohibited techniques.

Gate result must state whether the demonstrated restrictions are acceptable for a dedicated-device MVP. If the requested product requires an ordinary unrestricted personal-phone experience, report that requirement unmet. Do not spend later phases polishing an architecture that already fails an essential use-case requirement.

## 24. Gate D — scheduling, state integrity, and idle behavior

Only after Gates A–C pass for the intended MVP scope, implement the persistent settings, pure calculator, AlarmManager plan, shared reconciler, and minimal schedule controls.

First run a screen-on convenience test beginning a few minutes ahead and ending several minutes later. Then run controlled screen-off/idle tests with events at least 20 minutes apart. Finally run the actual 02:00–05:00 interval or an equivalent three-hour interval.

For each transition collect desired boundary, submitted alarm, delivery, policy readback, runtime state, and manually observed menu behavior. Verify the 60-second observed transition threshold and record any missed/delayed event.

Cases:

1. Screen on and app foreground.
2. Another allowlisted app foreground.
3. Screen off and locked.
4. Controlled Doze.
5. Charging and not charging.
6. Protection app removed from Recents.
7. App process killed without force-stop, using an explicitly documented test method.
8. Force-stop separately, if possible on the managed device; do not confuse it with ordinary process death.
9. Editing times before an old alarm arrives.
10. Disabling before an old alarm arrives.
11. Duplicate and deliberately delayed alarm delivery in controlled tests.
12. Time changed forward beyond the end.
13. Time changed backward into the interval.
14. Time zone changed while currently protected.
15. Overnight interval crossing midnight.
16. Scheduling API failure injected before arming and while replacing a plan.
17. Settings/logging failure injected during release.
18. Exact capability unavailable/lost, including real loss only if applicable.
19. Fallback delivery behavior following a missing primary release.

Gate PASS requires enabled and disabled behavior, stale-event safety, ordinary process-death recovery, and measured idle transitions on the supported configuration. Force-stop/permission-loss limitations must be documented even if they cannot meet the normal timing target.

## 25. Gate E — boot and update recovery

Test separately:

1. Reboot outside the protected interval, before unlock.
2. Reboot inside the protected interval, before unlock.
3. First unlock with the protection app unopened.
4. Open app and explicitly resume after unlock.
5. Reboot across a start boundary.
6. Reboot across an end boundary.
7. Reboot while recoveryRequired is set.
8. Application update while enabled.
9. Temporary debug test active at reboot/update.

For each, report desired state, observed runtime lock state, effective policy, submitted alarms, physical menu behavior, whether user action was required, and delay.

Baseline PASS means: saved schedule is usable, alarms are restored, inactive runtime protection is reported honestly, no stale restrictive state is mistaken for protection, recovery remains possible, and explicit resume works.

**Unattended reboot recovery is a distinct acceptance item.** Mark it PASS only if automatic correct protection is observed without opening the app/resuming. Pre-first-unlock protection is another distinct item. Do not mark either PASS because Direct Boot ran or feature flags were restored.

If these stronger items fail or remain untested, the final report must state that the original unattended-recovery objective is incomplete. The baseline application can still be delivered as a limited prototype.

## 26. Unit and integration tests

Write meaningful tests that verify schedule/state behavior independently from implementation details. Use injected time and dependencies; do not wait hours in unit tests.

Required calculator tests:

- Same-day and overnight membership.
- Exact start included and exact end excluded.
- Disabled schedule.
- Equal times rejected and out-of-range minutes rejected.
- Upcoming boundaries strictly after now.
- Month/year rollover and leap day.
- Zone changed with the same current instant.
- DST gap and overlap under the explicit rules.
- A window collapsed by a gap.
- No fixed 24-hour assumption across a DST transition.

Required coordinator tests:

- Missing ownership never arms/restricts.
- PINNED is not treated as LOCKED.
- Missing exact capability prevents a new restriction.
- End alarm scheduling failure prevents restriction.
- Old start/end/fallback events use current settings.
- Disabled latest revision wins over queued callbacks.
- Failed recovery remains durable and is retried later.
- Logging/storage failures do not suppress best-effort release.
- Concurrent edit/alarm and recovery/alarm ordering.
- Success callback does not create an endless setter loop.
- No protection claim when runtime state is absent after boot.
- Interrupted arm operation has a safe recovery route.

Add Android integration tests where an instrumented managed test device can verify public API state. Distinguish them from manual physical menu and call-workflow observations.

Run unit tests, lint, and debug build with the repository's Gradle wrapper. Fix failures introduced by the work. Repeat broad checks only after changes or failures justify it. Report exact commands and results in development documentation, not just “tests passed.”

## 27. Final acceptance matrix

Every item must have evidence or an explicit unresolved status:

| Requirement | Classification |
| --- | --- |
| Android 16 build/install | Required baseline |
| Correct Device Owner detection and setup | Required baseline |
| Verified development and in-app recovery | Required baseline |
| Real Lock Task Mode, not screen pinning | Required baseline |
| Power-menu restriction/restoration on intended physical device | Required baseline |
| Known managed-mode limitations displayed | Required baseline |
| Schedule correctness including midnight/DST | Required baseline |
| Observed normal/idle transitions within test threshold | Required baseline |
| Ordinary process-death scheduling behavior | Required baseline |
| Stale-event and failed-policy handling | Required baseline |
| Honest before-unlock/after-reboot status | Required baseline |
| Explicit post-unlock resume | Required baseline |
| Automatic unattended post-reboot re-entry | Additional original objective; must be separately proven |
| Protection before the first unlock | Additional stronger objective; must be separately proven |
| Hard bounded release after force-stop/revocation | Not established by baseline; do not claim |
| Unrestricted personal-phone experience | Not promised by Lock Task architecture |
| Samsung/other OEM compatibility | Only for each actually tested model/build |
| Google Play availability/enrollment feasibility | Research deliverable; not a release guarantee |

The final result must use one of these labels:

- **Verified baseline MVP**: all required baseline items passed on the named device/build, with stronger limitations disclosed.
- **Prototype with blocked verification**: implementation exists, but specified physical/environment tests are unavailable.
- **Feasibility failed for requested use case**: a central gate failed or required usability is incompatible.

Do not call the entire original objective complete if unattended reboot recovery remains unmet. Do not claim that one Pixel result establishes all Android 16 devices.

## 28. Required deliverables and final response from the implementing agent

Deliver:

1. Source project with pinned build dependencies and Gradle wrapper.
2. Debug test-only APK if a build is available, with build identity and checksum.
3. README containing setup, feature behavior, limitations, commands, and run/test instructions.
4. ARCHITECTURE documenting state model, always-active session choice, storage, event handling, alarm identities, rollback, and policy verification.
5. RECOVERY with verified procedures and their device/build scope.
6. TEST_PLAN with runnable/manual steps.
7. TEST_RESULTS with evidence, timestamps/delays, failures, blocked items, and gate outcomes.
8. COMPATIBILITY_MATRIX separating every tested device and workflow.
9. PRODUCTION_FEASIBILITY covering Device Owner/managed enrollment, setup/reset implications, supported deployment audience, current Play policy constraints, package visibility if applicable, and why APK installation is insufficient.

Production research must use current primary Android/Google documentation. Do not assume QR enrollment for a custom DPC is automatically available for every distribution model. Do not assume a work profile provides the same device-wide behavior as the supported Device Owner deployment.

The implementing agent's final response must include:

```text
Result classification:
Completed gates:
Blocked/failed gates:
Device models and Android build fingerprints actually tested:
Power-menu behavior actually observed:
Daily-use restrictions:
Reboot behavior and user action required:
Scheduling delays and failure behavior:
Recovery procedure verified:
Build/test results:
Deliverable locations:
Remaining requirements before broader release:
```

If a gate cannot pass, explain the concrete blocker and give exact next test instructions. Do not fill the final response with hypothetical compatibility or unsupported promises.

## 29. Official references and verification tasks

Read these sources before using the corresponding APIs; follow their current public signatures and registration requirements. The project-specific algorithms, failure policy, tests, and thresholds above are implementation requirements, not guarantees made by these sources.

- [DevicePolicyManager reference](https://developer.android.com/reference/android/app/admin/DevicePolicyManager): feature masks, package policies, current policy-result behavior, and permissions.
- [Lock Task Mode guide](https://developer.android.com/work/dpc/dedicated-devices/lock-task-mode): real locked tasks, activity entry/exit, launcher interaction, and keyguard precautions.
- [AlarmManager reference](https://developer.android.com/reference/android/app/AlarmManager): exact capability, alarm identity/lifecycle, idle dispatch and ordering.
- [Schedule alarms guide](https://developer.android.com/develop/background-work/services/alarms): exact-alarm access and the lifecycle of access changes.
- [Doze and App Standby](https://developer.android.com/training/monitoring-device-state/doze-standby): power-management effects and testing.
- [Direct Boot](https://developer.android.com/privacy-and-security/direct-boot): pre-unlock component/storage design.
- [PolicyUpdateReceiver reference](https://developer.android.com/reference/android/app/admin/PolicyUpdateReceiver): protected result receiver and callbacks.
- [BroadcastReceiver reference](https://developer.android.com/reference/android/content/BroadcastReceiver): bounded asynchronous handling.
- [ADB documentation](https://developer.android.com/tools/adb): development provisioning and test-only admin removal.
- [Activity security and background launches](https://developer.android.com/guide/components/activities/secure-bal): current launch rules for any separately investigated auto-re-entry route.
- [Dedicated devices cookbook](https://developer.android.com/work/dpc/dedicated-devices/cookbook): optional managed home/launcher approaches; implementing a launcher is not automatically authorized by this brief.

If documentation differs from observed Android 16 behavior, preserve the observation, describe the exact model/build and API result, and reassess the gate. Do not bypass Android controls.

## 30. Execution checklist — use this order

```text
01 Read this entire brief and repository instructions.
02 Inventory tooling and test surfaces; write ENVIRONMENT.
03 Create API 36 skeleton and minimal admin/status screen.
04 Implement baseline capture and recovery first.
05 Verify provisioning and development escape route: Gate A.
06 Implement only manual feature toggle and real Lock Task entry.
07 Observe physical suppression/restoration/recovery: Gate B.
08 Expand allowlist and measure daily workflows: Gate C.
09 If the intended use case fails, report it before further gated build.
10 Implement pure schedule calculator and its unit tests.
11 Implement durable settings and serialized coordinator.
12 Implement primary/fallback alarms and failure handling.
13 Verify screen-on, idle, stale events, edits and process death: Gate D.
14 Implement Direct Boot, unlock, time changes and update reconciliation.
15 Verify baseline boot recovery and separate unattended claims: Gate E.
16 Build final Compose screens, disclosures and diagnostics.
17 Run final build/lint/tests and repeat affected device cases.
18 Complete compatibility, recovery and production-feasibility documents.
19 Deliver an honest result classification with remaining requirements.
```

Do not skip a gate because the code compiles. Do not implement the whole app first and discover the central device behavior afterward. Do not convert a blocked test into an assumed pass.
