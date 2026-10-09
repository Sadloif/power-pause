# Power Pause: Reno 15 setup and verification

Current editions: **Simple 0.4.3** updates the original ordinary app and keeps its package and saved settings. **Advanced 0.4.1** and **Compatibility 0.4.2** use separate packages and approvals. Compatibility 0.4.2 adds a narrowly matched Poco Android 13 profile; see [its current results and setup](POCO_COMPATIBILITY_RELEASE.md). The Reno-only statements and version records below describe Simple or historical builds, not every current APK.

Current Simple 0.4.3 adds narrowly scoped Reno shutdown-password cancellation; see [its current verification record](RENO_SHUTDOWN_AUTH.md). Its only credential-related text read is the static prompt heading; credential input is never traversed. The older implementation descriptions below remain historical and do not describe this added stage.

Updated 8 October 2026. Version 0.2.1 desktop verification passed: 257 tests, zero failures/errors/skipped; lint passed with zero errors and 23 warnings. It was installed over 0.2.0 with the saved schedule and Accessibility approval preserved. The owner subsequently confirmed all requested 0.2.1 time-entry, repeated-menu, expiry and retained-service checks worked; the independent readback and limits are separated below.


## Interface update: version 0.3.0

Desktop checks: 257 tests passed; lint zero errors, 23 warnings. See POWER_PAUSE_RELEASE.md for the final APK hash, UI checks, cleanup and preserved owner-requested paused state.

The final app is now named **Power Pause**. Its package remains `com.example.shutdownprotection`, so an in-place update keeps the existing schedule, Accessibility approval and package-based ColorOS exception. The name in Android settings and Phone Manager changes; it is the same app.

- **Schedule**: current status, immediate Stop, start/end input, save/enable and save while paused.
- **Setup**: Phone Manager Allowlist instructions, Accessibility connection and compatibility guidance.
- **Tools**: optional 60-second test, service-off control, diagnostics and separate managed-device recovery.

The adaptive launcher icon combines the power symbol and pause bars. Light and dark palettes follow the phone theme. Stop/expiry/service matching and persistence use the existing 0.2.1 backend. No reset, enrollment or additional permissions are introduced.

On 8 October, the three temporary probe packages were uninstalled at the owner's request: `com.zeshan.powermenuprobe`, `com.zeshan.shizukumenuprobe` and `com.zeshan.accessibilitymenuprobe`. Shizuku and unrelated apps were left installed. Historical probe evidence below remains valid as a record of those tests, not as an instruction to reinstall the probes.

## What this mode does

The app closes the ordinary Power off / Restart menu during a daily schedule. The menu can briefly appear before it closes. This is not physical power-button disabling and does not prevent forced hardware restart or emergency functions.

No factory reset, account removal, Device Owner enrollment, root, system-setting write or running Shizuku is required. The new mode uses a manually enabled Accessibility service. The app does not turn that service back on if Android or ColorOS disables it.

The current compatibility guard accepts only OPPO CPH2825, Android 16 / API 36 and display build `CPH2825_16.0.10.501(EX01)`. The observed menu title is English `Phone options`. Do not claim support for another phone, firmware or language without testing and updating the narrow detector.

## Evidence so far

On the owner's phone, the separate Accessibility Menu Probe dismissed the menu repeatedly with Shizuku stopped. The owner also confirmed dismissal after pressing Home and while locked. After the 60-second test ended, the menu stayed visible.

ColorOS initially disabled the probe with an Abnormal device control notice. Its own Phone Manager history confirmed the block. Adding only that probe to the supported per-app Allowlist resolved the observed block during subsequent trials; no new notice appeared and the service stayed enabled. The main suspicious-activity guard stayed On.

These are probe results. The final app has a different package and must receive its own Allowlist entry. Final app scheduling, Stop, timing and service-removal results must be recorded separately.

The owner then observed the final app dismiss a menu, followed by another miss and Accessibility being turned off. Android readback confirmed the final service disabled while the schedule intent remained enabled (14:30–14:32); its last record contained four accepted Back requests, 17ms event delivery and 3ms detection. Those timings do not measure button hold or visible animation. The owner confirmed another Abnormal device control notice, then added **Scheduled Power Menu Restriction itself** to Phone Manager's Allowlist and reported “now its working”. This identifies the final-package OEM exemption as necessary; the probe's existing exemption did not cover it. No app timer is claimed to have revoked the permission.

Version 0.2.1 also prevents repeated Android events from sending multiple Back requests for the same menu window, preserves the service on schedule/test expiry and ordinary Stop, and leaves compatibility-check failures idle rather than automatically disabling Accessibility. Only the explicit **Stop and turn off this Accessibility service** control requests disableSelf. ColorOS can still revoke authorization independently; the app neither suppresses the guard nor secretly re-enables itself.

### Final 0.2.1 owner confirmation

The owner was asked to enter `1430`, check automatic `14:30` formatting, use ordinary Stop, run a 60-second trial with repeated menu openings, observe the menu staying visible afterwards and check Accessibility remained enabled. The owner replied **“yes, all working”**. This is the physical observation, rather than an inference from an API return.

Subsequent independent Android readback confirmed this app's Accessibility authorization still enabled. Its own latest lifecycle record says ordinary Stop left Accessibility enabled/idle; a later action record contains window 2092, API accepted, 5ms event delivery and 4ms detection. The owner subsequently left an enabled daily schedule at **14:50–15:00**, revision 10, which was preserved without further changes. The final snapshot is a later state, not a saved `test_expired` record; do not claim computer readback independently captured the temporary test's exact expiry. A Shizuku-server process check still returned no PID.

A later immediate UI check reported the probe disconnected. Android still listed it as enabled, and subsequent checks confirmed it bound and its own report connected again. The owner reported no manual Stop/disable or new notice. No automatic re-enabling command was sent. This is an observed transient disconnection/reconnection with cause and duration unmeasured, not proof of a new guard block or uninterrupted service lifetime. The production schedule is durable; temporary tests intentionally never restart after reconnection. Long-duration availability still needs observation.

## Set up the final app, one step at a time

1. Install the reviewed Power Pause Simple 0.4.3 APK as an update. Do not uninstall the final app first. Saved hours and approval are retained; check the current status on **Schedule**. A fresh installation starts paused.
2. The temporary probe apps have been removed. Leave unrelated apps and services alone.
3. Open **Setup**, then tap **Open Phone Manager**.
4. Navigate to **Viruses & risks → Block suspicious app activities → More options → Allowlist**.
5. Select **Power Pause** if it is not already allowed. The existing entry previously named Scheduled Power Menu Restriction belongs to this same package. Confirm its entry. Keep the main blocking switch On; do not run optimisation or cleanup.
6. Return to **Setup** and tap **Open Accessibility settings**.
7. In downloaded apps/services, select **Power Pause**. Enable its service if it is off. Existing approval normally survives the update. Leave other services unchanged.
8. Return to **Setup**. It should say **Service connected**. Enabling Accessibility alone does not enable a new schedule.
9. For an optional trial, go to **Tools**, tap **Stop protection now** to pause an enabled schedule, then **Test for 60 seconds**. Briefly hold power until the ordinary menu appears and release. Never select Power off or Restart. The menu should close automatically.
10. After the minute ends, the menu should stay visible again. Dismiss it normally with Back. An accepted Back request in diagnostics alone is insufficient evidence. Re-enable your daily schedule afterwards if desired.
## Choose a daily schedule

1. Open **Schedule**. Enter Start and End using the **24-hour clock**. You can simply type four digits: `0230` automatically becomes `02:30`. Existing `HH:mm` input is also accepted; a pasted semicolon separator is normalized to a colon.
2. Examples: `0000` → `00:00` (midnight); `0230` → `02:30` (2:30 am); `1200` → `12:00` (noon); `1430` → `14:30` (2:30 pm); `2030` → `20:30` (8:30 pm). Hours must be 00–23; minutes must be 00–59. Invalid or incomplete input is refused, rather than silently changing your schedule.
3. A start later than the end means overnight: `23:00` to `01:00` runs into the next day.
4. Equal times are refused. They do not mean all-day protection.
5. Tap **Save and enable schedule**. Confirm the status says active if within the hours, or enabled/outside selected hours otherwise.
6. Times follow the phone's current local time zone. Start is included; end is excluded. The app evaluates the current time when the exact menu appears and again immediately before sending Back. This mode does not rely on a boundary alarm to undo a changed system power setting.
7. When the end time passes, menu dismissal stops but **Accessibility remains enabled**, ready for the next daily start. You do not need to grant Accessibility again every day. The 60-second test also ends without turning the service off.
8. **Save times and keep paused** saves the hours but turns dismissal off and ends any temporary test. It keeps Accessibility enabled.

## Stop or remove the service

1. Tap **Stop protection now** to disable the stored schedule, end a temporary test and cancel pending dismissal retries. The service can remain enabled but idle.
2. Open the power menu and check it stays visible, then dismiss it normally with Back.
3. To also turn off the service, open **Tools**, tap **Turn off Accessibility service**, then confirm **Turn off service**. Check the service status afterwards.
4. You can also turn off this service in Android Accessibility settings. The app will then report dismissal unavailable.
5. Force-stop or uninstall stops this app's service. It has changed no OS power-menu setting that needs restoration. Do not clear other apps' data or change other services.

If ColorOS shows a new abnormal-control notice, stop testing and check this app's Allowlist entry and service status. A disconnected service means dismissal is unavailable. A saved enabled schedule is user intent, not proof the service is running.

## Implementation and verification record

The new store is `no_reset_schedule`; it does not modify Device Owner policy baselines, recovery markers or alarm receipts. The ordinary main-activity path does not attach the managed lock-task controller or invoke its foreground coordinator. Already enrolled Device Owner devices retain the original managed UI; managed recovery tools remain reachable as an explicit separate entry.

The service requires the exact system event class, package, current window ID, type, active/focused flags, root class and observed title. It does not inspect event text, child contents or screenshots. Its 60-second test uses an elapsed-time deadline and does not resume after a service/process restart. Persistent schedules can resume when Android reconnects an authorized service; that lifecycle and reboot/Doze behaviour still require separate observation.

Metadata retries are bounded to six 20ms delays. The app records event delivery and detection timing separately after requesting Back. Those timings do not measure the entire button-hold-to-visible-dismissal interval.

Verified desktop checks include actual API 36 Accessibility-service callbacks under Robolectric, valid/invalid menu matching, durable daily activation and disabling, cancellation of pending retries on Stop/revision change, elapsed expiry without executing the end callback, trial restart refusal, corrupt settings refusal, firmware refusal and ordinary activity isolation from the lock-task controller. The existing managed regression suite also passed. Those framework tests do not certify physical menu behaviour.

Version 0.2.1 APK SHA256: `DAB5F72104694A5F8753FF72608CA3D442E87BE9D1274D6280E0CA439BC502C7`. Signing certificate SHA256: `97ab9013f33d02dee6b83a3ad04258b53a30689202bae8960760b3dc0239a135` (matches the original). Debug APK remains test-only/debuggable and was installed through the existing USB debugging connection. Release main manifest processing passed; no signed release APK is claimed.

APK permissions remain RECEIVE_BOOT_COMPLETED, SCHEDULE_EXACT_ALARM and the AndroidX app-local receiver permission. BIND_ACCESSIBILITY_SERVICE protects the service declaration, rather than granting the app a general system privilege. The new backend has no reset, enrollment, reboot, shutdown, shell, Shizuku, settings-write or lock-task call. Its DevicePolicyManager call only reads whether this app is already Device Owner and refuses that mixed mode.

Build log for 0.2.1: `E:\CodexData\work\scheduled-power-menu-repair\_working\no-reset-021-build.txt`. XML test results and lint reports are under `app/build`. New regression checks intercept Android's actual disableSelf method: expiry, ordinary Stop and incompatible connection send no disable request; the explicit service-off control sends exactly one. Duplicate events for one menu request one Back, while a new window can be dismissed. Compact-time validation accepts intended 24-hour minutes and refuses invalid/incomplete entries. The final app's initial-screen UI evidence is under the investigation's `_working/reno-device/final-app-initial-ui.xml`.

The final package's Allowlist setup and the requested 0.2.1 physical checks now have owner confirmation, with retained authorization independently verified. Daily schedule arithmetic and real service admission have desktop coverage; a fully observed final-version wall-clock start/end sequence and unrelated system-window control have not been separately recorded on the phone. Other firmware/languages, reboot/Doze and long-duration OEM behaviour remain unverified. Those limits are not silently promoted to passed results.



