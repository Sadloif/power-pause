# ARCHITECTURE — Scheduled Power Menu Restriction

Product name: **Scheduled Power Menu Restriction**. Application ID:
`com.example.shutdownprotection` (overridable with `-Pspm.appId=…`).

This document covers what brief section 28.4 requires: the state model, the always-active
session choice, storage, event handling, alarm identities, rollback, and policy verification —
plus the full permission and exported-component inventory required by brief section 6.

---

## 1. Component map

Everything is in one `app` module and one process (brief section 12: "Keep all components in
the same process for the MVP").

```
admin/
  DevicePolicyGateway.kt            Seam over DevicePolicyManager + ActivityManager, masks,
                                    PolicyReadback, PolicyOperationResult, runtime states
  DevicePolicyController.kt         Every documented policy operation and read
  ShutdownProtectionAdminReceiver.kt DeviceAdminReceiver; lock-task enter/exit observation
  LockTaskPolicyUpdateReceiver.kt   PolicyUpdateReceiver; overrides the callbacks, not onReceive
data/
  ProtectionSettings.kt             Durable user intent + validation + 24-hour formatting
  SettingsRepository.kt             Store interface, read/write results, receipt/marker/incident
  DataStoreSettingsRepository.kt    Device-protected implementation, keys, corruption handling
  PolicyBaseline.kt                 The pre-mutation lock-task configuration
  DiagnosticsRepository.kt          Bounded event log + last runtime observation
protection/
  ProtectionState.kt                Runtime states, triggers, UI mapping, claim gate, POC override
  ProtectionSeams.kt                Session/entry, environment, and restriction-inhibitor seams
  ProtectionCoordinator.kt          The single reconcile() entry point + the arm transaction
  RecoveryManager.kt                The idempotent recovery sequence
scheduling/
  ScheduleCalculator.kt             Pure Kotlin schedule arithmetic with injected clock and zone
  ScheduleManager.kt                SchedulingGateway + the AlarmManager implementation
  AlarmActions.kt                   The four alarm identities and their request codes
  ProtectionAlarmReceiver.kt        Boundary and retry deliveries
  SystemEventReceiver.kt            Boot, unlock, clock, time-zone, update, exact-alarm access
ui/
  MainActivity.kt                   The one activity; the eligible foreground entry surface
  MainViewModel.kt                  The only bridge from Compose to the policy layer
  MainScreen.kt / SetupScreen.kt / ScheduleScreen.kt / AllowedAppsScreen.kt /
  DiagnosticsScreen.kt / PocScreen.kt
ShutdownProtectionApplication.kt    Container construction + dynamic USER_UNLOCKED registration
AppContainer.kt                     Process-wide wiring; one DataStore instance per file
```

**Compose never touches `DevicePolicyManager`.** Screens render `UiState` and invoke lambdas;
the ViewModel calls the coordinator; the coordinator uses policy, scheduling, settings, and
diagnostics components. No screen imports a repository or an Android policy class.

## 2. State model: intent is not observation

The brief's central distinction (sections 7.5 and 7.11) is enforced by two separate types:

- **`ProtectionSettings`** — durable *intent*: `enabled`, times, `revision`, `allowedPackages`,
  `recoveryRequired`. `enabled` is a preference, never proof that protection is operating.
- **`RuntimeObservation`** — the last *observed* Android state, stored in the diagnostics
  store with the time it was observed. A stored "Protected" value from yesterday is not
  today's runtime state.

`ProtectionStatus` is the per-pass result and carries both: the derived state plus the
observed `deviceOwner`, `lockTaskState`, `effectiveFeatures`, `effectivePackages`,
`exactAlarmCapability`, and `releasePlanSubmittedForCurrentRevision`.

### Internal runtime states (brief section 7)

`NOT_DEVICE_OWNER`, `DISARMED`, `ARMING`, `ARMED_POWER_MENU_ALLOWED`,
`ARMED_POWER_MENU_RESTRICTED`, `WAITING_FOR_UNLOCK`, `WAITING_FOR_SESSION`,
`EXACT_SCHEDULING_UNAVAILABLE`, `POLICY_PENDING`, `CONFIGURATION_ERROR`, `RECOVERING`,
`RECOVERY_FAILED`.

### Screen wording

| Screen row | Internal source |
|---|---|
| Device Owner: Ready / Required / **Lost** | `deviceOwner` plus the durable `ownerEverEstablished` flag, so "Lost" means access went away rather than never having existed |
| User preference: Enabled / Disabled | `settings.enabled` |
| Managed session: Active / Not active / Waiting for unlock | `lockTaskState == LOCK_TASK_MODE_LOCKED`, never `PINNED` |
| Power menu: Restricted / Allowed / Not verified | the claim gate below |

### The seven-condition claim gate

`PowerMenuClaimInputs.canClaimRestricted()` requires **all** of:

1. enabled intent is current and valid
2. the current instant is inside a protected interval
3. Device Owner access is present
4. runtime state is `LOCK_TASK_MODE_LOCKED` (pinning is failure)
5. effective policy matches the required mask
6. the release plan was submitted successfully for the current revision
7. no unresolved policy or recovery failure exists

The UI shows **Power menu restricted** only when this returns true. `POLICY_PENDING` (policy
submitted, readback not yet confirming) deliberately shows *Not verified*, and
`ARMED_POWER_MENU_ALLOWED` shows *Allowed* with the exact brief wording
"Power menu allowed — managed session active" — never "normal device mode".

## 3. Operating model: the session stays active while armed

The brief's MVP model (section 3.3) is kept: the Lock Task session is active throughout an
armed session, and schedule boundaries change only whether global actions are permitted.

| Situation | Behaviour |
|---|---|
| Disarmed | App restriction removed, managed session exited, captured baseline restored |
| Armed, outside protected hours | Global actions permitted; managed-session restrictions still apply |
| Armed, inside protected hours | Global actions restricted, once every prerequisite is verified |

Two masks are used, built from the platform constants and pinned numerically against platform
36 in `docs/API_FACTS.md`:

```kotlin
protectedFeatures = SYSTEM_INFO | NOTIFICATIONS | HOME | OVERVIEW | KEYGUARD        // 47
allowedFeatures   = protectedFeatures | GLOBAL_ACTIONS                             // 63
```

`LOCK_TASK_FEATURE_BLOCK_ACTIVITY_START_IN_TASK` (64) is **never** set. `allowedFeatures` is
the permissive *app* mask, never a stand-in for the device's original configuration — full
disarm restores the captured baseline instead.

**Known limitation, recorded rather than papered over:** Quick Settings is expected to remain
unavailable under the documented notifications feature. This is not restored by adding
unrelated flags, and the enable disclosure says so.

## 4. Storage

Two DataStore Preferences files, exactly one instance each (brief section 7.7):

| File | Storage area | Contents |
|---|---|---|
| `spm_protection_settings` | **device-protected** (`createDeviceProtectedStorageContext`) | intent, revision, allowlist, `recoveryRequired`, policy baseline, scheduling receipt, temporary-test marker, recovery incident, boot generation, corruption flag |
| `spm_diagnostics` | **credential-protected** | bounded event log and the last runtime observation |

**Why device-protected for settings:** Direct Boot reconciliation must read the interval, the
baseline, and the recovery journal before the user unlocks (brief section 7.8). The file holds
configuration only — no personal content — and is deliberately not the diagnostic store, so
diagnostics stay behind credential encryption. A pre-unlock read of the diagnostics store
fails safely because diagnostics are never required to make a correct decision.

**Corruption is a recovery condition, not a silent reset** (brief section 7.10). The
corruption handler replaces an unreadable file with a valid store holding only a
`corrupt_detected` flag, so every setting reads as its **disabled** default while
`readSettings()` still reports `SettingsReadResult.Corrupt`. The coordinator then takes the
recovery path, releases app-imposed restriction, and surfaces an error. The flag is cleared
only after recovery is verified. A fresh enabled schedule is never created silently.

**Backup is disabled** (brief section 6.12): `android:allowBackup="false"`,
`android:fullBackupContent="false"`, and `res/xml/data_extraction_rules.xml` excludes every
domain from both cloud backup and device-to-device transfer, so a restore onto another device
cannot silently activate protection or carry over a stale baseline.

**Revision** advances on enabling, disabling, changing times, changing the allowlist, or
invalidating scheduled work. A newer revision always wins; every public entry point re-reads
the latest durable settings rather than trusting Compose state or alarm extras.

## 5. Event handling

`ProtectionCoordinator.reconcile(trigger)` is the **only** entry point for policy decisions
(brief section 12). Both alarm actions, startup, schedule edits, resume, policy callbacks,
boot, time changes, and time-zone changes go through it. There are no separate start/end
implementations that could disagree.

Serialization is one process-wide coroutine `Mutex` (FIFO, so recovery cannot be starved by
status refreshes). The critical section is kept short, and nothing waits indefinitely for a
policy callback that would itself need the lock.

| Trigger | Where it comes from |
|---|---|
| `USER_ENABLE`, `USER_ARM_REQUEST`, `USER_RESUME_REQUEST` | Main screen / POC screen |
| `USER_DISABLE` | Restore Normal Device Mode |
| `USER_EDIT` | Schedule and allowlist edits |
| `APP_FOREGROUND` | `MainActivity.onResume()` |
| `START_ALARM`, `END_ALARM`, `RELEASE_FALLBACK` | `ProtectionAlarmReceiver` |
| `RECOVERY_RETRY_ALARM` | `ProtectionAlarmReceiver` → `RecoveryManager` |
| `BOOT_LOCKED`, `BOOT_UNLOCKED` | `SystemEventReceiver` |
| `USER_UNLOCKED` | dynamically registered receiver in the Application |
| `TIME_CHANGED`, `TIMEZONE_CHANGED` | `SystemEventReceiver` |
| `APP_UPDATED` | `SystemEventReceiver` |
| `EXACT_ACCESS_GRANTED` | `SystemEventReceiver` |
| `POLICY_CHANGED` | `LockTaskPolicyUpdateReceiver` |
| `LOCK_TASK_CHANGED` | `ShutdownProtectionAdminReceiver.onLockTaskModeEntering` |
| `POC_*` | debug-only POC screen |

### Broadcast discipline (brief section 16)

`onReceive()` validates the action, takes `goAsync()` because asynchronous storage is needed,
delegates to the app's coordinator scope, and finishes the pending result in a `finally`
block. There is no untracked fire-and-forget coroutine after `onReceive()` returns, no waiting
for user interaction, and no network or long package scan. Missing extras cannot crash a
receiver, and unknown actions are logged and ignored. Reconciliation is bounded by a
**5-second operation deadline** with explicit timeout handling; the arm transaction, which
additionally waits for the runtime session to report `LOCKED`, uses a 12-second bound. A
timeout invalidates a success status — it is reported as `POLICY_PENDING`, never as protected.

`onLockTaskModeExiting` calls `refreshObservation()`, which writes no policy at all. That is
what makes recursive re-arming from an exit callback impossible.

## 6. Alarm identities and lifecycle

One-shot `RTC_WAKEUP` alarms only. No repeating alarms, no in-process timer, no
`setAlarmClock()`. Four stable app-owned identities, each derived from the actual application
ID:

| Identity | Request code | Kind |
|---|---|---|
| `<applicationId>.action.START_PROTECTION` | 1001 | exact `setExactAndAllowWhileIdle` |
| `<applicationId>.action.END_PROTECTION` | 1002 | exact `setExactAndAllowWhileIdle` |
| `<applicationId>.action.RELEASE_FALLBACK` | 1003 | **inexact** `setAndAllowWhileIdle`, 60 s after the scheduled end |
| `<applicationId>.action.RECOVERY_RETRY` | 1004 | **inexact** `setAndAllowWhileIdle`, ≥ 20 min after a failed release |

PendingIntents are explicit-component, `FLAG_IMMUTABLE`, and distinguished by action plus
request code — never by a revision extra, because PendingIntent equality ignores extras.
Revision, planned boundary, and event kind travel as diagnostic/staleness metadata only; a
receiver always re-reads current settings and the current time before acting, so an obsolete
alarm can trigger a safe reconciliation but can never impose an obsolete requested state.

Installation order is deliberate: **end first, then the next start, then the fallback**, so an
already-restricted session never loses its submitted release path. Any failure leaves the
device permissive or triggers recovery; restriction is not activated. The receipt is persisted
only after the calls succeed, and records what was *submitted* — not a promise of delivery.
Alarms are reinstalled when revision, computed boundary, boot generation, capability, or
installation validity changes — not merely because the UI recomposed.

`RECOVERY_RETRY` is routed through `RecoveryManager`, which verifies the incident identity
against unresolved cleanup state, caps automatic attempts at three, and never treats the alarm
as a request to enable protection. In ordinary disabled state with no cleanup pending it is a
no-op.

## 7. The crash-safe arm transaction

`ProtectionCoordinator.arm()` runs inside the same mutex as reconciliation and follows the
brief's ordering exactly:

1. Read settings and prerequisite state; reject if recovery is unresolved, if restriction is
   inhibited in this process, if there is no Device Owner, if keyguard is locked, if the
   schedule is invalid, if this app is missing from the allowlist, if no eligible foreground
   activity is attached, or if exact alarms are unavailable.
2. Read the original policy baseline **before** modifying anything.
3. Durably write the journal: `enabled=false`, `recoveryRequired=true`, revision advanced. At
   this point the journal means "clean up if interrupted", not "start protection".
4. Apply the permissive mask and the allowlist, verifying each result; then install the alarm
   plan for that revision.
5. Enter from the eligible foreground activity and verify real `LOCK_TASK_MODE_LOCKED`.
   `LOCK_TASK_MODE_PINNED` fails entry.
6. Durably commit `enabled=true`, `recoveryRequired=false` — **without** changing the revision
   the alarm plan was built from.
7. Reconcile immediately. Restriction can start only now.
8. Any failure before step 6 invokes recovery. A startup or event that discovers an
   interrupted journal invokes recovery rather than completing the abandoned activation.

`Activity.startLockTask()` is an activity API and must run on the main thread.
`ActivityLockTaskSessionController` therefore **refuses** the request when it is not on the
main thread instead of silently marshalling across threads, which would make the "did it
actually start?" verification meaningless. `arm()` is only ever invoked from the ViewModel,
whose scope is the main dispatcher.

### Schedule edits while armed

Validated before the saved schedule is touched; serialized with alarm and policy operations; a
permissive mask is requested before an existing protected plan is replaced; the new times and
revision are persisted atomically; the replacement plan is installed and reconciliation runs
immediately from the new settings. If installation or policy verification fails, the device
disarms or recovers. The UI reports whether the edit started or ended restriction immediately.

### Allowlist edits

Fully disarm first, save the new selection, then require an explicit foreground resume. This is
why tasks are never silently changed beneath an active session (brief section 11.11).

## 8. Policy verification

Brief section 8 forbids reporting unconditional success because a setter returned without
throwing. Four things are kept distinct:

- **request submitted** — `PolicyOperationResult.submitted`
- **current readback** — `PolicyOperationResult.readback` (features, packages, runtime state)
- **asynchronous policy result** — arrives later through `PolicyUpdateReceiver`
- **runtime state** — `PolicyReadback.runtimeLockTaskState`

`Applied(readback, verified)` reports `verified = readback.features == requested` (or packages
for the allowlist), so a setter that returns but does not take effect is `verified = false`.
`Rejected` covers a thrown exception, including `SecurityException` from
`setLockTaskPackages`; `NotAuthorized` means nothing was attempted. Read failures degrade to
`null` ("unknown") rather than to a value that could look verified.

Policy callbacks: `PolicyUpdateReceiver.onReceive` is `final` on platform 36, so the two
callbacks are overridden. All five parameters of both are `@NonNull` in the platform stub.
Success **refreshes observations and writes no policy**, so a success callback cannot create an
endless setter loop.

**How the feedback loop is actually prevented.** The coordinator remembers the last submission as
`(revision, mask, verified)`. If the same revision and mask have already been submitted, the next
pass **reads back instead of writing again** — that alone removes the submit → callback → submit
cycle. If that readback still disagrees with the requested mask, the situation is a policy
conflict, not something to retry: the coordinator invalidates the success display and takes the
bounded recovery path (`POLICY_READBACK_MISMATCH`). Conflict, storage-limit,
hardware-limitation, and unknown callback results likewise reconcile against current intent,
which then either confirms the state or enters the same bounded recovery. A late result cannot
undo a newer disable, because every pass re-reads the latest revision. The policy identifier is
handled opaquely: `DevicePolicyIdentifiers.LOCK_TASK_POLICY = "lockTask"` exists on platform 36,
but nothing observable locally proves that string is what the callbacks deliver, so it is
recorded and never used as an authority. The identifier, result code, observation time, and the
revision in force are all recorded, and the diagnostics screen reports the real values.

**Readback never proves the physical menu is absent.** Only manual device evidence does, which
is why the app reports "Device behavior not yet validated".

That notice is driven by `AppContainer.deviceBehaviorValidated`, which is deliberately **inert**:
no runtime code path sets it. Clearing it requires a deliberate code change made alongside a
recorded physical observation in `docs/TEST_RESULTS.md`, so that "validated" can never be
switched on by accident, by a build, or by a passing test. The flag is documented here rather
than exposed as a toggle precisely because a toggle would be the wrong shape for an honesty
claim.

## 9. Recovery and rollback

**Scope of recovery, stated plainly (repair R10):** the in-app **Restore Normal Device Mode** ends
the app's managed Lock Task session, restores the saved original policy, and disables the app's
intent. It does **not** remove Device Owner management — the app remains the Device Owner
afterwards. Removing management is a separate, deliberate step, and in this project it is a
debug/test-only escape route that depends on the packaged `android:testOnly` flag and on working
authorized ADB. See `docs/RECOVERY.md` §1 for the four-action model.

`RecoveryManager.recover()` is the idempotent sequence described in `docs/RECOVERY.md`:
inhibit new restriction first → durably disable and flag → cancel every app-owned alarm → request a
mask including global actions and **verify it by readback** → stop the session via documented task
ownership and DPC allowlist removal → verify the runtime session ended (unknown is not `NONE`, and
`PINNED` is not a verified normal state) → restore the captured baseline and **read back both** the
mask and the package list against it → commit the required cleanup fields in one atomic
transaction → clear the in-process brake only when everything above verified.

`RecoveryResult` keeps the outcomes separate — `sessionReleased`, `baselineRestored`,
`durableCleanupCommitted`, and `verified` — because ending the session does not prove baseline
restoration, and restoring normal controls does not remove device management.

Recovery is available on every management screen, works outside the protected interval as well
as inside it, and requires no network, subscription, or valid schedule.

**Failure preference** (brief section 3.5): a detected error makes the app restore access to
the power menu and end its session, rather than leave the menu restricted to preserve an
"enabled" indicator. If durable disable cannot be written, `RestrictionInhibitor` blocks new
restriction **in this process** and the result is reported as durable cleanup *unverified* —
the in-memory flag is a safety brake, never a claim that the persistence failure was repaired.
A failed release submits one separate inexact, release-only retry at least 20 minutes later,
capped at three attempts per incident, which can never arm a session or apply a restrictive
mask. The manual recovery route always remains available.

## 10. Permission and exported-component inventory

### Declared permissions (brief section 6)

| Permission | Why it is needed | Notes |
|---|---|---|
| `android.permission.RECEIVE_BOOT_COMPLETED` | Required to receive `LOCKED_BOOT_COMPLETED` / `BOOT_COMPLETED` for schedule restoration and honest post-reboot status | Brief section 6.3 |
| `android.permission.SCHEDULE_EXACT_ALARM` | Required to use `setExactAndAllowWhileIdle` for the primary boundaries | Brief section 6.3. Capability is always *evaluated* with `canScheduleExactAlarms()`, never inferred from this declaration |

**Deliberately not declared:** `USE_EXACT_ALARM` (brief section 6.4), `POST_NOTIFICATIONS`,
`INTERNET`, `QUERY_ALL_PACKAGES`, contacts, SMS, calls, location, camera, microphone, and broad
storage.

`POST_NOTIFICATIONS` deserves its own note because brief section 6.11 discusses it. That
requirement is **conditional** — "If a persistent user-visible status notification is
implemented…" — and this MVP implements no status notification. Status is shown on the main
screen, which is always reachable and is never gated on a permission, and the recovery controls
are never hidden. Declaring a permission the app never uses would be unnecessary privilege, so
it is omitted; the permission and its denial path must be added together with the notification
itself. Because no notification exists, "notification permission denied" is **NOT TESTED** rather
than untested-but-declared.

**One permission is not app-declared and should not be mistaken for one.**
`com.example.shutdownprotection.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION` appears in both the
debug and release merged manifests. It is injected by `androidx.core` to accompany
`CoreComponentFactory` (which the manifest merger also adds to `<application>`), it is
signature-level (`protectionLevel="0x2"`), and it is therefore neither runtime-grantable nor
user-visible. It cannot be removed without dropping `androidx.core:core-ktx`, which is not worth
doing. The honest description of the shipped manifest is: **two app-declared permissions plus one
AndroidX-contributed signature-level permission.** This was confirmed by reading both merged
manifests, not inferred.

Package visibility uses a narrow `<queries>` block for the `HOME` and `LAUNCHER` intent shapes
only, which is what the allowed-applications picker needs.

### On "every declared permission and every exported component"

The component table above inventories the components **this app declares**. The merged manifests
additionally contain components contributed by libraries and by the build type, and those are not
listed here because the app neither declares nor controls them. Observed in the merged debug
manifest, and confirmed against the shipped APK:

| Library-merged component | Exported | Protection |
|---|---|---|
| `androidx.profileinstaller.ProfileInstallReceiver` | **true** | `android.permission.DUMP` |
| `androidx.activity.ComponentActivity` | **true** | AGP-generated, debug only |
| `androidx.compose.ui.tooling.PreviewActivity` | **true** | AGP-generated, debug only |
| `androidx.startup.InitializationProvider` | false | content provider |

A reviewer auditing the *shipped* manifest should read the merged manifest for the variant being
shipped, not only the app's own declarations. Note that the two AGP-generated activities are
debug-only and do not exist in a release build; the profile installer receiver and the startup
provider come from AndroidX and are present in both.

### Components

| Component | Exported | Permission | `directBootAware` | Why |
|---|---|---|---|---|
| `ui.MainActivity` | **yes** | — | no | Launcher entry; the explicit foreground arm/resume surface. Not usable before unlock, which is intended |
| `admin.ShutdownProtectionAdminReceiver` | **yes** | `android.permission.BIND_DEVICE_ADMIN` | no | Device admin component with the `android.app.device_admin` metadata and the `DEVICE_ADMIN_ENABLED` action. `BIND_DEVICE_ADMIN` stops any other app delivering admin callbacks |
| `admin.LockTaskPolicyUpdateReceiver` | **yes** | `android.permission.BIND_DEVICE_ADMIN` | no | Registered for both documented policy actions (`DEVICE_POLICY_SET_RESULT`, `DEVICE_POLICY_CHANGED`) |
| `scheduling.SystemEventReceiver` | **yes** | — (system broadcasts only) | **yes** | Accepts only the six known actions; `LOCKED_BOOT_COMPLETED` requires Direct Boot. Its dependency, the device-protected store, is readable before unlock by construction |
| `scheduling.ProtectionAlarmReceiver` | **no** | — | **yes** | Internal-only. `exported=false` plus explicit-component PendingIntents means no other app can arm or release protection, and alarms can still fire before unlock |

The `USER_UNLOCKED` event cannot be delivered to a manifest receiver, so it is registered
dynamically with `RECEIVER_NOT_EXPORTED` by `ShutdownProtectionApplication`.

`android:lockTaskMode="if_whitelisted"` is **not** applied to any activity. The baseline enters
only from the explicit foreground arm/resume flow (brief section 6.9). If that manifest
mechanism is ever investigated for automatic re-entry, it must be isolated, explained, and
proved unable to re-enter after disarm.

## 11. Schedule arithmetic

`ScheduleCalculator` is pure Kotlin with an injected `Clock` and an injected **zone provider
function** — a function, not a value, because the schedule follows the device's *current* zone
and a cached `ZoneId` would pin it to whatever zone was in force at construction.

It never adds a fixed 24 hours to an epoch timestamp. Each boundary is rebuilt from local
calendar dates through the zone's own rules, with the brief's explicit DST handling: ordinary
times use their sole offset; a nonexistent time uses the first valid instant at or after the
gap's end (located by walking real transitions, never by inventing a clock time); an ambiguous
start takes the earlier instant; an ambiguous end takes the later instant. If the rules
collapse an interval, that day is skipped and the reason recorded — never turned into all-day
protection. Membership evaluates the intervals anchored on yesterday and today, so an
overnight interval that began yesterday is still recognised during an overlap.

Future boundaries are computed by scanning local dates starting at **yesterday** for at most
**eight** dates — that is, yesterday through `today + 6` — and both boundaries must be strictly
after the current instant; failing to find both is an error, not an infinite loop.

Diagnostic records carry operational state, the trigger or event kind, the settings revision, the
intended boundary and the actual delivery time with the observed delta for alarm deliveries, the
policy result code and the time it was observed, the runtime state, and an exception
class/message with sensitive data removed. A diagnostic write failure is always non-fatal: it can
never block power-menu release.

## 12. Privacy and threat model

Diagnostics are bounded local structured records — 1,000 events or 1 MiB, whichever is reached
first — holding operational state, event kind, settings revision, intended/actual transition
timestamps, policy result, runtime state, and exception class/message with record separators,
control characters, and long digit runs removed. No contacts, notification contents, messages,
browsing content, location, photos, credentials, or activity contents are recorded, and
application labels and package selections are configuration rather than a usage history.
Export is deliberate and local through a user-selected destination; there is no automatic
upload and no network permission at all. A failure to write a log never blocks power-menu
release.

**Threat model.** This feature intentionally controls a software menu on a voluntarily managed
device. It does not prevent hardware reset, recovery-mode actions, battery depletion, all
privileged system reboots, OS updates, or every OEM shutdown path, and manual user recovery is
always available by design — so it is not tamper resistant, not anti-theft, and not a claim
that the phone is impossible to turn off.

Exported components and PendingIntent identities are inventoried above precisely so that
another installed app cannot send an arbitrary intent that arms protection or rewrites the
allowlist. There is no hidden remote activation and no backdoor.

## 13. Limitations of this baseline — stated, not implied

These follow from the architecture above and must not be read as satisfied. They are repeated
here because an engineering document that omits them invites exactly the wrong conclusion.

1. **A reboot does not resume protection by itself.** The baseline restores scheduling and
   reconciles configuration, but the supported recovery route is: the user unlocks the device,
   opens the app, and explicitly resumes the managed session. The original objective of
   **unattended post-reboot re-entry is not met**, and automatic re-entry is a separate
   investigation that must use documented mechanisms, respect keyguard, and never be represented
   as working before it is demonstrated.
2. **There is no protection before the first unlock.** `LOCKED_BOOT_COMPLETED` reconciliation is
   deliberately permissive: it reads only device-protected state, reinstalls future scheduling,
   resumes pending recovery when it is authorized to, and reports `WAITING_FOR_UNLOCK` or
   `WAITING_FOR_SESSION`. It never starts a locked session while keyguard is locked. Protection
   before first unlock is a **separate stronger objective and is not met**.
3. **There is no hard bounded release after a force-stop or after exact-alarm permission is
   revoked.** The inexact `RELEASE_FALLBACK` and the release-only `RECOVERY_RETRY` are
   best-effort opportunities, not guarantees: they can be delayed or prevented by platform/OEM
   restrictions or by force-stop. This architecture **does not establish** a maximum release
   delay in those cases, and the brief forbids claiming one.
4. **Quick Settings is expected to remain unavailable** under the documented notifications
   feature. It is a known consequence of the Lock Task architecture, not a defect, and it is not
   restored by adding unrelated flags.
5. **This is not an ordinary unrestricted personal-phone product.** The session stays active for
   the whole armed period, so application workflows are affected. If an unrestricted personal
   phone experience is the requirement, that requirement is unmet by this architecture.

Additionally, no claim is made about any device or OS build that has not actually been tested:
one device's result never establishes another's.
