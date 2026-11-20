"""Synthetic reports for the web app's demo relay. Coordinates only around 48.70 / 21.25.

    cd host && uv run python ../pwa/tools/gen_demo.py > ../pwa/tools/demo-reports.json

Each tag lists reports as (seconds ago, sealed payload). Nothing here is real data.
"""
import base64
import json
import sys

from libretag.lt2 import Keys, Mode, Report, epoch, seal_report, report_tag

import time
NOW_FIX = int(time.time())  # sealed with real clock time; regenerate before use


def rep(n, keys, ago_s, **kw):
    r = Report(**kw)
    if r.fix_time:
        r.fix_time = NOW_FIX - ago_s
    p = seal_report(keys.view(epoch(n)), n, 0, r)
    return {"n": n, "ago_s": ago_s, "x": report_tag(keys.view(epoch(n)), n, 0).hex(),
            "content": base64.b64encode(p).decode()}


def tag(name, type_, mark, k0_hex, build):
    keys = Keys(bytes.fromhex(k0_hex))
    return {"name": name, "type": type_, "mark": mark, "k0": k0_hex, "reports": build(keys)}


def car(keys):
    out = []
    n0 = 120
    spots = [(48.7012, 21.2541)] * 12
    # drove in 11 h ago ... then quiet at home
    for i in range(12):
        ago = (13 - i) * 3600 - 120 * (i % 3)
        lat, lon = spots[i]
        out.append(rep(n0 + i, keys, ago, has_fix=True, fix_time=1, lat=lat + i * 0.00002, lon=lon, hacc_m=9,
                       battery_pct=84 - i // 5, mode=Mode.IDLE, next_in_s=3600, nb_total=3))
    # the last report before the quiet stretch: zone, with checks, heartbeat 24 h
    out.append(rep(n0 + 12, keys, 95 * 60, has_fix=True, fix_time=1, lat=48.70121, lon=21.25412, hacc_m=7,
                   battery_pct=82, mode=Mode.IDLE, quiet=True, checks=True, next_in_s=86400, nb_total=2))
    return out


def backpack(keys):
    out = []
    route = [(48.7100, 21.2300), (48.7125, 21.2362), (48.7151, 21.2410), (48.7179, 21.2468), (48.7203, 21.2530),
             (48.7221, 21.2577), (48.7233, 21.2596), (48.7240, 21.2610)]
    for i, (lat, lon) in enumerate(route):
        ago = (len(route) - 1 - i) * 3600 + (12 * 60 if i == len(route) - 1 else 300)
        if i == len(route) - 1:
            ago = 12 * 60
        out.append(rep(300 + i, keys, ago, has_fix=True, fix_time=1, lat=lat, lon=lon, hacc_m=8, battery_pct=71 - i,
                       mode=Mode.IDLE, next_in_s=3600, nb_total=4))
    return out


def anna(keys):
    out = []
    route = [(48.7060, 21.2200), (48.7078, 21.2236), (48.7101, 21.2274), (48.7123, 21.2301), (48.7148, 21.2340),
             (48.7166, 21.2362), (48.7181, 21.2380), (48.7185, 21.2391)]
    for i, (lat, lon) in enumerate(route):
        ago = (len(route) - 1 - i) * 600 + 4 * 60
        out.append(rep(50 + i, keys, ago, has_fix=True, fix_time=1, lat=lat, lon=lon, hacc_m=6, battery_pct=63 - i,
                       mode=Mode.LOST, next_in_s=600, nb_total=3))
    return out


def keysfob(keys):
    out = []
    for i in range(5):
        ago = 4 * 3600 + 10 * 60 + (4 - i) * 3600
        out.append(rep(200 + i, keys, ago, has_fix=True, fix_time=1, lat=48.6962 + i * 0.0001, lon=21.2477, hacc_m=12,
                       battery_pct=20 - i // 2, mode=Mode.IDLE, next_in_s=3600, nb_total=2))
    # the last one: no GPS this time, only the last fix it remembers, low battery
    out.append(rep(205, keys, 4 * 3600 + 10 * 60, has_fix=False, fix_time=1, lat=48.6966, lon=21.2477, hacc_m=12,
                   battery_pct=17, low_batt=True, mode=Mode.IDLE, next_in_s=3600, nb_total=3,
                   neighbours=[(b"\xa5\xe1", 4.25)]))
    return out


TAGS = [
    tag("Car", "vehicle", "red-stripe", "000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f", car),
    tag("Backpack", "thing", "blue-triangle", "ff" * 32, backpack),
    tag("Anna", "person", "green-circle", "11" * 32, anna),
    tag("Keys", "thing", "yellow-square", "22" * 32, keysfob),
]
json.dump({"passphrase": "legal winner thank year wave sausage worth useful legal winner thank yellow", "tags": TAGS}, sys.stdout, indent=1)
