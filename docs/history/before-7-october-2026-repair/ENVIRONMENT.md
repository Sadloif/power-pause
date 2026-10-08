# ENVIRONMENT — Scheduled Power Menu Restriction

Spec: `docs/Agent-Brief-v2.0.md` (Brief v2.0, SHA256 `730849da…`).
Work folder: `E:\Deepseek\Projects\Scheduled-Power-Menu-Restriction\`.
Re-verified: 2026-10-06 (toolchain, emulator acceleration, device state, `android.jar` hash).

This file answers brief section 4 (steps 1–9). Every row below was verified by running a
command or reading a file in this session; nothing is carried over as an assumption.

---

## 1. Repository instructions, existing code, current modifications

- Workspace root `E:\Deepseek` is governed by `AGENTS.md` (folder discipline, do-not-move
  list, script path convention). The project lives under the existing `Projects\` category.
- **Existing code at start of this session:** a Gradle skeleton that already built a debug
  APK, plus `docs/ENVIRONMENT.md`, `docs/plans/2026-10-06-gate-a-b-poc.md`, and a copy of the
  brief. There was **no Kotlin source at all** — no receiver, no activity, no admin XML.
- **Preserved:** `docs/plans/2026-10-06-gate-a-b-poc.md` and the copied brief are left in
  place unchanged. Two claims in the plan were later found wrong and are corrected in
  `docs/API_FACTS.md` (see §9).
- `E:\Deepseek\Linksi\` is a self-contained project (its git repo is `Linksi\repo\`). It is
  used **read-only as a toolchain donor** (JDK + Android SDK + proven Gradle/AGP/Kotlin
  versions). Nothing inside it was reorganized, moved, or provisioned.
- Prior art for conventions only: `E:\Deepseek\Projects\Android-Secure-Screenshot\`.

## 2. Tool availability (verified)

No system JDK, Gradle, or ADB on `PATH` (`java`, `adb`, `gradle` are all unresolvable), so
every build and test command sets `JAVA_HOME` and calls the SDK tools by absolute path.

| Tool | Version | Location | Evidence |
|---|---|---|---|
| JDK | Temurin **17.0.20.1+1** (OpenJDK 17) | `E:\Deepseek\Linksi\toolchain\jdk-17` | `java.exe -version` |
| ADB | 1.0.41, version **37.0.1**-15733141 | `…\android-sdk\platform-tools\adb.exe` | `adb version` |
| Emulator | **37.2.9.0** (build 16322952) | `…\android-sdk\emulator` | `emulator -version` |
| Emulator acceleration | **WHPX usable** (host 10.0.26200) | — | `emulator-check.exe accel` → exit 0, "WHPX(10.0.26200) is installed and usable." |
| SDK platform | **android-36**, Android 16, rev 2, ext level 17 | `…\android-sdk\platforms\android-36` | `source.properties` |
| SDK platforms also present | android-34, android-35 | `…\android-sdk\platforms` | directory listing |
| Build-tools | 34.0.0, 35.0.0, **36.0.0** | `…\android-sdk\build-tools` | `source.properties` |
| cmdline-tools | `latest` (avdmanager, sdkmanager, lint) | `…\android-sdk\cmdline-tools\latest` | directory listing |
| System image | **android-36 / google_apis / x86_64**, rev 7 | `…\system-images\android-36\google_apis\x86_64` | `source.properties` (`AndroidVersion.ApiLevel=36`, `SystemImage.Abi=x86_64`) |
| `android.jar` (platform 36) | 27,768,026 bytes, SHA256 `D9EB9DA824D9E247A352F570F01E1169E725B2954BCA9E283A71786C59B59F9A` | `…\platforms\android-36\android.jar` | `Get-FileHash` |

## 3. Pinned build tool versions and why

| Component | Pinned version | Rationale |
|---|---|---|
| Gradle | **8.13** | The wrapper distribution is already cached locally and is the version the proven `Linksi` build uses. No download needed, no dynamic version. |
| Android Gradle Plugin | **8.13.2** | Proven against compileSdk 36 in the `Linksi` build; present in the warm local Gradle module cache. |
| Kotlin | **2.0.21** | Same provenance. Matches the Compose compiler plugin version, which must track the Kotlin version exactly. |
| Compose compiler plugin | **2.0.21** | Must equal the Kotlin version. |
| compileSdk / minSdk / targetSdk | **36 / 36 / 36** | Brief section 3.1. The `Linksi` precedent uses `minSdk 26`; that lower minimum belongs to Linksi and was deliberately **not** copied. |
| Compose BOM | **2024.10.00** | The BOM present in the local cache; it is a BOM, so the individual Compose artifacts are not separately versioned. |
| DataStore Preferences | **1.0.0** | Present in the local cache. |
| kotlinx-coroutines | **1.7.3** | Matches the `Linksi` precedent. |

All dependency versions are fixed literals. There are no dynamic (`+`) versions and no
guessed future versions. The full list is in `app/build.gradle.kts`.

### Host-specific build settings that are not version choices

- `kotlin.compiler.execution.strategy=in-process` — the Kotlin compile daemon needs to write
  a timestamp marker under `%LOCALAPPDATA%\kotlin\daemon`, which is outside this workspace
  and not writable here (`java.nio.file.AccessDeniedException` on
  `kotlin-daemon-client-*.tmp`). Gradle falls back to in-process compilation anyway; pinning
  the strategy removes the noisy retry. It does not change what is compiled.
- `DEBUG_KEYSTORE_PATH` — AGP normally writes `~/.android/debug.keystore`; a keystore is
  supplied from `_working\debug.keystore` so the build never depends on a writable home.
- `android.experimental.androidTest.useUnifiedTestPlatform=false` — AGP's Unified Test
  Platform keeps scratch state outside the project directory, which fails in restricted
  environments. The legacy runner writes results under `app/build` and reports the same JUnit
  XML.

### Network

Outbound HTTPS **does** work from Gradle and from Python (`urllib` reached Maven Central with
HTTP 200). `Invoke-WebRequest` and `curl.exe` both fail on this host with a connection-closed
error, so they must not be used as a network probe — a `000` from `curl.exe` here is a
tooling artifact, not evidence of an offline machine. Dependency resolution therefore uses
the network normally, with a warm local cache in `_working\gradle-home`.

## 4. Available emulators and connected test devices

Live state at the time of writing (re-checked after the emulator run; see `docs/TEST_RESULTS.md`):

| Surface | State |
|---|---|
| AVD `spm-test-36` | **Created and booting.** Hand-written under `_working\avd\spm-test-36.ini` + `_working\avd\spm-test-36.avd\config.ini`, because `avdmanager create avd` fails on this machine with `Error: "emulator" package must be installed!` (the SDK has no `emulator/package.xml`). Creating that metadata file would mean modifying the shared donor SDK, so it was not done. |
| `avdmanager list avd` | Still reports **none**, because the AVD is not in the default `%USERPROFILE%\.android\avd`. `ANDROID_AVD_HOME` must point at `_working\avd`; `emulator -list-avds` then reports `spm-test-36`. |
| Emulator home redirection | Required. `ANDROID_EMULATOR_HOME` and `ANDROID_USER_HOME` are redirected into `_working\` because the default `%USERPROFILE%\.android` is outside the writable workspace and the emulator otherwise loops on `error: 5` creating `emu-last-feature-flags.protobuf.lock`. |
| Connected devices | `adb devices -l` → `emulator-5554  device  product:sdk_gphone64_x86_64 model:sdk_gphone64_x86_64 device:emu64xa` |
| Boot state | `sys.boot_completed=1` |
| Emulator acceleration | WHPX usable (see §2) |
| System image | `system-images;android-36;google_apis;x86_64` is the **only** API 36 image available; there is no AOSP/`default` image. This matters: on this GMS image the global-actions gesture is pinned to the Google Assistant, which is why the menu cannot be observed here (`docs/TEST_RESULTS.md` §5.2). |

## 5. Dedicated physical test phone

**Not attached.** No personal phone has been provisioned, and none will be: brief section 4.5
forbids provisioning a primary personal phone for unattended testing. A dedicated test phone has
been offered by the operator and will be used once attached; until then every physical-device item
is **BLOCKED / NOT TESTED**, which is a missing test surface, not evidence that the feature fails
(brief section 4.9).

Emulator evidence is explicitly preliminary (brief section 22) and is never recorded as a
physical PASS.

## 6. Device Owner state

- **Currently provisioned for testing.** `dpm list-owners` →
  `1 owner: User 0: admin=com.example.shutdownprotection/.admin.ShutdownProtectionAdminReceiver,DeviceOwner,Affiliated`
- The exact working provisioning sequence, including the misleading failure that preceded it, is
  recorded in `docs/TEST_RESULTS.md` §4.1.
- Ownership was also observed to survive a full `adb reboot`.
- The app has been removed and re-provisioned several times during Gate A, and its data cleared
  once to capture the fresh-install "Device Owner: Required" state.
- Standing rules applied: never remove someone else's device management (brief section 4.6);
  never factory reset any device without explicit per-device authorization (brief section 4.7).
  No factory reset has been performed.

## 7. Official references (brief section 29)

The listed developer.android.com pages could not be fetched as prose: the harness web-search
tool returned HTTP 402 (insufficient balance) and the host's PowerShell HTTP stack is broken
(§3). Rather than guess, the API surface was verified against the **local platform 36
`android.jar`** with `javap`, which is the same artifact the compiler checks against.

Result: `docs/API_FACTS.md` — every method signature and constant value used by this project,
with the raw `javap` output quoted as evidence, plus an explicit
**UNVERIFIED (no local source)** section listing everything a stub jar cannot answer
(behavioural prose, permission-requirement documentation, target-SDK registration rules).

Two claims in the pre-existing plan document were **wrong** and are corrected there:

1. It stated that API 36 defines no lock-task policy-identifier constant. That is true only of
   `DevicePolicyManager`; `DevicePolicyIdentifiers.LOCK_TASK_POLICY = "lockTask"` exists.
2. It called the policy result codes `RESULT_CODE_*`. The real prefix is `RESULT_*`
   (`RESULT_POLICY_SET`, `RESULT_FAILURE_CONFLICTING_ADMIN_POLICY`, …). Code using
   `RESULT_CODE_*` would not compile.

Confirmed as originally claimed: masks 47 / 63, `LOCK_TASK_FEATURE_GLOBAL_ACTIONS = 16`,
`LOCK_TASK_FEATURE_BLOCK_ACTIVITY_START_IN_TASK = 64` (never set),
`isLockTaskAllowed` does not exist, `PolicyUpdateReceiver.onReceive` is `final`, and
`setLockTaskPackages` is the only one of the nine methods declaring `SecurityException`.

## 8. Exact command template

```powershell
$env:JAVA_HOME      = "E:\Deepseek\Linksi\toolchain\jdk-17"
$env:ANDROID_HOME   = "E:\Deepseek\Linksi\toolchain\android-sdk"
$env:GRADLE_USER_HOME = "E:\Deepseek\Projects\Scheduled-Power-Menu-Restriction\_working\gradle-home"
$env:DEBUG_KEYSTORE_PATH = "E:\Deepseek\Projects\Scheduled-Power-Menu-Restriction\_working\debug.keystore"
Set-Location "E:\Deepseek\Projects\Scheduled-Power-Menu-Restriction"

.\gradlew.bat :app:assembleDebug
.\gradlew.bat :app:testDebugUnitTest
.\gradlew.bat :app:lintDebug
```

`local.properties` pins `sdk.dir` to the donor SDK; it is git-ignored because it is
machine-specific.

## 9. Missing tooling versus unsupported behaviour

**Missing, obtainable in this project**

- Nothing outstanding for the emulator surface: the `spm-test-36` AVD exists and boots, the app
  is installed and provisioned, and the API-level behaviour has been observed.

**Missing, blocks gates**

- A dedicated physical Android 16 phone → the physical halves of Gates B, C, D and E are
  **BLOCKED / NOT TESTED**. Emulator results are preliminary and are labelled as such.
- An AOSP (non-GMS) API 36 system image → the emulator cannot invoke the global-actions dialog at
  all, because this GMS image pins long-press Power to the Google Assistant. Without either a
  different image or the physical phone, the central product behaviour **cannot be observed** on
  any surface available here.

**Observed platform behaviour that constrains the design**

Two runtime findings are recorded rather than assumed, both in `docs/TEST_RESULTS.md`:

1. A provisioned Device Owner is **not** automatically exempt from exact-alarm special access on
   this build: `canScheduleExactAlarms()` returned false until
   `cmd appops set … SCHEDULE_EXACT_ALARM allow`. The app correctly refused to arm in that state.
2. The power-menu gesture is pinned by the GMS configuration overlay
   (`WindowManager: powerLongPress: mLongPressOnPowerBehavior=5`), and
   `Settings.Secure.power_button_long_press` does not override it, even across a reboot.

No result is fabricated: every untested item is `NOT TESTED`, and the app itself reports
"Device behavior not yet validated" until a manual observation is recorded in
`docs/TEST_RESULTS.md`.
