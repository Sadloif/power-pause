# Power Pause 0.4.0 editions — verification record

8 October 2026. Both downloadable APKs are locally signed release builds: version code 5, version name 0.4.0, package `com.example.shutdownprotection`, minimum/target/compile Android API 36. They are not debuggable or test-only. Both retain signing certificate SHA256 `97ab9013f33d02dee6b83a3ad04258b53a30689202bae8960760b3dc0239a135`. The private keystore is excluded from GitHub. Independently built/CI artifacts have another key and cannot update these APKs in place.

## Desktop results

- Simple: 21 tests, zero failures/errors/skipped. This includes 18 gate/service tests and three actual manifest/startup isolation tests.
- Advanced: 260 tests, zero failures/errors/skipped. This includes the full 257-test regression suite and the same three edition-aware checks.
- Both release lint tasks: zero errors, 23 warnings each, primarily existing legacy/dependency warnings.
- Both signed release APKs assembled successfully and passed signature verification.
- Simple's actual packaged manifest contains no Device Admin, policy-update, managed alarm or system-event receivers, and no RECEIVE_BOOT_COMPLETED or SCHEDULE_EXACT_ALARM permission. Its only declared uses-permission is the AndroidX application-local signature receiver permission. BIND_ACCESSIBILITY_SERVICE remains the service binding protection.
- Advanced retains the original managed declarations and permissions. No new permissions are added to the managed edition.
- Simple startup and ordinary activity do not construct the managed container, including when a Robolectric-only shadow reports Device Owner. Managed sources remain shared in the repository; Simple removes their component entry points and UI, rather than claiming those class files are physically absent from its unminified APK.
- Exact no-reset service, detector, parser and store source hashes match the previously tested implementation. Only edition startup/UI wiring changes.

## Cautions and downloads

Use Simple for the tested unenrolled Reno Accessibility workflow. Advanced adds an explanatory caution card, an opening confirmation and a persistent caution on the managed main screen. GitHub README, release notes and ADVANCED_MODE.md explain the tools, benefits, provisioning trade-offs and unverified physical behaviour.

Do not experiment with managed settings on an everyday phone. Standard Device Owner provisioning normally requires initial setup or a factory reset. Opening the advanced page does not enroll/reset/erase a phone, but it stops the Accessibility schedule. Restore reverses app-applied managed rules; it neither wipes the phone nor removes ownership. Do not switch to Simple while this app is Device Owner or a managed session is active, because Simple removes managed recovery components.

The same package and certificate allow an ordinary existing installation to update without discarding its saved hours or Accessibility approval. Only one edition can be installed at a time. Download the signed release assets; CI produces separately signed debug/test-only developer artifacts. No store publication or general-device support is claimed.

## Evidence limits

The earlier Reno physical menu tests are recorded in NO_RESET_MODE.md. No new managed suppression, enrollment, removal, reboot/Doze or long-duration test is claimed. The editions do not broaden firmware/language compatibility and do not prevent forced hardware restart or every shutdown path. Source upload excludes caches, phone dumps/screenshots, private keys, local SDK configuration and credentials. Historical reports remain under docs/history with current cautions in README.

## APK hashes

Simple SHA256: 5DA977C3DB8E84F53E440570C7106837BBC1085FE4BAF64ACB79ACFF7295FE2B

Advanced SHA256: 7CA25190BED5CCF7683FE5C8A10372E2B9A343A456B28FFCAE98798D28F9939A


## Owner phone update

The existing phone installation was updated in place to Advanced 0.4.0 to retain its managed-tools option, as requested, with the new cautions. A read-only owner check reported no owners. The saved 14:50–15:00 hours and paused state were displayed after the update, and Accessibility authorization remained enabled. The new caution text was inspected in Tools without opening or activating managed mode. No factory reset, enrollment, shutdown or reboot was performed. No new physical menu-closure test is claimed for this edition build.

