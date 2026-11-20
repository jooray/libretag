"""Battery-life estimator. Numbers marked 'est' are guesses until measured with a PPK2."""

from __future__ import annotations

from dataclasses import dataclass, replace

from .meshcore import lora_airtime_s
from .proto import REPORT_GRP_LEN


@dataclass(frozen=True)
class Device:
    name: str
    battery_mah: float
    rx_ma: float  # whole device with radio in RX
    tx_ma: float  # whole device while transmitting at full power
    gnss_ma: float  # GNSS acquiring/tracking (module only)
    mcu_active_ma: float  # MCU awake while waiting for GNSS
    sleep_ua: float  # whole board, GNSS fully off
    gnss_idle_ua: float  # extra while GNSS is "off" (backup or standby domain)
    source: str
    ble_scan_ma: float = 6.0  # 0.9.3: whole device during a passive Bluetooth scan (nRF52840 radio RX + CPU, est)


X1 = Device(
    "MeshTracker X1", 1100, 8.55, 110.5, 15.8, 3.0, 50, 10,
    "rx/tx/gnss: Seeed measurement sheet; sleep, gnss_idle, mcu: est",
)
L1_PRO = Device(
    "Wio Tracker L1 Pro", 2000, 6.5, 118, 25.0, 3.0, 50, 500,
    "all est: SX1262 + L76K datasheet-class numbers; L76K standby ~0.5 mA (GNSS supply never cut in ZephCore)",
)
DEVICES = {"x1": X1, "l1pro": L1_PRO}


# The live MeshCore preset of the tags and bridges (EU/SK narrow): SF7 / 62.5 kHz / CR 4/5.
LIVE_SF, LIVE_BW_HZ, LIVE_CR = 7, 62_500, 5


def preset_label(sf: int, bw_hz: float, cr: int) -> str:
    return f"SF{sf}/{bw_hz / 1000:g} kHz/CR4/{cr}"


@dataclass(frozen=True)
class Scenario:
    report_interval_min: float = 60
    listen_s: float = 90
    fix_s: float = 15
    tx_count: int = 1  # how many times the report is sent
    sf: int = LIVE_SF          # SF11 / 250 kHz up to 2026-11-17, which matches no live preset
    bw_hz: float = LIVE_BW_HZ
    cr: int = LIVE_CR
    usable: float = 0.85
    check_interval_min: float | None = None  # 0.7.3: wake (listen only) this often between reports
    check_listen_s: float = 34  # receiver per listen-only wake-up: check_s 30 + 2 x 2 s drift guard
    ble_cmd_s: float = 1.5  # 0.9.3: every wake-up scans this long for the phone's command advert


def daily_mah(dev: Device, sc: Scenario) -> dict[str, float]:
    per_day = 24 * 60 / sc.report_interval_min
    checks = 0.0
    if sc.check_interval_min:
        checks = max(24 * 60 / sc.check_interval_min - per_day, 0.0)  # wake-ups that only listen
    airtime = lora_airtime_s(2 + REPORT_GRP_LEN, sc.sf, sc.bw_hz, sc.cr)
    parts = {
        "rx": dev.rx_ma * sc.listen_s / 3600 * per_day,
        "checks": (dev.rx_ma + dev.mcu_active_ma) * sc.check_listen_s / 3600 * checks,
        "gnss": (dev.gnss_ma + dev.mcu_active_ma) * sc.fix_s / 3600 * per_day,
        "tx": dev.tx_ma * airtime * sc.tx_count / 3600 * per_day,
        # 0.9.3: the command scan at every wake-up (a wake-up with a presence scan has it already;
        # counted for all, an upper bound). It runs alongside the LoRa window, so only the scan adds
        "ble": dev.ble_scan_ma * sc.ble_cmd_s / 3600 * (per_day + checks),
        "sleep": (dev.sleep_ua + dev.gnss_idle_ua) / 1000 * 24,
    }
    parts["total"] = sum(parts.values())
    parts["days"] = dev.battery_mah * sc.usable / parts["total"]
    parts["airtime_s"] = airtime
    parts["sf"], parts["bw_hz"], parts["cr"] = sc.sf, sc.bw_hz, sc.cr
    parts["listen_only_per_day"] = checks
    return parts


DEFAULT_SCENARIOS = [
    ("1 h, RX 5 min, fix 30 s", Scenario(listen_s=300, fix_s=30)),
    ("1 h, RX 90 s, fix 15 s", Scenario(listen_s=90, fix_s=15)),
    ("1 h, RX 30 s, fix 15 s", Scenario(listen_s=30, fix_s=15)),
    ("1 h, RX 30 s, fix 15 s, GNSS fully off", Scenario(listen_s=30, fix_s=15)),
    ("4 h, RX 60 s, fix 30 s", Scenario(report_interval_min=240, listen_s=60, fix_s=30)),
]


def report_every_table(sf: int = LIVE_SF, bw_hz: float = LIVE_BW_HZ, cr: int = LIVE_CR, listen_s: float = 90,
                       fix_s: float = 15) -> str:
    """0.7.3: wake every hour, report every 1 h vs every 3 h (listen-only wake-ups between)."""
    out = [f"report every n-th wake-up, {preset_label(sf, bw_hz, cr)}, RX after a report {listen_s:g} s, "
           f"fix {fix_s:g} s, listen-only wake-up 34 s"]
    for dev in (L1_PRO, X1):
        out.append(f"{dev.name} ({dev.battery_mah:.0f} mAh)  [{dev.source}]")
        out.append(f"  {'scenario':40s} {'rx':>6s} {'checks':>6s} {'gnss':>6s} {'tx':>6s} {'ble':>6s} "
                   f"{'sleep':>6s} {'mAh/d':>7s} {'days':>6s}")
        for label, rep, chk in (("report 1 h (wake 1 h)", 60, None), ("check 1 h, report 3 h", 180, 60),
                                ("check 30 min, report 3 h", 180, 30), ("check 1 h, report 6 h", 360, 60)):
            p = daily_mah(dev, Scenario(report_interval_min=rep, listen_s=listen_s, fix_s=fix_s, sf=sf, bw_hz=bw_hz,
                                        cr=cr, check_interval_min=chk))
            out.append(f"  {label:40s} {p['rx']:6.2f} {p['checks']:6.2f} {p['gnss']:6.2f} {p['tx']:6.2f} "
                       f"{p['ble']:6.2f} {p['sleep']:6.2f} {p['total']:7.2f} {p['days']:6.0f}")
        out.append("")
    return "\n".join(out)


def default_table() -> str:
    sc0 = DEFAULT_SCENARIOS[0][1]
    air = lora_airtime_s(2 + REPORT_GRP_LEN, sc0.sf, sc0.bw_hz, sc0.cr)
    out = [f"report every hour, {preset_label(sc0.sf, sc0.bw_hz, sc0.cr)}, report airtime {air:.3f} s x {sc0.tx_count}"]
    for dev in (X1, L1_PRO):
        rows = []
        for label, sc in DEFAULT_SCENARIOS:
            d = replace(dev, gnss_idle_ua=0) if "fully off" in label else dev
            rows.append((label, sc, d))
        out.append(f"{dev.name} ({dev.battery_mah:.0f} mAh)  [{dev.source}]")
        out.append(f"  {'scenario':40s} {'rx':>6s} {'gnss':>6s} {'tx':>6s} {'sleep':>6s} {'mAh/d':>7s} {'days':>6s}")
        for label, sc, d in rows:
            p = daily_mah(d, sc)
            out.append(
                f"  {label:40s} {p['rx'] + p['checks']:6.2f} {p['gnss']:6.2f} {p['tx']:6.2f} {p['sleep']:6.2f} "
                f"{p['total']:7.2f} {p['days']:6.0f}"
            )
        out.append("")
    return "\n".join(out)
