# Python bridge 0.5.0 and tools (first public release, preview)

The bridge announces itself as version 0.5.0 (`BRIDGE_VERSION`, the signed mailbox level), the same as the Python package.

`host/` holds two command-line tools, run with `uv` from the repository:

- `nostrmesh`: the MeshCore to Nostr bridge, the reference for the Android bridge.
- `libretag`: the reference implementation of the LT2 protocol, a tracker simulator, a base station, a battery estimator and the test vector generator.

## The bridge

- Connects to its own MeshCore node over USB (companion protocol), to a KISS modem, or to MeshCore Open's Local API on a phone (`adb forward tcp:8787 tcp:8787`).
- Publishes LibreTag reports and Bluetooth packets as kind 4243, Roadstr road events, and messages and low-battery DMs from registered devices.
- Sends LibreTag commands from the signed Nostr mailbox (kind 4244): only entries signed with the key the tag's report committed to, zero-hop when it heard the tag directly, timed entries at the tag's next check.
- Optional: a bridge key with announcements and receipts (kind 38244), and relays to mesh DMs for registered devices (off by default).
- Airtime budget for the node's radio preset, per-first-hop limits, watchdog exit codes for a supervisor.

## Run it

```sh
cd host
uv sync
cp bridge.example.toml bridge.toml        # port, relays, region; [downlink] and [registry] optional
uv run nostrmesh listen -p /dev/ttyACM0 --add-channels
uv run nostrmesh run -c bridge.toml
```

As a service: `host/deploy/nostrmesh.service` (systemd user unit) or `host/deploy/local.nostrmesh.plist` (launchd). Steps in `docs/nostr-mesh-bridge.md`, section 8.11.

Requires Python 3.11 or newer and `uv`.
