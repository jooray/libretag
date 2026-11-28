# LibreTag tracker firmware 0.10.2 (preview)

This is the quiet mesh release. The tag now relies on the community repeaters as little as it can. Most of the time it sends nothing a repeater forwards, and a flood is the exception. Nothing in it works with apps or bridges older than app 0.14, Android bridge 0.6.0 and Python bridge 0.7.0, so update everything together.

It also includes 0.9.7 (start records, an event log and a watchdog, `firmware-0.9.7.md`) and 0.9.8 (a field-test log), which were not released on their own.

## The quiet mesh

- The regular report is a zero-hop hello. It's a full, encrypted report that no repeater passes on. Your own MeshCore node or a Nostr bridge close to the tag hears it.
- Only the owner confirms it. Your app opens the report and answers with a short ACK, and your node or the bridge that heard the report sends it back, again zero-hop. A bridge can't make an ACK, because it can't read the report.
- A flood is the fallback. The tag floods when a report has to arrive and nobody confirmed it, for example after it moved, after a command or in Track and Lost. A tag that stands still doesn't flood on a timer.
- A flood starts in the bridge area, then goes to the country, and only then out without a scope. It widens a level only when no repeater passed the narrower one on.
- For a tag one or two repeaters from a bridge, the bridge can answer along the path it learned, which saves a flood each time.
- Commands arrive by listening. The tag listens for commands in short slots that the app can work out, and transmits only if a command came.

The more bridges there are, the fewer floods. Anyone can run one (`docs/nostr-mesh-bridge.md`).

Details: `SPEC.md` 3.2 (behaviour and defaults), `PROTOCOL.md` 2.6, 2.7, 3.3 to 3.5 (wire formats), `docs/design/quiet-mesh.md` (the reasoning).

## Fixes from the field tests (0.10.1, 0.10.2)

- The Track interval now runs from one wake-up to the next. Before, the GPS search and the listen window were added on top, so a tag in Track reported less often than you set.
- When Track or Lost runs out, the tag goes back to IDLE before it builds the report. The last report then says IDLE and when the next one comes. Before, it still said Track, and the app raised a false "silent" alarm.
- `track` on the USB console starts at once instead of waiting for the next regular wake-up.
- In Track and Lost, "next report in" points at the start of the next wake-up again, as `PROTOCOL.md` says. 0.10.1 added the GPS search time, which the next search doesn't repeat.

## The field-test log (0.9.8)

The tag can keep a log of what it sent and heard, its listen windows, the GPS and its events, on the Wio's external flash. `libretag fieldlog` reads it over the unlocked USB console, and `libretag fieldeval` puts it together with the bridge and app logs. Positions are in it, so keep the files private.

## Install

UF2 drag-and-drop or serial DFU for the Wio Tracker L1, esptool for the Heltec (`README.md`). The app 0.14.2 carries this firmware and offers the update over the cable or Bluetooth. Pairing, settings and zones survive the update. A tag updated from 0.9.x starts in the FAR state and finds its NEAR link with its first confirmed report.

## Known limits

- Battery life with the quiet mesh hasn't been measured yet.
- The Heltec build has no GPS and no watchdog; it's for the bench.

## Source

ZephCore commit `da3513aa6d08` (pinned in `firmware/zephcore.lock`) plus the 50 patches in `firmware/zephcore-patches/`.
