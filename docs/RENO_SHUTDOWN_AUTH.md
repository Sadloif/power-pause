# Simple 0.4.4: Reno shutdown authentication follow-up

Current follow-up: Compatibility 0.4.5 now uses this same Reno path. Its shared regression tests passed, but its own physical Oppo/Poco tests remain pending. The observations below belong to Simple; see [the Compatibility port record and safe setup](COMPATIBILITY_RENO_AUTH.md).

9 October 2026. The owner found that a sufficiently fast swipe on the ordinary Oppo power-menu slider could reach a separate password prompt before Simple 0.4.1 dismissed the menu. The previous successful menu tests did not exercise that path. Simple 0.4.3 added a narrowly scoped attempt to cancel that second stage. Owner testing then found an intermittent miss: the original-menu authorization expired after 1,500 ms even though the menu remained visible. A live capture confirmed an authentication event about 2,929 ms after the menu event being refused for missing context. Simple 0.4.4 addresses that gap by verifying the live original menu while it stays focused; it does not make generic authentication cancellable. Compatibility 0.4.2 and Advanced 0.4.1 binaries are retained unchanged; they do not include this new cancellation stage.

## Physical evidence and scope

On the same tested Reno CPH2825, Android 16 / API 36, display build `CPH2825_16.0.10.501(EX01)`, Android reported that the existing shutdown-password requirement was enabled. The owner opened the shutdown password prompt without entering credentials. It was a protected system authentication window; its screenshot was blocked. One Back cancelled the entire prompt, confirmed by the owner and focus returning to the original power dialog. Another Back closed that dialog.

A temporary, explicitly requested read-only check inside the existing Simple app captured structural Accessibility metadata while protection was paused. It did not read input values, descriptions or event text. It captured the original Oppo ActionsDialog event followed by a System UI LinearLayout authentication event, an active/focused SYSTEM window with title exactly one space, and its bounded layout. The owner confirmed the static heading `Enter Lock screen password`. No password was requested or collected. The temporary observation UI and logging are removed from the final source.

## Admission rules

- The existing exact Reno build guard remains required. This stage is enabled in Simple 0.4.4 and Compatibility 0.4.5. Advanced and the Poco profile remain excluded.
- A trusted System UI event must identify the exact Oppo power-menu class. Its nonnegative window ID and event timestamp must be fresh: no future timestamp and at most 750 ms old when received.
- The event creates a one-shot episode tied to the current schedule revision. Exact verification of the same focused original menu refreshes a short 1,500 ms transition window. Mere duplicate events do not refresh this proof, and the episode has an immutable 60-second maximum. Generic authentication without the episode is ignored.
- The focused second-stage window must be SYSTEM, from System UI, have a LinearLayout root and the captured single-space title. The service checks the exact fifteen-node structural prefix, resource IDs and child counts, plus the fixed heading. Metadata retries are limited to six 20 ms intervals.
- Traversal stops at `com.android.systemui:id/input_layout`. The service never retrieves its children, typed password or input text. It reads text only from the non-password static title TextView with its exact ID.
- The durable revision, current clock, active protection and service generation are checked again before Back. Stop, edits, expiry, interruption and disconnect invalidate authorization.
- Authentication requests are deduplicated within one power-menu episode. A fresh episode can reuse a window ID. Cancelling authentication can reveal the same original menu; a fresh event may close that menu once more.

The original menu can already have been replaced before its event is handled. In that case the same fresh event/context can admit the strictly matched focused authentication window. This fallback does not match every password screen or every System UI dialog.

Android may omit a window hidden behind a modal dialog from Accessibility results ([official API documentation](https://developer.android.com/reference/android/accessibilityservice/AccessibilityService#getWindows())). The implementation verifies the menu while it is visible rather than depending on reading it behind the password prompt.

## Verification record

New tests execute the actual Simple Accessibility service with the captured layout, including a poison subtree beneath the credential container. They cover normal two-stage cancellation, an already-preempted menu, duplicate and reused window IDs, missing/stale context, wrong titles/headings/layouts, Stop/revision/expiry/unbind cancellation, and the actual merged Simple service flags. Pure checks exercise all structural fields and temporal boundaries. The existing Reno, Poco and managed regressions are also run.

The earlier 0.4.3 desktop verification passed **405 tests**: Simple 47, Compatibility 86 and Advanced 272, with zero failures, errors or skipped tests. All three release lint tasks passed with zero errors; warnings remain.

Earlier 0.4.3 signed Simple APK SHA256: `f7b2d48cb1f06aa9498776b5e4f9d42f75070fde7a89d2ff4e94d7e604632bb5`. Package `com.example.shutdownprotection`, version 0.4.3/code 8, Android 16/API 36 minimum, non-debuggable, label Power Pause Simple. The existing certificate matches. The final update was installed in place on the Oppo; Accessibility was bound with no crashed service and the owner-requested Paused state and hours 14:50–17:45 were preserved.

In the first final-version owner trial, the owner tried the fast swipe but the original menu closed before the password prompt could be reached. The service remained bound with no crashed service afterwards. No authentication-cancellation action was recorded. This verifies menu closure in that trial, not execution of the new authentication branch. In the subsequent separate password-cancellation trial, the owner reported that some password prompts remained visible while the 60-second countdown was active. Two action records showed Back accepted, but they do not prove visual closure. This is a real physical failure; 0.4.3 must not be treated as a reliable shutdown-authentication blocker. That failure led to the 0.4.4 correction described below. The manual Back result above proves cancellation is possible; it does not establish that the new automatic branch has passed physical testing.

## Separate timed password-cancellation test

Tools also offers **Test password cancellation for 60 seconds**, only on supported Reno firmware in Simple 0.4.4 and Compatibility 0.4.5. It requires a valid paused schedule and a connected, eligible service. It temporarily leaves the original menu open, while the same exact authentication matcher and 1,500 ms power-menu context are used. The corrected episode is refreshed only while the exact original menu remains focused. A late transition after the menu disappeared or the episode expired is intentionally refused. The mode is held only in memory, never enables the saved schedule, and clears on Stop, saving or editing hours, a normal test, expiry, interruption, disconnect or reconnection.

## Owner test, without shutdown

1. Update the existing Simple app in place. Keep its data and saved hours. Confirm Accessibility connected and Paused.
2. Keep Oppo's existing shutdown-password requirement enabled. Do not enter credentials or use biometrics while testing this path; keep the phone facing away from your face.
3. First use Tools → Test for 60 seconds for ordinary protection. Try the fast power-menu swipe that exposed the issue, in both shutdown and restart directions. Do not finish authentication. Report whether the menu closes before the prompt or any prompt stays visible.
4. Stop that trial. In Tools, start **Test password cancellation for 60 seconds**. Briefly hold power until the menu opens and release. Immediately swipe toward one direction; do not wait on the menu. The password prompt should cancel while the original menu may remain. Cancel that menu with Back, then repeat the other direction during the minute. Never enter a shutdown password or use biometrics. Report the prompt result explicitly.
5. During an ordinary 60-second protection trial, confirm normal phone locking and unlocking still work. Only the owner enters a normal unlock credential; the app does not read it.
6. Use Stop protection now. Confirm the ordinary menu and its password prompt remain available while paused. Cancel the prompt and menu with Back; never complete shutdown/restart for this test.
7. Confirm Accessibility remains connected. Leave the schedule paused unless you intentionally enable it yourself.

## Limits

This remains reactive Accessibility dismissal. Android first displays a window, then delivers an event; cancellation cannot guarantee beating every swipe or successful authentication. A stalled/disconnected service, unavailable metadata or a layout mismatch leaves the app unable to dismiss the prompt. The app does not change Oppo's password requirement, replace the lock-screen password, intercept credentials, disable emergency features or prevent forced hardware restart. No factory reset, data clearing, enrollment, root or running Shizuku is involved. A successful trial does not prove long-term reliability or support for other firmware/languages.


## 0.4.4 repair verification

Final desktop verification passed **423 tests**: Simple 55, Compatibility 91 and Advanced 277, with zero failures, errors or skipped tests. All three release lint checks passed with zero errors; warnings remain. The service regression now reproduces a three-second wait with the original menu continuously focused, and refuses late authentication after a missing/wrong menu or Stop/revision change. Pure checks cover verified freshness, absolute episode expiry, duplicate events, clock/revision invalidation and stale episode IDs.

Final signed Simple 0.4.4 APK SHA256: `4528a3508cc077d61e7d10ed89a68ef163b27170d9abd3b0ca0b7ce1f71d2c01`, package `com.example.shutdownprotection`, version code 9, Android 16/API 36 minimum, existing certificate and non-debuggable release manifest. It was installed in place; Accessibility reconnected with no crashed service.

The owner confirmed that all prompts closed in the final trial, including after a deliberate three-second wait and repeated attempts toward shutdown/restart. Live capture recorded authentication admitted after original-menu waits exceeding five seconds with recent exact menu verification, fixing the earlier 2,929 ms refusal. The service requested Back only after the unchanged exact authentication fingerprint passed; closing animation can keep the window visible briefly before the original menu returns. The owner also confirmed normal unlocking worked during the ordinary 60-second power-menu protection trial, after being instructed to lock and unlock without opening the shutdown menu and then tap Stop. Trial-only, capped diagnostics contain timing and window identity/match results, never password input. These diagnostics do not perform extra Back actions.
