# LibreTag tracker firmware 0.9.5 (preview)

This release has one change. The owner's phone can open the tag's Bluetooth console from next to the tag, even when it has missed the tag's reports.

## What changed

- The tag now also looks for a "console wake" advert from the owner's phone in every Bluetooth scan it runs at a wake-up. When it finds one from its owner, it opens the Bluetooth console for 3 minutes, as the `BLE_CONSOLE` command does.
- The advert is keyed with the pairing's USB key, not with the report counter. A phone that missed reports, or a tag that moved on to new keys while out of range, still match.
- The same rules as for the command apply: a paired tag, a battery above the transmit floor, and no window after 5 failed logins since the last restart. The tag sends no report for it.
- Each advert carries a random salt, so adverts of two sessions cannot be linked, and the tag refuses a salt it already took since its last restart.
- Console: `blecmd` shows `wake_seen=` and `wake_taken=`.

## Why

In the field a tag was out of mesh range for almost two days. Next to it, the owner asked the app to open Bluetooth. The command was sealed for the last report the phone had heard, the tag had long moved past it, and it never matched.

## Install

As for 0.9.4: UF2 drag-and-drop or serial DFU for the Wio Tracker L1, esptool for the Heltec (`README.md`). The pairing survives the update. The app needs 0.13.1 to send the wake advert.

## Known limits

- The wake advert has been tested against the reference vectors only, not yet on air between a phone and a tag.
- The window opens at the tag's next wake-up, so it can take up to one wake-up interval.

## Source

ZephCore commit `da3513aa6d08` (pinned in `firmware/zephcore.lock`) plus the 41 patches in `firmware/zephcore-patches/`.
