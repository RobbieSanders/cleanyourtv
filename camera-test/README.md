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

Playback recovery retries a failed stream once, falls back from HD to SD when available, and never keeps retrying indefinitely. Single-camera audio is opt-in and may not be available from all cameras. Dashboard audio stays disabled. Controls auto-hide after seven seconds, with remote navigation to reveal them. These changes require on-TV testing.

## v0.8 TV polish
Numeric PIN/verification fields use an in-app keypad. Dashboard hides decoder diagnostics. Single-camera buttons preserve remote focus and confirm events are consumed once. No automatic focus-stealing timer. Audio track selection/volume diagnostics and overlay test independent of monitoring settings. Tests report submitted overlay/notification rather than assuming firmware displayed them. No rewind implementation: the tested system reported no compatible Smart Drive service.

## Recording research branch
This build adds an opt-in read-only schema report from existing authenticated system inventory. It outputs field names/types and explicit recording-related booleans, not raw JSON, URLs, credentials, IDs or footage. No new network endpoint, stream recording, security changes or playback implementation. Compare supported camera schemas against the older indoor camera. Branch is independent of v0.8 main.

## v0.10 diagnostic
Single view waits for old surfaces to release, labels current quality, retries in both directions and attempts software decode on later retries. Where LibVLC provides displayed-picture statistics, automatic recovery checks frame progress and remembers successful quality per camera. Audio remains opt-in. No claim of verified on-device recovery or historical playback.

## v0.11 Premium Glass 2.0
Default balanced seven-day authorization stored in encrypted vault, no sleep lock, verified one-time notification ticket for camera-only viewing when PIN authorization expired. Manual lock revokes remembered access and bypass; strict mode locks on leaving. Settings require a fresh PIN with a five-minute foreground grant. Whole-tile focus, long press options, floating single-view controls, darker palette, categorized settings, remembered camera selection and keep-awake setting. Existing 30-day sign-in unchanged. Real event delivery, Sony focus/overlay rendering and security lifecycle require device testing. Native rewind not implemented.

## v0.12
Two-column all-camera catalog, centered numeric authentication with conventional 3x4 keypad, docked controls by default (floating optional), About/developer credit and bundled license notices. Camera names and stream endpoints are dynamically discovered from the signed-in account, not hardcoded. Compatible Android 8+ TV and reachable private RTSP streams required. Independent, free experimental software; not an official Vivint client or substitute for monitoring. Before broad APK distribution review applicable Vivint terms and all bundled library licenses, including exact native-module corresponding source/relinking obligations. No guarantee of legal immunity or compatibility.

Developer credit: @1TickRob (X / Twitter). The GitHub repository remains the source-code location.

## v0.13
Plain-language PIN settings and duration labels, immediate PIN check/loading progress and retry feedback, clarification of history-based TV notifications. About no longer advertises the launcher project; original notices and source references remain in License notices to preserve attribution and licensing. No changes to PIN defaults, camera settings, playback or notification polling.
