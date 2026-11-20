# LibreTag web (PWA), Nostr only

Reads your tags' reports from Nostr relays (kind 4243), shows them on a map, and sends commands
through the Nostr mailbox (kind 4244). Design notes for this release: `../docs/design/pwa-0.2.md`. No radio, no cable, no server of ours. Planned home:
`https://cypherpunk.today/libretag/app/` (static files, relative paths, nothing deployed from here).

    npm install
    npm test            # vitest: LT2 vectors, key backup vectors, merge, mailbox, status, i18n
    npm run build       # dist/ with sw.js and version.json
    npm run dev

## Getting the keys (first one that applies)

1. **Sign in with Nostr** (SPEC 12.2 B): NIP-07 extension, `bunker://`, or `nostrconnect://` QR (Amber).
   The owner's kind 30078 events are fetched without any marker, NIP-44 (to self) decrypted through the signer.
2. **Phrase** (SPEC 12.2 A): BIP-39 phrase of 12 or 24 words, checksum validated, then SPEC 12.1 derivation.
3. **One tag**: paste or scan a key. Accepted: 64 hex characters, `libretag://k0?v=1&k=<K0 hex>&name=..&type=person|thing|vehicle&mark=<color>-<symbol>`
   (also `libretag:k0?...`), and the Android app's `libretag://share?...` (V_e / C_e from an epoch; view-only without `ce`).
   Single tags are never written to the backup.

Reading never needs a login. The signer is only used for backup copy B.

## Privacy

"Stay signed in on this device" (checkbox on the sign-in screen, on by default) keeps you signed in across reloads and
browser restarts. It stores in IndexedDB one AES-GCM blob with the NIP-46 session (client secret key, remote signer,
relays, your pubkey) or the NIP-07 choice, plus a cache of the tags (their keys, names, reports read so far) and, after a
phrase restore, the key derived from the phrase (never the phrase). The AES key is a WebCrypto `CryptoKey` made once with
`extractable: false` and kept in the same IndexedDB. On load the cache opens at once, with no signer round-trip; the
signer is contacted only when something must be signed (saving the backup after a rename). If it does not answer, the app
keeps working read-only, shows "Signer offline" and writes the change when the signer is back.

What someone with your browser profile gets: the blob and the key sit side by side, so the profile is enough to open the
cache **in that browser**. The key cannot be exported (no script and no profile copy yields its bytes outside that browser
profile's own crypto store), but anyone using your unlocked browser sees your tags, history and positions, can send them
commands, and can ask your signer to decrypt your backup (the signer may still ask you to approve). Anyone with the
profile files on disk should be assumed to get the same. Hence: on a shared or borrowed computer uncheck the box, and
always use **Sign out** in the menu, which deletes the whole IndexedDB (session, cache and key). Without the checkbox keys
live in memory only. Across the automatic version reload they pass through `sessionStorage` (gone with the tab) and are
deleted the moment the new page reads them.
Versions before 0.1.5 could keep a passphrase-locked blob in `localStorage`; it is now offered once for unlock and
conversion, or can be forgotten.
No analytics. Outside connections: your Nostr relays, `wss://purplepag.es` (only when sending the welcome message, to
look up the owner's DM inbox relays, kinds 10050 and 10002), OpenStreetMap tiles, and the support page only if tapped.

Reports are read without a `#y` filter: the app asks for every kind 4243 since its last sync (paginated per relay) and
matches them locally, so a relay does not learn which tags belong to one tag. Above 20 000 events per relay and sync it
falls back to the per-tag `#y` windows for the rest of the session. Commands (kind 4244) carry a group tag `g` and are
signed with the mailbox key of the report they answer (PROTOCOL.md 4.2); anchors for the next 8 reports are kept
published for every tag the browser can command, and a command queued right after a report also goes out as a late
entry for it. No proof of work. The welcome DM is a NIP-17 gift wrap from the tag's key (NIP-04 only when
the owner has no kind 10050 list). History of a tag can be saved as GPX or CSV (under the timeline).
The area estimate without GPS needs the 60 MB public repeater map and is not done on the web (said so in the UI).

## Backup freshness

The menu shows which backup the web app is using ("Backup from 23:12 (your Nostr account)") and has "Check for a newer
backup" (read both copies, merge per SPEC 12.2, apply, write nothing). The same check runs when the app returns to the
foreground and every 10 minutes while open, so changes from the Android app appear on their own.

## Versioning and refresh

`package.json` version is the release. The build writes `version.json` and a `sw.js` that differs per release.
The page checks on load, on return to the foreground, when online again and every 15 minutes (service worker update
and `version.json`), then reloads by itself (waits while a passphrase is being typed) and shows "Updated to version X".

## Local demo (synthetic data, coordinates near 48.70 / 21.25)

    (cd ../host && uv run python ../pwa/tools/gen_demo.py > ../pwa/tools/demo-reports.json)
    node tools/devrelay.mjs 7777          # in-memory relay with reports, backup A and backup B
    node tools/fake-signer.mjs 'nostrconnect://...'   # NIP-46 signer for the demo user
    npm run build && npm run preview
    # add ws://127.0.0.1:7777 as a relay; phrase: legal winner thank year wave sausage worth useful legal winner thank yellow

## Backup contents used by the web app

Per tag: `name, k0, type, mark, updated`, plus `interval_min` (check times and timed mailbox slots), `zones`
(zone names, "Left Home 14:02" alarm with the 50 m hysteresis, zone circles on the map). Every other field
(`check_s`, `still`, `use`, `fw`, `anchors`, unknown ones) is kept as it came and written back unchanged.
The web app can also make a backup: menu, "Back up my keys" (12-word BIP-39 phrase, shown once, words 3, 7, 11
confirmed, copy A; copy B too when signed in). A single-tag import can be added to the backup from its edit dialog.
