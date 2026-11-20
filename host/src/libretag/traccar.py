"""Forward positions to Traccar via the OsmAnd protocol (default port 5055)."""

from __future__ import annotations

import logging
import urllib.parse
import urllib.request

from .lt2 import Report

log = logging.getLogger(__name__)


def osmand_url(base_url: str, device_id: str, rep: Report) -> str:
    q = {
        "id": device_id,
        "timestamp": str(rep.fix_time),
        "lat": f"{rep.lat:.7f}",
        "lon": f"{rep.lon:.7f}",
        "mode": rep.mode.name.lower(),
    }
    if rep.hacc_m != 255:
        q["accuracy"] = str(rep.hacc_m)
    if rep.battery_pct is not None:
        q["batt"] = str(rep.battery_pct)
    return base_url.rstrip("/") + "/?" + urllib.parse.urlencode(q)


def send(base_url: str, device_id: str, rep: Report, timeout: float = 5.0) -> bool:
    if not rep.has_fix or not rep.fix_time:
        return False
    url = osmand_url(base_url, device_id, rep)
    try:
        with urllib.request.urlopen(urllib.request.Request(url, method="POST"), timeout=timeout) as r:
            return 200 <= r.status < 300
    except OSError as e:
        log.warning("traccar forward failed: %s", e)
        return False
