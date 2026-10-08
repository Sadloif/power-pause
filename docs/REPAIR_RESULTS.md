# Repair and review record — 7 October 2026

**Desktop verification passed. Physical-device behavior remains unverified.**

The owner subsequently requested a shorter, lower-usage finish. Extra review workers and the broad mutation campaign were stopped. Mutation runners/maps are preparation only and must not be presented as executed coverage evidence. The remaining completion path is the current safety correction, one stable desktop verification, accurate reporting and backed-up delivery to the original folder. Physical testing remains excluded.

The owner requested LUNA with maximum reasoning for fixes, followed by repeated independent review. The original project is `E:\Deepseek\Projects\Scheduled-Power-Menu-Restriction`. Repairs are being made in `E:\CodexData\work\scheduled-power-menu-repair\project`; delivery uses the backed-up explicit file plan below. No phone operation was performed.

## Final verified outcome

- Final verification: 239 tests, zero failures, zero errors and zero skipped tests.
- lintDebug passed with zero errors and 19 nonblocking warnings. Compiler/deprecation warnings remain; this is not a warning-free build.
- Debug app APK and Android test APK assembled. Android tests were compiled only, never run on a phone.
- Release main manifest processed; testOnly is absent. Debug APK is test-only and debuggable.
- App ID: com.example.shutdownprotection; version 1 / 0.1.0; minimum and target Android API 36.
- Debug APK: 26,849,013 bytes; SHA256 9EF61541373C03203326595E45258C5C1C06EA8094DA8FEC4462D6BC0F94329F.
- Signing certificate SHA256 matches the original: 97ab9013f33d02dee6b83a3ad04258b53a30689202bae8960760b3dc0239a135.
- All 93 tracked files were unchanged during verification. Final report edits are documentation only; delivery checks runtime/test/config/resource hashes against that passing source.
- Evidence: E:\CodexData\work\scheduled-power-menu-repair\_working\results\final-12. Full log, source manifests, test XML, lint reports, APK metadata and release manifest are retained there.
- Broad isolated mutation experiments were stopped to reduce usage. Runners/maps were prepared but no mutation coverage claim is made.

The corrected race, token ownership, external-policy preservation, failed status read and active-baseline admission cases passed in the final suite. An enabled or temporary session cannot install a fresh plan or restriction without a valid, trusted PREPARED original. Missing, ambiguous or retired originals require release and a truthful incomplete-restoration result. Three older tests needed explicit original-baseline fixtures; their behavioral assertions were retained.

## Delivery and rollback

The reviewed source snapshot is revision-1 under E:\CodexData\work\scheduled-power-menu-repair. Delivery targets E:\Deepseek\Projects\Scheduled-Power-Menu-Restriction, including app\build\outputs\apk\debug\app-debug.apk.

Backup directory: E:\Deepseek\Projects\Scheduled-Power-Menu-Restriction\_working\reviewed-repair-backups\revision-1-20261007-160750

Before replacing anything, the delivery script checks all 71 recorded original source files, the original APK and every selected destination. It backs up every replacement before copying the explicit list, then verifies every destination SHA256. The backup's sync-plan.csv identifies new files versus replacements; delivery-result.json is written only after all destination hashes match. Do not infer delivery completion if that result is absent. Signing material, toolchains, caches and unrelated files are excluded.

For rollback, first preserve any subsequent changes, read sync-plan.csv, and restore only replacement paths from this backup using literal paths. New files are explicitly marked ABSENT; review them separately rather than deleting a directory. Do not mirror or reset the project. This rollback procedure concerns project files and the APK, not phone management or data.

The original historical reports remain under docs/history/before-7-october-2026-repair. Diagnostic failures below are retained as historical evidence, not unresolved claims about the final passing candidate. No primary-phone installation, provisioning, reset, account removal or activation was performed. Real power-menu behavior, OEM behavior, alarms/Doze/boot, enrollment/removal and phone-data preservation still require a separately authorized dedicated device.

## Findings, fixes and regression evidence

| Issue | Required final behavior | Evidence to retain |
|---|---|---|
| Clean Restore invented a recovery failure | Preserve a clean device's current policy. Do not manufacture a baseline, incident or restriction. Distinguish preserving current policy from restoring an original. | CoreLifecycleSafetyTest: clean masks 0/16/63; no owner and lost authority; retired baseline; repeated Restore |
| Existing restriction could become the saved original | Run a shared entry guard before daily and temporary prerequisite refusals or preparation. Release uncertainty, never capture the app's already-modified policy as original. | ThirdReviewRegressionTest; CoreLifecycleSafetyTest: temporary foreground refusal and daily revoked-alarm refusal |
| Original mask 47 was refused by value alone | Trust explicit capture provenance and lifecycle, plus actual application identity and record validation. Preserve ambiguous legacy records without applying or overwriting them. | Real DataStore tests; original-47 restore and real second session tests |
| Baseline reused after a completed session | Mark successful cleanup RESTORED. Capture the then-current external policy before any mutation of the next actual session. | Two successful entries separated by cleanup and external policy changes; setter counts and retained original assertions |
| Null policy callback hid failure | Relevant missing result is unknown/failure and invokes release. Use the public lock-task identifier. Unrelated policy callbacks cannot enable this feature. | Policy receiver integration and live null-result release tests |
| Auxiliary reads, logging and timeout bookkeeping delayed release | Bound cooperative queueing/work/finalization. Release before optional diagnostics and incident bookkeeping. Keep unknown facts unknown; propagate external cancellation; no stale status publication. | Deadline matrix with reached injection counters, actual stop/empty-package attempts, elapsed virtual time, and cancellation tests |
| Retry count not durable before next alarm | Persist the increment before installing the next retry. At most three automatic attempts; keep manual recovery available. Incident creation atomically disables restrictive intent. | RecoveryIncidentSafetyTest and disk-backed repository tests |
| Restart could ignore unresolved incident | Incident is an unconditional release-only admission gate, including inconsistent historical settings still saying enabled. | Explicit historical-state restart regression with both release exits blocked and no fresh restrictive plan |
| Missing temporary marker concealed active session | Missing marker plus LOCKED/unknown runtime still attempts release, even when policy values equal a trusted original. | CoreLifecycleSafetyTest missing-marker case |
| Temporary alarms could release a newer session | Fence modern deliveries by UUID and original expiry. Fresh session/incident timers cancel old tokens rather than rewriting their extras. Manual release has an explicit separate route. | Two real same-clock sessions; production receiver adapter; old-token send cancellation; manual release test |
| Async receiver behavior was only inspected | Exercise all receiver routes with real framework PendingResults. Finish once on success, failure, cancellation, submission rejection, and deadline. Safety action precedes delivery logging. | ReceiverIntegrationTest and ReceiverWorkTest |
| Unknown platform facts looked safe | Owner, runtime, exact-alarm, unlock, feature/package and diagnostics failures remain unavailable. Paused activity cannot enter; failed export cannot claim success. | PlatformUnknownStateTest, DevicePolicyControllerTest and unlock-gated diagnostics tests |
| Passing suite did not detect deleted fixes | Run deliberate defect reintroductions only in isolated copies of the frozen passing revision. A behavioral assertion failure is required; compilation failure is not a successful mutation result. | Mutation ledger: exact edit, command, named failure, original frozen hashes unchanged |

## Verification already observed

- Baseline reproduction: 164 tests; five third-review regressions failed, one passed. Existing 158 tests did not establish these repairs.
- First complete repaired diagnostic run: production and tests compiled; 212 tests executed, 40 failed. Failure evidence is archived, not replaced by later successes.
- Focused platform correction: 43 tests, zero failures/errors. This is not yet a passing complete frozen revision.

## Step-by-step instructions for a future agent

1. Read this current record and `docs/API_FACTS.md`. Treat documents under `docs/history` and earlier review reports as historical evidence. Do not use an old green test count as proof about current files.
2. Identify the project directory explicitly. Read any applicable AGENTS.md. Record hashes of source, tests and build configuration. Preserve existing changes and signing material. Do not reset or delete the project.
3. Do desktop work only. Do not install on, provision, activate, wipe, reset, remove accounts from, or change management of the owner's primary phone. Hardware testing needs a separately authorized test device and procedure.
4. Reproduce the named defect with a test that reaches the failing operation. Add an invocation counter when fault injection could otherwise be bypassed. Assert policy, runtime, persistence and release attempts, rather than only a displayed label.
5. Make the smallest production correction that fixes the real failure. Do not weaken assertions, substitute trusted provenance for legacy records, silently default unknown values, or modify a fixture just to hide a reachable failure.
6. If a production API changes, move the test fault to the new actual persistence/receiver boundary. Preserve the original safety outcome and prove the fault was reached.
7. Run the focused test. Inspect the actual result and cause, not only the process exit code. Preserve failed output. Then run the complete suite after all writers stop.
8. Use JDK 17 for Android compilation and a JDK 21 test runtime for API-36 Robolectric. Pass the test runtime through `-Pspm.testJavaHome=<absolute JDK21 path>`; do not replace donor toolchains or the debug key.
9. Run `:app:testDebugUnitTest`, `:app:lintDebug`, `:app:assembleDebug`, `:app:assembleDebugAndroidTest`, and `:app:processReleaseMainManifest`. The instrumented APK is compiled only; this list does not authorize a device run.
10. Freeze a complete passing revision. Record that the broad mutation campaign was stopped to reduce usage. If the owner later requests those experiments, use separate copies, change one repair per experiment, run the named regression and inspect its behavioral failure. Never touch the frozen source. Record survivors as coverage gaps; prepared maps alone do not prove coverage.
11. Independently read the production path and the test. Check that there was a real first entry, cleanup, second entry and second cleanup wherever the case claims multiple sessions. Check that unknown, corrupt, stale and canceled paths cannot newly restrict.
12. If review finds another defect, repeat the correction and affected checks on a new frozen revision. Never run mutations against a tree that another agent is editing.
13. Verify the actual packaged debug APK's application ID, API levels, test-only flag, signer and SHA-256. Compile the release manifest and confirm test-only is absent there. Matching signer preserves update identity; it does not prove safe migration on hardware.
14. Update the current report with exact counts, paths, hashes and limitations. Archive superseded reports. Do not write “fixed” for inspection-only or device-unobserved behavior.
15. Before updating the original folder, compare every replacement to the saved starting hash. Stop on an unexpected change. Back up each replaced file. Copy only an explicit reviewed list; do not mirror/delete caches or unrelated files. Verify destination hashes afterwards.

## Device evidence still required

Power-menu suppression and restoration, real AlarmManager/Doze/boot delivery, ColorOS behavior, enrollment, management removal and data preservation have not been validated on a physical device during this repair. Coroutine deadlines bound cooperative suspension; they do not forcibly interrupt synchronous Binder or filesystem work. No desktop result certifies primary-phone activation.

Android references: [lock-task policy identifier](https://developer.android.com/reference/android/app/admin/DevicePolicyIdentifiers#LOCK_TASK_POLICY), [broadcast async lifetime](https://developer.android.com/reference/android/content/BroadcastReceiver#goAsync()), [PendingIntent update semantics](https://developer.android.com/reference/android/app/PendingIntent#FLAG_UPDATE_CURRENT), [receiver export rules](https://developer.android.com/guide/topics/manifest/receiver-element). Test runtime reference: [Robolectric configuration](https://robolectric.org/configuring/).

## Additional defects discovered and corrected (chronological notes)

1. Concurrent unlocked timeout handlers could save incident B but leave only a retry for incident A. The handler would reject that mismatched alarm. Diagnostic 8b and Diagnostic 9 reproduced this with gated actual repository writes and scheduling calls. Fix requires one consistent ordering for durable incident identity and retry submission, including failed recovery attempts.
2. An unlocked timeout handler could capture an unresolved incident, let Restore finish and mark the original RESTORED, then write the old incident back. Diagnostic 9 reproduced recoveryRequired=true after Restore returned DISARMED. Cleanup, retry cancellation and late incident creation must share safe ordering; obsolete work must not resurrect state or leave a stale inhibitor. A newer independent failure must retain its own inhibitor.
3. Plain JUnit disk tests silently saw mocked Android SDK 0 and selected DataStore's old rename fallback on Windows. Pinning 1.1.7 alone did not fix this. Inspected library bytecode and official AndroidX source established the branch. The same real production factory now runs under Robolectric API 36. Diagnostic 9 executed all 11 disk tests with zero failures, including the binary original-serializer fixture and stopped-store persistence. This does not prove migration on a physical device.
4. Restore could overwrite an unrelated external kiosk policy on a device with fully readable absent app journals, disabled settings and runtime NONE. The narrow preservation rule requires a confirmed owner and known external allowlist excluding this application; own-package, mixed-package, unknown and pending-history cases still require release. A new regression and its deliberate reintroduction are pending. Do not claim this correction verified until those checks execute.
5. The same external-policy state could be changed by editing a disabled schedule: the coordinator applied the permissive feature mask directly before asking recovery to establish ownership. The schedule edit, disabled reconciliation, daily entry and temporary entry now need separate actual-call regressions proving no external policy replacement and no invented original/session.
6. A lock-wait timeout's final observation ran outside the acquired-operation exception handler. A throwing receipt/settings read could escape the public call rather than return its bounded emergency recovery status. The repair must return truthful failure without publishing over a newer holder result, while propagating external cancellation. Reached-fault regression and verification are pending.
7. Daily reconciliation could accept enabled intent and LOCKED runtime without a saved trusted PREPARED original, then submit a new protected mask or plan. Two legacy successful-edit tests used exactly that incomplete fixture. Restrictive admission must require readable, valid capture provenance and active lifecycle; missing, legacy-unverified or retired originals must take release-only recovery. Successful-session tests must establish a real arm or an explicit valid prepared session; do not globally make malformed fixtures trusted. New production gate and regression are pending.

Diagnostic 10 executed 238 tests: 234 passed, four failed. Source manifests before/after matched for all 93 tracked files. The two incident-race regressions, delayed-obsolete token, independent newer-token inhibition, real second-session restoration, lock-wait final-status IO containment and serialized-unit cancellation tests all passed. The remaining failures were two existing edit tests with missing active-session originals, a one-shot receipt fault consumed by the new earlier evidence read, and an external-preservation text assertion expecting an extra word. The full invocation stopped at unit-test failure; lintDebug, debug APK, Android test APK and release manifest were not freshly completed. Evidence: `_working/results/diagnostic-10-full-20261007T104105664Z`.

The actual two-session regression was strengthened to run independent second-original cases 16/{external.kiosk} and 47/{app}, each after a genuine initial 0/empty session and verified cleanup, then restore the second session and repeat Restore without mutation. Both scenarios passed Diagnostic 9. The old historical test name that claimed a second session without performing one is not evidence of this result.
