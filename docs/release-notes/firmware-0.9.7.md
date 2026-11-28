# LibreTag tracker firmware 0.9.7 (preview)

When a tag goes silent, this release lets you find out afterwards what it was doing. The radio and the reports are unchanged.

## What changed

- The tag keeps a record of each start: why it restarted (the raw reset register of the chip), its battery, and what it was doing just before: which step of its work cycle, how long it had been running, the report counter. The last 16 to 32 starts are kept.
- The tag keeps a log of its state changes: quiet zone entered or left, mode changes, network switches, parked or moving, low battery for sending, and one "alive" record an hour.
- A hardware watchdog. If the tag gets stuck in one step of its cycle for too long, it restarts by itself within about 25 minutes, and the start record says where it was stuck. Before, a stuck tag stayed silent until someone pressed reset or the battery ran out.
- If the radio lost a packet while sending, the tag could wait for that packet for good and never wake up again. Now it counts the packet as lost and goes on.
- Console: `boots` and `events` print the new records; `status` shows the last reset, what the previous run was doing, and the watchdog.

## Why

A tag went silent for about 14 hours overnight, used a quarter of its battery, and came back after a reset. Nothing on the tag could tell whether it had hung, restarted, or stayed in a state that never reports.

## Install

As for 0.9.6: UF2 drag-and-drop or serial DFU for the Wio Tracker L1, esptool for the Heltec (`README.md`). The pairing, the settings and the zones survive the update.

## Known limits

- The records about the previous run survive a restart, not a battery that runs empty.
- The Heltec board has no watchdog in this release; it keeps the start records and the event log.

## Source

ZephCore commit `da3513aa6d08` (pinned in `firmware/zephcore.lock`) plus the 43 patches in `firmware/zephcore-patches/`.
