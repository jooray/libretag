# LibreTag Android app 0.13.1 (preview)

Signing certificate SHA-256: `79750fb49349ec3729be5a3e825507a15e47c6d987e854ae91d5d50a20b3a24d`. Android 8.0 or newer.

## Open Bluetooth on a tag the app has lost track of

Before, **Open Bluetooth remotely** sent a request that only fits the tag's last report the app heard. If the tag had moved on (it was out of range for a day or two and the app missed its reports), the tag never accepted the request, even with the phone right next to it.

The phone now also sends a wake-up request over Bluetooth that doesn't depend on the tag's reports. The tag looks for it every time it wakes up and opens its Bluetooth for 3 minutes.

- The tag's screen says the phone is waiting for the tag to wake up, and roughly when that should be. Keep the phone near the tag.
- The phone waits for up to two report periods, at least 2 hours. **Stop waiting** cancels it.
- When the tag answers, the app connects by itself. If you left the screen, a notification tells you the tag is listening; tap it to connect.
- The request still goes through your MeshCore node or the Nostr bridges as before.

## Fixes

- "Checking for missed reports…" no longer stays on screen after the app has finished checking. If the check failed (a relay dropped mid-query), the app now tries again instead of showing that line for good.
- The Bluetooth scan for a tag's console no longer gets stuck on after a "scan failed 1" error, which kept the radio scanning until the app was closed.

## Upgrading

Install over 0.13.0. The app needs tracker firmware 0.9.5 or newer. A tag with older firmware shows "Update firmware" on its screen. Update it over the cable first.
