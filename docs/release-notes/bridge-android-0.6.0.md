# Nostr bridge for Android 0.6.0 (preview)

The Nostr bridge (`app.nostrmesh`) now speaks the quiet mesh of tracker firmware 0.10, the same way as the Python bridge 0.7.0. It works only with app 0.14 and tracker firmware 0.10; older tags and apps are not supported.

## What changed

- When the owner's app answers a report with an ACK, the bridge that heard the report sends it back zero-hop, after a short random wait in which it listens for the same ACK from another bridge. For a report that came through one or two repeaters with a path, it sends the ACK along that path. It never floods an ACK, and it keeps its own hourly limit for them.
- Commands go zero-hop, along a path or as a flood, depending on how the report was heard. A command along a path that the tag doesn't answer within 12 s goes out once more as a flood. Timed commands go at their slot: an exact alarm and a wake lock start 10 s before, and a refused send is retried until 20 s after the slot. The bridge publishes "ack sent" and "miss" notes so the app knows what happened.
- A report heard directly, or through one or two repeaters with a path, goes to the relays within a second when nothing waits for it. A frame MeshCore Open queued while the bridge wasn't reading gets an `approx` time.
- The bridge watches MeshCore Open's state, failed sends and long silence, and says so in its hourly announcement, at once when the state changes.
- Since 0.5.1 it keeps a field-test log of what the node heard (with the route, path, SNR and RSSI when MeshCore Open gives them), what went out and what was published per relay.
- `nos.lol` is no longer in the default relays.

## Install

Download `nostrmesh-bridge-0.6.0.apk` from the release, check the certificate with `apksigner verify --print-certs` (SHA-256 `79750fb49349ec3729be5a3e825507a15e47c6d987e854ae91d5d50a20b3a24d`) and install it over 0.5.0. It needs MeshCore Open test build `localapi-test-10` or later and uses only the features that build lists. On Android 12 and newer, allow the bridge to set alarms and reminders (Settings > Apps > Special app access > Alarms & reminders), or timed commands may go out late.
