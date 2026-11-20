# Product

<!-- impeccable:product-schema 1 -->

## Platform

android

The main surface is the native Android app LibreTag (`android/`, Kotlin, Android Views with Material 3). The user guide `docs/guide.html` is a secondary web surface (static HTML, published as an artifact). The Android "Nostr most" app (`android-bridge/`) is out of scope for design work unless asked.

## Users

- **Primary:** the MeshCore community, Nostr people and freedom-tech enthusiasts. They already run MeshCore nodes and MeshCore Open, care about privacy, and don't want a cloud or an account.
- **Later, same app:** people who need to find someone or something without a phone signal or a company in the middle. Examples: a car or bag after theft, a child hiking in the hills, and eventually protection against kidnapping. The owner gives the tracker to a family member or puts it in a car, then checks on it from the app.
- The first real users are early testers (car, backpack).

## Product Purpose

LibreTag is a sleeping LoRa GPS tracker on the MeshCore mesh. Once an hour it sends an encrypted position, then listens briefly for commands. Only the owner can read where it is. The app pairs a tracker over USB, shows its positions and an estimated area when it has no GPS (from repeaters heard), sends commands (Lost, Track 1 h, Beep, Normal) that are delivered after the next report, and alerts on silence, low battery and leaving a guarded spot.

Success: the owner can tell where the tracker is, or roughly where it is and how sure that is, from the mesh alone, without a phone network, a server they don't control, or anyone else being able to follow it.

## Positioning

- No company in the middle. Reports travel over the community MeshCore mesh, plus Nostr relays through anyone's bridge.
- No permanent identity on air. Reports are sealed with a ratcheting key, carry a fresh tag each time, and are readable only by the owner.
- Works where phones don't: hills, forests, regions without coverage.
- When there is no GPS fix, the app estimates the area from the repeaters the tracker heard, and shows it as an area with a confidence, not as a fake point.
- Open hardware (Seeed Wio Tracker L1, SenseCAP X1), open firmware (ZephCore patches), open protocol (`PROTOCOL.md`).

## Operating Context

- Phone with MeshCore Open running the Local API, connected to the owner's MeshCore node (for example a companion radio in the car or pocket). Without it, reports come from Nostr relays published by bridges (`nostr.cypherpunk.today`, nos.lol, primal).
- Pairing and settings happen over a USB-C cable between the phone and the tracker. The phone also charges the tracker ("service charging"). After pairing the tracker's USB console is locked.
- Typical moments:
  - a glance at the main list ("last heard 12 min ago, 82 %");
  - a stressful search (the car is gone, the child is late) with a map, an estimate circle and commands;
  - setup at the kitchen table with a cable.
- Reports arrive at most hourly in normal mode, so the app must make it clear how old a position is and when the tracker will next be heard or can take a command.
- The phone may be GrapheneOS (no Google services). Avoid location permission unless it is truly needed.

## Capabilities and Constraints

- Commands take effect only after the tracker's next report, and the UI must say so ("goes out with the next report").
- Without GPS the position is an area (68 % / 95 % circles) built from a downloaded public repeater map (~55 000 repeaters, refreshed after 30 days). Repeater hash collisions can make an estimate impossible, and the app must say so instead of guessing.
- Planned (SPEC.md 11):
  - home zones where the tracker stays silent;
  - silence when known Bluetooth devices are near;
  - a timeline;
  - history sync over USB;
  - a firmware version check;
  - a battery life estimate;
  - "next report expected at".
- Map: osmdroid with OSM tiles. Location data never goes to external services such as geocoders.
- Release APKs are signed with the shared publisher key. Distribution through Zapstore happens only when explicitly asked.

## Brand Commitments

- Name: **LibreTag**.
- Language: **English is the primary UI language.** Translations: Slovak, Czech, German, Spanish, Dutch. Today the UI is Slovak only and hardcoded in Kotlin. It has to move to `strings.xml` with English as the base.
- Translations are always made by a Sonnet subagent, and every text (source and translations) goes through the humanizer skill. The rule is also recorded in `CLAUDE.md`.
- Voice: plain, factual, honest about uncertainty (age of a position, probability of an area, "I don't know which repeater this is"). No marketing tone and no fear-mongering, even in safety contexts.

## Evidence on Hand

- Working hardware: Wio Tracker L1 Pro with firmware 0.5.x. A test bridge runs next to a repeater node.
- Real field data: discover answers, SNR of repeaters, a test drive, and a known estimate error of about 1–1.5 km on real data.
- Explainer videos (EN, SK) exist outside the repo.
- Missing, so never invent them: user testimonials, user counts, measured battery life (no PPK2 measurement yet), and any certification or anti-kidnapping claims.

## Product Principles

1. **Privacy is the product.** Every feature is judged by what an outside observer, a thief with the device, or a relay operator learns from it.
2. **Honest about uncertainty.** Always show how old a position is and how sure the app is. Never show a guess as a fact.
3. **Little traffic on the mesh.** The community network is shared and slow, so every extra packet must earn its place.
4. **Works without infrastructure.** The mesh first, Nostr as a bonus, no account, no server of our own required.
5. **Calm under stress.** A frightened owner must find the position, its age and the next action at a glance.

## Accessibility & Inclusion

- Readable outdoors and in a car: strong contrast in light and dark themes. Contrast problems in the M3 theme have already been fixed once.
- Large touch targets for one-handed use under stress.
- The UI must work in all six languages, so layouts have to handle longer German and Dutch strings.
