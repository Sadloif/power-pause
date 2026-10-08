# Recovery behavior and limits — 7 October 2026

The previous procedure is preserved in `history/before-7-october-2026-repair/RECOVERY.md`. Use this document for current semantics and `TEST_RESULTS.md` for executed evidence. This document does not authorize enrollment or changes to any phone.

## What Restore means

**Restore Normal Device Mode** requests release of this app's managed session and disables its restrictive intent. It does not remove Device Owner management. Session exit, original-policy restoration, durable cleanup and management removal are separate outcomes.

Restore is independent of the daily schedule. The interface waits for an active operation to finish before accepting another submission; duplicate presses do not bypass serialization.

## Recovery sequence

1. Inhibit fresh restriction immediately.
2. Read settings, schedule receipt, baseline, temporary marker, incident, corruption and owner history within separate cooperative budgets. Preserve missing, corrupt and unavailable evidence as distinct states. Capture this evidence before changing policy.
3. Preserve current policy only after fresh readable durable evidence proves disabled intent, no recovery requirement, no temporary marker, no incident and no corruption, together with runtime NONE and an absent or valid retired baseline. With no baseline, known nonrestrictive policy and an empty allowlist qualify; confirmed absence of owner authority also qualifies. A confirmed owner with a known external allowlist excluding this app qualifies under the narrow external-policy rule only when the schedule receipt is also known absent and platform values are valid. An allowlist containing this app, unknown facts or pending journal must not use that external-policy exception. A valid completed retained baseline establishes that the previous session finished; do not reapply it after someone else changes policy. A clean no-op does not claim an invented original was restored. See TEST_RESULTS.md for the rule's executed verification status.
4. Otherwise request the permissive mask, task exit and empty lock-task package list as independent release attempts. Unknown owner/runtime or rejected mask requests must not suppress the other exits. The controller refuses unauthorized writes.
5. Observe runtime NONE, persist disabled/recovery-required intent and cancel app alarms. Failed or unavailable observation cannot certify session exit.
6. Restore only a valid original captured before preparation, belonging to the actual application ID, with PREPARED lifecycle. Verify setter results and independently read back features and packages. Original mask 47 is permitted when provenance establishes it is the original.
7. Atomically mark the baseline RESTORED, clear pending recovery/temporary/schedule records and corruption, and keep protection disabled. Check the commit result.
8. Serialize incident creation, failed-attempt updates, cleanup and matching retry submission/cancellation under the same bounded bookkeeping lock. A delayed fallback must reread evidence and become obsolete after verified cleanup. Clear only the inhibition generation owned by completed recovery or its registered release fallback; a newer independent failure retains its inhibitor. Optional diagnostics follow safety actions and have their own cooperative budget.

Missing or ambiguous legacy baselines are retained for diagnosis and never silently made trusted. Release can succeed while original-policy restoration remains unknown; status must say so. Legacy records lacking provenance need a separately designed explicit recovery/migration procedure. Repeated Restore presses cannot manufacture that evidence.

## Retry and identity

An unresolved incident blocks restrictive entry, including restart with inconsistent historical enabled settings. Identity and attempt count must be durable before submitting a release-only retry. Automatic attempts are capped at three. Failed durable writes cannot justify another scheduled retry.

Temporary sessions carry a UUID and original expiry. Modern alarm delivery must match both; old A cannot release new B even when both were created at the same time. Manual temporary release is separate. Replacing a temporary or incident timer cancels the previous token rather than rewriting identity.

## Limits

Coroutine budgets bound cooperative suspension; they cannot forcibly interrupt synchronous Binder or filesystem work. The receiver watchdog is an additional completion safeguard, not a guaranteed physical release deadline.

Desktop tests do not establish real power-menu suppression/restoration, AlarmManager/Doze/boot delivery, ColorOS behavior, enrollment, management removal, or preservation of phone data. Those need a separately authorized dedicated test device. The primary phone remains outside scope.
