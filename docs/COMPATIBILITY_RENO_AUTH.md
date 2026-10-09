# Compatibility 0.4.5: Oppo shutdown-password cancellation

9 October 2026. Compatibility gains the same narrowly scoped shutdown/restart password cancellation implemented and physically tested in Simple 0.4.4. It uses the shared service implementation and exact existing Reno profile. The new Compatibility binary has not yet been physically tested on the Oppo or Poco. The Simple result establishes the source behavior on the Oppo; it is not a physical certification of a different APK.

## What changed

- Compatibility version 0.4.5 / code 10 keeps package `com.example.shutdownprotection.compatibility`, the existing release certificate, and Android 10 / API 29 minimum.
- On the existing exact Reno CPH2825 Android 16 firmware and English layout, it tracks the same original focused power menu every 100 ms, maintains a 1,500 ms transition bridge, and caps each episode at 60 seconds.
- The same exact shutdown authentication event, window, static heading and bounded hierarchy must match before one Back is requested. Credential-input children are never read.
- Stop, edits, expired tests, interruption and disconnect invalidate tracking. Normal unlocking is outside the narrowly matched shutdown context.
- Tools offers the separate 60-second password-cancellation trial only on the supported Reno. The ordinary 60-second menu trial remains available on supported profiles.
- Poco keeps its existing profile and ordinary menu dismissal. No Poco shutdown-password detector is introduced. Other firmware stays unsupported.
- Simple 0.4.4 and Advanced 0.4.1 release APKs remain unchanged.

See [the detailed shared matcher, timing repair and Simple owner evidence](RENO_SHUTDOWN_AUTH.md). This remains reactive Accessibility dismissal; forced hardware restart, biometric races, missing metadata and OEM service lifetime are outside its guarantee.

## Install and test without changing the working Simple installation

1. Download `Power-Pause-Compatibility-0.4.5.apk` from the signed GitHub release. A Compatibility update preserves its own data; a fresh installation starts paused. Its data and approval are separate from Simple.
2. On the Oppo, leave Simple's schedule paused. Disable only **Power Pause Simple** in Android Accessibility settings before enabling **Power Pause Compatibility**. Keep unrelated services alone. Enable only one Power Pause service at a time.
3. Add **Power Pause Compatibility** itself to Phone Manager → Viruses & risks → Block suspicious app activities → More options → Allowlist. Simple's existing Allowlist entry does not cover a different package.
4. Open Compatibility and confirm **Accessibility connected**. Set valid different start/end hours, using the 24-hour format. Leave the schedule paused for these temporary trials.
5. In Tools, run **Test for 60 seconds**. Briefly open and release the power menu several times. Confirm it closes; after expiry confirm it stays visible. Short-press power to lock, wake and unlock normally during this ordinary trial. Never enter credentials into a shutdown/restart prompt. Then tap Stop.
6. On supported Reno firmware only, run **Test password cancellation for 60 seconds**. The original menu deliberately stays open in this trial. Open it and release power, wait three seconds, then swipe toward Power off without completing authentication. Keep the phone away from your face, do not enter its password and do not use biometrics. Confirm the prompt disappears; cancel any remaining menu with Back. Repeat toward Restart. If any prompt stays open, cancel it manually with Back and report the result.
7. Tap **Stop protection now** and confirm **Paused** while Accessibility stays connected. Cancel menus with Back. Do not select shutdown/restart, reset the device, clear app data or use managed-device commands.
8. To return to the existing working Simple app, disable only Compatibility's Accessibility service, then re-enable only Simple's. Keep both schedules paused until you intentionally choose one to use.

On the Poco, use the ordinary menu trial only. The new release does not claim a tested password-cancellation path on that phone. Existing Poco physical repetition, Stop and wall-clock boundary limitations remain described in [the earlier Poco record](POCO_COMPATIBILITY_RELEASE.md).

## Release verification

The frozen build completed successfully in 2 minutes 46 seconds. All 55 Simple and 109 Compatibility tests passed, with zero failures, errors or skips. Both editions execute the same Reno framework service regression class, including the delayed visible-menu transition, exact hierarchy rejection, normal-authentication exclusion, Stop/revision invalidation, expiry/disconnect and required merged Accessibility flags. Compatibility additionally tests that unsupported phones and the Poco cannot enter the Reno password path. Existing Compatibility startup, edition-isolation and explicit-Back tests execute on simulated API 29 through 36. These are desktop tests, not physical phone results.

Compatibility release lint passed with zero errors and 35 warnings. Signature verification passed with the existing certificate SHA256 `97ab9013f33d02dee6b83a3ad04258b53a30689202bae8960760b3dc0239a135`. The signed release manifest declares the correct Compatibility package and label, version 0.4.5/code 10, minSdk 29, targetSdk 36 and no debuggable flag.

Signed Compatibility APK SHA256: `357de54c622b01af8274044401493bc2af452dfc20a09a246bbbf0528fdd6a5e`.

This port was built without installing another app or changing services/settings on the owner's phone. Simple's working installed version remains in place. New Compatibility physical tests remain pending on both Oppo and Poco; this release does not certify all Android 10+ devices.
