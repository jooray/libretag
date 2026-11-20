# LibreTag Android app 0.12.0 (first public release, preview)

The LibreTag app pairs tags over USB, shows where they are on a map, and sends them commands. It talks to the MeshCore network through your own node in MeshCore Open, and it also fetches reports and sends commands over Nostr relays, so a tag can be found outside the range of your own radio.

Signing certificate SHA-256: `79750fb49349ec3729be5a3e825507a15e47c6d987e854ae91d5d50a20b3a24d`. Android 8.0 or newer.

## What it does

- **Pairing** in four steps over a USB OTG cable: plug in, name, where the tag usually rests, done. The app reads the key, sets the radio and your Nostr npub, and locks the tag. From then on the key exists only in the app.
- **Map and history** (OpenStreetMap): the last position, the next expected report, a timeline you can scrub, GPX and CSV export.
- **Commands**: Lost (every 10 min for 24 h), Track (every 2 to 30 min, for 30 min to 24 h; one tap tracks for an hour every 5 min, and the dialog warns when a choice would hit the tag's daily transmission limit), Beep, stop, a Bluetooth scan, and opening the tag's Bluetooth console remotely. The app picks the route: its own radio, the Nostr mailbox for any bridge, or a Bluetooth advert when the phone is near the tag. It shows when a command went out and when the tag confirmed it.
- **Places**: GPS places tapped on the map, and Wi-Fi places for tags without GPS. The tag stays quiet inside a place. Each place can notify you when the tag leaves or arrives.
- **Quiet near your devices**: pick your ring, watch or other Bluetooth device from a scan, and the tag stays quiet while it is near.
- **Position without GPS**: an area (68 % and 95 %) from the repeaters the tag heard and a downloaded repeater map (about 60 MB, kept on the phone; the app never asks a server where a repeater is). Wi-Fi reports can be looked up through BeaconDB, the GrapheneOS proxy or Apple, only when you choose.
- **When a tag is late**: the app says the most likely reason in one sentence and shows what it is based on. It can see that the tag is in update mode on the cable, that it keeps restarting, that the phone itself is offline, that a bridge heard a report that never reached your relays, that the battery is probably empty, or that the schedule differs between the tag and the app. Each reason comes with the one action that settles it, such as "Check if it's here" over Bluetooth, "Finish the update" or "Add relay", and "All checks" lists everything the app looked at. One missed report on a shared mesh no longer counts as silent: an unguarded tag is reported silent after the second missed report. A guess (the tag may be quiet near you) never turns a warning off; only evidence does.
- **Notice my tags over Bluetooth** (Settings, on by default, Android 12+): a low-power background scan notices when one of your tags restarts near the phone. It needs no location permission.
- **Alerts**: not heard when expected, low battery (25 % in the app and as a Nostr DM; below 10 % the tag sends the DM itself), Lost mode, and Guard (the tag moved more than 300 m).
- **Key backup**: a 12-word recovery phrase, plus a copy encrypted to your npub on Nostr relays. The web app can restore from either.
- **Firmware update** of a Wio tag over the USB cable.
- English, Slovak, Czech, German, Spanish and Dutch.

## What you need

- A tag with LibreTag firmware 0.9.0 or newer (0.9.4 recommended: it marks the first report after a restart).
- For the "own node" path: a MeshCore companion node and **MeshCore Open with the Local API**. Today that is the fork build at https://github.com/jooray/meshcore-open/releases/tag/localapi-test-9. The changes are proposed upstream in zjs81/meshcore-open pull requests #619, #628 and #670. Without it, the app still works over Nostr through public bridges.

## Upgrading from earlier test builds

0.11.0 introduced the signed mailbox. Tags on firmware before 0.9.0 send reports this app cannot open; update them over USB from the tag's Service screen.

## Known limits

- The phone's Bluetooth command advert has not been tested on air against a tag, nor has the extra advert for a report the phone may have missed.
- Restarting a Heltec ESP32 tag out of its download mode from the phone, and recognising that mode over the cable, have not been tried on hardware yet.
- No reproducible build yet. Check the certificate above before installing.
