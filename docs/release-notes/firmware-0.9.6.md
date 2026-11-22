# LibreTag tracker firmware 0.9.6 (preview)

This release fixes one case: a tag that comes back across a border without a GPS fix.

## What changed

- The tag remembers the network it was on before it last switched networks by country (radio settings and region). The memory survives a restart.
- When a report has no GPS fix and the tag has heard nothing on its current network for 3 listening windows in a row, it sends the report a second time on that previous network.
- The second copy goes out only if the previous network uses other radio settings than the current one, the network is not set by hand, and the tag is under its daily limit of 200 transmissions.
- The tag still changes its network only after a GPS fix. It listens on its current network after the copy, so it can't tell whether anyone on the previous network heard it.
- Console: `status` has a `previous network:` line, and `network` without an argument shows the current and the previous network.

## Why

In a field test a tag crossed from Slovakia into Czechia and switched to the Czech network. It came back in a bag without a GPS fix and kept sending on the Czech network, where nobody could hear it.

## Install

As for 0.9.5: UF2 drag-and-drop or serial DFU for the Wio Tracker L1, esptool for the Heltec (`README.md`). The pairing and the settings survive the update.

## Known limits

- Built for both boards, not yet run on a tag.
- While the tag stays silent without a fix, each report costs two transmissions instead of one.
- The Heltec board has no GPS, so it never switches networks and never sends this copy.

## Source

ZephCore commit `da3513aa6d08` (pinned in `firmware/zephcore.lock`) plus the 42 patches in `firmware/zephcore-patches/`.
