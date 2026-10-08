# PRODUCTION FEASIBILITY — Scheduled Power Menu Restriction

This document answers brief section 28.9: Device Owner / managed enrollment, setup and reset
implications, the supported deployment audience, current Play policy constraints, package
visibility, and why APK installation is insufficient.

## Sourcing rule for this document, stated up front

Brief section 28 requires production research to use **current primary Android/Google
documentation**. That was not possible in this environment:

- The harness web-search tool returned `HTTP 402 (Insufficient Balance)`.
- The host's PowerShell HTTP stack fails on outbound HTTPS (`Invoke-WebRequest` and `curl.exe`
  both fail with a connection-closed error), although Gradle and Python reach the network
  normally.

Everything below is therefore split into two clearly separated classes:

- **[LOCAL]** — verified against artifacts on this machine: the platform 36 `android.jar`
  (`javap`), the SDK metadata, the emulator's actual runtime behaviour, and the app's own
  observed behaviour. Evidence is recorded in `docs/API_FACTS.md` and `docs/TEST_RESULTS.md`.
- **[UNVERIFIED — no primary source]** — a claim that depends on Google's published policy or
  enrollment documentation, which could not be fetched. These are stated as **questions that
  must be answered from primary sources before any production decision**, not as findings.

No claim in this document is presented as a production guarantee.

---

## 1. Why APK installation is insufficient — [LOCAL, verified]

This is the single most important deployment fact, and it is observable, not policy.

- The feature works by applying **documented lock-task policy** through
  `DevicePolicyManager.setLockTaskPackages()` and `setLockTaskFeatures()`.
- Those calls require the calling package to be the **Device Owner** (or profile owner) for the
  user. `DevicePolicyController.applyFeatures()` and `applyAllowedPackages()` check
  `isDeviceOwnerApp(packageName)` first and return `NotAuthorized` without attempting anything
  when it is false.
- **Observed on the emulator:** before provisioning, the app reported *Device Owner: Required*
  and `dpm list-owners` reported `no owners`. After
  `dpm set-device-owner com.example.shutdownprotection/.admin.ShutdownProtectionAdminReceiver`,
  the app reported *Device Owner: Ready* and `dpm list-owners` reported
  `1 owner: User 0: admin=…,DeviceOwner,Affiliated`.
- Installing the APK, or enabling ordinary Device Administrator access, grants **none** of this.
  The app's own Setup screen states the required sentence verbatim: "Device Owner provisioning
  is required. Installing the APK or activating ordinary Device Administrator access does not
  enable this feature."

So the deployment unit is not "an app"; it is **a provisioned managed device**.

## 2. Device Owner provisioning — [LOCAL, verified on an emulator only]

> **Emulator evidence, not a phone procedure (guide R10).** Everything in this section was done on a
> disposable emulator. The `device_provisioned` / `user_setup_complete` step in particular changes a
> device's setup state and **must not** be applied to a physical phone. For a real device, check the
> enrollment prerequisites in `docs/RECOVERY.md` §3 and **stop** if any fails.

What was actually done on the test emulator (`sdk_gphone64_x86_64`, Android 16 / API 36,
fingerprint `google/sdk_gphone64_x86_64/emu64xa:16/BE2A.250530.026.F3/13894323:userdebug/dev-keys`):

```text
adb install -t app-debug.apk
adb shell dpm list-owners                       -> no owners
adb shell dpm set-device-owner com.example.shutdownprotection/.admin.ShutdownProtectionAdminReceiver
   -> FAILED: "Not allowed to set the device owner because there are already some accounts on
      the device."   (dumpsys account actually reported "Accounts: 0" - the message is
      misleading; the real blocker was the provisioning flag)
adb shell settings put global device_provisioned 0        <-- EMULATOR ONLY
adb shell settings put secure user_setup_complete 0       <-- EMULATOR ONLY
adb shell dpm set-device-owner ...              -> Success
adb shell dpm list-owners                       -> 1 owner: …,DeviceOwner,Affiliated
```

Implications that matter for a real deployment:

1. **Provisioning is a device-setup-time operation.** On a physical device the supported route
   is a managed-enrollment flow (factory-fresh device, enrollment during setup), not
   `dpm set-device-owner` after the device has been used. The adb route used here is a
   development/diagnostic route, and the brief permits adb only for development provisioning,
   diagnostics, recovery, and documented test execution.
2. **The provisioning attempt fails closed on an already-used device.** The failure above is a
   safe failure — it refused rather than taking over — but it means a device that has already
   been set up generally cannot become a Device Owner without a factory reset.
3. **Ownership survives a reboot.** Verified: after `adb reboot`, `dpm list-owners` still
   reported the app as Device Owner.
4. **A development escape route exists only because the build is `testOnly`.** The debug APK
   carries `android:testOnly="true"` from `app/src/debug/AndroidManifest.xml` (verified present
   in the merged debug manifest, in the built APK, and **absent** from the merged release
   manifest). That is what makes `adb install -t` and `dpm remove-active-admin` work on an owner
   app. A production build would not have it, so a production deployment needs a different,
   deliberately designed off-boarding route.
5. **Removing the active admin worked and left no restrictive policy behind** — verified with
   no lock-task session active (`mLockTaskModeState=NONE` before removal, `no owners` after).
   The brief warns not to assume this on every OEM; the power-menu re-check after removal is
   specified in `docs/RECOVERY.md` §2.4 and is **NOT TESTED** here for the reason in §5 below.

## 3. Setup, reset, and support implications — [LOCAL] + [UNVERIFIED]

[LOCAL] Facts established, with their exact scope:

- The app captures the device's original lock-task configuration as a baseline before its first
  policy mutation, and full disarm restores that baseline rather than the app's own masks. That
  is what makes "restore normal device mode" meaningful rather than cosmetic.
- Recovery was implemented **before** any restrictive test was attempted, is idempotent, works
  without a valid schedule, and is present on every management screen. On the emulator its
  sequence was observed to work at the API level: the runtime session ended
  (`mLockTaskModeState=NONE`), every app-owned alarm was cancelled (no pending
  `Alarm{…shutdownprotection` entries), the app's lock-task policy was cleared, and the app
  reported `DISARMED: Normal device mode restored` only after that verification.
- **Scope limit, stated plainly:** the *decisive* escape-route check — confirming the power menu
  actually returns after `dpm remove-active-admin` — is **NOT TESTED**, because the menu cannot
  be invoked on the only available emulator image and no physical device was attached
  (`docs/TEST_RESULTS.md` §5). Brief section 21 requires a verified escape route for a Gate A
  PASS; the provisioning half passed, the menu half is blocked. Recovery is therefore verified as
  a *policy and session* operation, **not** as an observed restoration of the power menu.
- A factory reset is treated as a last-resort destructive action requiring explicit per-device
  authorization; no factory reset was performed for this project.

[UNVERIFIED — no primary source] Questions a deployment plan must answer:

- Which enrollment method is actually available for a **custom** DPC in the intended
  distribution model (QR / NFC / zero-touch / managed Google account). The brief explicitly
  warns: *do not assume QR enrollment for a custom DPC is automatically available for every
  distribution model.*
- Whether the intended audience can obtain the enrollment credentials at all, and who owns the
  management relationship afterwards.
- What the supported off-boarding / device-repurposing procedure is for a device whose owner
  app has no `testOnly` escape hatch.

## 4. Supported deployment audience — [LOCAL] judgement, grounded in observed behaviour

This architecture fits **a dedicated, single-purpose managed device** whose operator accepts a
Lock Task session running for the whole armed period. Observed consequences of that choice:

- Quick Settings is expected to remain unavailable under the documented notifications feature.
  It is recorded as a known limitation, not a bug to be fixed with unrelated flags.
- Permission dialogs, authentication handoffs, share sheets, document pickers, and camera
  handoffs are separate compatibility cases; allowlisting a package is not proof that every
  workflow involving it works. These are enumerated in `docs/COMPATIBILITY_MATRIX.md`.
- A reboot does **not** resume protection by itself in the baseline MVP. The supported route is:
  the user unlocks, opens the app, and explicitly resumes. Automatic unattended re-entry and
  protection before the first unlock are **separate objectives that this baseline does not
  meet**, and the brief requires that to be stated rather than glossed.
- There is no hard bounded release after a force-stop or after exact-alarm permission is
  revoked. This architecture does not establish that guarantee, and the brief forbids claiming
  it.

**It is not an ordinary unrestricted personal-phone product.** If a customer's requirement is a
normal phone experience with only the power menu restricted, this architecture does not satisfy
it, and the honest answer is that the requirement is unmet rather than that the product needs
polish.

## 5. Exact-alarm capability is a real deployment prerequisite — [LOCAL, verified]

Observed on the emulator, and worth recording because it contradicts the common assumption that
a Device Owner is automatically exempt:

- With `SCHEDULE_EXACT_ALARM` declared in the manifest and the app provisioned as Device Owner,
  the app reported **Exact scheduling: Unavailable** and refused to arm — the designed
  behaviour, since the coordinator checks `canScheduleExactAlarms()` and takes the
  detected-error recovery path rather than newly restricting.
- `adb shell cmd appops get com.example.shutdownprotection SCHEDULE_EXACT_ALARM` reported
  `Default mode: default`, i.e. the special access was **not** granted.
- After `adb shell cmd appops set com.example.shutdownprotection SCHEDULE_EXACT_ALARM allow`,
  the app reported **Exact scheduling: Available**.
- The documented per-app settings screen resolves on this build: the intent
  `android.settings.REQUEST_SCHEDULE_EXACT_ALARM` with data `package:<app id>` resolves to
  `com.android.settings/.Settings$AlarmsAndRemindersAppActivity`.

Deployment consequence: a production rollout must either grant Alarms & reminders access during
provisioning or verify that the intended device/build genuinely exempts the DPC. The app is
built to fail safe (permissive, disarmed, with a clear corrective action) when it is not
available. Whether a given OEM/build exempts a Device Owner is **[UNVERIFIED — no primary
source]** and must be tested per model.

## 6. Package visibility — [LOCAL, verified]

The app deliberately does **not** declare `QUERY_ALL_PACKAGES`. Package visibility uses a narrow
`<queries>` block covering only the two launcher-shaped intent filters:

```xml
<queries>
    <intent><action android:name="android.intent.action.MAIN" />
            <category android:name="android.intent.category.HOME" /></intent>
    <intent><action android:name="android.intent.action.MAIN" />
            <category android:name="android.intent.category.LAUNCHER" /></intent>
</queries>
```

That is exactly what the allowed-applications picker needs: it resolves the device's **actual**
default launcher package on the device rather than assuming a manufacturer's package name, and
it shows both the package name and the app label for every selected entry.

[UNVERIFIED — no primary source] Whether this narrow query shape satisfies current Play policy
for the intended distribution, and what the declaration requirements are if the app is ever
distributed on Google Play. The brief treats Play availability as a **research deliverable, not
a release guarantee**.

## 7. Google Play availability and enrollment feasibility — [UNVERIFIED — no primary source]

This section is deliberately a list of questions, because answering it requires current primary
documentation that could not be fetched. **Nothing here should be read as a finding.**

To be answered from primary sources before any production or distribution decision:

1. What the current Play policy requires of an app whose primary purpose is applying device
   policy through Device Owner provisioning. Which policy area governs it, and does the
   declared permission set (`SCHEDULE_EXACT_ALARM`, `RECEIVE_BOOT_COMPLETED`) and the narrow
   `<queries>` block raise any declaration requirement. `POST_NOTIFICATIONS` is **not**
   declared, because no status notification is implemented — see `docs/ARCHITECTURE.md` §10.
2. Whether the intended distribution is Play at all. A DPC is frequently distributed outside
   Play as an enterprise-managed app, which changes the answer entirely.
3. Which managed-enrollment method is available for a custom DPC (QR, NFC, zero-touch,
   managed Google account), and what the enrollment prerequisites are for each.
4. Whether a **work profile** could be used instead of full Device Owner. The brief is explicit:
   *do not assume a work profile provides the same device-wide behaviour as the supported Device
   Owner deployment.* The power menu is a device-global surface, so a work profile is not
   expected to be equivalent — but that expectation itself must be confirmed, not assumed.
5. What the supported device models and OS builds are. The brief requires per-model reporting:
   one Pixel result does not establish all Android 16 devices, and Samsung/other OEM
   compatibility is only claimed for each model actually tested.

## 8. What would have to be true before a production release

Not a promise — a checklist of unmet conditions:

1. Every required-baseline item in the brief's §27 acceptance matrix passed **on a named
   physical device and build**, with the observed power-menu suppression and restoration
   recorded as manual evidence.
2. The Gate C usability matrix filled in on that device, with an explicit statement of whether
   the resulting restrictions are acceptable for the intended MVP.
3. Gate D and Gate E run on that device, including idle/Doze transitions measured against the
   60-second project threshold and honest reporting of force-stop and permission-loss limits.
4. A production (non-`testOnly`) build with a deliberately designed off-boarding route, since
   `dpm remove-active-admin` depends on the debug `testOnly` flag.
5. Alarms & reminders access granted during provisioning, or a documented per-model exemption.
6. The §7 questions above answered from current primary documentation.
7. An explicit product decision that a dedicated-device experience is acceptable, because that
   is what this architecture delivers.
