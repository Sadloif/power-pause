# Verification plan and handoff instructions — 7 October 2026

This is the current desktop plan. Earlier device/emulator procedures are preserved under `history/before-7-october-2026-repair`. The owner's primary phone is excluded.

## Before editing

1. Read `REPAIR_RESULTS.md`, `TEST_RESULTS.md`, `RECOVERY.md`, `API_FACTS.md` and applicable AGENTS.md. Identify the revision actually verified, not merely the latest file timestamp.
2. Preserve source, existing user changes, original signing key and all failed verification logs. Record hashes before any replacement. Do not reset, mirror or delete the project.
3. Reproduce the precise defect first. Write a regression that reaches the real failing boundary. Use counters or gates to prove injection was reached. A passing test that never entered its claimed second session is invalid evidence.
4. Keep one build owner for each project tree. Stop writers during verification. Use JDK 17 for compilation and JDK 21 for API 36 Robolectric.

## Safety cases that must remain covered

- Clean Restore: masks 0, 16 and 63; absent/lost owner; no policy mutations, invented original or false failed journal.
- Original policy: valid captured 47 is allowed; partial, wrong-app, negative timestamp and legacy provenance remain invalid/unknown. Never overwrite pending PREPARED originals.
- Actual second daily and temporary sessions: first entry, actual cleanup, external policy change, second entry, capture before setters and second cleanup. Same clock time must not collapse UUID identity.
- Admission: active restriction is guarded before prerequisite refusals in daily and temporary routes. Missing marker with LOCKED/unknown runtime still attempts exit.
- Recovery: stop and empty-package exits remain independent even if reads or permissive mask fail. Independent final baseline readback must detect mismatch after apparently successful setters. Durable cleanup failure remains incomplete.
- Storage: strict presence groups, real 1.0.0 binary decode, unchanged legacy reads, refused ambiguous overwrite, actual write/close/reopen persistence and propagated cancellation. Never delete destination files to hide write failure.
- Incidents: durable count before scheduling, at most three automatic attempts, restart admission gate despite historical inconsistent settings, correct identity after overlapping fallback operations and cleanup.
- Deadlines/cancellation: reached reads/writes/log gates, actual exits, no fresh restrictive setters, bounded cooperative waiting, propagated external cancellation and no stale success status.
- Receivers: real PendingResult, every public route, exactly-once completion on cancellation/failure/rejection/deadline, action before logging, actual production adapter forwarding and callback classification.
- Unknown platform facts: no invented owner/runtime/mask/packages/unlock/alarm certainty; paused activity cannot enter; export cannot report success without a written stream.

## Verify a candidate

1. Run focused regressions and inspect the behavioral assertions. A compile failure does not reproduce a safety defect.
2. Stop all source writers. Run the complete unit suite, lint, debug APK, Android test APK compilation and release manifest tasks.
3. Preserve the full log, individual XML, lint report and APK. Count tests/failures/errors from the actual results, not a remembered console line.
4. Independently review both implementation and tests. If another defect is found, reproduce and repair it, then rerun affected and complete checks.
5. Copy a passing candidate into an immutable revision and record its source manifest. The broad deliberate-defect campaign was stopped at the owner's request to reduce time and usage; prepared runners are not executed evidence. If further mutation verification is separately requested, reintroduce one defect per isolated copy and require a named behavioral failure. Compilation failure is not a kill; surviving defects remain coverage gaps.
6. Check frozen-source hashes before and after experiments. Do not patch the live candidate while relying on mutation evidence from it.
7. Inspect the packaged debug application ID, API levels, test-only flag, signer and SHA256. Compare the signer with the original APK. Check the release manifest separately. This is desktop packaging evidence, not a successful update on hardware.
8. Update current reports with exact revision, commands, counts, failed/passed evidence, mutation outcomes, dependency changes and remaining limits. Preserve earlier claims in history.
9. Before delivery, compare every original file with its starting hash. Stop if anything changed unexpectedly. Back up every replacement, copy only an explicit reviewed list, then verify every destination hash. Keep scratch/cache directories out of that list.

## Physical testing is a separate task

Power-menu behavior, OEM differences, real alarms/Doze/boot, enrollment, removal of management and preservation of user data remain unverified without hardware. Do not activate, provision, install on, reset, remove accounts from or change management of the primary phone. Device testing requires a separately authorized dedicated device and a reviewed procedure.
