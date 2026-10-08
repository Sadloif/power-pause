# Compatibility status — 7 October 2026

The desktop repair does not certify a physical device or OEM. Earlier device/workflow entries are preserved verbatim in `history/before-7-october-2026-repair/COMPATIBILITY_MATRIX.md`. They are historical evidence and must not be relabeled as current repaired-build observations.

| Surface | Current repair evidence |
|---|---|
| Pure coordinator/recovery/storage logic | See exact executed counts and revision in TEST_RESULTS.md |
| Android API 36 framework behavior | Robolectric; same limitation as desktop simulation |
| Debug / Android test APK packaging | Build and manifest/signature inspection; no device execution |
| Real power menu suppression and restoration | Unverified |
| ColorOS / OEM differences | Unverified |
| AlarmManager / Doze / boot / force-stop | Real-device behavior unverified |
| Enrollment / removal / update / data preservation | Unverified |
| Launcher, permission dialogs, authentication, sharing and camera handoffs | Unverified on the repaired build |

For future tests, record the dedicated test device, build fingerprint, application ID/version/APK hash, signer, exact steps, expected and observed outcomes, session/policy facts, and recovery result. Mark unexecuted cases unverified. The primary phone remains excluded; no desktop result authorizes its activation.
