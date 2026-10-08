# Power Pause 0.4.1 — separate editions and verification

8 October 2026. This release replaces the shared-package 0.4.0 layout with three separately installable editions.

| Edition | Package | Minimum Android |
|---|---|---|
| Simple | com.example.shutdownprotection | 16 / API 36 |
| Compatibility preview | com.example.shutdownprotection.compatibility | 10 / API 29 |
| Advanced | com.example.shutdownprotection.advanced | 16 / API 36 |

Simple retains the existing ordinary package/certificate and saved hours. The other two packages start with separate data and approval. Enable only one dismissal service at a time. Existing enrolled Device Owner packages are outside this migration; do not replace their recovery components with Simple.

## Implementation

Managed application/container, receivers, policy code, coordinator/recovery and managed UI now live only in the Advanced source set. Managed tests and instrumented tests are likewise Advanced-specific. Simple and Compatibility have minimal ordinary application/activity wiring. DataStore is an Advanced-only dependency. Their merged manifests contain no app-managed admin/alarm receivers or managed alarm/boot permissions.

Each manifest has an explicit edition name and bitmap launcher/round icons in mdpi through xxxhdpi densities. The original Reno package updated successfully without uninstall or data clearing. Its own screen retained 14:50 and 15:00. Android App info initially retained the previous Power Pause label while reporting version 0.4.1; closing/reopening Android Settings refreshed it to Power Pause Simple. The bitmap icon was observed in App info, and the owner reported the icon in the app drawer. A prominent Accessibility connection card is now separate from the schedule status.

Compatibility can install on Android 10+. Automatic detection still accepts only the verified Reno profile. Unknown firmware cannot arm a schedule or automatic test. Its optional observation and owner-requested one-Back checks are described in ANDROID10_COMPATIBILITY.md. They do not create an automatic profile or claim support for every manufacturer.

## Executed desktop checks

| Edition | Tests | Failures/errors/skipped | Lint errors | Lint warnings |
|---|---|---|---|---|
| Simple | 21 | 0/0/0 | 0 | 35 |
| Compatibility | 66 | 0/0/0 | 0 | 33 |
| Advanced | 260 | 0/0/0 | 0 | 32 |

Compatibility’s application/activity isolation and cancellable one-Back checks execute on simulated API 29, 30, 31, 32, 33, 34, 35 and 36. Common service behavior executes on API 36. The first compatibility lint run caught two LocalDate.ofInstant calls requiring API 34; both now use Instant.atZone(zone).toLocalDate(), supported by the older minimum. Robolectric downloads official Android runtime artifacts from Maven Central. Lint runs against each release variant; signed release APKs are built for all three.

Passing framework tests does not prove physical OEM menu recognition, long-term service availability, reboot/Doze behavior, or managed power-menu suppression. No older phone was physically tested in this release. Use the documented spare-phone procedure before implementing and enabling a new profile.

## Signing and hashes

All supplied release APKs use the existing private local certificate: SHA256 97ab9013f33d02dee6b83a3ad04258b53a30689202bae8960760b3dc0239a135. They are non-debuggable local builds signed with the existing Android Debug certificate; the private key is excluded from GitHub. Different package IDs allow coexistence. Independently built CI APKs cannot update these supplied APKs in place.

- `877d24a10de2e322b13890cb0b2e4afc1b559046d17487131f99726c452b9691  Power-Pause-Simple-0.4.1.apk`
- `b27f93adeccfffe01fd7d8b58d34a98da3cae2122b0c367a5f8d7b481600779e  Power-Pause-Compatibility-0.4.1.apk`
- `646bdb4793abf22c7a5656ba3d6cfe906d38c043ae93d83f66d1982922da9f91  Power-Pause-Advanced-0.4.1.apk`

Original-project changes are delivered with per-file backups and hash verification. Source, source icon assets, tests, documentation, checksums and CI are published. Phone screenshots, local reports, credentials, keystores and build caches are excluded.

Final phone check: after the owner reconnected the service, Android listed Power Pause Simple as bound and the visible screen showed Accessibility connected and Paused, with current fields 14:50–17:45. Earlier UI text readbacks differed; direct screen observation resolved the availability check. No new menu trial or uninterrupted service lifetime is claimed. APK archive/class inspection also verified the three managed implementation classes are absent from both Simple APKs and present in Advanced.
