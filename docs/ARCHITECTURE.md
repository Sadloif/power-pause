# Current architecture — updated 8 October 2026

## Independent no-reset backend (version 0.2.x)

Ordinary installations open `NoResetScreen`. This path does not attach the lock-task controller,
create its ViewModel or call its foreground coordinator. Device Owner installations retain the
managed UI. An explicit managed-tools entry stops the no-reset schedule first and preserves
access to the existing recovery interface.

`NoResetStore` uses a separate credential-protected SharedPreferences file; malformed values
refuse activation. `NoResetGate` reuses the pure `ScheduleCalculator` with separate intent.
`NoResetMenuService` requests Back only for the exact observed Reno System UI event/window,
checking current configuration revision and schedule/elapsed deadline before every retry and
immediately before acting. It adds no alarm, shell, settings-write, enrollment or lock-task path.
The temporary test is memory-only and expires after 60 seconds; a reconnect does not resume it.
Stop disables durable intent and invalidates pending work while leaving the service enabled.
Schedule/test expiry and unavailable compatibility facts do not call disableSelf; only the
owner's explicit service-off control does. A bounded window-ID fence prevents duplicate Back
requests while one dialog closes. The NoResetTimeInput helper accepts four-digit 24-hour input,
adds a colon and delegates range validation to the existing time parser. API acceptance and actual menu
closure remain separate evidence. Setup and device results are in `NO_RESET_MODE.md`.

## Existing managed backend

Earlier notes are preserved in `history/before-7-october-2026-repair/ARCHITECTURE.md`. The current test/repair reports identify the revision verified. This description alone is not verification evidence.

`ProtectionCoordinator` serializes admission, schedule transitions and normal recovery. Its inhibitor is checked across suspending preparation and policy boundaries. Queue waits, operations, cleanup, bookkeeping and status reads have separate cooperative budgets. Timeout/cancellation fallbacks attempt release and never authorize entry. Pending incidents and unavailable safety facts cannot be hidden by a success status.

`RecoveryManager` captures evidence before release, distinguishes clean preservation from original restoration, attempts independent exits, validates provenance/lifecycle, checks setters and separate readback, and commits cleanup atomically. Incident fallbacks can originate outside the coordinator lock; durable identity and retry submission need consistent ordering. See `REPAIR_RESULTS.md` for final race-fix evidence.

`SettingsRepository` uses one real Preferences DataStore instance per file. `prepareSession` atomically saves the original and pending journal before policy mutation. Valid PREPARED originals survive unfinished recovery; RESTORED originals are replaced with a fresh capture before the next actual session. Legacy provenance is never inferred. Strict field-group decoding prevents orphaned or partial records from becoming absence. Cancellation propagates.

`DevicePolicyController` exposes unavailable owner, runtime, capability and policy facts as nullable evidence. Foreground activity availability is checked before entry. The latest activity remains available for best-effort stop after pause. Setter verification is distinct from requesting a policy.

`ScheduleManager` follows current calendar/time-zone semantics and release-first ordering. UUID-fenced temporary and incident timers invalidate replaced tokens. Receipt fields describe observed submission evidence, not proof that Android currently holds a timer.

Receivers use a production adapter covered by framework tests. Relevant callbacks use `DevicePolicyIdentifiers.LOCK_TASK_POLICY`. Async work acquires a PendingResult and finishes once on success, failure, deadline, cancellation or rejected submission. Alarm safety handling precedes optional logging.

UI facts remain Unknown when unavailable. Credential-protected diagnostics require positively known unlock. Export requires an opened stream and actual writes; unavailable storage cannot appear as a successful empty export.

No desktop result certifies physical power-menu or OEM behavior. See `RECOVERY.md` for release and management limits.

## Permission and exported-component inventory

| Declaration | Purpose / access |
|---|---|
| RECEIVE_BOOT_COMPLETED | Observe boot and locked boot; never unattended restrictive entry |
| SCHEDULE_EXACT_ALARM | User-controlled exact-alarm capability; read/permission failure remains Unknown |
| HOME / LAUNCHER package queries | Narrow launcher/app selection; no QUERY_ALL_PACKAGES |
| MainActivity exported | Launcher entry; restrictive admission still checks owner, unlock and foreground state |
| NoResetMenuService exported | Android binding protected by BIND_ACCESSIBILITY_SERVICE; owner must enable it manually; receives System UI window events only |
| Accessibility window retrieval flag | Read root package/class, window ID/type/title/focus for exact menu matching; no text/child contents/screenshots recorded |
| Admin / policy receivers exported | Framework delivery protected by BIND_DEVICE_ADMIN |
| Alarm receiver not exported | Explicit app-owned alarm delivery |
| System receiver not exported | System/same-UID events; no public app-triggered reconciliation |
| Main activity not direct-boot aware | No restrictive foreground entry before unlock |
| Alarm / system receivers direct-boot aware | Device-protected settings support release/reconciliation; credential diagnostics stay gated |
| Backup and data extraction disabled | No automatic backup/migration of management state |

No INTERNET, USE_EXACT_ALARM, QUERY_ALL_PACKAGES, wipe policy or root/shell privilege is requested. Version 0.2.0 declares the separate, manually enabled Accessibility service described above. The debug manifest marks the build test-only; release packaging is checked separately. The absence of network permission does not establish phone data safety.
