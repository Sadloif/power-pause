# API FACTS — verified from local `android.jar` via `javap`

**Scope.** The original sections record signatures and constants from local Android SDK 36
`android.jar`. On 7 October 2026, the lock-task identifier linkage was additionally checked
against official Android documentation. That correction is labelled below; a documented API
contract does not establish observed behaviour on a particular phone.

---

## 0. Provenance

| Item | Value |
|---|---|
| `android.jar` path | `E:\Deepseek\Linksi\toolchain\android-sdk\platforms\android-36\android.jar` |
| File size | `27,768,026` bytes |
| Last write time (as reported by the filesystem) | `1/1/2008 12:00:00 AM` |
| SHA256 | `D9EB9DA824D9E247A352F570F01E1169E725B2954BCA9E283A71786C59B59F9A` |
| `javap` executable | `E:\Deepseek\Linksi\toolchain\jdk-17\bin\javap.exe` |
| `javap -version` | `17.0.20.1` |
| Jar entry count | `14996` |

Command form used (PowerShell):

```powershell
& "E:\Deepseek\Linksi\toolchain\jdk-17\bin\javap.exe" `
  -classpath "E:\Deepseek\Linksi\toolchain\android-sdk\platforms\android-36\android.jar" `
  -constants -public android.app.admin.DevicePolicyManager
```

For every class below, output was captured with **both** `-constants -public` and `-constants -p`
(all members). A `Compare-Object` of the two captures was used to prove that no non-public
overload of a relevant member exists. Where that diff showed anything beyond an implicit
constructor or `static {}` initializer, it is noted in the relevant section.

`javap -v -p android.provider.Settings` was additionally run for the annotation check in §F.3.

### Prior fact-sheet check (first step)

Globbed `docs\**` for `*API*`, `*FACT*`, `*fact*` (case-insensitive variants) under
`E:\Deepseek\Projects\Scheduled-Power-Menu-Restriction\`.

**Result: no prior API fact-sheet exists.** The only files under `docs\` are:

- `docs\Agent-Brief-v2.0.md` (63,253 bytes)
- `docs\ENVIRONMENT.md` (5,927 bytes)
- `docs\plans\2026-10-06-gate-a-b-poc.md` (16,715 bytes)

Two of those files make API claims that overlap this task, so they were grepped and are compared
against the `javap` evidence in the **Contradictions found** section at the end. `API_FACTS.md`
was therefore written from scratch, not extended from a predecessor.

---

## A. `android.app.admin.DevicePolicyManager`

Class declaration as printed:

```
Compiled from "DevicePolicyManager.java"
public class android.app.admin.DevicePolicyManager {
```

`Compare-Object` between `-public` and `-p` output showed exactly one extra line with `-p`:
`android.app.admin.DevicePolicyManager();` (the implicit constructor). **There are no protected or
private members relevant to lock task.**

### A.1 Exact signatures, return types, declared exceptions

Every line below is quoted verbatim from `javap -constants -public`. Where a line has no `throws`
clause, `javap` printed no `throws` clause — meaning **no checked exception is declared in the
class file**. Runtime exceptions that are not declared cannot be seen by `javap` at all.

| Method | Verbatim `javap` line | Declared exceptions |
|---|---|---|
| `isDeviceOwnerApp` | `public boolean isDeviceOwnerApp(java.lang.String);` | none printed |
| `isLockTaskPermitted` | `public boolean isLockTaskPermitted(java.lang.String);` | none printed |
| `setLockTaskPackages` | `public void setLockTaskPackages(android.content.ComponentName, java.lang.String[]) throws java.lang.SecurityException;` | **`java.lang.SecurityException`** |
| `getLockTaskPackages` | `public java.lang.String[] getLockTaskPackages(android.content.ComponentName);` | none printed |
| `setLockTaskFeatures` | `public void setLockTaskFeatures(android.content.ComponentName, int);` | none printed |
| `getLockTaskFeatures` | `public int getLockTaskFeatures(android.content.ComponentName);` | none printed |
| `isAdminActive` | `public boolean isAdminActive(android.content.ComponentName);` | none printed |
| `setProfileEnabled` | `public void setProfileEnabled(android.content.ComponentName);` | none printed |
| `clearDeviceOwnerApp` | `public void clearDeviceOwnerApp(java.lang.String);` | none printed |

(`setLockTaskPackages` appears once in the task list twice; it is declared exactly once in the
class file — there is no overload.)

Exact `javap` line numbers in the captured `-constants -public` output, for reproducibility:

```
206:   public void clearDeviceOwnerApp(java.lang.String);
254:   public int getLockTaskFeatures(android.content.ComponentName);
255:   public java.lang.String[] getLockTaskPackages(android.content.ComponentName);
325:   public boolean isAdminActive(android.content.ComponentName);
335:   public boolean isDeviceOwnerApp(java.lang.String);
338:   public boolean isLockTaskPermitted(java.lang.String);
415:   public void setLockTaskFeatures(android.content.ComponentName, int);
416:   public void setLockTaskPackages(android.content.ComponentName, java.lang.String[]) throws java.lang.SecurityException;
455:   public void setProfileEnabled(android.content.ComponentName);
```

`setLockTaskPackages` is the **only** one of these nine methods with a declared `throws` clause.
All of the above are instance (non-static) methods.

### A.2 `isLockTaskAllowed` — **ABSENT**

`javap -constants -public android.app.admin.DevicePolicyManager` printed **no member whose name
contains `isLockTaskAllowed`**. A `Select-String -SimpleMatch 'isLockTaskAllowed'` across the
`-public` and `-p` captures of every class dumped for this task returned **zero matches**.

**Finding: the method `isLockTaskAllowed` does not exist on `DevicePolicyManager` in
`android.jar` platform 36. The correct member is `isLockTaskPermitted(java.lang.String)`.**

### A.3 `LOCK_TASK_FEATURE_*` constant values

Verbatim from `javap -constants -public android.app.admin.DevicePolicyManager`:

```
  public static final int LOCK_TASK_FEATURE_BLOCK_ACTIVITY_START_IN_TASK = 64;
  public static final int LOCK_TASK_FEATURE_GLOBAL_ACTIONS = 16;
  public static final int LOCK_TASK_FEATURE_HOME = 4;
  public static final int LOCK_TASK_FEATURE_KEYGUARD = 32;
  public static final int LOCK_TASK_FEATURE_NONE = 0;
  public static final int LOCK_TASK_FEATURE_NOTIFICATIONS = 2;
  public static final int LOCK_TASK_FEATURE_OVERVIEW = 8;
  public static final int LOCK_TASK_FEATURE_SYSTEM_INFO = 1;
```

| Constant | Value |
|---|---|
| `LOCK_TASK_FEATURE_NONE` | `0` |
| `LOCK_TASK_FEATURE_SYSTEM_INFO` | `1` |
| `LOCK_TASK_FEATURE_NOTIFICATIONS` | `2` |
| `LOCK_TASK_FEATURE_HOME` | `4` |
| `LOCK_TASK_FEATURE_OVERVIEW` | `8` |
| `LOCK_TASK_FEATURE_GLOBAL_ACTIONS` | `16` |
| `LOCK_TASK_FEATURE_KEYGUARD` | `32` |
| `LOCK_TASK_FEATURE_BLOCK_ACTIVITY_START_IN_TASK` | `64` |

These are **exactly eight** constants; a search for `LOCK_TASK_FEATURE` across all dumped classes
returned only these eight lines, all from `DevicePolicyManager`. There is no
`LOCK_TASK_FEATURE_*` constant on any other class dumped.

### A.4 Computed mask values

Computed by bitwise OR from the values in §A.3 (arithmetic on observed values, no external
input):

```
protectedFeatures = SYSTEM_INFO(1) | NOTIFICATIONS(2) | HOME(4) | OVERVIEW(8) | KEYGUARD(32)
                  = 1 | 2 | 4 | 8 | 32
                  = 47          (0b101111)

allowedFeatures   = protectedFeatures(47) | GLOBAL_ACTIONS(16)
                  = 47 | 16
                  = 63          (0b111111)
```

| Expression | Value |
|---|---|
| `protectedFeatures` | **47** |
| `allowedFeatures` | **63** |

Note that `allowedFeatures` = 63 equals `protectedFeatures` + `GLOBAL_ACTIONS` only because bit
`16` is unset in `protectedFeatures`. `LOCK_TASK_FEATURE_BLOCK_ACTIVITY_START_IN_TASK` (64) is
**not** included in either mask.

### A.5 Policy-identifier constants

Verbatim from `javap -constants -public android.app.admin.DevicePolicyManager`:

```
  public static final java.lang.String POLICY_DISABLE_CAMERA = "policy_disable_camera";
  public static final java.lang.String POLICY_DISABLE_SCREEN_CAPTURE = "policy_disable_screen_capture";
```

| Constant | Exists | Value |
|---|---|---|
| `POLICY_DISABLE_CAMERA` | **yes** | `"policy_disable_camera"` |
| `POLICY_DISABLE_SCREEN_CAPTURE` | **yes** | `"policy_disable_screen_capture"` |

A search for `POLICY_` across the whole `-public` capture of `DevicePolicyManager` returned
**nine** lines. Only two are policy-identifier string constants; the rest are unrelated:

```
4:   public static final java.lang.String ACTION_ADMIN_POLICY_COMPLIANCE = "android.app.action.ADMIN_POLICY_COMPLIANCE";
6:   public static final java.lang.String ACTION_CHECK_POLICY_COMPLIANCE = "android.app.action.CHECK_POLICY_COMPLIANCE";
10:  public static final java.lang.String ACTION_DEVICE_POLICY_RESOURCE_UPDATED = "android.app.action.DEVICE_POLICY_RESOURCE_UPDATED";
20:  public static final java.lang.String ACTION_SYSTEM_UPDATE_POLICY_CHANGED = "android.app.action.SYSTEM_UPDATE_POLICY_CHANGED";
164: public static final int PERMISSION_POLICY_AUTO_DENY = 2;
165: public static final int PERMISSION_POLICY_AUTO_GRANT = 1;
166: public static final int PERMISSION_POLICY_PROMPT = 0;
170: public static final java.lang.String POLICY_DISABLE_CAMERA = "policy_disable_camera";
171: public static final java.lang.String POLICY_DISABLE_SCREEN_CAPTURE = "policy_disable_screen_capture";
```

**Finding: `DevicePolicyManager` declares NO policy-identifier constant relating to lock task.**
There is no `POLICY_LOCK_TASK` on `DevicePolicyManager`.

**However — see the important adjacent finding in §A.7: a lock-task policy identifier string
*does* exist, on a different class (`DevicePolicyIdentifiers`).**

### A.6 `ACTION_*` string constants on `DevicePolicyManager`

Complete list of every `ACTION_*` constant printed by
`javap -constants -public android.app.admin.DevicePolicyManager` (18 lines total):

| Constant | Value |
|---|---|
| `ACTION_ADD_DEVICE_ADMIN` | `"android.app.action.ADD_DEVICE_ADMIN"` |
| `ACTION_ADMIN_POLICY_COMPLIANCE` | `"android.app.action.ADMIN_POLICY_COMPLIANCE"` |
| `ACTION_APPLICATION_DELEGATION_SCOPES_CHANGED` | `"android.app.action.APPLICATION_DELEGATION_SCOPES_CHANGED"` |
| `ACTION_CHECK_POLICY_COMPLIANCE` | `"android.app.action.CHECK_POLICY_COMPLIANCE"` |
| `ACTION_DEVICE_ADMIN_SERVICE` | `"android.app.action.DEVICE_ADMIN_SERVICE"` |
| `ACTION_DEVICE_FINANCING_STATE_CHANGED` | `"android.app.admin.action.DEVICE_FINANCING_STATE_CHANGED"` |
| `ACTION_DEVICE_OWNER_CHANGED` | `"android.app.action.DEVICE_OWNER_CHANGED"` |
| `ACTION_DEVICE_POLICY_RESOURCE_UPDATED` | `"android.app.action.DEVICE_POLICY_RESOURCE_UPDATED"` |
| `ACTION_GET_PROVISIONING_MODE` | `"android.app.action.GET_PROVISIONING_MODE"` |
| `ACTION_MANAGED_PROFILE_PROVISIONED` | `"android.app.action.MANAGED_PROFILE_PROVISIONED"` |
| `ACTION_PROFILE_OWNER_CHANGED` | `"android.app.action.PROFILE_OWNER_CHANGED"` |
| `ACTION_PROVISIONING_SUCCESSFUL` | `"android.app.action.PROVISIONING_SUCCESSFUL"` |
| `ACTION_PROVISION_MANAGED_DEVICE` | `"android.app.action.PROVISION_MANAGED_DEVICE"` |
| `ACTION_PROVISION_MANAGED_PROFILE` | `"android.app.action.PROVISION_MANAGED_PROFILE"` |
| `ACTION_SET_NEW_PARENT_PROFILE_PASSWORD` | `"android.app.action.SET_NEW_PARENT_PROFILE_PASSWORD"` |
| `ACTION_SET_NEW_PASSWORD` | `"android.app.action.SET_NEW_PASSWORD"` |
| `ACTION_START_ENCRYPTION` | `"android.app.action.START_ENCRYPTION"` |
| `ACTION_SYSTEM_UPDATE_POLICY_CHANGED` | `"android.app.action.SYSTEM_UPDATE_POLICY_CHANGED"` |

**Lock task:** no `ACTION_*` constant on `DevicePolicyManager` relates to lock task. Searching
`ACTION_LOCK_TASK` across every dumped class matched only `DeviceAdminReceiver`
(see §C.2), never `DevicePolicyManager`. **ABSENT on `DevicePolicyManager`.**

**Device-owner / admin:** the related constants are `ACTION_DEVICE_OWNER_CHANGED`,
`ACTION_PROFILE_OWNER_CHANGED`, `ACTION_ADD_DEVICE_ADMIN`, `ACTION_DEVICE_ADMIN_SERVICE`,
`ACTION_MANAGED_PROFILE_PROVISIONED`, `ACTION_PROVISION_MANAGED_DEVICE`,
`ACTION_PROVISION_MANAGED_PROFILE`, `ACTION_PROVISIONING_SUCCESSFUL`. Their values are as tabled
above. No `ACTION_*` constant with `LOCK_TASK` in the name exists here.

### A.7 Adjacent class `android.app.admin.DevicePolicyIdentifiers` (not requested, but material)

Because §A.5 asks specifically about lock-task policy identifiers, this class was inspected. It
**does** exist in the jar (`android/app/admin/DevicePolicyIdentifiers.class`) and contains:

```
Compiled from "DevicePolicyIdentifiers.java"
public final class android.app.admin.DevicePolicyIdentifiers {
  ...
  public static final java.lang.String LOCK_TASK_POLICY = "lockTask";
  ...
  public static java.lang.String getIdentifierForUserRestriction(java.lang.String);
}
```

| Constant | Value |
|---|---|
| `DevicePolicyIdentifiers.LOCK_TASK_POLICY` | `"lockTask"` |

**Finding: a lock-task policy identifier string DOES exist in platform 36 — but on
`DevicePolicyIdentifiers`, not on `DevicePolicyManager`.** Full constant list of this class:

`ACCOUNT_MANAGEMENT_DISABLED_POLICY="accountManagementDisabled"`,
`APPLICATION_HIDDEN_POLICY="applicationHidden"`,
`APPLICATION_RESTRICTIONS_POLICY="applicationRestrictions"`,
`APP_FUNCTIONS_POLICY="appFunctions"`, `AUTO_TIMEZONE_POLICY="autoTimezone"`,
`AUTO_TIME_POLICY="autoTime"`, `BACKUP_SERVICE_POLICY="backupService"`,
`CAMERA_DISABLED_POLICY="cameraDisabled"`, `CONTENT_PROTECTION_POLICY="contentProtection"`,
`KEYGUARD_DISABLED_FEATURES_POLICY="keyguardDisabledFeatures"`,
`LOCK_TASK_POLICY="lockTask"`, `PACKAGES_SUSPENDED_POLICY="packagesSuspended"`,
`PACKAGE_UNINSTALL_BLOCKED_POLICY="packageUninstallBlocked"`,
`PASSWORD_COMPLEXITY_POLICY="passwordComplexity"`, `PERMISSION_GRANT_POLICY="permissionGrant"`,
`PERSISTENT_PREFERRED_ACTIVITY_POLICY="persistentPreferredActivity"`,
`RESET_PASSWORD_TOKEN_POLICY="resetPasswordToken"`, `SECURITY_LOGGING_POLICY="securityLogging"`,
`STATUS_BAR_DISABLED_POLICY="statusBarDisabled"`,
`USB_DATA_SIGNALING_POLICY="usbDataSignaling"`,
`USER_CONTROL_DISABLED_PACKAGES_POLICY="userControlDisabledPackages"`.

**Documentation correction, 7 October 2026:** Android documents
`DevicePolicyIdentifiers.LOCK_TASK_POLICY` as the identifier for `setLockTaskPackages`, with
value `"lockTask"`. The application should compare callbacks with this public constant.
The identifier is verified from [official Android documentation](https://developer.android.com/reference/android/app/admin/DevicePolicyIdentifiers#LOCK_TASK_POLICY).
Delivery and enforcement on an actual device still require physical testing.

---

## B. `android.app.admin.PolicyUpdateReceiver`

### B.1 Superclass

```
Compiled from "PolicyUpdateReceiver.java"
public abstract class android.app.admin.PolicyUpdateReceiver extends android.content.BroadcastReceiver {
```

- Superclass: **`android.content.BroadcastReceiver`**
- The class itself is declared **`abstract`**.

### B.2 Every declared method

Complete member list (the `-public` and `-p` captures are byte-for-byte identical; there are **no
private or protected members**):

```
  public static final java.lang.String ACTION_DEVICE_POLICY_CHANGED = "android.app.admin.action.DEVICE_POLICY_CHANGED";
  public static final java.lang.String ACTION_DEVICE_POLICY_SET_RESULT = "android.app.admin.action.DEVICE_POLICY_SET_RESULT";
  public static final java.lang.String EXTRA_ACCOUNT_TYPE = "android.app.admin.extra.ACCOUNT_TYPE";
  public static final java.lang.String EXTRA_INTENT_FILTER = "android.app.admin.extra.INTENT_FILTER";
  public static final java.lang.String EXTRA_PACKAGE_NAME = "android.app.admin.extra.PACKAGE_NAME";
  public static final java.lang.String EXTRA_PERMISSION_NAME = "android.app.admin.extra.PERMISSION_NAME";
  public android.app.admin.PolicyUpdateReceiver();
  public void onPolicyChanged(android.content.Context, java.lang.String, android.os.Bundle, android.app.admin.TargetUser, android.app.admin.PolicyUpdateResult);
  public void onPolicySetResult(android.content.Context, java.lang.String, android.os.Bundle, android.app.admin.TargetUser, android.app.admin.PolicyUpdateResult);
  public final void onReceive(android.content.Context, android.content.Intent);
```

| Member | Modifiers | Return type | Parameters |
|---|---|---|---|
| `PolicyUpdateReceiver()` | `public` | — | — |
| `onPolicyChanged` | `public` (**not** final, **not** abstract) | `void` | `Context, String, Bundle, TargetUser, PolicyUpdateResult` |
| `onPolicySetResult` | `public` (**not** final, **not** abstract) | `void` | `Context, String, Bundle, TargetUser, PolicyUpdateResult` |
| `onReceive` | **`public final`** | `void` | `Context, Intent` |

**`onReceive` IS declared `final`** — verbatim: `public final void onReceive(android.content.Context, android.content.Intent);`

**`onReceive` is NOT declared `abstract`.** No member of this class carries the `abstract`
modifier, even though the class is abstract.

Neither `onPolicySetResult` nor `onPolicyChanged` declares a `throws` clause.

### B.3 `android.app.admin.PolicyUpdateResult`

Complete output of `javap -constants -public` (identical with `-p`):

```
Compiled from "PolicyUpdateResult.java"
public final class android.app.admin.PolicyUpdateResult {
  public static final int RESULT_FAILURE_CONFLICTING_ADMIN_POLICY = 1;
  public static final int RESULT_FAILURE_HARDWARE_LIMITATION = 4;
  public static final int RESULT_FAILURE_STORAGE_LIMIT_REACHED = 3;
  public static final int RESULT_FAILURE_UNKNOWN = -1;
  public static final int RESULT_POLICY_CLEARED = 2;
  public static final int RESULT_POLICY_SET = 0;
  public android.app.admin.PolicyUpdateResult(int);
  public int getResultCode();
}
```

Constants:

| Constant | Value |
|---|---|
| `RESULT_POLICY_SET` | `0` |
| `RESULT_FAILURE_CONFLICTING_ADMIN_POLICY` | `1` |
| `RESULT_POLICY_CLEARED` | `2` |
| `RESULT_FAILURE_STORAGE_LIMIT_REACHED` | `3` |
| `RESULT_FAILURE_HARDWARE_LIMITATION` | `4` |
| `RESULT_FAILURE_UNKNOWN` | `-1` |

Methods: `public android.app.admin.PolicyUpdateResult(int);` and `public int getResultCode();`.
These are the **only** two members besides the constants — there is no `getTargetUser()` or any
other accessor on this class.

**Naming note:** the constant prefix is `RESULT_`, **not** `RESULT_CODE_`. The class is declared
`final`.

### B.4 `android.app.admin.PolicyIdentifier`

```
javap.exe : Error: class not found: android.app.admin.PolicyIdentifier
```

A jar entry listing of all 14,996 entries filtered to `app/admin/.*(Policy|TargetUser)` returned:

```
android/app/admin/DevicePolicyIdentifiers.class
android/app/admin/DevicePolicyManager$InstallSystemUpdateCallback.class
android/app/admin/DevicePolicyManager$OnClearApplicationUserDataListener.class
android/app/admin/DevicePolicyManager.class
android/app/admin/DevicePolicyResources.class
android/app/admin/DevicePolicyResourcesManager.class
android/app/admin/FactoryResetProtectionPolicy$Builder.class
android/app/admin/FactoryResetProtectionPolicy.class
android/app/admin/ManagedSubscriptionsPolicy.class
android/app/admin/PackagePolicy.class
android/app/admin/PolicyUpdateReceiver.class
android/app/admin/PolicyUpdateResult.class
android/app/admin/SystemUpdatePolicy$ValidationFailedException.class
android/app/admin/SystemUpdatePolicy.class
android/app/admin/TargetUser.class
android/app/admin/WifiSsidPolicy.class
```

**Finding: the class `android.app.admin.PolicyIdentifier` is ABSENT from `android.jar` platform
36.** There are therefore no method signatures to report. The policy identifier is passed as a
plain `java.lang.String` (see the `onPolicySetResult` / `onPolicyChanged` signatures in §B.2).
`android.app.admin.TargetUser` **does** exist as a class entry.

### B.5 Documented requirement that `onReceive` be overridden

`javap` emits **no Javadoc and no documentation prose**. There is no source, no `-sources` jar,
and no documentation artifact in the jar that `javap` can read. Therefore:

- **No documented requirement can be observed.** `UNVERIFIED (no local source)`.
- What *can* be observed: `onReceive` is declared **`final`**, so a subclass **cannot** override
  it — attempting to do so is a compile error. This is a signature fact, not a documentation
  claim.
- Whether the platform requires `PolicyUpdateReceiver` to be declared in the manifest, and with
  which intent filters, is likewise `UNVERIFIED (no local source)`. The class does declare two
  action string constants (`ACTION_DEVICE_POLICY_SET_RESULT`,
  `ACTION_DEVICE_POLICY_CHANGED`) and four `EXTRA_*` keys, which are the only locally observable
  hints about the broadcast contract.

### B.6 Nullability annotations on both callbacks — all five parameters are `@NonNull`

This is a signature fact that is **not** visible from `javap -public` or `javap -p` alone; it
requires `javap -v`, which prints `RuntimeInvisibleParameterAnnotations`.

```
$ javap -v -classpath <platform-36>/android.jar android.app.admin.PolicyUpdateReceiver
  public void onPolicyChanged(android.content.Context, java.lang.String, android.os.Buffer, ...);
    RuntimeInvisibleParameterAnnotations:
      parameter 0: 0: #53()  android.annotation.NonNull
      parameter 1: 0: #53()  android.annotation.NonNull
      parameter 2: 0: #53()  android.annotation.NonNull
      parameter 3: 0: #53()  android.annotation.NonNull
      parameter 4: 0: #53()  android.annotation.NonNull
  public void onPolicySetResult(android.content.Context, java.lang.String, android.os.Bundle, ...);
    RuntimeInvisibleParameterAnnotations:
      parameter 0: 0: #53()  android.annotation.NonNull
      ... (all five, identically)
```

(The exact `javap` output uses `android.os.Bundle` for parameter 2; the abbreviated form above is
for readability. Re-run the command to see it verbatim.)

**Consequence for the implementation:** because every parameter is annotated `@NonNull`, Kotlin
sees non-null platform types and the overrides in `LockTaskPolicyUpdateReceiver` must declare
non-null parameter types. Declaring them nullable makes the override fail to compile with
`'onPolicySetResult' overrides nothing` — which is exactly what happened on the first build
attempt and is why the overrides in this project use non-null types.

Also observed: the parameter name in the stub is `additionalPolicyParams`, not `additionalData`.
Parameter names do not affect override validity in Kotlin, but the implementation matches the
stub's name to avoid a warning.

---

## C. `android.app.admin.DeviceAdminReceiver`

```
Compiled from "DeviceAdminReceiver.java"
public class android.app.admin.DeviceAdminReceiver extends android.content.BroadcastReceiver {
```

`-public` and `-p` captures are identical (no private/protected members).

### C.1 Requested method signatures

| Method | Verbatim `javap` line | Declared exceptions |
|---|---|---|
| `onLockTaskModeEntering` | `public void onLockTaskModeEntering(android.content.Context, android.content.Intent, java.lang.String);` | none printed |
| `onLockTaskModeExiting` | `public void onLockTaskModeExiting(android.content.Context, android.content.Intent);` | none printed |
| `onEnabled` | `public void onEnabled(android.content.Context, android.content.Intent);` | none printed |
| `onDisabled` | `public void onDisabled(android.content.Context, android.content.Intent);` | none printed |
| `onReceive` | `public void onReceive(android.content.Context, android.content.Intent);` | none printed |

`onReceive` here is **`public void`** — it is **not** `final` (contrast with
`PolicyUpdateReceiver.onReceive`, §B.2). No `throws` clause is printed for any of the five.

### C.2 String constants

Verbatim, all string constants on the class:

| Constant | Value |
|---|---|
| `ACTION_CHOOSE_PRIVATE_KEY_ALIAS` | `"android.app.action.CHOOSE_PRIVATE_KEY_ALIAS"` |
| `ACTION_DEVICE_ADMIN_DISABLED` | `"android.app.action.DEVICE_ADMIN_DISABLED"` |
| `ACTION_DEVICE_ADMIN_DISABLE_REQUESTED` | `"android.app.action.DEVICE_ADMIN_DISABLE_REQUESTED"` |
| `ACTION_DEVICE_ADMIN_ENABLED` | `"android.app.action.DEVICE_ADMIN_ENABLED"` |
| `ACTION_LOCK_TASK_ENTERING` | `"android.app.action.LOCK_TASK_ENTERING"` |
| `ACTION_LOCK_TASK_EXITING` | `"android.app.action.LOCK_TASK_EXITING"` |
| `ACTION_NETWORK_LOGS_AVAILABLE` | `"android.app.action.NETWORK_LOGS_AVAILABLE"` |
| `ACTION_PASSWORD_CHANGED` | `"android.app.action.ACTION_PASSWORD_CHANGED"` |
| `ACTION_PASSWORD_EXPIRING` | `"android.app.action.ACTION_PASSWORD_EXPIRING"` |
| `ACTION_PASSWORD_FAILED` | `"android.app.action.ACTION_PASSWORD_FAILED"` |
| `ACTION_PASSWORD_SUCCEEDED` | `"android.app.action.ACTION_PASSWORD_SUCCEEDED"` |
| `ACTION_PROFILE_PROVISIONING_COMPLETE` | `"android.app.action.PROFILE_PROVISIONING_COMPLETE"` |
| `ACTION_SECURITY_LOGS_AVAILABLE` | `"android.app.action.SECURITY_LOGS_AVAILABLE"` |
| `DEVICE_ADMIN_META_DATA` | `"android.app.device_admin"` |
| `EXTRA_DISABLE_WARNING` | `"android.app.extra.DISABLE_WARNING"` |
| `EXTRA_LOCK_TASK_PACKAGE` | `"android.app.extra.LOCK_TASK_PACKAGE"` |
| `EXTRA_TRANSFER_OWNERSHIP_ADMIN_EXTRAS_BUNDLE` | `"android.app.extra.TRANSFER_OWNERSHIP_ADMIN_EXTRAS_BUNDLE"` |

Note the `ACTION_PASSWORD_*` values are **inconsistent with their constant names**: the constant
is `ACTION_PASSWORD_CHANGED` but the string is `"android.app.action.ACTION_PASSWORD_CHANGED"`
(the redundant `ACTION_` is present in the string literal). This is what `javap -constants`
printed; it is quoted as observed.

**`BIND_DEVICE_ADMIN` is ABSENT from `DeviceAdminReceiver`.** It is not a string constant on this
class; it exists as a permission constant on `android.Manifest.permission` (see §F.2).

Non-string constants on the class: `BUGREPORT_FAILURE_FAILED_COMPLETING = 0`,
`BUGREPORT_FAILURE_FILE_NO_LONGER_AVAILABLE = 1`.

---

## D. `android.app.ActivityManager`

### D.1 `LOCK_TASK_MODE_*` values

Verbatim from `javap -constants -public android.app.ActivityManager`:

```
  public static final int LOCK_TASK_MODE_LOCKED = 1;
  public static final int LOCK_TASK_MODE_NONE = 0;
  public static final int LOCK_TASK_MODE_PINNED = 2;
```

| Constant | Value |
|---|---|
| `LOCK_TASK_MODE_NONE` | `0` |
| `LOCK_TASK_MODE_LOCKED` | `1` |
| `LOCK_TASK_MODE_PINNED` | `2` |
| `LOCK_TASK_MODE_LOCKED_SYSTEM` | **ABSENT** |

**Finding: `LOCK_TASK_MODE_LOCKED_SYSTEM` does NOT exist in `android.jar` platform 36.** A
`Select-String -SimpleMatch 'LOCK_TASK_MODE_LOCKED_SYSTEM'` across all `-public` and `-p`
captures returned zero matches. The `-public`/`-p` diff for `ActivityManager` showed only the
implicit constructor, so it is not hiding as a non-public member either.

### D.2 Lock-task state accessor signatures

```
  public int getLockTaskModeState();
  public boolean isInLockTaskMode();
```

| Method | Verbatim line | Return type | Declared exceptions |
|---|---|---|---|
| `getLockTaskModeState` | `public int getLockTaskModeState();` | `int` | none printed |
| `isInLockTaskMode` | `public boolean isInLockTaskMode();` | `boolean` | none printed |

Both are instance methods with no parameters and no declared `throws`.

### D.3 `ACTION_*` broadcast constants relating to lock task state

The **complete** set of `ACTION_*` constants on `ActivityManager` is a single line:

```
  public static final java.lang.String ACTION_REPORT_HEAP_LIMIT = "android.app.action.REPORT_HEAP_LIMIT";
```

**Finding: `ActivityManager` declares NO `ACTION_*` constant relating to lock task state.
ABSENT.** (`ACTION_REPORT_HEAP_LIMIT` is the only `ACTION_*` constant on the class and is
unrelated to lock task.)

---

## E. `android.app.AlarmManager`

```
Compiled from "AlarmManager.java"
public class android.app.AlarmManager {
```

`-public`/`-p` diff showed only the implicit constructor `android.app.AlarmManager();`.

### E.1 Requested method signatures

| Method | Verbatim `javap` line | Return type | Declared exceptions |
|---|---|---|---|
| `setExactAndAllowWhileIdle` | `public void setExactAndAllowWhileIdle(int, long, android.app.PendingIntent);` | `void` | none printed |
| `setAndAllowWhileIdle` | `public void setAndAllowWhileIdle(int, long, android.app.PendingIntent);` | `void` | none printed |
| `setAlarmClock` | `public void setAlarmClock(android.app.AlarmManager$AlarmClockInfo, android.app.PendingIntent);` | `void` | none printed |
| `canScheduleExactAlarms` | `public boolean canScheduleExactAlarms();` | `boolean` | none printed |
| `cancel` | `public void cancel(android.app.PendingIntent);` and `public void cancel(android.app.AlarmManager$OnAlarmListener);` | `void` | none printed |
| `set` | `public void set(int, long, android.app.PendingIntent);` and `public void set(int, long, java.lang.String, android.app.AlarmManager$OnAlarmListener, android.os.Handler);` | `void` | none printed |

`cancel` and `set` are each **overloaded twice** (the second `cancel` overload is printed before
the first in `javap`'s ordering):

```
  public void cancel(android.app.AlarmManager$OnAlarmListener);
  public void cancel(android.app.PendingIntent);
  public void set(int, long, android.app.PendingIntent);
  public void set(int, long, java.lang.String, android.app.AlarmManager$OnAlarmListener, android.os.Handler);
```

For completeness, the other `set*` overloads present on the class:

```
  public void setExact(int, long, android.app.PendingIntent);
  public void setExact(int, long, java.lang.String, android.app.AlarmManager$OnAlarmListener, android.os.Handler);
  public void setInexactRepeating(int, long, long, android.app.PendingIntent);
  public void setRepeating(int, long, long, android.app.PendingIntent);
  public void setWindow(int, long, long, android.app.PendingIntent);
  public void setWindow(int, long, long, java.lang.String, android.app.AlarmManager$OnAlarmListener, android.os.Handler);
  public void setWindow(int, long, long, java.lang.String, java.util.concurrent.Executor, android.app.AlarmManager$OnAlarmListener);
```

### E.2 Type constant values

| Constant | Value |
|---|---|
| `RTC_WAKEUP` | `0` |
| `RTC` | `1` |
| `ELAPSED_REALTIME_WAKEUP` | `2` |
| `ELAPSED_REALTIME` | `3` |

Verbatim:

```
  public static final int ELAPSED_REALTIME = 3;
  public static final int ELAPSED_REALTIME_WAKEUP = 2;
  public static final int RTC = 1;
  public static final int RTC_WAKEUP = 0;
```

### E.3 Exact-alarm permission state broadcast

```
  public static final java.lang.String ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED = "android.app.action.SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED";
```

| Constant | Value |
|---|---|
| `ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED` | `"android.app.action.SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED"` |

This is the **only** `ACTION_*` constant on `AlarmManager` whose name contains `ALARM`. The other
`ACTION_*` constant on the class is `ACTION_NEXT_ALARM_CLOCK_CHANGED =
"android.app.action.NEXT_ALARM_CLOCK_CHANGED"`.

---

## F. Manifest / platform constants

### F.1 `android.content.Intent` action values

Verbatim from `javap -constants -public android.content.Intent`:

```
  public static final java.lang.String ACTION_BOOT_COMPLETED = "android.intent.action.BOOT_COMPLETED";
  public static final java.lang.String ACTION_LOCKED_BOOT_COMPLETED = "android.intent.action.LOCKED_BOOT_COMPLETED";
  public static final java.lang.String ACTION_MY_PACKAGE_REPLACED = "android.intent.action.MY_PACKAGE_REPLACED";
  public static final java.lang.String ACTION_TIMEZONE_CHANGED = "android.intent.action.TIMEZONE_CHANGED";
  public static final java.lang.String ACTION_TIME_CHANGED = "android.intent.action.TIME_SET";
  public static final java.lang.String ACTION_USER_UNLOCKED = "android.intent.action.USER_UNLOCKED";
```

| Constant | Value |
|---|---|
| `ACTION_BOOT_COMPLETED` | `"android.intent.action.BOOT_COMPLETED"` |
| `ACTION_LOCKED_BOOT_COMPLETED` | `"android.intent.action.LOCKED_BOOT_COMPLETED"` |
| `ACTION_USER_UNLOCKED` | `"android.intent.action.USER_UNLOCKED"` |
| `ACTION_TIME_CHANGED` | `"android.intent.action.TIME_SET"` |
| `ACTION_TIMEZONE_CHANGED` | `"android.intent.action.TIMEZONE_CHANGED"` |
| `ACTION_MY_PACKAGE_REPLACED` | `"android.intent.action.MY_PACKAGE_REPLACED"` |

**Watch out:** `ACTION_TIME_CHANGED`'s value is `"android.intent.action.TIME_SET"`, which does
**not** match its constant name. This is exactly what `javap -constants` printed.

(`-public`/`-p` diff for `Intent` showed only `static {};`.)

### F.2 `android.Manifest.permission` field existence

All five fields exist. Verbatim:

```
  public static final java.lang.String BIND_DEVICE_ADMIN = "android.permission.BIND_DEVICE_ADMIN";
  public static final java.lang.String POST_NOTIFICATIONS = "android.permission.POST_NOTIFICATIONS";
  public static final java.lang.String RECEIVE_BOOT_COMPLETED = "android.permission.RECEIVE_BOOT_COMPLETED";
  public static final java.lang.String SCHEDULE_EXACT_ALARM = "android.permission.SCHEDULE_EXACT_ALARM";
  public static final java.lang.String USE_EXACT_ALARM = "android.permission.USE_EXACT_ALARM";
```

| Field | Exists | Value |
|---|---|---|
| `SCHEDULE_EXACT_ALARM` | **yes** | `"android.permission.SCHEDULE_EXACT_ALARM"` |
| `USE_EXACT_ALARM` | **yes** | `"android.permission.USE_EXACT_ALARM"` |
| `RECEIVE_BOOT_COMPLETED` | **yes** | `"android.permission.RECEIVE_BOOT_COMPLETED"` |
| `POST_NOTIFICATIONS` | **yes** | `"android.permission.POST_NOTIFICATIONS"` |
| `BIND_DEVICE_ADMIN` | **yes** | `"android.permission.BIND_DEVICE_ADMIN"` |

`javap` shows the **existence and string value** of these permission fields. It shows **nothing**
about protection level, which SDK level grants them, or which components may hold them —
see the UNVERIFIED section.

Adjacent finding while searching `POLICY_LOCK_TASK`: `android.Manifest.permission` declares
`MANAGE_DEVICE_POLICY_LOCK_TASK = "android.permission.MANAGE_DEVICE_POLICY_LOCK_TASK"` (line 167
of the `-public` capture). This is a **permission**, not a `DevicePolicyManager` policy-identifier
constant, and it is a different thing from `DevicePolicyIdentifiers.LOCK_TASK_POLICY` (§A.7).

### F.3 Exact-alarm special-access settings action

`javap -constants -public android.provider.Settings` was searched for `EXACT_ALARM`. **Exactly
one** match, and it is the only `ACTION_*` constant in the class whose name contains `ALARM`:

```
  public static final java.lang.String ACTION_REQUEST_SCHEDULE_EXACT_ALARM = "android.settings.REQUEST_SCHEDULE_EXACT_ALARM";
```

| Item | Value |
|---|---|
| Constant name | **`ACTION_REQUEST_SCHEDULE_EXACT_ALARM`** |
| Owning class | `android.provider.Settings` |
| Exact string value | **`"android.settings.REQUEST_SCHEDULE_EXACT_ALARM"`** |

**Annotation / SDK-gating check.** `javap -v -p android.provider.Settings` was run and the
constant pool plus the field's verbose record were inspected:

```
  public static final java.lang.String ACTION_REQUEST_SCHEDULE_EXACT_ALARM;
    descriptor: Ljava/lang/String;
    flags: (0x0019) ACC_PUBLIC, ACC_STATIC, ACC_FINAL
    ConstantValue: String android.settings.REQUEST_SCHEDULE_EXACT_ALARM
```

- The field record shows **no `RuntimeVisibleAnnotations` block** — no `@RequiresPermission`, no
  `@RequiresApi`, no `@SystemApi` is visible on this field in the class file.
- A count of occurrences of the literal string `RequiresPermission` in the entire
  `javap -v -p android.provider.Settings` output is **0**. (`Settings.class` *does* carry
  `RuntimeVisibleAnnotations` — for `java.lang.Deprecated`, on unrelated members — so the absence
  is a genuine absence on this field, not a stripped class file.)
- **Finding: no `@RequiresPermission` or SDK-gating annotation is observable on
  `Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM` in `android.jar` platform 36.** What the platform
  actually enforces at runtime is `UNVERIFIED (no local source)`.

`-public`/`-p` diff for `Settings` was empty.

---

## G. Lock Task entry API on `android.app.Activity`

### G.1 Requested signatures

Searching the `-public` capture of `android.app.Activity` for `LockTask`/`lockTask` returns
**exactly three** lines:

```
  public void showLockTaskEscapeMessage();
  public void startLockTask();
  public void stopLockTask();
```

| Requested member | Result |
|---|---|
| `startLockTask()` | **PRESENT** — `public void startLockTask();` |
| `startLockTask(String[])` | **ABSENT** |
| `stopLockTask()` | **PRESENT** — `public void stopLockTask();` |
| `isInLockTaskMode()` | **ABSENT on `Activity`** (it exists on `ActivityManager`, §D.2) |
| `setLockTaskMode`-like API | **ABSENT** — no member of `Activity` contains `setLockTaskMode` |
| `showLockTaskEscapeMessage()` | PRESENT — `public void showLockTaskEscapeMessage();` (not requested, reported for completeness) |

**Finding: `Activity.startLockTask(String[])` does NOT exist in platform 36. ABSENT.** The only
`startLockTask` member is the no-argument form. `Activity` exposes **no** `setLockTaskMode`-like
API. `Activity` exposes **no** `isInLockTaskMode()`.

The `-public`/`-p` diff for `Activity` listed only `protected` lifecycle methods and
`protected static final int[] FOCUSED_STATE_SET;` — **none** of them lock-task related. So no
non-public lock-task overload is hiding either.

### G.2 Declared exception on `Activity.startLockTask()`

```
  public void startLockTask();
```

**No `throws` clause is printed. `Activity.startLockTask()` declares no exception** in the class
file. (An undeclared runtime exception such as `SecurityException` cannot be observed by `javap`;
see UNVERIFIED.)

---

## UNVERIFIED (no local source)

Everything below could **not** be confirmed from local files. `javap` reads only the constant
pool, member signatures, and whatever annotations survived into the stub class files — it exposes
**no method bodies, no Javadoc, and no documentation prose**. No `-sources` jar, no
`docs/` reference bundle, and no `api-versions.xml`-style metadata was found or consulted.

**Behavioral prose / semantics**

1. What any method in §A–§G actually *does* at runtime. Only signatures were observed.
2. Whether `setLockTaskFeatures` / `setLockTaskPackages` / `setProfileEnabled` /
   `clearDeviceOwnerApp` throw `SecurityException` at runtime. Only `setLockTaskPackages` has a
   *declared* `throws java.lang.SecurityException`; undeclared runtime exceptions are invisible
   to `javap`.
3. Whether `isDeviceOwnerApp` / `isAdminActive` / `isLockTaskPermitted` return `false` versus
   throwing when called by a non-owner process.
4. The runtime meaning and effect of the `LOCK_TASK_FEATURE_*` bits, and whether the computed
   masks (47 / 63) are accepted or rejected by `setLockTaskFeatures`. The **values** are verified
   (§A.3); the **semantics** are not.
5. Whether `ActivityManager.getLockTaskModeState()` can return any value outside `{0, 1, 2}`.
6. Whether `AlarmManager.canScheduleExactAlarms()` returning `true` implies
   `setExactAndAllowWhileIdle` will succeed.

**Permission requirements and documentation**

7. Every `@RequiresPermission` / permission-requirement annotation for every method in this
   document. None is visible in the class files inspected; the count of the literal string
   `RequiresPermission` in the verbose dump of `android.provider.Settings` is 0. Whether the
   platform enforces such permissions at runtime is not locally determinable.
8. Protection level, grant semantics, or SDK-level gating of `SCHEDULE_EXACT_ALARM`,
   `USE_EXACT_ALARM`, `RECEIVE_BOOT_COMPLETED`, `POST_NOTIFICATIONS`, `BIND_DEVICE_ADMIN`, or
   `MANAGE_DEVICE_POLICY_LOCK_TASK`. Only the field names and string values are verified (§F.2).
9. SDK gating / `@RequiresApi` / `@SystemApi` status of
   `Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM`. No annotation is present on the field (§F.3),
   but absence of an annotation in a stub is not proof that the platform imposes no gating.
10. Deprecation status of the nine `DevicePolicyManager` methods in §A.1. `javap -public` does not
    print the `Deprecated` attribute and this was not separately checked per method.

**Target-SDK / manifest registration rules**

11. Whether `PolicyUpdateReceiver` requires a specific manifest declaration, and with which
    intent filters or `<meta-data>`. Only the two action constants and four `EXTRA_*` keys are
    observable (§B.2).
12. Any documented requirement that `PolicyUpdateReceiver.onReceive` be overridden. No
    documentation exists locally. The only observable fact is that it is declared `final`, so
    overriding it is impossible (§B.2, §B.5).
13. Whether `BIND_DEVICE_ADMIN` is the required `android:permission` for a `PolicyUpdateReceiver`
    or `DeviceAdminReceiver` declaration. The permission constant exists (§F.2); the requirement
    is not observable.
14. Target-SDK-level rules for exact-alarm scheduling, boot-completed receivers, or
    `POST_NOTIFICATIONS`.
15. **Superseded on 7 October 2026:** the documented lock-task identifier is verified (§A.7).
    Actual callback delivery on a particular phone remains unobserved. It is no longer correct
    to describe the public identifier's linkage as a hardware-only verification blocker.

**Other**

16. Whether `android.app.admin.PolicyIdentifier` exists on any *device* at runtime. It is ABSENT
    from `android.jar` platform 36 (§B.4) — that is a fact about the SDK stub jar only.
17. Whether `ActivityManager.LOCK_TASK_MODE_LOCKED_SYSTEM` exists on any device at runtime. It is
    ABSENT from `android.jar` platform 36 (§D.1) — same caveat.
18. Whether `Activity.startLockTask(String[])` exists on any device at runtime. ABSENT from the
    platform 36 stub jar (§G.1) — same caveat.

---

## Contradictions found

No prior `API_FACTS.md` existed, so these are contradictions against the two pre-existing
project documents that make overlapping API claims. Each is checked against the `javap` evidence
above.

### C-1. CONTRADICTED — "API 36 defines NO lock-task policy-identifier constant"

`docs\plans\2026-10-06-gate-a-b-poc.md` (Step 3b) states:

> "API 36 defines NO lock-task policy-identifier constant (only `POLICY_DISABLE_CAMERA` /
> `POLICY_DISABLE_SCREEN_CAPTURE` exist)."

and

> "Never compare against an invented `POLICY_LOCK_TASK` string."

**Partially contradicted.** The javap evidence is:

- **True** for `DevicePolicyManager`'s own `POLICY_*` constants: only `POLICY_DISABLE_CAMERA`
  and `POLICY_DISABLE_SCREEN_CAPTURE` exist there, and there is no `POLICY_LOCK_TASK` on
  `DevicePolicyManager` (§A.5).
- **False as a statement about API 36 as a whole:**
  `android.app.admin.DevicePolicyIdentifiers.LOCK_TASK_POLICY = "lockTask"` **does exist** in
  `android.jar` platform 36 (§A.7).

The advice "never compare against an invented `POLICY_LOCK_TASK` string" remains sound — no
constant by that name exists. But the claim that no lock-task policy identifier exists anywhere
is incorrect, and the correct identifier `"lockTask"` is reachable via
`DevicePolicyIdentifiers.LOCK_TASK_POLICY`. Its documented linkage is now verified (§A.7,
correction dated 7 October 2026). Classify relevant callbacks using that public constant;
do not use the historical UNVERIFIED #15 entry to justify ignoring policy failures.

### C-2. NAMING CORRECTED — result codes are `RESULT_*`, not `RESULT_CODE_*`

The task brief and `docs\plans\2026-10-06-gate-a-b-poc.md` Step 3b describe the result codes as
`RESULT_CODE_*` and list `0=set, 1=conflict, 2=cleared, 3=storage-limit, 4=hardware-limitation,
-1=unknown`.

**The numeric values are all CONFIRMED** (§B.3). **The prefix is wrong**: the actual constants
are `RESULT_POLICY_SET`, `RESULT_FAILURE_CONFLICTING_ADMIN_POLICY`, `RESULT_POLICY_CLEARED`,
`RESULT_FAILURE_STORAGE_LIMIT_REACHED`, `RESULT_FAILURE_HARDWARE_LIMITATION`,
`RESULT_FAILURE_UNKNOWN`. There is no `RESULT_CODE_*` constant on
`android.app.admin.PolicyUpdateResult`. Code referencing `RESULT_CODE_*` would not compile.

### C-3. CONFIRMED — masks 47 / 63 and `GLOBAL_ACTIONS = 16`

`docs\plans\2026-10-06-gate-a-b-poc.md` Step 3 states protected mask = 47, allowed mask = 63,
`GLOBAL_ACTIONS = 16`, and "Do NOT set `BLOCK_ACTIVITY_START_IN_TASK` (64)".

**All confirmed by §A.3 and §A.4.** `LOCK_TASK_FEATURE_GLOBAL_ACTIONS = 16`,
`LOCK_TASK_FEATURE_BLOCK_ACTIVITY_START_IN_TASK = 64`, `protectedFeatures = 47`,
`allowedFeatures = 63`. No disagreement.

### C-4. CONFIRMED — `isLockTaskAllowed` must not exist

`docs\Agent-Brief-v2.0.md` line 239 and the plan doc both assert that `isLockTaskAllowed` does
not exist and that `isLockTaskPermitted()` is the real method.

**Confirmed by §A.2 and §A.1.** `isLockTaskAllowed` is ABSENT;
`public boolean isLockTaskPermitted(java.lang.String);` is present. No disagreement.

### C-5. CONFIRMED — `PolicyUpdateReceiver.onReceive` is final; override the callbacks

`docs\Agent-Brief-v2.0.md` line 162 states: "override the policy callbacks, not its final
`onReceive()`."

**Confirmed by §B.2.** `public final void onReceive(android.content.Context,
android.content.Intent);` — `final`, therefore not overridable. `onPolicySetResult` and
`onPolicyChanged` are `public` and non-final, therefore overridable. No disagreement.

### C-6. CONFIRMED with a caveat — `setLockTaskPackages` declares `SecurityException`

The plan doc Step 3b states "`setLockTaskPackages` declares `throws SecurityException`".

**Confirmed by §A.1**: `public void setLockTaskPackages(android.content.ComponentName,
java.lang.String[]) throws java.lang.SecurityException;`. It is the only one of the nine
requested `DevicePolicyManager` methods with a declared `throws`.

**Caveat / gap:** the same document also claims "`getLockTaskPackages()` returns `String[]` —
wrap to Set with null/empty defense." The return type `java.lang.String[]` is **confirmed**
(§A.1), but whether it can return `null` or an empty array is a behavioral claim that `javap`
cannot show — recorded as UNVERIFIED, not contradicted.

### C-7. NOT CHECKED AGAINST EVIDENCE — `BIND_DEVICE_ADMIN` as the receiver's protection

`docs\Agent-Brief-v2.0.md` line 162 states: "Protect it with `BIND_DEVICE_ADMIN`."

`BIND_DEVICE_ADMIN` **exists** as a `Manifest.permission` field with value
`"android.permission.BIND_DEVICE_ADMIN"` (§F.2). Whether it is the *required* protection for a
`PolicyUpdateReceiver` declaration is a manifest/platform rule that is not observable via `javap`
— recorded as UNVERIFIED #13 rather than confirmed or contradicted.

---

## Quick-reference summary

| Question | Answer | Section |
|---|---|---|
| `isLockTaskAllowed` exists? | **NO — ABSENT** | §A.2 |
| Lock-task `POLICY_*` constant on `DevicePolicyManager`? | **NO — ABSENT** | §A.5 |
| Lock-task policy identifier anywhere in API 36? | **YES** — `DevicePolicyIdentifiers.LOCK_TASK_POLICY = "lockTask"` | §A.7 |
| `protectedFeatures` | **47** | §A.4 |
| `allowedFeatures` | **63** | §A.4 |
| `PolicyUpdateReceiver.onReceive` final? | **YES** — `public final void onReceive(...)` | §B.2 |
| `PolicyIdentifier` class exists? | **NO — ABSENT** | §B.4 |
| `LOCK_TASK_MODE_LOCKED_SYSTEM` exists? | **NO — ABSENT** | §D.1 |
| `Activity.startLockTask(String[])` exists? | **NO — ABSENT** | §G.1 |
| `Activity.isInLockTaskMode()` exists? | **NO — ABSENT** | §G.1 |
| `ACTION_REQUEST_SCHEDULE_EXACT_ALARM` | `"android.settings.REQUEST_SCHEDULE_EXACT_ALARM"` (declared on `android.provider.Settings`) | §F.3 |
