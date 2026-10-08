# Power Pause 0.3.0 — delivery record

8 October 2026.

## What changed

The existing final app is now named **Power Pause**, with a navy and mint adaptive power/pause launcher icon, a monochrome themed icon and a matching launch screen. The phone's light/dark preference controls the application palette.

The ordinary interface has three tabs:

1. **Schedule** — a prominent status card, immediate Stop, the current saved daily hours, start/end fields and save actions. Four digits still convert to 24-hour time with a colon.
2. **Setup** — the two required ColorOS/Accessibility steps, service connection status and an explanation of actual menu dismissal.
3. **Tools** — an optional 60-second trial, explicit service-off control, diagnostics and separate managed-device recovery. Turning off the service or opening managed tools requires a clear in-app confirmation.

The package, signing certificate, component names, permissions and no-reset backend are unchanged. This is an update of the same final app, not a second installation. No reset, shutdown, reboot, enrollment, data-clear or change to other app settings was performed.

## Phone cleanup

Only these three temporary test packages were uninstalled, each returning Success:

- `com.zeshan.powermenuprobe` — Experimental Power Menu Probe.
- `com.zeshan.shizukumenuprobe` — Shizuku Menu Dismissal Probe.
- `com.zeshan.accessibilitymenuprobe` — Accessibility Menu Probe.

Their package paths were subsequently absent. The final app remains installed. Shizuku manager and unrelated apps remain installed. Historical probe reports were retained on the computer.

## Verification

The first 0.3.0 update preserved the 14:50–15:00 enabled schedule, revision 10, byte-for-byte, and retained Accessibility approval. The owner then manually paused the schedule and explicitly requested that it remain paused. The later snapshot has the same hours, enabled=false and revision 11. This change was confirmed by the owner and is not data loss.

The phone preview covered the Schedule, Setup and Tools tabs in its current light theme and custom system font. The service displayed Connected and its final paused state retained Accessibility authorization. The trial button was correctly disabled while the saved schedule was enabled, as confirmed by its Android accessibility semantics. A brief unrelated app notification obscured the top part of the screen during screenshots; it was not modified. No shutdown-menu retest is claimed for this presentation-only update.

All no-reset service, schedule-store, input-parser and detector source hashes match the pre-update hashes. Previous physical behaviour evidence and its limitations remain in NO_RESET_MODE.md. The redesigned interface does not broaden firmware compatibility or claim to disable the physical power button. The dark palette and launcher masks were compiled; a separate dark-mode or launcher home-screen trial was not performed on this phone.

Final verification: 257 tests passed, zero failures/errors/skipped. Lint passed with zero errors and 23 pre-existing warnings. The APK and release main manifest were built successfully. Signing certificate SHA256 remains 97ab9013f33d02dee6b83a3ad04258b53a30689202bae8960760b3dc0239a135. Debug/test-only APK SHA256: 883E0B7595FC7382B2B12902BA2C94105E7C402C5540A022FBD76CF0F27D4287. The verified build was installed as an in-place update, and the owner-requested paused state and saved hours were unchanged. No signed release APK is claimed.

The scoped original-folder delivery saves verified copies of every replaced file under _working/no-reset-backups/revision-4-20261008-* before copying. Its sync-plan.csv and delivery-result.json identify exact prior/delivered hashes. Build logs and phone UI/readback evidence are in E:\CodexData\work\scheduled-power-menu-repair\_working\power-pause. No mirroring, deletion of project files or copying of signing keys is used.

