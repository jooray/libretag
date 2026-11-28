"""Bridge ledger (SQLite): event ids for dedupe and loop prevention, LT2 report keys, the command
mailbox and the tags already answered, the airtime spent, the timed transmissions, the outbox of
events not published yet. Kept on disk (default ~/.config/nostrmesh/ledger.sqlite) so a restart
neither republishes nor resends nor forgets its budgets; pruned to `retention_days`."""
from __future__ import annotations

import hashlib
import json
import sqlite3
import threading
from dataclasses import dataclass
from pathlib import Path

SCHEMA = """
CREATE TABLE IF NOT EXISTS events (
    id TEXT PRIMARY KEY,
    first_seen REAL NOT NULL,
    source TEXT NOT NULL,              -- radio | relay | own
    profile TEXT,
    heard_on_radio REAL,
    published_at REAL,
    sent_to_radio_at REAL
);
CREATE INDEX IF NOT EXISTS events_first_seen ON events(first_seen);
CREATE TABLE IF NOT EXISTS mailbox (
    event_id TEXT NOT NULL,
    y TEXT NOT NULL,
    payload BLOB NOT NULL,
    route TEXT NOT NULL,               -- near | direct | flood | both
    created_at INTEGER NOT NULL,
    expires REAL NOT NULL,
    known_at REAL NOT NULL,
    sent_at REAL,
    at INTEGER,                        -- timed entry (0.7 checks): transmit at this unix second
    via TEXT,                          -- timed entry: x of the report whose path to use
    pubkey TEXT,                       -- author of the 4244 event (a NIP-09 deletion must match it)
    PRIMARY KEY (event_id, y)
);
CREATE INDEX IF NOT EXISTS mailbox_y ON mailbox(y);
CREATE INDEX IF NOT EXISTS mailbox_expires ON mailbox(expires);
CREATE TABLE IF NOT EXISTS report_paths (  -- path record per caught LT2 report x (PROTOCOL.md 4.3)
    x TEXT PRIMARY KEY,
    hops INTEGER,                      -- NULL: hop count unknown (a DIRECT arrival, or not reported)
    path BLOB,                         -- tag-to-bridge order: a flood's path as heard, or for a DIRECT
                                       -- arrival the path learned from its `via` (NULL: none)
    hash_size INTEGER NOT NULL,
    heard_at REAL NOT NULL
);
CREATE INDEX IF NOT EXISTS report_paths_heard ON report_paths(heard_at);
CREATE TABLE IF NOT EXISTS cancelled (  -- NIP-09 deletions of mailbox events (kind 5), also before the event
    event_id TEXT NOT NULL,
    pubkey TEXT NOT NULL,              -- author of the deletion; only cancels an event by the same key
    at REAL NOT NULL,
    PRIMARY KEY (event_id, pubkey)
);
CREATE INDEX IF NOT EXISTS cancelled_at ON cancelled(at);
CREATE TABLE IF NOT EXISTS answered (   -- tags a command already went out for: timed y, or "x:pubkey" (untimed)
    y TEXT PRIMARY KEY,
    at REAL NOT NULL
);
CREATE TABLE IF NOT EXISTS sent_payloads (  -- SHA-256 of every Pc transmitted or heard on air: once only
    h TEXT PRIMARY KEY,
    at REAL NOT NULL
);
CREATE TABLE IF NOT EXISTS timed_tx (   -- own timed transmissions (per-via gap, hourly cap)
    via TEXT NOT NULL,
    at REAL NOT NULL
);
CREATE INDEX IF NOT EXISTS timed_tx_at ON timed_tx(at);
CREATE TABLE IF NOT EXISTS open_tx (    -- own floods of open entries (no "p", or format 1): hourly cap
    at REAL NOT NULL
);
CREATE TABLE IF NOT EXISTS ack_tx (     -- own ACK transmissions: hourly cap, apart from commands
    at REAL NOT NULL
);
CREATE TABLE IF NOT EXISTS blind_tx (   -- own blind LOST floods, per via: 3 per via and 6 in all per 24 h
    via TEXT NOT NULL,
    at REAL NOT NULL
);
CREATE TABLE IF NOT EXISTS done_groups (  -- command groups ["g"] whose tracker answered: rest dropped
    g TEXT PRIMARY KEY,
    at REAL NOT NULL
);
CREATE TABLE IF NOT EXISTS airtime (    -- own time on air, per budget, for the rolling window
    bucket TEXT NOT NULL,
    at REAL NOT NULL,
    secs REAL NOT NULL
);
CREATE INDEX IF NOT EXISTS airtime_at ON airtime(at);
CREATE TABLE IF NOT EXISTS outbox (     -- events not accepted by any relay yet, retried until they expire
    id TEXT PRIMARY KEY,
    event TEXT NOT NULL,
    ledger_key TEXT NOT NULL,          -- what mark_published marks (event id, or the LT2 report key)
    stat TEXT NOT NULL,                -- counter to bump on success
    expires REAL NOT NULL,
    next_try REAL NOT NULL,
    tries INTEGER NOT NULL DEFAULT 0
);
CREATE TABLE IF NOT EXISTS anchors (  -- signed mailbox: keys that named a report tag y before the report
    y TEXT NOT NULL,
    pubkey TEXT NOT NULL,
    event_id TEXT NOT NULL,
    known_at REAL NOT NULL,            -- first time an event of this key with this y reached the bridge
    expires REAL NOT NULL,
    relay TEXT,
    PRIMARY KEY (y, pubkey)
);
CREATE INDEX IF NOT EXISTS anchors_expires ON anchors(expires);
CREATE TABLE IF NOT EXISTS locks (    -- keys whose commitment matched a report heard on the radio, by its x
    x TEXT NOT NULL,
    pubkey TEXT NOT NULL,
    at REAL NOT NULL,
    PRIMARY KEY (x, pubkey)
);
CREATE INDEX IF NOT EXISTS locks_at ON locks(at);
CREATE TABLE IF NOT EXISTS kv (
    k TEXT PRIMARY KEY,
    v TEXT NOT NULL
);
"""

OUTBOX_BACKOFF_S = (900, 900, 1800, 3600)  # first retry after the pool's own retries (~13 min)


@dataclass
class MailboxEntry:
    event_id: str
    y: str
    payload: bytes
    route: str
    created_at: int
    expires: float
    known_at: float
    at: int | None = None
    via: str | None = None
    grp: str | None = None
    pow: int = 0                     # unused since the signed mailbox (no proof of work); kept for old ledgers
    fmt: int = 2                     # always 2 (PROTOCOL.md 4.1); old ledgers may hold 1
    plist: str = ""                  # the bridges named in "p", space separated, in order
    pubkey: str = ""                 # author: the mailbox key of the report it answers (PROTOCOL.md 4.2)
    ack: int = 0                     # an ACK entry (["a", "1"], PROTOCOL.md 4.3): payload is 13 or 26 B
    fb: int = 1                      # flood fallback after a zero-hop (both) or path send; ["fb", "0"] turns it off
    blind: int = 0                   # blind LOST entry (["blind", "1"]): a flood at `at` without `via` heard
    vias: str = ""                   # every ["via", x], space separated (`via` is the first)

    @property
    def via_list(self) -> list[str]:
        return self.vias.split() if self.vias else ([self.via] if self.via else [])

    @property
    def p(self) -> list[str]:
        return self.plist.split() if self.plist else []

    @property
    def open(self) -> bool:
        return not self.plist


ENTRY_COLS = "event_id, y, payload, route, created_at, expires, known_at, at, via, grp, pow, fmt, plist, " \
             "COALESCE(pubkey, ''), ack, fb, blind, COALESCE(vias, '')"


@dataclass
class ReportPath:
    x: str
    hops: int | None
    path: list[bytes] | None
    hash_size: int
    heard_at: float
    route: str | None = None         # flood | tflood | direct | tdirect as it arrived (None: unknown)
    scope: str | None = None         # the flood's scope when known ("" = unscoped)
    used_at: float | None = None     # last use for an ACK or a command (R33: the 48 h run from here)

    @property
    def is_direct(self) -> bool:
        return self.route in ("direct", "tdirect")

    @property
    def heard(self) -> dict:
        """As quietmesh.plan takes it (lt2svc.arrival): "path" None when the route or path is not known."""
        if self.is_direct:
            return {"route": self.route, "path": []}
        if self.hops is None:
            return {"route": "unknown", "path": None, "hops": None}
        if self.hops > 0 and not self.path:
            return {"route": self.route or "flood", "path": None, "hops": self.hops}
        return {"route": self.route or "flood", "path": [h.hex() for h in (self.path or [])]}


class Ledger:
    def __init__(self, path: str | Path = ":memory:"):
        if str(path) != ":memory:":
            Path(path).expanduser().parent.mkdir(parents=True, exist_ok=True)
            path = Path(path).expanduser()
        self._db = sqlite3.connect(str(path), check_same_thread=False)
        self._db.executescript(SCHEMA)
        cols = {r[1] for r in self._db.execute("PRAGMA table_info(mailbox)")}
        for col, typ in (("at", "INTEGER"), ("via", "TEXT"), ("pubkey", "TEXT"), ("pow", "INTEGER NOT NULL DEFAULT 0"),
                         ("relay", "TEXT"), ("grp", "TEXT"), ("fmt", "INTEGER NOT NULL DEFAULT 1"),
                         ("plist", "TEXT NOT NULL DEFAULT ''"), ("ack", "INTEGER NOT NULL DEFAULT 0"),
                         ("fb", "INTEGER NOT NULL DEFAULT 1"), ("blind", "INTEGER NOT NULL DEFAULT 0"),
                         ("vias", "TEXT")):  # older ledgers
            if col not in cols:
                self._db.execute(f"ALTER TABLE mailbox ADD COLUMN {col} {typ}")
        cols = {r[1] for r in self._db.execute("PRAGMA table_info(report_paths)")}
        for col, typ in (("route", "TEXT"), ("scope", "TEXT"), ("used_at", "REAL")):
            if col not in cols:
                self._db.execute(f"ALTER TABLE report_paths ADD COLUMN {col} {typ}")
        for ix in ("at", "via", "relay", "grp"):
            self._db.execute(f"CREATE INDEX IF NOT EXISTS mailbox_{ix} ON mailbox({ix})")
        self._db.commit()
        self._lock = threading.Lock()

    def _x(self, sql: str, args=()):
        with self._lock:
            cur = self._db.execute(sql, args)
            self._db.commit()
            return cur.fetchone()

    def _all(self, sql: str, args=()) -> list:
        with self._lock:
            return self._db.execute(sql, args).fetchall()

    # ------------------------------------------------------------ events

    def get(self, eid: str) -> dict | None:
        row = self._x("SELECT id, first_seen, source, profile, heard_on_radio, published_at, sent_to_radio_at"
                      " FROM events WHERE id=?", (eid,))
        if not row:
            return None
        keys = ("id", "first_seen", "source", "profile", "heard_on_radio", "published_at", "sent_to_radio_at")
        return dict(zip(keys, row))

    def add(self, eid: str, source: str, now: float, profile: str | None = None) -> bool:
        """True if the id is new."""
        with self._lock:
            cur = self._db.execute("INSERT OR IGNORE INTO events(id, first_seen, source, profile) VALUES (?,?,?,?)",
                                   (eid, now, source, profile))
            self._db.commit()
            return cur.rowcount == 1

    def mark_heard(self, eid: str, now: float) -> None:
        self._x("UPDATE events SET heard_on_radio=COALESCE(heard_on_radio, ?) WHERE id=?", (now, eid))

    def mark_published(self, eid: str, now: float) -> None:
        self._x("UPDATE events SET published_at=? WHERE id=?", (now, eid))

    def mark_sent(self, eid: str, now: float) -> None:
        self._x("UPDATE events SET sent_to_radio_at=? WHERE id=?", (now, eid))

    def on_radio(self, eid: str) -> bool:
        """Heard on the radio or already sent there: never transmit again."""
        r = self.get(eid)
        return bool(r and (r["heard_on_radio"] or r["sent_to_radio_at"]))

    # ------------------------------------------------------------ LT2 command mailbox (kind 4244)

    def mailbox_add(self, event_id: str, ys: list[str], payload: bytes, route: str, created_at: int,
                    expires: float, known_at: float, cap: int, at: int | None = None,
                    via: str | None = None, pubkey: str | None = None, pow: int = 0, relay: str | None = None,
                    grp: str | None = None, per_via_max: int | None = None,
                    relay_share: float | None = None, fmt: int = 1, plist: str = "", ack: bool = False,
                    fb: bool = True, blind: bool = False, vias: list[str] | None = None) -> bool:
        """True if the event is new and stays. Limits, in this order (PROTOCOL.md 4, "Limits"):
        - a timed entry: at most `per_via_max` unsent timed events per `via`; over it the one seen last goes;
        - one source relay holds at most `relay_share` x `cap` tags;
        - at most `cap` tags in all.
        For the last two, transmitted entries go first, then the oldest inserted."""
        with self._lock:
            cur = self._db.executemany(
                "INSERT OR IGNORE INTO mailbox(event_id, y, payload, route, created_at, expires, known_at, at, via, "
                "pubkey, pow, relay, grp, fmt, plist, ack, fb, blind, vias) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                [(event_id, y, payload, route, created_at, expires, known_at, at, via, pubkey, pow, relay, grp, fmt,
                  plist, int(ack), int(fb), int(blind), " ".join(vias) if vias else None) for y in ys])
            new = cur.rowcount > 0
            if new and at is not None and per_via_max:
                ids = [r[0] for r in self._db.execute(
                    "SELECT event_id FROM mailbox WHERE via=? AND at IS NOT NULL AND sent_at IS NULL "
                    "GROUP BY event_id ORDER BY MAX(pow) DESC, MIN(known_at), event_id", (via,))]
                for eid in ids[per_via_max:]:
                    self._db.execute("DELETE FROM mailbox WHERE event_id=?", (eid,))
            if new and relay is not None and relay_share:
                self._evict("relay=?", (relay,), int(cap * relay_share))
            self._evict("1", (), cap)
            stays = self._db.execute("SELECT 1 FROM mailbox WHERE event_id=? LIMIT 1", (event_id,)).fetchone()
            self._db.commit()
            return new and stays is not None

    def _evict(self, where: str, args: tuple, limit: int) -> None:
        """Remove whole events matching `where` until at most `limit` tags of them remain (lock held)."""
        over = self._db.execute(f"SELECT COUNT(*) FROM mailbox WHERE {where}", args).fetchone()[0] - limit
        if over <= 0:
            return
        rows = self._db.execute(
            f"SELECT event_id, COUNT(*) FROM mailbox WHERE {where} GROUP BY event_id "
            "ORDER BY MAX(sent_at IS NULL), MIN(pow), MIN(known_at), MIN(rowid)", args).fetchall()
        for eid, n in rows:
            if over <= 0:
                break
            self._db.execute("DELETE FROM mailbox WHERE event_id=?", (eid,))
            over -= n

    # ------------------------------------------------------------ signed mailbox (PROTOCOL.md 4.2)

    def anchor_add(self, y: str, pubkey: str, event_id: str, known_at: float, expires: float, relay: str | None,
                   cap: int) -> bool:
        """Remember that key `pubkey` named tag `y` (an anchor, content ""). The first time counts; a later
        event of the same key only extends the expiration. At most `cap` rows in all, the oldest go first."""
        with self._lock:
            cur = self._db.execute("INSERT OR IGNORE INTO anchors(y, pubkey, event_id, known_at, expires, relay) "
                                   "VALUES (?,?,?,?,?,?)", (y, pubkey, event_id, known_at, expires, relay))
            new = cur.rowcount == 1
            if not new:
                self._db.execute("UPDATE anchors SET expires=MAX(expires, ?) WHERE y=? AND pubkey=?",
                                 (expires, y, pubkey))
            over = self._db.execute("SELECT COUNT(*) FROM anchors").fetchone()[0] - cap
            if over > 0:
                self._db.execute("DELETE FROM anchors WHERE rowid IN (SELECT rowid FROM anchors "
                                 "ORDER BY known_at LIMIT ?)", (over,))
            self._db.commit()
            return new

    def keys_of_y(self, y: str) -> set[str]:
        """Distinct keys that named report tag `y` (anchors and untimed entries)."""
        rows = self._all("SELECT pubkey FROM anchors WHERE y=? UNION "
                         "SELECT pubkey FROM mailbox WHERE y=? AND at IS NULL AND pubkey IS NOT NULL", (y, y))
        return {r[0] for r in rows}

    def lock_candidates(self, x: str, before: float, now: float) -> set[str]:
        """Keys that named tag `x` at `before` or earlier (an anchor or an untimed entry): they may lock."""
        rows = self._all("SELECT pubkey FROM anchors WHERE y=? AND known_at <= ? AND expires > ? UNION "
                         "SELECT pubkey FROM mailbox WHERE y=? AND at IS NULL AND known_at <= ? AND pubkey IS NOT NULL",
                         (x, before, now, x, before))
        return {r[0] for r in rows}

    def key_known_at(self, x: str, pubkey: str) -> float | None:
        """When an event of `pubkey` first named `x` (anchor or untimed entry)."""
        r = self._x("SELECT MIN(k) FROM (SELECT known_at AS k FROM anchors WHERE y=? AND pubkey=? UNION ALL "
                    "SELECT known_at FROM mailbox WHERE y=? AND pubkey=? AND at IS NULL)", (x, pubkey, x, pubkey))
        return r[0] if r else None

    def lock_add(self, x: str, pubkey: str, now: float) -> bool:
        return self._x_rowcount("INSERT OR IGNORE INTO locks(x, pubkey, at) VALUES (?,?,?)", (x, pubkey, now)) == 1

    def locked(self, x: str) -> set[str]:
        return {r[0] for r in self._all("SELECT pubkey FROM locks WHERE x=?", (x,))}

    def drop_unlocked(self, x: str, keys: set[str]) -> int:
        """After report `x` locked `keys`: unsent entries for it from any other key can never go out."""
        with self._lock:
            rows = self._db.execute("SELECT DISTINCT event_id, pubkey FROM mailbox WHERE sent_at IS NULL AND "
                                    "((y=? AND at IS NULL) OR via=?)", (x, x)).fetchall()
            drop = {eid for eid, pk in rows if pk not in keys}
            for eid in drop:
                self._db.execute("DELETE FROM mailbox WHERE event_id=?", (eid,))
            self._db.commit()
            return len(drop)

    def _x_rowcount(self, sql: str, args=()) -> int:
        with self._lock:
            cur = self._db.execute(sql, args)
            self._db.commit()
            return cur.rowcount

    def mailbox_match(self, y: str, heard_at: float, now: float) -> list[MailboxEntry]:
        """Unsent commands for tag `y` that were known BEFORE the report was heard, the one seen first first
        (newest-first let anyone copy the owner's y tags into a later event with junk content)."""
        rows = self._all(f"SELECT {ENTRY_COLS} FROM mailbox "
                         "WHERE y=? AND known_at < ? AND expires > ? AND sent_at IS NULL AND at IS NULL AND ack=0 "
                         "ORDER BY known_at, rowid", (y, heard_at, now))
        return [MailboxEntry(*r) for r in rows]

    def ack_match(self, y: str, now: float) -> list[MailboxEntry]:
        """Unsent live ACK entries (untimed) for report tag `y`, the one seen first first."""
        rows = self._all(f"SELECT {ENTRY_COLS} FROM mailbox "
                         "WHERE y=? AND expires > ? AND sent_at IS NULL AND at IS NULL AND ack=1 "
                         "ORDER BY known_at, rowid", (y, now))
        return [MailboxEntry(*r) for r in rows]

    def entry(self, event_id: str) -> MailboxEntry | None:
        r = self._x(f"SELECT {ENTRY_COLS} FROM mailbox WHERE event_id=? ORDER BY rowid LIMIT 1", (event_id,))
        return MailboxEntry(*r) if r else None

    def entry_ys(self, event_id: str) -> set[str]:
        return {r[0] for r in self._all("SELECT y FROM mailbox WHERE event_id=?", (event_id,))}

    def entry_mark_vias(self, event_id: str, via: str) -> None:
        """The `via` of a timed entry that this bridge heard (of the several the app names)."""
        self._x("UPDATE mailbox SET via=? WHERE event_id=?", (via, event_id))

    def group_untimed(self, g: str) -> dict[str, set[str]]:
        """Untimed command entries of group `g`: event id -> its y tags (ACK entries have no group)."""
        out: dict[str, set[str]] = {}
        for eid, y in self._all("SELECT event_id, y FROM mailbox WHERE grp=? AND at IS NULL AND ack=0", (g,)):
            out.setdefault(eid, set()).add(y)
        return out

    def mailbox_timed_due(self, until: float, now: float) -> list[MailboxEntry]:
        """Unsent, unexpired timed entries with `at` <= `until` whose tags were not answered yet."""
        rows = self._all(f"SELECT {ENTRY_COLS} FROM mailbox m "
                         "WHERE at IS NOT NULL AND at <= ? AND expires > ? AND sent_at IS NULL "
                         "AND NOT EXISTS (SELECT 1 FROM mailbox m2 JOIN answered a ON a.y = m2.y "
                         "WHERE m2.event_id = m.event_id) ORDER BY at, created_at DESC, event_id, y",
                         (until, now))
        return [MailboxEntry(*r) for r in rows]

    # ------------------------------------------------------------ LT2 report paths (for timed entries)

    def report_path_put(self, x: str, hops: int | None, path: list[bytes] | None, hash_size: int,
                        heard_at: float, route: str | None = None, scope: str | None = None) -> None:
        """Remember how report `x` reached us; of several copies the one with the fewest hops stays (a
        flood copy with a known count replaces an unknown one; a DIRECT arrival never replaces a record)."""
        blob = b"".join(path) if path else None
        with self._lock:
            old = self._db.execute("SELECT hops, route FROM report_paths WHERE x=?", (x,)).fetchone()
            if old is not None and (hops is None or (old[0] is not None and hops >= old[0])):
                return
            self._db.execute("INSERT OR REPLACE INTO report_paths(x, hops, path, hash_size, heard_at, route, scope) "
                             "VALUES (?,?,?,?,?,?,?)", (x, hops, blob, hash_size, heard_at, route, scope))
            self._db.commit()

    def report_path_learn(self, x: str, path: list[bytes], hash_size: int, now: float) -> None:
        """A DIRECT arrival x answered along the path of its `via`: the path is now x's too (PROTOCOL.md 4.3)."""
        self._x("UPDATE report_paths SET path=?, hash_size=?, used_at=? WHERE x=? AND path IS NULL",
                (b"".join(path), hash_size, now, x))

    def report_path_touch(self, x: str, now: float) -> None:
        """A use for an ACK or a command restarts the 48 h of the record and of its key lock (R33)."""
        with self._lock:
            self._db.execute("UPDATE report_paths SET used_at=? WHERE x=?", (now, x))
            self._db.execute("UPDATE locks SET at=MAX(at, ?) WHERE x=?", (now, x))
            self._db.commit()

    def report_path(self, x: str, since: float) -> ReportPath | None:
        """The record of `x` if it was heard or used at `since` or later."""
        r = self._x("SELECT hops, path, hash_size, heard_at, route, scope, used_at FROM report_paths "
                    "WHERE x=? AND MAX(heard_at, COALESCE(used_at, 0)) >= ?", (x, since))
        if r is None:
            return None
        hops, blob, hs, heard_at, route, scope, used_at = r
        path = None if blob is None else [bytes(blob[i:i + hs]) for i in range(0, len(blob), hs)]
        return ReportPath(x, hops, path, hs, heard_at, route, scope, used_at)

    def report_paths_prune(self, before: float) -> int:
        with self._lock:
            n = self._db.execute("DELETE FROM report_paths WHERE MAX(heard_at, COALESCE(used_at, 0)) < ?",
                                 (before,)).rowcount
            self._db.commit()
            return n

    def mailbox_by_payload(self, payload: bytes) -> list[str]:
        return [r[0] for r in self._all("SELECT DISTINCT event_id FROM mailbox WHERE payload=?", (payload,))]

    def mailbox_sent(self, event_id: str) -> bool:
        return self._x("SELECT 1 FROM mailbox WHERE event_id=? AND sent_at IS NOT NULL LIMIT 1",
                       (event_id,)) is not None

    def mailbox_mark_sent(self, event_id: str, now: float) -> None:
        self._x("UPDATE mailbox SET sent_at=COALESCE(sent_at, ?) WHERE event_id=?", (now, event_id))

    def mailbox_pending(self, event_id: str) -> bool:
        """Still in the mailbox and not sent (False once cancelled, sent or dropped)."""
        return self._x("SELECT 1 FROM mailbox WHERE event_id=? AND sent_at IS NULL LIMIT 1", (event_id,)) is not None

    def mailbox_cancelled(self, event_id: str, pubkey: str) -> bool:
        """A deletion of `event_id` signed by `pubkey` arrived (possibly before the event itself)."""
        return self._x("SELECT 1 FROM cancelled WHERE event_id=? AND pubkey=?", (event_id, pubkey)) is not None

    def mailbox_cancel(self, event_id: str, pubkey: str, now: float, cap: int) -> str:
        """Apply a NIP-09 deletion of mailbox event `event_id` signed by `pubkey` and remember it.
        Returns "cancelled" (unsent entry dropped), "sent" (too late, already transmitted), "other_key"
        (the event has another author: ignored) or "unknown" (not here yet: kept for when it comes)."""
        with self._lock:
            rows = self._db.execute("SELECT pubkey, sent_at FROM mailbox WHERE event_id=?", (event_id,)).fetchall()
            if rows and any(r[0] != pubkey for r in rows):
                return "other_key"
            self._db.execute("INSERT OR IGNORE INTO cancelled(event_id, pubkey, at) VALUES (?,?,?)",
                             (event_id, pubkey, now))
            over = self._db.execute("SELECT COUNT(*) FROM cancelled").fetchone()[0] - cap
            if over > 0:
                self._db.execute("DELETE FROM cancelled WHERE rowid IN (SELECT rowid FROM cancelled "
                                 "ORDER BY at LIMIT ?)", (over,))
            if not rows:
                result = "unknown"
            elif any(r[1] is not None for r in rows):
                result = "sent"
            else:
                self._db.execute("DELETE FROM mailbox WHERE event_id=?", (event_id,))
                result = "cancelled"
            self._db.commit()
            return result

    def mailbox_size(self) -> int:
        return self._x("SELECT COUNT(*) FROM mailbox")[0]

    def answer(self, y: str, now: float) -> bool:
        """Claim tag `y` for one downlink; False if a command already went (or is going) out for it."""
        with self._lock:
            cur = self._db.execute("INSERT OR IGNORE INTO answered(y, at) VALUES (?,?)", (y, now))
            self._db.commit()
            return cur.rowcount == 1

    # ------------------------------------------------------------ LT2 limits and groups

    @staticmethod
    def _ph(payload: bytes) -> str:
        return hashlib.sha256(payload).hexdigest()

    def payload_sent(self, payload: bytes) -> bool:
        """This Pc already went out (from here, or heard on air): identical payloads go once."""
        return self._x("SELECT 1 FROM sent_payloads WHERE h=?", (self._ph(payload),)) is not None

    def payload_mark_sent(self, payload: bytes, now: float) -> None:
        self._x("INSERT OR IGNORE INTO sent_payloads(h, at) VALUES (?,?)", (self._ph(payload), now))

    def timed_tx_add(self, via: str, now: float) -> None:
        self._x("INSERT INTO timed_tx(via, at) VALUES (?,?)", (via, now))

    def timed_tx_last(self, via: str) -> float | None:
        r = self._x("SELECT MAX(at) FROM timed_tx WHERE via=?", (via,))
        return r[0] if r else None

    def timed_tx_count(self, since: float) -> int:
        return self._x("SELECT COUNT(*) FROM timed_tx WHERE at >= ?", (since,))[0]

    def open_tx_add(self, now: float) -> None:
        self._x("INSERT INTO open_tx(at) VALUES (?)", (now,))

    def open_tx_count(self, since: float) -> int:
        return self._x("SELECT COUNT(*) FROM open_tx WHERE at >= ?", (since,))[0]

    def ack_tx_add(self, now: float) -> None:
        self._x("INSERT INTO ack_tx(at) VALUES (?)", (now,))

    def ack_tx_count(self, since: float) -> int:
        return self._x("SELECT COUNT(*) FROM ack_tx WHERE at >= ?", (since,))[0]

    def blind_tx_add(self, via: str, now: float) -> None:
        self._x("INSERT INTO blind_tx(via, at) VALUES (?,?)", (via, now))

    def blind_tx_count(self, since: float, via: str | None = None) -> int:
        if via is None:
            return self._x("SELECT COUNT(*) FROM blind_tx WHERE at >= ?", (since,))[0]
        return self._x("SELECT COUNT(*) FROM blind_tx WHERE at >= ? AND via=?", (since, via))[0]

    def group_done(self, g: str) -> bool:
        return self._x("SELECT 1 FROM done_groups WHERE g=?", (g,)) is not None

    def group_close(self, g: str, now: float) -> int:
        """Mark group `g` answered and drop its unsent timed entries; the number of events dropped."""
        with self._lock:
            self._db.execute("INSERT OR IGNORE INTO done_groups(g, at) VALUES (?,?)", (g, now))
            n = self._db.execute("SELECT COUNT(DISTINCT event_id) FROM mailbox WHERE grp=? AND at IS NOT NULL "
                                 "AND sent_at IS NULL", (g,)).fetchone()[0]
            self._db.execute("DELETE FROM mailbox WHERE grp=? AND at IS NOT NULL AND sent_at IS NULL", (g,))
            self._db.commit()
            return n

    def groups_of_y(self, y: str) -> list[str]:
        """Groups that have an untimed entry with tag `y` (a report with this x is the tracker's own)."""
        return [r[0] for r in self._all("SELECT DISTINCT grp FROM mailbox WHERE y=? AND at IS NULL AND ack=0 "
                                        "AND grp IS NOT NULL", (y,))]

    def group_has_timed(self, g: str) -> bool:
        return self._x("SELECT 1 FROM mailbox WHERE grp=? AND at IS NOT NULL LIMIT 1", (g,)) is not None

    def group_timed(self, g: str) -> list[MailboxEntry]:
        return [MailboxEntry(*r) for r in self._all(f"SELECT {ENTRY_COLS} FROM mailbox WHERE grp=? AND at IS NOT NULL "
                                                    "ORDER BY at", (g,))]

    def group_has_untimed_y(self, g: str, y: str) -> bool:
        return self._x("SELECT 1 FROM mailbox WHERE grp=? AND y=? AND at IS NULL LIMIT 1", (g, y)) is not None

    def mailbox_relay_count(self, relay: str) -> int:
        return self._x("SELECT COUNT(*) FROM mailbox WHERE relay=?", (relay,))[0]

    # ------------------------------------------------------------ airtime (survives a restart)

    def airtime_add(self, bucket: str, now: float, secs: float) -> None:
        self._x("INSERT INTO airtime(bucket, at, secs) VALUES (?,?,?)", (bucket, now, secs))

    def airtime_load(self, bucket: str, since: float) -> list[tuple[float, float]]:
        return [(a, s) for a, s in self._all("SELECT at, secs FROM airtime WHERE bucket=? AND at >= ? ORDER BY at",
                                             (bucket, since))]

    # ------------------------------------------------------------ outbox (unpublished events)

    def outbox_put(self, event: dict, ledger_key: str, stat: str, expires: float, now: float,
                   next_try: float | None = None) -> None:
        """`next_try`: when a restarted bridge may publish it at the latest (a report written at hearing time,
        R21, is due after its publish delay); default after the pool's own retries."""
        self._x("INSERT OR IGNORE INTO outbox(id, event, ledger_key, stat, expires, next_try, tries) "
                "VALUES (?,?,?,?,?,?,0)", (event["id"], json.dumps(event, separators=(",", ":")), ledger_key, stat,
                                           expires, now + OUTBOX_BACKOFF_S[0] if next_try is None else next_try))

    def outbox_has(self, eid: str) -> bool:
        return self._x("SELECT 1 FROM outbox WHERE id=?", (eid,)) is not None

    def outbox_done(self, eid: str) -> None:
        self._x("DELETE FROM outbox WHERE id=?", (eid,))

    def outbox_failed(self, eid: str, now: float) -> None:
        with self._lock:
            r = self._db.execute("SELECT tries FROM outbox WHERE id=?", (eid,)).fetchone()
            if r is not None:
                tries = r[0] + 1
                delay = OUTBOX_BACKOFF_S[min(tries, len(OUTBOX_BACKOFF_S) - 1)]
                self._db.execute("UPDATE outbox SET tries=?, next_try=? WHERE id=?", (tries, now + delay, eid))
                self._db.commit()

    def outbox_due(self, now: float, limit: int = 50) -> list[tuple[dict, str, str]]:
        """(event, ledger key, stat) of unexpired events due for another try; expired ones are dropped."""
        with self._lock:
            self._db.execute("DELETE FROM outbox WHERE expires <= ?", (now,))
            self._db.commit()
            rows = self._db.execute("SELECT event, ledger_key, stat FROM outbox WHERE next_try <= ? ORDER BY next_try "
                                    "LIMIT ?", (now, limit)).fetchall()
        return [(json.loads(e), k, st) for e, k, st in rows]

    def outbox_size(self) -> int:
        return self._x("SELECT COUNT(*) FROM outbox")[0]

    def kv_get(self, k: str) -> str | None:
        r = self._x("SELECT v FROM kv WHERE k=?", (k,))
        return r[0] if r else None

    def kv_set(self, k: str, v: str) -> None:
        self._x("INSERT OR REPLACE INTO kv(k, v) VALUES (?,?)", (k, v))

    # ------------------------------------------------------------ housekeeping

    def prune(self, now: float, retention_days: float) -> int:
        cutoff = now - retention_days * 86400
        with self._lock:
            n = self._db.execute("DELETE FROM events WHERE first_seen < ?", (cutoff,)).rowcount
            n += self._db.execute("DELETE FROM mailbox WHERE expires < ?", (now,)).rowcount
            n += self._db.execute("DELETE FROM answered WHERE at < ?", (cutoff,)).rowcount
            n += self._db.execute("DELETE FROM cancelled WHERE at < ?", (cutoff,)).rowcount
            n += self._db.execute("DELETE FROM sent_payloads WHERE at < ?", (cutoff,)).rowcount
            n += self._db.execute("DELETE FROM done_groups WHERE at < ?", (cutoff,)).rowcount
            n += self._db.execute("DELETE FROM anchors WHERE expires < ?", (now,)).rowcount
            n += self._db.execute("DELETE FROM locks WHERE at < ?", (now - 2 * 86400,)).rowcount
            n += self._db.execute("DELETE FROM timed_tx WHERE at < ?", (now - 2 * 86400,)).rowcount
            n += self._db.execute("DELETE FROM open_tx WHERE at < ?", (now - 2 * 86400,)).rowcount
            n += self._db.execute("DELETE FROM ack_tx WHERE at < ?", (now - 2 * 86400,)).rowcount
            n += self._db.execute("DELETE FROM blind_tx WHERE at < ?", (now - 2 * 86400,)).rowcount
            n += self._db.execute("DELETE FROM airtime WHERE at < ?", (now - 2 * 86400,)).rowcount
            self._db.commit()
            return n
