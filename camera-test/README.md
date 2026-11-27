# Home Security (experimental Android TV application)

Package com.gathin.cameratest. Independent of the launcher. Use the existing Build Camera Test APK workflow. No HOME role is registered. Built on the v0.4 VLC RTSP path; the working OkHttp Vivint login/MFA flow is retained. No alarm-control or camera-configuration endpoints are called.

## Features
- Android Keystore AES-GCM encrypted access/refresh tokens and a salted PBKDF2 six-digit PIN verifier. No password or stream URL persistence. Mandatory full login 30 days after enrollment, even if tokens still work. The time limit uses the device clock with a rollback check. Refresh tokens are rotated and persisted before further requests. A revoked or expired session can require earlier login. Five incorrect PIN attempts trigger a five-minute local lockout. Lock when leaving the application or screen-off; an optional five-minute idle lock is off by default. Explicit Android permission settings can return to the unlocked settings screen within two minutes, unless the TV sleeps. Changing the PIN requires the current PIN and does not extend the session. The PIN gates the UI, not the background listener's ability to decrypt tokens; it is not protection against a rooted or compromised TV.
- Paginated four-camera local SD overview, individual VLC HD/SD, all-camera list and manual camera refresh. Newly added compatible cameras are discovered on each unlock or refresh. Dedicated remote-focusable camera buttons and guarded single-camera transitions. Only private IPv4 RTSP URLs from the authenticated account are used. Privacy-mode cameras are skipped. Streams release on leaving camera views. No background video.
- Optional silent alerts: read-only Vivint history is polled approximately every 30 seconds while the TV is interactive. Current alarm-state filters, explicit recognized event descriptions, short freshness window and deduplication. No general motion by default. Real lingering/doorbell event labels must be validated against actual hardware; unrecognized or unknown-state events are deliberately ignored. Alerts are not guaranteed or a replacement for the official alarm service.
- Android notifications with View/Dismiss, plus optional ten-second top-right interactive overlay. The overlay requires explicit Android permission and can temporarily take remote focus; compatibility with streaming apps and HDMI is device-dependent. View opens a PIN-protected camera; no automatic video or audio.
- Boot receiver attempts to restart enabled monitoring on supported devices. Notifications may be hidden by Android TV firmware; this is experimental.

## Launcher shortcut
After installing, the existing launcher supports the config key `top_bar_apps`: add `com.gathin.cameratest` to the array in its external config.json. No launcher rebuild or uninstall is required. This uses the Home Security app icon.

## Verification
Unit tests cover the fixed session lifetime, rollback guard and event/state filtering. Real account refresh, encrypted storage, PIN lockout, simultaneous decoding, sleep, boot and notification delivery need device testing. No actual user credentials are included. The build uses a temporary signing key, so replacing a prior Camera Test build may require uninstalling only this app. Keep the existing launcher unchanged.

## v0.6
Modern dark layout, explicit D-pad camera selection, guarded full-view transition, optional idle lock, permission-return handling, camera paging, manual refresh and Change PIN. Build and device navigation validation still required.

## v0.7 Premium Glass
TV-first split authentication layout, consistent blue glass surfaces, live camera switching, optional hidden controls, camera favorites/order/visibility and focus/quad modes, read-only alarm and recent history, DND, test notification, 90-second per-camera/event cooldown, and Smart Drive detection. History events open live video, not recordings. The actual Smart Drive recording transport requires its own authenticated TLS client and is not yet implemented. Six-stream layout is intentionally deferred until the TV can be tested. 

## Permanent release signing
Create the repository Actions secret HOME_SECURITY_SIGNING containing a JSON object with a base64 PKCS12 signing key (`keystore`) and its password (`password`). Alias must be home-security. Keep the private bundle offline, never commit it or print it. The workflow now refuses to build a release without this secret; it restores the key only on the runner and deletes it afterward. First installation requires removing the previous temporary-key APK once. Later builds with the same key and package can be installed with pm install -r, retaining app data. This key is separate from the launcher.
