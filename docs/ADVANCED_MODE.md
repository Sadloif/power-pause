# Advanced managed-device tools — read before opening

**Caution: do not experiment with these tools on your everyday phone.** They are for deliberate testing or recovery on a dedicated compatible device with existing Device Owner enrollment. Managed power-menu behaviour has not been verified on physical hardware. The Simple edition is the intended choice for the currently tested Reno Accessibility workflow.

## Why keep these tools?

They preserve the original managed backend, allow controlled future device-policy testing and provide a recovery interface for devices using that backend. Android lock-task policy can suppress the ordinary power menu while a managed session is active, instead of reacting with Accessibility Back after it appears. This route does not depend on Accessibility or Shizuku, and it can allow only selected apps.

These are platform capabilities and intended future benefits, not a claim that this app's managed behaviour has passed a physical test. Its existing UI also allows diagnostics and release of app-applied managed rules. It does not guarantee prevention of forced hardware restart or every shutdown path.

## What opening the menu does

1. In Advanced → Tools, read the caution card.
2. Tap **Open managed device tools** only if you intentionally need this separate backend.
3. The confirmation explains the risk and offers Cancel.
4. **Stop and open** disables the Accessibility schedule and opens the original managed screens.
5. Opening the screen alone does not enroll, reset or erase the phone.

A fresh or ordinary installation cannot obtain Device Owner authority by pressing these buttons. Standard production enrollment normally occurs during initial setup or after a factory reset. No enrollment should be attempted on a personal phone as part of trying Power Pause.

## What each option means

| Option | Purpose | Caution |
|---|---|---|
| Enable / resume managed session | Applies the managed schedule and enters an authorized lock-task session | Requires real Device Owner prerequisites; may restrict apps and system controls; physically unverified |
| Restore Normal Device Mode | Releases app-applied restrictions and attempts to restore the saved original policy | Not a factory reset and not management removal; verify recovery status, and do not assume success from a button press |
| Change start / end | Edits the managed backend's separate schedule | Does not edit the Accessibility schedule; policy/schedule edits can disarm a managed session |
| Manage allowed applications | Selects the launcher/apps allowed during managed operation | Omitting needed apps can limit normal workflows; changes require explicit resume |
| Diagnostics | Shows requested/effective state, errors and bounded event history | Observed API state is not proof of physical menu behaviour |
| Setup and prerequisites | Checks ownership, version, session/recovery and alarm requirements | A passed checklist is not enrollment and does not certify OEM behaviour |
| Development test controls | Manual managed proof-of-concept controls in debug builds | Absent from downloadable release builds; never experiment on a daily-use phone |

## Edition switching and recovery

Both downloadable APKs intentionally share one application ID and certificate. They can update the existing ordinary installation without creating a second service or discarding saved hours.

**Do not install Simple over this app while it is Device Owner or a managed session is active.** Simple omits the admin/policy/alarm receivers and managed recovery entry points. Replacing managed components in an enrolled installation is outside the validated switching procedure. Use the existing Advanced recovery tools, verify release, and plan management removal on a dedicated device separately before any switch.

For an ordinary unenrolled Reno installation, switching the supplied editions preserves the same Accessibility component and store. Check connection, current schedule state and the app's own ColorOS Allowlist entry afterwards. Switching does not itself grant or revoke Accessibility approval; Android/ColorOS remains responsible for service availability.

## References

Android documents [lock-task power-menu controls](https://developer.android.com/work/dpc/dedicated-devices/lock-task-mode#customize-ui) and [dedicated-device provisioning](https://developer.android.com/work/dpc/dedicated-devices). Read the project's [Recovery](RECOVERY.md), [Repair results](REPAIR_RESULTS.md) and [physical evidence](NO_RESET_MODE.md) before interpreting a status claim.
