# Final desktop verification — 7 October 2026

The repaired candidate passed all 239 unit/Robolectric tests, with zero failures, errors or skipped tests. lintDebug passed with zero errors and 19 warnings. The debug app APK and Android test APK assembled, and the release main manifest processed. The test APK was compiled only; no device tests were run.

Evidence: `E:\CodexData\work\scheduled-power-menu-repair\_working\results\final-12`. The full log shows 90 tasks, 22 executed and 68 up-to-date. Unchanged app compilation from final-11 was reused; changed test fixtures were recompiled and the entire test suite executed. All 93 source-file hashes matched before/after. Documentation was finalized afterwards; code/tests/build configuration/resources are compared to the passing manifest before delivery.

Debug APK SHA256: `9EF61541373C03203326595E45258C5C1C06EA8094DA8FEC4462D6BC0F94329F`; size 26,849,013 bytes. ID `com.example.shutdownprotection`, versionCode 1/versionName 0.1.0, minimum/target API 36, test-only and debuggable. Signer certificate SHA256 `97ab9013f33d02dee6b83a3ad04258b53a30689202bae8960760b3dc0239a135` matches the original. Release testOnly is absent.

Historical diagnostic results: baseline164/five new failures; Diagnostic5 212/40 failures; focused platform43/zero; Diagnostic7 228/11; Diagnostic8b231/four; focused Diagnostic9 34/two reproduced incident races; Diagnostic10 238/four; final-11 239/three missing active-session fixture failures. Logs/XML remain preserved. final-12 is the authoritative passing result.

New proof includes actual clean Restore, original-47 restoration, two real daily sessions with fresh originals, two same-clock UUID temporary sessions, independent release exits, strict real-file DataStore migration/reopen behavior, framework receiver routing/lifetimes, incident identity/cleanup races, inhibition generation ownership, external-policy preservation, reached status-read failures/cancellation, and missing/legacy/retired active-original admission. See REPAIR_RESULTS.md for the handoff instructions and issue map.

The broad mutation campaign was stopped to reduce usage; prepared scripts are not executed coverage evidence. No real power-menu, OEM, AlarmManager/Doze/boot, enrollment/removal or phone-data-preservation claim follows from these desktop results. The owner's primary phone was not used.