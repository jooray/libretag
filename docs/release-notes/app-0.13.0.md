# LibreTag Android app 0.13.0 (preview)

This release changes how the app talks to MeshCore Open. The tags, the map and the commands work as in 0.12.0.

Signing certificate SHA-256: `79750fb49349ec3729be5a3e825507a15e47c6d987e854ae91d5d50a20b3a24d`. Android 8.0 or newer.

## You need a newer MeshCore Open

The "own node" path now speaks the [MeshCore Local API](https://github.com/jooray/meshcore-local-api), protocol 1, through its shared client library. Pairing signs only the new string `meshcore-local-api-pair-v1:<nonce>`, so the app no longer pairs with older MeshCore Open builds.

- Install MeshCore Open test build **`localapi-test-10` or later**: https://github.com/jooray/meshcore-open/releases/tag/localapi-test-10. With `localapi-test-9` or older the connection fails (Connections shows the error), and the app works over Nostr only.
- Your pairing is kept. The app uses the same key as before, so MeshCore Open does not ask you to approve it again unless you reinstalled MeshCore Open.

## What changed

- The app's own Local API client is gone. It now uses the `meshcore-local-api` library (Kotlin, v0.1.0), the same one the Nostr bridge uses.
- The app uses a server feature only when the server lists it in `hello.capabilities`. Zero-hop commands from your own node still need `tx_path_data`; without it the app does not send them from the node, so they never turn into a flood. A region on floods is used only when the server lists `regions`.
- Reports heard while the app was off are fetched again on reconnect only when the server lists `replay`. A report the server sends twice is shown once.

## Upgrading

Update MeshCore Open to `localapi-test-10` or later first, then LibreTag. Tags need no update; firmware 0.9.4 stays current.
