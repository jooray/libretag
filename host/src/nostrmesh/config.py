"""Bridge configuration (TOML, docs/nostr-mesh-bridge.md 5.8)."""
from __future__ import annotations

import tomllib
from dataclasses import dataclass, field
from pathlib import Path

CONFIG_DIR = Path.home() / ".config" / "nostrmesh"
DEFAULT_STATUS_PATH = str(CONFIG_DIR / "status.json")
UPLINK_PROFILES = {"road.report": True, "road.vote": True, "dm4.ref": True, "dm4.full": True,
                   "note": False, "generic": False}
RETIRED_PROFILES = {"libretag.public"}  # LT1 public reports; ignored if an old config still lists them
DOWNLINK_PROFILES = {"road.report": False, "road.vote": False, "dm4.ref": False}
MAX_AGE_S = {"road.vote": 30 * 86400, "dm4.ref": 7 * 86400, "dm4.full": 7 * 86400,
             "note": 86400, "generic": 86400}


@dataclass
class RadioCfg:
    adapter: str = "companion_serial"  # | kiss | local_api
    port: str = ""
    url: str = "ws://127.0.0.1:8787/api/v1"  # local_api
    identity: str = ""                       # local_api pairing key file (default ~/.config/nostrmesh)
    region: str = ""                         # local_api flood region, empty = the radio's own
    baud: int = 115200
    channels: list[str] = field(default_factory=lambda: ["#nostr", "#roadstr", "#libretag"])
    add_channels: bool = False


@dataclass
class UplinkCfg:
    enabled: bool = True
    rate_global_per_min: int = 60
    rate_per_pubkey_per_min: int = 6
    rate_per_pubkey_per_hour: int = 60
    future_s: int = 300
    allow_fragments: bool = False   # note/generic; DMs always may be fragmented
    unregistered_dm: bool = False   # publish dm4.full from keys not in the register
    unregistered_expiring_dm: bool = True  # dm4x (expires in 14 d, e.g. a tracker's low battery) from any key
    dm_inbox_relays: bool = True    # also publish a DM to the recipient's inbox relays (10050, 10002 read)
    dm_inbox_max: int = 6           # at most this many extra relays per DM
    profiles: dict[str, bool] = field(default_factory=lambda: dict(UPLINK_PROFILES))


@dataclass
class Geofence:
    lat: float
    lon: float
    radius_km: float = 40.0


@dataclass
class DownlinkCfg:
    enabled: bool = False
    hold_min_s: float = 3.0
    hold_max_s: float = 15.0
    dm_jitter_s: float = 12.0
    rate_global_per_hour: int = 20
    rate_per_author_per_hour: int = 4
    dm_per_hour: int = 6
    dm_per_device_per_hour: int = 4
    min_gap_s: float = 20.0
    fragment_gap_s: float = 3.0
    queue_ttl_s: float = 86400.0
    queue_per_device: int = 8
    direct_replies: bool = True     # KISS: DM to a device along the reverse path of its uplink
    route_max_age_s: float = 900.0
    rendezvous_max_age_s: float = 600.0  # only a recent signed frame opens a device's queue
    geofence: Geofence | None = None
    profiles: dict[str, bool] = field(default_factory=lambda: dict(DOWNLINK_PROFILES))


# [libretag] keys of earlier bridges that no longer mean anything (signed mailbox, 2026-11-18): proof of
# work and format 1 are gone. Ignored so an old bridge.toml still loads.
RETIRED_LIBRETAG = {"pow_untimed_bits", "pow_timed_tiers", "pow_timed_max_bits", "accept_legacy", "pow_route_bits",
                    "pow_open_bits", "pow_max_bits"}


@dataclass
class LibreTagCfg:
    """LT2 on #libretag (PROTOCOL.md 4, SPEC 6.2 variant B). The bridge never holds a tracker key."""
    uplink: bool = True               # publish heard reports as kind 4243 under a fresh key per event
    publish_delay_min_s: float = 2.0  # random wait min..max before publishing; the floor is above
    publish_delay_max_s: float = 5.0  # claim_wait_s, so only a bridge that heard a report can prove it in time
    dedup_min_relays: int = 2         # skip if the same y + content is already on this many relays
    mailbox: bool = True              # deliver kind 4244 commands right after the matching report
    max_hops: int = 0                 # unused since 0.7.3 (commands flood unless heard directly); kept so
                                      # older config files still load
    claim: bool = True                # coordinate with other bridges (kind 24244) before sending
    claim_wait_s: float = 1.5
    mailbox_cap: int = 200_000        # tags in all (Q4: 10,000 was one GPU minute to fill)
    mailbox_ttl_s: float = 48 * 3600  # cap on a command's expiration
    report_max_age_s: float = 30.0    # only a report heard this recently triggers a command
    timed_commands: bool = True       # 0.7 checks: mailbox entries with ["at", t] and ["via", x]
    report_path_ttl_s: float = 48 * 3600  # keep the path of each caught report this long (by x)
    timed_claim_lead_s: float = 5.0   # publish the claim this long before `at`, decide at `at`
    timed_max_late_s: float = 60.0    # drop entries whose `at` is further in the past than this
    # abuse limits (PROTOCOL.md 4, "Limits"): a stranger can publish timed entries for any public x
    future_max_s: float = 60.0        # created_at at most this far in the future
    timed_max_after_report_s: float = 25 * 3600  # `at` <= heard_at(via) + this (heartbeat 24 h + 1 h)
    timed_per_via_max: int = 12       # timed entries kept per `via` (earlier seen first)
    timed_via_gap_s: float = 600.0    # at most one timed transmission per `via` in this time
    timed_per_hour: int = 20          # timed transmissions per rolling hour, all `via` together
    rx_per_min: int = 30              # new #libretag packets per minute (separate from uplink.rate_global)
    rx_per_first_hop_per_min: int = 10  # of those, per first repeater of the path (direct reception: one key)
    airtime_pct: float = 0.1          # #libretag downlink budget per rolling hour, separate from [airtime]
    mailbox_relay_share: float = 0.34  # one source relay fills at most this share of mailbox_cap
    # signed mailbox (PROTOCOL.md 4.2): an entry counts only when it is signed by the key a heard report commits to
    anchor_lead_s: float = 60.0       # a key locks only if an event of it was here this long before the report
    late_max_s: float = 60.0          # a locked key's entry for a report heard at most this long ago goes out at once
    keys_per_y: int = 8               # distinct keys stored per report tag y (anchors and entries; first seen stay)
    group_ack: bool = True            # drop the rest of a command group ["g"] once the tracker answered
    ack_window_s: float = 180.0       # a report this soon after an own timed transmission may be its ack
    # PROTOCOL.md 4.1: bridge identity, targeting, cost (the key itself is [bridge] key)
    peers: list = field(default_factory=list)     # bridge pubkeys (hex or npub) whose claims always count
    networks: list = field(default_factory=list)  # networks served (announcement "d", entries' "r")
    receipts: bool = False            # publish kind 4245 "heard" receipts (operator opt-in)
    receipts_per_hour: int = 120
    announce: bool = True             # publish kind 38244 (needs a bridge key)
    announce_interval_s: float = 86400.0
    accept_open: bool = True          # store entries that name no bridge (no "p")
    direct_max_hops: int = 3          # route "direct": reversed path of a report heard this many hops away
    timed_airtime_share: float = 0.5  # timed transmissions use at most this share of airtime_pct
    open_floods_per_hour: int = 6     # floods of open entries per rolling hour


@dataclass
class BridgeIdCfg:
    """The bridge's stable Nostr key (claims, receipts, announcements; PROTOCOL.md 4.1)."""
    key: str = ""                     # file with a hex key or nsec (mode 600), or the value itself
    name: str = ""                    # shown in the announcement


@dataclass
class WatchdogCfg:
    """Exit non-zero (the supervisor restarts the bridge) when the radio has gone quiet."""
    rx_silence_s: float = 1800.0      # no frame and no adapter activity this long -> exit 3 (0 = off)


@dataclass
class StatusCfg:
    path: str = ""                    # written hourly and at start (Config.load: ~/.config/nostrmesh/status.json)
    interval_s: float = 3600.0


@dataclass
class AirtimeCfg:
    """Budget for the bridge's own transmissions, LoRa time-on-air over a rolling window.
    sf/bw_hz/cr must match the node (default: the SK/CZ/HU preset 7 / 62.5 kHz / 4/5). A Local API
    node that reports its preset (`radio.stats`) overrides them, with a warning when they differ."""
    budget_pct: float = 0.1
    window_s: float = 3600.0
    sf: int = 7
    bw_hz: float = 62_500
    cr: int = 5
    preamble: int = 16


@dataclass
class Config:
    radio: RadioCfg = field(default_factory=RadioCfg)
    publish: list[str] = field(default_factory=list)
    subscribe: list[str] = field(default_factory=list)
    uplink: UplinkCfg = field(default_factory=UplinkCfg)
    downlink: DownlinkCfg = field(default_factory=DownlinkCfg)
    libretag: LibreTagCfg = field(default_factory=LibreTagCfg)
    airtime: AirtimeCfg = field(default_factory=AirtimeCfg)
    watchdog: WatchdogCfg = field(default_factory=WatchdogCfg)
    status: StatusCfg = field(default_factory=StatusCfg)
    bridge: BridgeIdCfg = field(default_factory=BridgeIdCfg)
    ledger_path: str = str(CONFIG_DIR / "ledger.sqlite")
    retention_days: float = 30.0
    registry_path: str | None = None
    auth_key: str | None = None        # NIP-42: nsec/hex or a file with one; None = never AUTH
    outbox_ttl_s: float = 14 * 86400   # unpublished events are retried this long (capped by their expiration)

    @classmethod
    def load(cls, path: str | Path) -> "Config":
        d = tomllib.loads(Path(path).read_text())
        base = Path(path).parent
        c = cls()
        c.radio = RadioCfg(**d.get("radio", {}))
        rel = d.get("relays", {})
        c.publish, c.subscribe = rel.get("publish", []), rel.get("subscribe", rel.get("publish", []))
        up = dict(d.get("uplink", {}))
        up["profiles"] = {**UPLINK_PROFILES, **{k: v for k, v in up.get("profiles", {}).items()
                                                if k not in RETIRED_PROFILES}}
        c.uplink = UplinkCfg(**up)
        dn = dict(d.get("downlink", {}))
        dn["profiles"] = {**DOWNLINK_PROFILES, **dn.get("profiles", {})}
        if "geofence" in dn:
            dn["geofence"] = Geofence(**dn["geofence"])
        c.downlink = DownlinkCfg(**dn)
        lt = {k: v for k, v in d.get("libretag", {}).items() if k not in RETIRED_LIBRETAG}
        c.libretag = LibreTagCfg(**lt)
        c.airtime = AirtimeCfg(**d.get("airtime", {}))
        c.watchdog = WatchdogCfg(**d.get("watchdog", {}))
        bid = dict(d.get("bridge", {}))
        if bid.get("key") and (base / Path(bid["key"]).expanduser()).exists():
            bid["key"] = str(base / Path(bid["key"]).expanduser())
        c.bridge = BridgeIdCfg(**bid)
        st = dict(d.get("status", {}))
        st["path"] = str(base / Path(st["path"]).expanduser()) if st.get("path") else DEFAULT_STATUS_PATH
        c.status = StatusCfg(**st)
        if rel.get("auth_key"):
            k = rel["auth_key"]
            c.auth_key = str(base / Path(k).expanduser()) if (base / Path(k).expanduser()).exists() else k
        led = d.get("ledger", {})
        if led.get("path"):
            c.ledger_path = str(base / Path(led["path"]).expanduser())
        c.retention_days = led.get("retention_days", 30)
        c.outbox_ttl_s = led.get("outbox_ttl_s", c.outbox_ttl_s)
        if d.get("registry", {}).get("path"):
            c.registry_path = str(base / d["registry"]["path"])
        unknown = set(c.uplink.profiles) - set(UPLINK_PROFILES)
        if unknown:
            raise ValueError(f"unknown uplink profiles {unknown}")
        return c
