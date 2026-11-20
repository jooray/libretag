# 0.7: checks (listening wake-ups), parked quiet, configurable intervals

Status: implemented in firmware 0.7.4, app 0.7.3, PWA 0.1.2, bridges (Python, Android 0.2.1), 2026-11-16. PROTOCOL.md 2.1, 3 and 4 are the reference; this note keeps the reasoning.

## Why

0.6 quiet means no TX and no RX: a tracker that is quiet (home zone, owner's phone near) cannot take a command until its heartbeat report (8 h owner, 24 h zone). The app then shows "takes effect with the next check-in (20:47)" eight hours ahead, although the tracker wakes every hour and would report at once if the owner's phone disappeared. And a car parked overnight reports "still here" every hour.

0.7 makes every wake-up without a report a **check**: the tracker wakes at a time the app can compute, listens briefly, and goes back to sleep. A command can then reach a quiet or parked tracker within one interval. And a tracker that has not moved goes quiet after a few reports (**parked**).

## Words (UI and docs)

- **report**: the tracker transmits (a position, battery, …).
- **check**: the tracker wakes up, looks (GPS, Bluetooth), listens for a command for a few seconds, and transmits only if something changed (it left the zone, the owner's phone is gone, it moved, a command came).
- UI shows two times when the tracker is quiet: **"Next report by 20:47"** (the heartbeat, at the latest) and **"Checks every hour, next ~13:47"** (sooner if it leaves you, the zone or the parking spot; a command is picked up then).

## Schedule (keyed, deterministic)

After report `n` (transmission start `t0`, tracker uptime), while the tracker stays quiet in IDLE, check `k = 1, 2, …` is at

```
t_k = t0 + Σ_{i=1..k} (base + jit_i)
base = interval_min × 60                       (the configured IDLE interval; no still_factor in 0.7)
span = base / 5
jit_i = (u16_le(HMAC(C_e(n), "LT2-w" || u32(n) || u16(i))[0:2]) mod span) − span / 2   (span 0 → 0)
```

The ±10 % jitter stays (an observer cannot read a period), but the app, which has `C_e(n)`, computes it. The heartbeat report is still exactly `next_in` after `t0`; checks at or after it are not made.

The app anchors `t0` at the receive time of report `n` (flood latency is a few seconds) minus nothing; the window covers it.

**Window:** at check `k` the tracker starts the receiver at `t_k − g_k` and keeps it on until `t_k + check_s + g_k`, where `g_k = 2 s + (t_k − t0) × 100 ppm` (clock drift allowance; 24 h → 10.6 s) and `check_s` defaults to 30 s (0.7.3; 60 s in 0.7.2, 20 s in 0.7.0). 0 = no listening at checks, 0.6 behaviour. The radio listens in parallel with the GPS search and Bluetooth scan; if they run longer the receiver stays on until they end (0.7.2), but the window the app relies on is only the above. Senders transmit at `t_k + 3 s`.

Why 30 s: a flood in the Czech/Slovak SF7 mesh takes about 1 s per hop (median, p90 1.6 s), 10 hops about 9 s, and even long paths rarely beyond 20–30 s (`docs/research/flood-latency.md`). 60 s mostly paid for the tail. On a slower preset the delay per hop grows, but less than the airtime: on SF8 / CR 4/8 networks (Germany, Poland, Saarland) the measured delay per hop was ×1.5–1.7 at the median and about ×2.0 at p90, while a packet's airtime is ×2.7. So the tracker listens `check_s × (0.4 + 0.6 r)`, with `r` the airtime ratio of a 35 B command packet against SF7 / 62.5 kHz / CR 4/5, never shorter than `check_s`, at most 180 s (`lt_check_window_s`, 0.7.4; 0.7.3 used `r` alone): 30 s becomes 59 s on SF8 / CR 4/8 (EU/UK Narrow, Switzerland). The app does not need to know; senders still aim at `t_k + 3 s`.

The report announces checks with flags bit 7 **`CHECKS`**: "while quiet I listen at checks; my IDLE interval is the one you configured". The app knows `interval_min` (it set it over USB or with `SET_IDLE`); a report with `CHECKS` but an interval the app does not know → the app shows only the heartbeat.

## Commands at a check

A command for check `k ≥ 1` after report `n`:

```
kd = HMAC(C_e(n), "LT2-c"  || u32(n) || u16(k))
xc = HMAC(C_e(n), "LT2-cx" || u32(n) || u16(k))[0:4]
```

(`k = 0`, the window right after the report, keeps the 0.6 derivation without `u16(k)`.) Same 17 B plaintext, same 29 B `Pc`, same `cmd_seq` rules. The tracker computes `xc` for its own `(n, k)` at each check and ignores everything else.

After executing a command at a check, the tracker sends report `n + 1` in the same wake-up (with `CMD_ACK`), followed by the normal RX window. A mode change (TRACK, LOST) ends the quiet stretch as before.

**Route (0.7.3):** flood, region-scoped as the sender's radio does, or zero-hop when the sender heard report `n` directly. The first version sent direct along the reversed path of report `n` and nothing beyond 3 hops; a bridge four hops away then sent nothing at all, and a tracker that the owner's phone keeps quiet may have moved with the owner. Commands are rare, so flooding them costs little. Sending ahead of `t_k` by an estimate per hop was considered and dropped: too early misses the window as surely as too late, and flood latency varies a lot.

**Who sends:**
- the app, over MeshCore Open's Local API, at `t_k + 3 s`, if it is running then;
- bridges, from the mailbox: kind `4244` gains optional tags `["at", "<unix s of t_k + 3>"]` and `["via", hex(x of report n, j = 0)]`. A bridge that caught report `x` (it keeps the hop counts of reports it heard for 48 h) transmits `Pc` at `at`, zero-hop if it heard the report directly, otherwise flood. A bridge that never heard report `x` sends nothing. Each check is its own entry with its own `y`, so a missed check leaves the next ones in place. Coordination over `24244` as before. Without `at` the 0.6 behaviour (send when the report with `y` is caught) is unchanged. The app publishes slot mailbox entries for the next few checks (e.g. up to 12, or until the heartbeat), each bound to its own `(n, k)`.

## Parked quiet (reason STILL)

A third quiet reason, after zone and owner (priority zone > owner > parked):

- IDLE, a fix, and the fix is within `STILL_RADIUS_M` of the position of the report that started the count, for `still_after` reports in a row (default 3, so 2–3 h at hourly) → `REPORT_QUIET` with reason STILL, then silent (checks only) until `still_hb` (default 6 h) after the last report.
- A fix outside the radius ends the stretch → report at that check (moved).
- No fix: fail-open (report), like zones.
- Encoding: `QUIET` set, `QUIET_OWNER` clear and **byte 2 bit 3** set = parked (mode values stay 0–3 in bits 0–2). Zone = `QUIET` only, owner = `QUIET | QUIET_OWNER`.
- `still_after = 0` turns it off. The 0.5 `still <max_min>` interval doubling is removed (checks need a fixed base).

## Settings (USB `set` and radio `SET_IDLE`)

- `interval <min>`: IDLE interval (app offers 15 min, 30 min, 1 h, 2 h, 3 h, 6 h).
- `check <s>`: listening at checks, 0–180 s, default 30 (0.7.3), scaled up on slower presets.
- `report every <n>` (0.7.3): report at every n-th wake-up, 1–24, default 1; the wake-ups between only listen (see below).
- `still <after> <heartbeat_h>` or `still off`.
- `heartbeat zone <h>`, `heartbeat owner <h>` (defaults 24 and 8, owner max 8 because a phone stolen with the car is a real case).
- `SET_IDLE` (0x03) keeps `u16 interval_min, u16 listen_s` and gains `u8 check_s` at offset 7 and `u8 report_every` at offset 8 (0.7.3; 0 = keep).

## Report every n-th wake-up (0.7.3)

Waking every hour to look (GPS, Bluetooth) and report is what costs battery and mesh airtime. Listening is cheap. With `report_every = R` the tracker in IDLE wakes every `interval` on the keyed schedule after each report, also when it is not quiet, but only wake-ups `k` with `k mod R = 0` look and decide. The others open the check window only: no GPS, no Bluetooth scan, no transmission. A command there makes it look at once and report `n + 1` with `CMD_ACK`. The normal report's `next_in` is `t_R − t0`, so the app shows "next report by" and "next check" as for a quiet tag. Quiet rules apply at the wake-ups that look, so a move is noticed at the next of those. The app's Reporting screen offers "Report every" (15 min to 6 h) and "Check for commands every" (15 min, 30 min, 1 h, or with each report), which give `interval` and `R`. The report flag `CHECKS` keeps meaning "I listen at checks"; the app knows `R` from its settings and the backup (`report_every`).

Battery (`uv run libretag energy --report-every`, estimates, SF7, 90 s RX after a report, 15 s fix, 34 s per listen-only wake-up): Wio Tracker L1 Pro 20.1 mAh/day reporting hourly against 16.9 mAh/day checking hourly and reporting every 3 h (85 → 100 days; the L76K standby current dominates). MeshTracker X1 8.6 against 5.6 mAh/day (109 → 168 days).

## Track interval

`SET_MODE` already carries `interval_s`. App: one tap on **Track** = every 5 min for 1 h; long press chooses 2, 5, 10, 15 or 30 min (and the duration stays 1 h). The firmware minimum stays 30 s.

## Use cases (app, setup and settings; no firmware switch)

- **Things** (car, bag, keys): quiet when the owner's phone is near (you are together; a thief does not carry your phone, and the 8 h cap covers the phone stolen with the car) and in home zones; parked quiet on.
- **People** (a child, yourself, protection against abduction): the owner's phone does not silence it (an abductor may take the phone along), quiet only in home zones; parked quiet off by default.
- Settings show the rule as one sentence, e.g. "Stays quiet at Home (150 m) or near Pixel 9. Still reports every 8 h, and at the next check (hourly) when that stops being true."

## Cost

A check adds `check_s + 2 g_k` seconds of RX at 8.55 mA. At hourly checks with the default 30 s that is about 34 s ≈ 0.08 mAh per check, about 2 mAh a day (60 s: about 3.5 mAh, 2 min: about 7 mAh). If the GPS search runs past the window the receiver stays on next to it (~8.5 mA on top of the GNSS ~19 mA, at most until the fix timeout). The GPS search at the same wake-up costs more; checks do not add wake-ups (the tracker already wakes every interval while quiet).

## Drift

nRF52840 with the 32.768 kHz crystal: ±20 ppm → 24 h ≈ ±1.7 s. If the board runs on the RC oscillator (±500 ppm calibrated) the 100 ppm allowance is too small: the firmware must use the crystal (check `CONFIG_CLOCK_CONTROL_NRF_K32SRC_XTAL` for the tracker boards) or raise the allowance.
