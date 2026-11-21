# Nostr bridge for Android 0.5.0 (preview)

The Nostr bridge (`app.nostrmesh`) moves to the shared MeshCore Local API client. What it bridges, and how, stays as in 0.4.0.

## You need a newer MeshCore Open

The bridge speaks the [MeshCore Local API](https://github.com/jooray/meshcore-local-api), protocol 1. It signs only the new pairing string `meshcore-local-api-pair-v1:<nonce>`, so it needs MeshCore Open test build **`localapi-test-10` or later** (https://github.com/jooray/meshcore-open/releases/tag/localapi-test-10). With older builds it cannot pair.

The pairing key and the replay cursor are kept, so an approved bridge stays approved.

## What changed

- The bridge's own Local API client is gone. It uses the `meshcore-local-api` Kotlin library (v0.1.0).
- Features follow `hello.capabilities` strictly:
  - zero-hop and direct sends only with `tx_path_data`, the path of a received report only with `rx_path`;
  - the LoRa preset is read from `radio.stats` only with `radio_stats` (before, `airtime` was enough). Without it the bridge uses its configured preset;
  - a flood region only with `regions`. Zero-hop sends carry no region.
- Changing the channels in Settings restarts the Local API session instead of stopping and starting the whole radio link.

## Install

Download `nostrmesh-bridge-0.5.0.apk` from the release, check the certificate with `apksigner verify --print-certs` (SHA-256 `79750fb49349ec3729be5a3e825507a15e47c6d987e854ae91d5d50a20b3a24d`) and install it over 0.4.0.
