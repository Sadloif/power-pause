# Deployment feasibility — current limits, 7 October 2026

This repair verifies desktop behavior. It does not establish a production deployment or authorize any phone enrollment. Earlier research and emulator evidence are preserved in `history/before-7-october-2026-repair/PRODUCTION_FEASIBILITY.md`; its claim that web access was unavailable describes that earlier session.

The application implements a custom Device Policy Controller for a dedicated managed device. Installing an APK does not establish management authority. Android's dedicated-device model uses fully managed deployments and documented lock-task controls. See [Dedicated devices overview](https://developer.android.com/work/dpc/dedicated-devices) and [Lock task mode](https://developer.android.com/work/dpc/dedicated-devices/lock-task-mode).

Supported Device Owner provisioning is a separate process performed during initial setup of a new device or after a factory reset. That requirement is not authorization to reset a device. See [Google device provisioning documentation](https://developers.google.com/android/work/play/emm-api/prov-devices). Do not bypass setup/account checks or modify the primary phone.

In-app Restore releases the managed session and attempts original-policy restoration; it does not remove device management. Debug test-only packaging, a matching signer and desktop tests do not guarantee enrollment, ordinary uninstall, management removal or preservation of phone data.

Before any production release, separately verify enrollment/removal, update and recovery procedures on a dedicated test device; real OEM power-menu behavior; Doze/boot/alarm delivery; launcher and permission/authentication/share/camera workflows; and any applicable distribution/store requirements using current primary documentation. The current repair does not claim those decisions are closed.
