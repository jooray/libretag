"""SQLite storage for trackers (K0), report history and the command queue (LT2)."""

from __future__ import annotations

import json
import os
import sqlite3
import time
from dataclasses import dataclass
from pathlib import Path

from .lt2 import KEY_SIZE, WINDOW_BACK, Command, Report

SCHEMA = """
CREATE TABLE IF NOT EXISTS trackers (
    name TEXT PRIMARY KEY,
    k0 BLOB NOT NULL,
    last_n INTEGER NOT NULL DEFAULT -1,
    seen TEXT NOT NULL DEFAULT '',
    cmd_seq INTEGER NOT NULL DEFAULT 0,
    traccar_id TEXT
);
CREATE TABLE IF NOT EXISTS positions (
    id INTEGER PRIMARY KEY,
    tracker TEXT NOT NULL,
    n INTEGER NOT NULL,
    j INTEGER NOT NULL,
    received REAL NOT NULL,
    fix_time INTEGER,
    has_fix INTEGER,
    lat REAL, lon REAL, hacc INTEGER,
    battery INTEGER, mode INTEGER, cmd_ack INTEGER, cmd_seq INTEGER, low_batt INTEGER,
    nb_total INTEGER, nb_discover INTEGER, neighbours TEXT,
    hops INTEGER, path TEXT,
    UNIQUE(tracker, n)
);
CREATE TABLE IF NOT EXISTS commands (
    id INTEGER PRIMARY KEY,
    tracker TEXT NOT NULL,
    seq INTEGER NOT NULL,
    body BLOB NOT NULL,
    created REAL NOT NULL,
    sends INTEGER NOT NULL DEFAULT 0,
    sent_at REAL,
    sent_for_n INTEGER,
    acked_at REAL,
    failed_at REAL
);
"""

MAX_SENDS = 5             # a command goes out after at most this many reports, then it is given up
MAX_AGE_S = 7 * 86400     # and never after a week in the queue


def default_db_path() -> Path:
    return Path(os.environ.get("LIBRETAG_DB", Path.home() / ".local/share/libretag/libretag.db"))


@dataclass
class Tracker:
    name: str
    k0: bytes
    last_n: int
    seen: set[int]
    cmd_seq: int
    traccar_id: str | None


@dataclass
class Pending:
    id: int
    seq: int
    command: Command
    sends: int
    sent_for_n: int | None


class Store:
    def __init__(self, path: Path | str | None = None):
        path = Path(path) if path else default_db_path()
        if str(path) != ":memory:":
            path.parent.mkdir(parents=True, exist_ok=True)
        self.db = sqlite3.connect(str(path))
        cols = {r[1] for r in self.db.execute("PRAGMA table_info(trackers)")}
        if cols and "k0" not in cols:
            raise RuntimeError(f"{path} is a LibreTag v1 database; LT2 needs re-pairing: move it away and "
                               "pair the trackers again")
        self.db.executescript(SCHEMA)

    # trackers
    def add_tracker(self, name: str, k0: bytes, traccar_id: str | None = None) -> Tracker:
        if len(k0) != KEY_SIZE:
            raise ValueError("K0 must be 32 bytes")
        self.db.execute("INSERT INTO trackers(name, k0, traccar_id) VALUES (?,?,?)", (name, k0, traccar_id or name))
        self.db.commit()
        return self.get_tracker(name)

    @staticmethod
    def _row(r) -> Tracker:
        name, k0, last_n, seen, cmd_seq, tid = r
        return Tracker(name, k0, last_n, {int(x) for x in seen.split(",") if x}, cmd_seq, tid)

    def get_tracker(self, name: str) -> Tracker:
        row = self.db.execute("SELECT name, k0, last_n, seen, cmd_seq, traccar_id FROM trackers WHERE name=?",
                              (name,)).fetchone()
        if not row:
            raise KeyError(name)
        return self._row(row)

    def trackers(self) -> list[Tracker]:
        return [self._row(r) for r in self.db.execute("SELECT name, k0, last_n, seen, cmd_seq, traccar_id FROM trackers")]

    def set_window(self, name: str, last_n: int, seen: set[int]) -> None:
        keep = sorted(s for s in seen if s >= last_n - WINDOW_BACK)
        self.db.execute("UPDATE trackers SET last_n=?, seen=? WHERE name=?",
                        (last_n, ",".join(map(str, keep)), name))
        self.db.commit()

    # positions (one row per n; a late report slots into the history by n)
    def add_position(self, name: str, n: int, j: int, rep: Report, hops: int, path_hex: str,
                     received: float | None = None) -> bool:
        nbs = json.dumps([[h.hex(), snr] for h, snr in rep.neighbours])
        cur = self.db.execute(
            "INSERT OR IGNORE INTO positions(tracker,n,j,received,fix_time,has_fix,lat,lon,hacc,battery,mode,"
            "cmd_ack,cmd_seq,low_batt,nb_total,nb_discover,neighbours,hops,path) "
            "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
            (name, n, j, received or time.time(), rep.fix_time, int(rep.has_fix), rep.lat, rep.lon, rep.hacc_m,
             rep.battery_pct, int(rep.mode), int(rep.cmd_ack), rep.cmd_seq, int(rep.low_batt), rep.nb_total,
             int(rep.nb_discover), nbs, hops, path_hex))
        self.db.commit()
        return cur.rowcount == 1

    def positions(self, name: str, limit: int = 20) -> list[tuple]:
        """(n, received, fix_time, has_fix, lat, lon, battery, mode, hops), newest n first."""
        return self.db.execute(
            "SELECT n, received, fix_time, has_fix, lat, lon, battery, mode, hops FROM positions "
            "WHERE tracker=? ORDER BY n DESC LIMIT ?", (name, limit)).fetchall()

    # commands
    def queue_command(self, name: str, body_builder) -> int:
        """body_builder(seq) -> Command; returns the assigned seq."""
        t = self.get_tracker(name)
        seq = (t.cmd_seq + 1) & 0xFF
        cmd = body_builder(seq)
        self.db.execute("UPDATE trackers SET cmd_seq=? WHERE name=?", (seq, name))
        self.db.execute("INSERT INTO commands(tracker, seq, body, created) VALUES (?,?,?,?)",
                        (name, seq, cmd.encode(), time.time()))
        self.db.commit()
        return seq

    def pending_command(self, name: str, now: float | None = None) -> Pending | None:
        """Oldest command neither acknowledged nor given up. Gives up (bounded retry) on a command that
        already went out MAX_SENDS times or waited longer than MAX_AGE_S, and returns the next one."""
        now = time.time() if now is None else now
        while True:
            row = self.db.execute(
                "SELECT id, seq, body, sends, sent_for_n, created FROM commands WHERE tracker=? AND acked_at IS NULL "
                "AND failed_at IS NULL ORDER BY id LIMIT 1", (name,)).fetchone()
            if row is None:
                return None
            cid, seq, body, sends, sent_for_n, created = row
            if sends < MAX_SENDS and now - created <= MAX_AGE_S:
                return Pending(cid, seq, Command.decode(body), sends, sent_for_n)
            self.db.execute("UPDATE commands SET failed_at=? WHERE id=?", (now, cid))
            self.db.commit()

    def mark_sent(self, cmd_id: int, n: int, now: float | None = None) -> None:
        self.db.execute("UPDATE commands SET sent_at=?, sent_for_n=?, sends=sends+1 WHERE id=?",
                        (time.time() if now is None else now, n, cmd_id))
        self.db.commit()

    def ack_commands(self, name: str, n: int, rep: Report) -> int:
        """A report n acknowledges a sent command only if it is LATER than the report the command
        answered, has CMD_ACK and the command's seq & 0x0F."""
        if not rep.cmd_ack:
            return 0
        rows = self.db.execute(
            "SELECT id, seq, sent_for_n FROM commands WHERE tracker=? AND acked_at IS NULL AND sent_for_n IS NOT NULL",
            (name,)).fetchall()
        acked = 0
        for cid, seq, sent_for_n in rows:
            if seq & 0x0F == rep.cmd_seq & 0x0F and n > sent_for_n:
                self.db.execute("UPDATE commands SET acked_at=? WHERE id=?", (time.time(), cid))
                acked += 1
        self.db.commit()
        return acked

    def command_log(self, name: str, limit: int = 10) -> list[tuple]:
        return self.db.execute(
            "SELECT seq, body, created, sends, sent_for_n, acked_at, failed_at FROM commands WHERE tracker=? "
            "ORDER BY id DESC LIMIT ?", (name, limit)).fetchall()
