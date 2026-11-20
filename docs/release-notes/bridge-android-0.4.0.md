# Nostr bridge for Android 0.4.0 (first public release, preview)

The Nostr bridge (`app.nostrmesh`) turns a phone with MeshCore Open into a MeshCore to Nostr bridge. The radio stays in MeshCore Open and chat keeps working; the bridge uses its Local API.

## What it does

- **Radio to Nostr**: LibreTag reports and Bluetooth packets from `#libretag` go up as kind 4243. The bridge cannot read them; it signs each event with a fresh random key and skips what is already on the relays. Roadstr road reports and messages from registered devices go up too, and low-battery DMs from any tag reach the owner's inbox relays.
- **Nostr to radio**: commands from the LibreTag mailbox (kind 4244). Since 0.4.0 the bridge only sends entries signed with the key the tag's own report committed to (the signed mailbox, PROTOCOL.md 4.2), so a stranger cannot make it transmit. The bridge that heard the report directly sends zero-hop; others flood. DMs to registered devices are off by default.
- **Staying up**: an outbox that survives restarts, a radio watchdog after 30 minutes of silence, an hourly `status.json` heartbeat, and a limit of 10 uplinks per minute per first hop on `#libretag`.
- A bridge identity, announcements and optional "heard" receipts (kind 38244, off by default).

## What you need

MeshCore Open with the Local API (fork build https://github.com/jooray/meshcore-open/releases/tag/localapi-test-9; upstream pull requests zjs81/meshcore-open #619, #628, #670) and a companion node.

## Install

Download `nostrmesh-bridge-0.4.0.apk` from the release, check the certificate with `apksigner verify --print-certs`, install, then turn on the Local API in MeshCore Open and the switch in the bridge. MeshCore Open asks you to allow it and to add the `#nostr` and `#roadstr` channels.

## Compatibility

Needs tags on firmware 0.9.0 or newer and the app 0.11.0 or newer for commands. Version 0.4.0 removed the older mailbox format and the proof of work.
