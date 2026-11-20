"""Transmit policy of firmware 0.7.6+ (docs/design/fw-0.7.6.md section 3).

Python mirror of the section "0.7.6: transmit policy" in firmware/lt_core/lt_core.{c,h}: the TRACK/LOST
interval floor, the rolling 24 h flood cap, the LOST slow-down after 2 h at one place, and the
low-battery report once per crossing. Names follow lt_core without the `lt_` prefix. The numbers in
host/tests/test_sim.py are the ones the lt_core host test (`test_076`) gives for the same inputs.
"""

from __future__ import annotations

from dataclasses import dataclass, field
from enum import Enum

from .lt2 import Mode
from .wake import STILL_RADIUS_M, distance_m

TRACK_MIN_S = 120         # LT_TRACK_MIN_S: TRACK/LOST interval floor (0.7.5 allowed 30 s)
FLOOD_CAP_24H = 200       # LT_FLOOD_CAP_24H: floods in a rolling 24 h
CAP_FALLBACK_S = 3600     # LT_CAP_FALLBACK_S: over the cap every mode runs at max(IDLE interval, this)
LOST_FAST_S = 7200        # LT_LOST_FAST_S: LOST keeps the set interval for the first 2 h ...
LOST_SLOW_FACTOR = 2      # LT_LOST_SLOW_FACTOR: ... then x2 while the fixes stay within the still radius
TX_MIN_MV = 3250          # LT_TX_MIN_MV: no transmission below this
TX_RESUME_MV = 3350       # LT_TX_RESUME_MV: ... until this (or USB power)
LOW_REPORT_PCT = 10       # LT_LOW_REPORT_PCT: crossing below sends one report (and the DM)
LOW_REARM_PCT = 20        # LT_LOW_REARM_PCT: at or above, armed again
LOW_REPORTED = 1          # LT_LOW_REPORTED
LOW_DM = 2                # LT_LOW_DM
BOOT_DELAY_S = 30         # LT_BOOT_DELAY_S
BOOT_BACKOFF_MAX_S = 3600  # LT_BOOT_BACKOFF_MAX_S


def clamp_track_s(interval_s: int) -> int:
    """A TRACK/LOST interval as the tracker keeps it (SET_MODE, console `track`, a resumed mode)."""
    return max(int(interval_s), TRACK_MIN_S)


@dataclass
class TxCap:
    """lt_txcap_t: floods in 24 hourly buckets. A bucket remembers its hour number + 1 (0 = empty)."""

    hour: list[int] = field(default_factory=lambda: [0] * 24)
    count: list[int] = field(default_factory=lambda: [0] * 24)

    def add(self, now_s: float, n: int = 1) -> None:
        h = int(now_s) // 3600 + 1
        i = h % 24
        if self.hour[i] != h:
            self.hour[i] = h
            self.count[i] = 0
        self.count[i] = min(self.count[i] + n, 0xFFFF)

    def total(self, now_s: float) -> int:
        """Floods in the hour of now_s and the 23 before it (lt_txcap_count)."""
        h = int(now_s) // 3600 + 1
        return sum(c for hb, c in zip(self.hour, self.count) if hb and hb <= h and h - hb < 24)

    def over(self, now_s: float) -> bool:
        return self.total(now_s) >= FLOOD_CAP_24H


@dataclass
class LostState:
    """lt_lost_state_t: the reference is the first fix of a stretch at one place."""

    ref_valid: bool = False
    still: bool = False
    lat_e7: int = 0
    lon_e7: int = 0

    def note_fix(self, lat_e7: int, lon_e7: int, radius_m: int = STILL_RADIUS_M) -> None:
        if self.ref_valid and distance_m(self.lat_e7, self.lon_e7, lat_e7, lon_e7) <= radius_m:
            self.still = True
            return
        self.ref_valid, self.still, self.lat_e7, self.lon_e7 = True, False, lat_e7, lon_e7

    def interval_s(self, base_s: int, elapsed_s: float) -> int:
        """lt_lost_interval_s; elapsed_s: time since LOST began."""
        if elapsed_s >= LOST_FAST_S and self.ref_valid and self.still:
            return base_s * LOST_SLOW_FACTOR
        return base_s


def base_interval_s(mode: Mode, idle_s: int, track_s: int, over_cap: bool, lost: LostState | None = None,
                    lost_elapsed_s: float = 0) -> int:
    """lt_base_interval_s: the interval before jitter."""
    if over_cap:
        return max(idle_s, CAP_FALLBACK_S)
    if mode == Mode.LOST:
        return lost.interval_s(track_s, lost_elapsed_s) if lost else track_s
    return track_s if mode == Mode.TRACK else idle_s


class OverCap(Enum):
    """Transmissions the cap never blocks (fw-0.7.6.md 3, "still allowed")."""

    CMD_ACK = "cmd_ack"          # report n + 1 with CMD_ACK, and the BLE_SCAN packets of a command
    CONSOLE = "console"          # console `report` / `test`
    LOW_BATTERY = "low_battery"  # the report of a crossing below LOW_REPORT_PCT


def report_allowed(over_cap: bool, why: OverCap | None = None) -> bool:
    """May a wake-up that would report send it? Over the cap only the OverCap exceptions may."""
    return not over_cap or why is not None


def low_rearm(flags: int, pct: int) -> int:
    """lt_low_rearm: pct 255 means unknown and changes nothing."""
    return 0 if pct != 255 and pct >= LOW_REARM_PCT else flags


def low_report_due(flags: int, pct: int) -> bool:
    """lt_low_report_due"""
    return pct < LOW_REPORT_PCT and not flags & LOW_REPORTED


RST_PIN, RST_POWER_ON, RST_SOFT, RST_DEBUG, RST_BROWNOUT, RST_WATCHDOG, RST_CRASH = 1, 2, 4, 8, 16, 32, 64


def reset_deliberate(rst: int, boot_mv: int, external_power: bool) -> bool:
    """lt_reset_deliberate: reset button, power switch, cable or flashing, not a brown-out, watchdog,
    crash or unannounced software reset. A boot with no flag (the nRF52 reports power-on and brown-out
    alike that way) counts with the battery at TX_RESUME_MV or more, on USB, or with no reading."""
    if rst & (RST_BROWNOUT | RST_WATCHDOG | RST_CRASH):
        return False
    if rst & (RST_PIN | RST_POWER_ON | RST_DEBUG):
        return True
    if rst & RST_SOFT:
        return False
    return external_power or boot_mv == 0 or boot_mv >= TX_RESUME_MV


def boot_resets_next(prev: int, announced: bool, deliberate: bool) -> int:
    """lt_boot_resets_next: 1 after a deliberate boot or `reboot`/`dfu`, else one more (at most 250)."""
    return 1 if announced or deliberate else min(prev + 1, 250)


def vector_boot_resets() -> dict:
    rst = [0, RST_PIN, RST_POWER_ON, RST_SOFT, RST_DEBUG, RST_BROWNOUT, RST_WATCHDOG, RST_CRASH,
           RST_PIN | RST_SOFT, RST_PIN | RST_BROWNOUT, RST_POWER_ON | RST_WATCHDOG]
    return {
        "deliberate": [[r, mv, int(ext), int(reset_deliberate(r, mv, ext))]
                       for r in rst for mv in (0, 3300, 3350, 4100) for ext in (False, True)],
        "next": [[p, int(a), int(d), boot_resets_next(p, a, d)]
                 for p in (0, 1, 3, 4, 249, 250) for a in (False, True) for d in (False, True)],
    }


def boot_delay_s(resets: int) -> int:
    """lt_boot_delay_s: 30 s after a normal boot, 2 min after two resets in a row, doubling up to 1 h."""
    if resets <= 1:
        return BOOT_DELAY_S
    return BOOT_BACKOFF_MAX_S if resets - 1 >= 6 else min(60 << (resets - 1), BOOT_BACKOFF_MAX_S)
