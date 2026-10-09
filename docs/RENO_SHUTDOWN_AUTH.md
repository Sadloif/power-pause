# Simple 0.4.3: Reno shutdown authentication follow-up

9 October 2026. The owner found that a sufficiently fast swipe on the ordinary Oppo power-menu slider could reach a separate password prompt before Simple 0.4.1 dismissed the menu. The previous successful menu tests did not exercise that path. Simple 0.4.3 adds a narrowly scoped attempt to cancel that second stage. Compatibility 0.4.2 and Advanced 0.4.1 binaries are retained unchanged; they do not include this new cancellation stage.

## Physical evidence and scope

On the same tested Reno CPH2825, Android 16 / API 36, display build `CPH2825_16.0.10.501(EX01)`, Android reported that the existing shutdown-password requirement was enabled. The owner opened the shutdown password prompt without entering credentials. It was a protected system authentication window; its screenshot was blocked. One Back cancelled the entire prompt, confirmed by the owner and focus returning to the original power dialog. Another Back closed that dialog.

A temporary, explicitly requested read-only check inside the existing Simple app captured structural Accessibility metadata while protection was paused. It did not read input values, descriptions or event text. It captured the original Oppo ActionsDialog event followed by a System UI LinearLayout authentication event, an active/focused SYSTEM window with title exactly one space, and its bounded layout. The owner confirmed the static heading `Enter Lock screen password`. No password was requested or collected. The temporary observation UI and logging are removed from the final source.

## Admission rules

- The existing exact Reno build guard remains required. This stage is enabled only in Simple.
- A trusted System UI event must identify the exact Oppo power-menu class. Its nonnegative window ID and event timestamp must be fresh: no future timestamp and at most 750 ms old when received.
- The event creates a one-shot context tied to the current schedule revision. It lasts less than 1,500 ms; duplicate events cannot extend it. Generic authentication without that context is ignored.
- The focused second-stage window must be SYSTEM, from System UI, have a LinearLayout root and the captured single-space title. The service checks the exact fifteen-node structural prefix, resource IDs and child counts, plus the fixed heading. Metadata retries are limited to six 20 ms intervals.
- Traversal stops at `com.android.systemui:id/input_layout`. The service never retrieves its children, typed password or input text. It reads text only from the non-password static title TextView with its exact ID.
- The durable revision, current clock, active protection and service generation are checked again before Back. Stop, edits, expiry, interruption and disconnect invalidate authorization.
- Authentication requests are deduplicated within one power-menu episode. A fresh episode can reuse a window ID. Cancelling authentication can reveal the same original menu; a fresh event may close that menu once more.

The original menu can already have been replaced before its event is handled. In that case the same fresh event/context can admit the strictly matched focused authentication window. This fallback does not match every password screen or every System UI dialog.

## Verification record

New tests execute the actual Simple Accessibility service with the captured layout, including a poison subtree beneath the credential container. They cover normal two-stage cancellation, an already-preempted menu, duplicate and reused window IDs, missing/stale context, wrong titles/headings/layouts, Stop/revision/expiry/unbind cancellation, and the actual merged Simple service flags. Pure checks exercise all structural fields and temporal boundaries. The existing Reno, Poco and managed regressions are also run.

Final desktop verification passed **405 tests**: Simple 47, Compatibility 86 and Advanced 272, with zero failures, errors or skipped tests. All three release lint tasks passed with zero errors; warnings remain.

Final signed Simple APK SHA256: `f7b2d48cb1f06aa9498776b5e4f9d42f75070fde7a89d2ff4e94d7e604632bb5`. Package `com.example.shutdownprotection`, version 0.4.3/code 8, Android 16/API 36 minimum, non-debuggable, label Power Pause Simple. The existing certificate matches. The final update was installed in place on the Oppo; Accessibility was bound with no crashed service and the owner-requested Paused state and hours 14:50–17:45 were preserved.

In the first final-version owner trial, the owner tried the fast swipe but the original menu closed before the password prompt could be reached. The service remained bound with no crashed service afterwards. No authentication-cancellation action was recorded. This verifies menu closure in that trial, not execution of the new authentication branch. A separate controlled authentication trial is pending. The manual Back result above proves cancellation is possible; it does not establish that the new automatic branch has passed physical testing.

## Separate timed password-cancellation test

Tools also offers **Test password cancellation for 60 seconds**, only on supported Simple Reno firmware. It requires a valid paused schedule and a connected, eligible service. It temporarily leaves the original menu open, while the same exact authentication matcher and 1,500 ms power-menu context are used. Swipe promptly when the menu appears; waiting beyond that context intentionally refuses cancellation. The mode is held only in memory, never enables the saved schedule, and clears on Stop, saving or editing hours, a normal test, expiry, interruption, disconnect or reconnection.

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
