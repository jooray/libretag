# LibreTag app: design brief (shape, 2026-11-16)

Status: confirmed 2026-11-16. No code. Visual direction: **Trail Blaze** (picked on the decision page, sketch `.impeccable/mocks/decision/model-pick.html`). Surface mode: **Operate**. It plans for the target feature set from SPEC 3, 6, 11 and 12, not only today's code.

## 1. Who and why

- **Who:** an owner of 3–10 tags (car, backpack, keys, child, partner), technically capable (MeshCore, Nostr), or a person the owner shares a tag with (partner, grandparent) who is less technical.
- **Three moments of use:**
  1. **Glance** (daily, 5 s): "is everyone OK?"
  2. **Search** (rarely, under stress): the car is gone, the child is late. Where is it, how long ago, how sure, what can I do and when will it take effect.
  3. **Service** (at a desk with a cable): pair, set up zones and known devices, download history, update.
- **Success:** on every card it is clear within 2 seconds **what is going on, how old it is, how certain, when the tag will report next and what can be done.**

## 2. Direction: Trail Blaze

A marked hiking trail and its signpost. Every tag has a **blaze** (color + symbol, like a hiking trail marker). Its status reads like a signpost: where it is, how many minutes ago, and when the next blaze comes.

- **The world supplies four things, no more:**
  - type (Archivo and Archivo Narrow with tabular figures);
  - palette (white, graphite, and red, blue, green and yellow blazes);
  - density (compact rows like the plates on a signpost);
  - one signature stroke: **the tag blaze and the signpost row**.
- **Navigation and controls are standard Material 3:** top app bar, bottom sheet, buttons, chips, snackbar, dialogs and the Back gesture.
- **Blazes for 10 tags:** 4 marking colors × hiking-trail symbols:
  - stripe (main trail);
  - diagonal stripe (local);
  - triangle (summit);
  - circle (spring);
  - square (ruin).

  The shape tells tags apart even with color blindness, color alone never carries meaning.
- **Status colors** are separate from blaze colors. An alarm is a red band across the whole row plus an icon, not just a red blaze. A red tag blaze (for example the keys) therefore never means alarm.
- **Light and dark themes are equal.** Situations: car at night, mountains in direct sun. Dark theme: graphite background, the white stripes of the blaze stay, blaze colors do not change.

## 3. Communication language (EN primary, translations SK/CS/DE/ES/NL)

Principles: factual, no scaremongering, always with a time and a degree of certainty. One status sentence per tag. No technical jargon in the first layer (SNR, hop, epoch belong in the "Diagnostics" detail).

### Glossary (binding terms)

| Concept | EN | SK | Not |
|---|---|---|---|
| device | tag | tag | tracker, device, node |
| report | report | hlásenie | packet, beacon |
| age | heard 12 min ago | ozval sa pred 12 min | last seen |
| next | next report ~14:31 | ďalšie hlásenie ~14:31 | |
| position from GPS | GPS ±8 m | GPS ±8 m | exact |
| estimate | area ±1.2 km (68 %) | oblasť ±1,2 km (68 %) | approximate location |
| quiet on purpose | quiet at home / quiet, you're nearby | ticho doma / ticho, si pri ňom | offline |
| not reporting | silent for 3 h (expected hourly) | neozýva sa 3 h (čakám každú hodinu) | lost, offline |
| command waiting | waits for next report (~14:31) | odíde s ďalším hlásením (~14:31) | pending |
| path | via mesh / via Nostr | cez mesh / cez Nostr | |

### Primary tag status (exactly one, in priority order)

1. **Lost**: "Lost mode · reporting every 10 min" (red band).
2. **Alarm**: "Left Home 14:02" / "Moved while guarded" (red band).
3. **Silent** (past the deadline + margin): "Silent for 3 h · expected hourly" (orange band). If a `QUIET` came before, it is a quiet state, not an alarm.
4. **Low battery**: "Battery 18 % · charge soon" (orange band).
5. **Tracking**: "Tracking every 2 min until 15:10".
6. **Quiet**: "Quiet at Home · check-in 08:00" / "Quiet, Anna's ring nearby".
7. **OK**: "Heard 12 min ago · next ~14:31".

The second line always states the **position and certainty**:
- "GPS ±8 m · 48.70120, 21.25410";
- "No GPS · area ±1.2 km (68 %)";
- "No GPS · can't tell which repeater (code shared abroad)";
- "Last GPS 3 h ago".

**The app does not show place names** (an offline database would not be worth it for that, and an online geocoder is forbidden). Every position has two one-tap actions:
- **Open in map**: a `geo:` intent with an app chooser (OsmAnd, CoMaps, Organic Maps, Google Maps; anything that can open `geo:`). For an estimate, the center of the area is sent and the radius goes in the label.
- **Copy coordinates**: to the clipboard as `48.70120, 21.25410`, snackbar "Coordinates copied".

### Commands (always with a delivery time)

- Button: **Beep** → snackbar "Beep goes out with the next report, around 14:31."
- On the row and in the detail a ticket stays: "Beep · waiting (~14:31)" → "Beep · sent 14:31" → "Beep · confirmed 14:32", or "Not confirmed. Will retry with the next report."
- **Lost** requires confirmation by dialog ("Reports every 10 min for 24 h. Uses about a third of the battery. Floods the mesh less than you'd think: one packet every 10 min.").

## 4. Information architecture

```
Tags (home: map + list)
├─ Tag detail (full height, swipe between tags)
│   ├─ Status (signpost + actions)
│   ├─ Timeline (time axis, scrubber)
│   └─ ⋮ Tag menu: Zones · Known devices · Alerts · Share · Service (USB) · Edit (name, type, mark) · Diagnostics · Remove
├─ + Add tag (USB pairing, wizard)
└─ ⋮ App menu: Settings · Repeater map · My Nostr key & backup · Connections (MeshCore Open, relays, bridges) · Support the project · About
```

No bottom navigation bar: the app has one main goal (tags). Settings are in the menu.

## 5. Screens

### 5.1 Home (hybrid)

- **Top app bar:** "LibreTag" and a connection indicator: "● mesh" / "● Nostr only" / "○ offline". A tap opens Connections.
- **Map (~40 % of the height):**
  - all tags as blazes with names;
  - a GPS position as a solid blaze;
  - an estimate as a dashed circle in the blaze color;
  - an old position faded with age ("3 h").
  - Buttons: "Fit all" and **"Hide map"**.
- **The list** is an M3 bottom sheet with signpost rows. Dragging up or scrolling collapses the map and the list fills the whole screen (the state is remembered).
- **Tag row (signpost plate):**
  - blaze;
  - name and type (person / thing / vehicle);
  - status sentence;
  - second line with position and certainty;
  - on the right the age ("12 min") and battery (icon and %);
  - a ticket for a waiting command.
- **Order:** alarms on top, then by age.
- **Empty state:** "Plug a tag into this phone with a USB-C cable to pair it." and an illustration of a cable.
- **FAB:** "+ Add tag".

### 5.2 Tag detail

- **Map on top (~45 %):**
  - the tag's trail as a dashed line in the blaze color, reports numbered (report number `n`);
  - estimate: a hatched 68 % area and a 95 % outline;
  - a "Repeaters" toggle;
  - the last fix as a gray blaze with its age.
- **Signpost (status):** a large status sentence and four rows below it:
  - Where: GPS / area;
  - When: heard, next report;
  - Battery: %, runtime estimate "~5 weeks at this pace" (only after ~2 weeks of data);
  - Path: "via mesh, 0 hops" / "via Nostr (bridge)".
- **Actions:** four buttons in a 2×2 grid.
  - The main one is contextual and filled: **Lost** in an alarm, **Beep** when quiet at home.
  - The others are outline: Track, Beep, Normal.
  - Below them always "Takes effect with the next report (~14:31)".
- **Timeline:** a scrubber by days and reports as stations. Moving it updates the map and the signpost together. Stretches of "quiet at Home" are collapsed into one row.
- **Swipe left/right** switches between tags.

### 5.3 Add tag (wizard, USB)

1. "Plug in the tag". Detection: new / locked mine (unlock) / someone else's (pairing reset with a warning).
2. Name, type (person / thing / vehicle), blaze (a suggestion of a free color and symbol).
3. **Home zones:** the map centers on the tag's position, a tap adds a circle and dragging changes the radius. Location permission is not needed.
4. **Known devices:** the tag scans the BLE surroundings for 30 s and a "Seen now" list shows name, type and stability ("stable address" / "changes, can't use"). The owner ticks the devices.
5. **Alerts:** silent tag, low battery, left a zone and guarded spot.
6. "Locked to this phone. A welcome message from the tag went to your Nostr inbox." For the first tag there is also an offer to back up the keys to Nostr, for the web version.

### 5.4 Service (USB, when an existing tag is connected)

A "Car is connected" sheet opens automatically:
- "Charging";
- "History synced: 37 reports added";
- "Firmware 0.5.2 · up to date", or "0.6.0 available · Update" (DFU later; until then "Update from a computer" with a link to the procedure);
- shortcuts to Zones and Known devices.

### 5.5 Sharing

- "Share" → who:
  - **View only**: sees positions from now on, cannot send commands;
  - **View and control**: all commands including Lost.
- Transfer by QR code or Nostr DM. Revocation works only for the future and the app says so openly: "They keep what they've already seen."

### 5.6 Settings and menu

- **Settings:** Owner Nostr key (npub, QR), Language, Units, Theme, Notifications.
- **Connections:**
  - MeshCore Open: status, node, Local API;
  - Relays: a list showing which ones work;
  - Bridges: how many MeshCore<->Nostr bridges published recently.
- **Repeater map:** downloaded 12 days ago, 55,386 repeaters, an Update button. After 30 days the app reminds you by itself.
- **Key backup:** backup to Nostr for the web version.

### 5.7 Support the project (support widget)

- **Strip** (Support / Not now / Don't ask again), max 3× in a lifetime, never after "Don't ask again". Text: "LibreTag is free and open source. Support it and get an e-book." (SK/CS per ask.md §6). No price and no menu.
- **Moment of value** (not at startup, not during work, **never for a tag in the Lost, Alarm or Silent state**):
  - after a command is confirmed ("Beep · confirmed 14:32"), below the command block in the tag detail (`ref=command-confirmed`);
  - at the end of the wizard after a successful pairing (`ref=paired`).
- **A permanent entry** "Support the project" in the app menu (`ref=menu`), which works even after "Don't ask again".
- Support opens the system browser at `https://hackyourself.io/support/s/libre-tag?lang=…&theme=…&ref=…` (only `ACTION_VIEW`, the app sends nothing itself).
- Trigger: 7 days of use / 10 actions (opening a detail, a command, a pairing).
- Look: a signpost plate in the Trail Blaze style (white plate, graphite edge, yellow blaze), not a foreign card.

## 6. Tablet and foldable

- **Compact (phone, folded):** as above.
- **Medium (unfolded foldable, small tablet in portrait), M3 list-detail:**
  - on the left a list pane (~360 dp);
  - on the right the detail of the selected tag with the map;
  - on home with no selection the right pane shows the map of all tags.
- **Expanded (tablet in landscape):** three zones. List (360 dp), map (flex) and the signpost with actions and timeline (400 dp) as a side panel.
- **Transition on unfolding** keeps the selected tag and the map position, with no jump to home. In the half-open state (tabletop) the map is on top and the signpost with actions is below the hinge.

## 7. States the design must cover

- Tag count: 0 / 1 / 3 / 10 (scroll, clusters of blazes on the map).
- Without the repeater map ("Download the repeater map to estimate areas without GPS").
- Without a connection to MeshCore Open: "Nostr only", with reduced certainty of command delivery.
- Without internet and without mesh: data from cache and the age everywhere.
- Ambiguous repeater, old data (> 24 h), tag in Lost mode, charging, someone else's tag on USB, failed unlock.
- Long translations (DE/NL): rows wrap to two lines, never cutting off the age or the battery.
- Large font (font scale 1.3) and TalkBack: the status sentence is one element, age and battery are read as words.

## 8. Map: rules

- osmdroid, OSM tiles, muted style (fewer colors) so the blazes stand out.
- Certainty is drawn, not written: solid blaze = GPS; hatched circle = estimate; fading = age.
- No phone position and no location permission until the owner asks for it (the "My location" button in the Fit menu).
- Repeaters only on request, with name and SNR in the bubble.

## 9. Out of scope for this round

- The presentation website (Persuade, comp-first via Venice) and the PWA (same language, Nostr only, SPEC 12). They come as later rounds.
- Protocol changes (`next_in`, `QUIET`) are in SPEC 11; the design plans for them.

## 10. Decided 2026-11-16

- No place names: coordinates + "Open in map" (map app chosen via `geo:`) + "Copy coordinates".
- The battery runtime estimate shows only after ~2 weeks of data, until then "Battery estimate after 2 weeks of reports".
- Sharing "View and control" may send all commands including Lost.

## 11. Output of the next step

A clickable HTML prototype of all screens (home, detail with scrubber, pairing wizard, service, sharing, settings; light and dark theme; phone, unfolded foldable and tablet), published as an artifact for trying out on a phone. Then the implementation in Kotlin and `DESIGN.md` from the finished app.
