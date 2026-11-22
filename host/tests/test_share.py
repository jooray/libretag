"""Share links, the laptop's store of shared tags and the USB console unlock with a shared key."""
from __future__ import annotations

import io
import json
import os
import stat
from pathlib import Path

import pytest

from libretag import cli, console, lt2, share

VEC = json.loads((Path(__file__).parent / "vectors" / "share.json").read_text())
KEYS = lt2.Keys(bytes.fromhex(VEC["k0"]))
E = VEC["epoch"]


def test_vectors_are_current():
    assert share.vectors() == VEC


@pytest.mark.parametrize("level", ["view", "control", "full"])
def test_round_trip(level):
    sh = share.parse_link(VEC[level])
    assert sh is not None and sh.level == level
    assert (sh.name, sh.type, sh.mark, sh.epoch) == (VEC["name"], VEC["type"], VEC["mark"], E)
    assert sh.view == KEYS.view(E).hex()
    assert sh.cmd == (None if level == "view" else KEYS.cmd(E).hex())
    assert sh.usb_key == (KEYS.usb_key.hex() if level == "full" else None)
    assert sh.id == (KEYS.id.hex() if level == "full" else None)
    again = share.build_link(sh.name, sh.type, sh.mark, sh.epoch, bytes.fromhex(sh.view),
                             sh.cmd and bytes.fromhex(sh.cmd), sh.usb_key and bytes.fromhex(sh.usb_key),
                             sh.id and bytes.fromhex(sh.id))
    assert again == VEC[level]


def test_never_k0():
    for level in ("view", "control", "full"):
        assert VEC["k0"] not in VEC[level]


@pytest.mark.parametrize("bad", VEC["invalid"] + ["", "libretag://share?v=1"])
def test_invalid(bad):
    assert share.parse_link(bad) is None


def test_full_access_needs_control_and_id():
    with pytest.raises(ValueError):
        share.build_link("x", "thing", "", 0, KEYS.view(0), None, KEYS.usb_key, KEYS.id)


def test_save_mode_and_find(tmp_path):
    full = share.parse_link(VEC["full"])
    p = share.save(full, tmp_path)
    assert stat.S_IMODE(os.stat(p).st_mode) == 0o600
    assert stat.S_IMODE(os.stat(p.parent).st_mode) == 0o700
    assert share.find(None, tmp_path) == full
    assert share.find(KEYS.id.hex()[:6], tmp_path) == full
    assert share.find(VEC["name"].upper(), tmp_path) == full
    with pytest.raises(LookupError):
        share.find("nope", tmp_path)
    # the same tag again replaces the old file; a view-only share has no console key
    share.save(share.parse_link(VEC["view"]), tmp_path)
    assert len(share.saved(tmp_path)) == 1
    with pytest.raises(LookupError):
        share.find(None, tmp_path)
    assert share.find(None, tmp_path, need_console=False).level == "view"


class FakeTag:
    """A locked tracker's console on USB: echo, status, challenge/auth (PROTOCOL.md 6)."""

    def __init__(self, keys: lt2.Keys):
        self.keys, self.out, self.unlocked = keys, b"", False
        self.challenge = bytes(range(16))

    def write(self, b: bytes) -> None:
        line = b.decode().strip()
        ans = [line]
        if line == "status":
            ans += [f"LIBRETAG ID {self.keys.id.hex()}"] + ([] if self.unlocked else ["locked"])
        elif line == "challenge":
            ans.append(f"CHALLENGE {self.challenge.hex()}")
        elif line.startswith("auth "):
            ok = line[5:] == lt2.usb_auth(self.keys.usb_key, self.challenge).hex()
            self.unlocked = self.unlocked or ok
            ans.append("OK unlocked" if ok else "ERR auth")
        elif line == "interval 30":
            ans.append("OK interval=30min" if self.unlocked else "ERR locked")
        self.out += ("\r\n".join(ans) + "\r\n").encode()

    def read(self, n: int) -> bytes:
        b, self.out = self.out[:n], self.out[n:]
        return b

    def close(self) -> None:
        pass


def test_console_unlock_with_shared_key():
    sh = share.parse_link(VEC["full"])
    c = console.Console(FakeTag(KEYS), quiet_s=0.05)
    st = c.status()
    assert st.locked and st.id == sh.id
    assert c.command("interval 30") == ["ERR locked"]
    assert not c.unlock(bytes(32))
    assert c.unlock(bytes.fromhex(sh.usb_key))
    assert c.command("interval 30") == ["OK interval=30min"]


def test_cli_import_and_console(tmp_path, monkeypatch, capsys):
    monkeypatch.setattr("sys.stdin", io.StringIO(VEC["full"] + "\n"))
    cli.main(["share", "import", "--config", str(tmp_path)])
    out = capsys.readouterr().out
    assert "full access" in out and KEYS.id.hex() in out
    for secret in (KEYS.usb_key.hex(), KEYS.view(E).hex(), KEYS.cmd(E).hex()):
        assert secret not in out
    tag = FakeTag(KEYS)
    monkeypatch.setattr(console, "open_serial", lambda path, esp=False: tag)
    cli.main(["console", "--config", str(tmp_path), "--port", "/dev/fake", "--quiet", "0.05", "-c", "interval 30"])
    assert capsys.readouterr().out.strip() == "OK interval=30min"
    # another tag on the cable: refused before any unlock
    other = FakeTag(lt2.Keys(bytes(32)))
    monkeypatch.setattr(console, "open_serial", lambda path, esp=False: other)
    with pytest.raises(SystemExit):
        cli.main(["console", "--config", str(tmp_path), "--port", "/dev/fake", "--quiet", "0.05", "-c", "status"])
    assert not other.unlocked
