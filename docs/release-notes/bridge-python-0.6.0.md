# Python bridge 0.6.0 (preview)

The `nostrmesh` bridge in `host/` now reaches MeshCore Open and `meshcored` through the shared MeshCore Local API client. The USB companion and KISS adapters did not change.

## You need a newer server

The `local_api` adapter speaks the [MeshCore Local API](https://github.com/jooray/meshcore-local-api), protocol 1. It signs only the new pairing string `meshcore-local-api-pair-v1:<nonce>`, so the server must be MeshCore Open test build **`localapi-test-10` or later** (https://github.com/jooray/meshcore-open/releases/tag/localapi-test-10) or a `meshcored` that sends the new prefix. Against an older server the bridge logs a pairing error and keeps retrying.

The key in `~/.config/nostrmesh/meshcore-open.json` is kept, so the fingerprint and the approval stay the same.

## What changed

- `LocalApiRadio` is a thin wrapper over `meshcore_localapi.LocalApiClient` (package `meshcore-localapi`, installed by `uv` from the v0.1.0 tag on GitHub, so the first `uv sync` needs network access and git).
- Features follow `hello.capabilities` strictly. The LoRa preset is read from `radio.stats` only with `radio_stats` (before, `airtime` was enough). The `[radio] region` is checked against `regions.list` and sent with floods only with `regions`; without it the bridge warns once and floods in the node's default scope. Paths go out only with `tx_path_data`.
- The frequency comes from `hello.device.radio`.
- The replay cursor has a new format. The cursor stored by 0.5.0 is not read, so frames heard while the bridge was stopped for the upgrade are not replayed.
- The kind 38244 announcement still says `0.5.0`: that number is the signed mailbox level, not the package version.

## Upgrading

```sh
cd host
git pull            # or copy the new tree
uv sync --frozen
```

Then restart the service. Nothing in `host/deploy/` changed.
