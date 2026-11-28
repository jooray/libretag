# Python bridge 0.7.0 (preview)

The `nostrmesh` bridge in `host/` now speaks the quiet mesh of tracker firmware 0.10. It works only with app 0.14, tracker firmware 0.10 and Android bridge 0.6.0. Older tags and apps are not supported. The kind 38244 announcement says `0.7.0`.

## What the bridge does now

- It passes confirmations back to the tag. A tag's regular report is a zero-hop hello. The bridge that heard it publishes it fast, and when the owner's app answers with an ACK (in the mailbox, kind 4244), the bridge sends it back zero-hop. If several bridges heard the report, only one of them transmits: before sending, each one listens briefly for the same ACK on air.
- It answers along a short path. For a report that came through one or two repeaters with a path, the bridge sends the ACK and commands back along that path instead of a flood. It keeps the path for 48 h from its last use. If a path send isn't confirmed, the tag floods on its own; the bridge never floods an ACK.
- Commands go at the tag's slots. Timed entries are sent at their time, as the plan for the report they name says (zero-hop, path or flood). A send the radio refused is retried until 20 s after its time, then the bridge publishes a "miss" note so the app knows. It also notes every ACK it sent.
- `regions` in `[radio]` lists the flood scopes the bridge serves, narrow first (the bridge's area, then the country). A reply goes in the scope of the report it answers.
- A frame the node queued while no bridge read it, or one older than 20 s, is published with an `approx` tag, and apps don't anchor a tag's schedule on it.
- The bridge watches the node for late answers, failed sends and silence, and tells the relays in its hourly announcement, at once when the state changes. With `degraded_reboot_s = 0`, the default, it only reports late answers and doesn't reboot the node for them.
- It keeps a field-test log in `~/.config/nostrmesh/fieldlog/` (JSON lines) of what it heard, published, sent and skipped. `libretag fieldeval` reads it together with the tag and app logs.

Details: `docs/nostr-mesh-bridge.md` 8.9, `PROTOCOL.md` 3.3, 3.4 and 4.3.

## Upgrading

```sh
cd host
git pull            # or copy the new tree
uv sync --frozen
```

Then change `bridge.toml` before restarting:

- `[radio] region = "sk"` is now `regions = ["<your area>", "sk"]`, narrow first. Use names from your node's region catalogue.
- `nos.lol` is no longer in the default relays. Remove it from `publish` if you copied the old list.
- The other new keys in `[libretag]` have defaults; `bridge.example.toml` explains them.

Then restart the service. With MeshCore Open, test build `localapi-test-10` or later; the bridge uses only what the server lists in `hello.capabilities`.
