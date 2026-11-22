"""Share links (PROTOCOL.md 1, sharing) and the laptop's store of shared tags.

    libretag://share?v=1&name=<name>&type=<type>&mark=<colour>-<symbol>&e=<epoch>&ve=<V_e>[&ce=<C_e>[&uk=<usb_key>&id=<id>]]

Three levels: view (`ve`), view and control (`ce` too) and full access (`uk` and `id` too: the
tag's console key, PROTOCOL.md 6 and 6.2). Never K0.

`libretag share import` keeps one JSON file per tag under ~/.config/libretag/tags (or
$LIBRETAG_CONFIG/tags), the directory mode 700 and each file mode 600. Keys never go to stdout or
a log; the console tools (`libretag console`, tools/blecon.py --tag) read them from there.

    uv run python -m libretag.share vectors    # writes tests/vectors/share.json for the Kotlin tests
"""
from __future__ import annotations

import json
import os
import re
import sys
from dataclasses import asdict, dataclass
from pathlib import Path
from urllib.parse import quote, unquote

PREFIX = "libretag://share?"
COLORS = ("red", "blue", "green", "yellow")
SYMBOLS = ("stripe", "diag", "triangle", "circle", "square")


@dataclass(frozen=True)
class Share:
    name: str
    type: str
    mark: str
    epoch: int
    view: str
    cmd: str | None = None
    usb_key: str | None = None
    id: str | None = None

    @property
    def level(self) -> str:
        return "full" if self.usb_key else "control" if self.cmd else "view"


def _hex(v: str | None, size: int) -> str | None:
    if v is None or not re.fullmatch(r"[0-9a-fA-F]*", v) or len(v) != 2 * size:
        return None
    return v.lower()


def _enc(v: str) -> str:
    # the same bytes as Java's URLEncoder with %20 for a space (ShareLink.kt)
    return quote(v, safe="*").replace("~", "%7E")


def build_link(name: str, type_: str, mark: str, epoch: int, view: bytes, cmd: bytes | None = None,
               usb_key: bytes | None = None, id_: bytes | None = None) -> str:
    if usb_key is not None and (cmd is None or id_ is None):
        raise ValueError("full access includes control and the id")
    p = [("v", "1"), ("name", name), ("type", type_), ("mark", mark), ("e", str(epoch)), ("ve", view.hex())]
    if cmd is not None:
        p.append(("ce", cmd.hex()))
    if usb_key is not None:
        p += [("uk", usb_key.hex()), ("id", id_.hex())]
    return PREFIX + "&".join(f"{k}={_enc(v)}" for k, v in p)


def parse_link(s: str) -> Share | None:
    """None for anything that isn't a whole share link (as ShareLink.parse in the app)."""
    t = s.strip()
    if not t.lower().startswith(PREFIX):
        return None
    q: dict[str, str] = {}
    for kv in t[len(PREFIX):].split("#", 1)[0].split("&"):
        if kv:
            k, _, v = kv.partition("=")
            q[unquote(k.replace("+", " "))] = unquote(v.replace("+", " "))
    ve = _hex(q.get("ve"), 32)
    if ve is None:
        return None
    ce = _hex(q.get("ce"), 32)
    if "ce" in q and ce is None:
        return None
    uk, id_ = _hex(q.get("uk"), 32), _hex(q.get("id"), 8)
    if "uk" in q and (uk is None or id_ is None or ce is None):
        return None
    m = q.get("mark", "").split("-")
    color = m[0] if m[0] in COLORS else ""
    symbol = m[1] if len(m) > 1 and m[1] in SYMBOLS else ""
    try:
        e = max(0, int(q.get("e", "0")))
    except ValueError:
        e = 0
    return Share(q.get("name") or "Tag", q.get("type") or "thing", f"{color}-{symbol}" if color else "", e,
                 ve, ce, uk, id_ if uk else None)


# ---------------------------------------------------------------- the laptop's store

def config_dir() -> Path:
    base = os.environ.get("LIBRETAG_CONFIG")
    return Path(base) if base else Path.home() / ".config" / "libretag"


def tags_dir(base: Path | None = None) -> Path:
    return (base or config_dir()) / "tags"


def _slug(name: str) -> str:
    s = re.sub(r"[^a-z0-9]+", "-", name.lower()).strip("-")
    return s or "tag"


def save(sh: Share, base: Path | None = None) -> Path:
    """Write the share to <tags>/<id or name>.json, mode 600 (the directory 700). Replaces an older copy."""
    d = tags_dir(base)
    d.mkdir(parents=True, exist_ok=True, mode=0o700)
    os.chmod(d, 0o700)
    for old in d.glob("*.json"):  # the same tag shared again (another level, a new epoch)
        o = _read(old)
        if o and (o.view == sh.view or (sh.id and o.id == sh.id)):
            old.unlink()
    path = d / f"{sh.id or _slug(sh.name)}.json"
    tmp = path.with_suffix(".tmp")
    fd = os.open(tmp, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
    with os.fdopen(fd, "w") as f:
        json.dump(asdict(sh), f, indent=1)
    os.chmod(tmp, 0o600)
    os.replace(tmp, path)
    return path


def _read(p: Path) -> Share | None:
    try:
        return Share(**json.loads(p.read_text()))
    except (OSError, ValueError, TypeError):
        return None


def saved(base: Path | None = None) -> list[tuple[Path, Share]]:
    d = tags_dir(base)
    out = [(p, s) for p in sorted(d.glob("*.json")) if (s := _read(p))] if d.is_dir() else []
    return out


def find(sel: str | None, base: Path | None = None, need_console: bool = True) -> Share:
    """The stored tag by name (any case) or id prefix; with no selector the only one there is."""
    tags = [s for _, s in saved(base) if s.usb_key or not need_console]
    if sel:
        k = sel.lower()
        tags = [s for s in tags if s.name.lower() == k or (s.id and s.id.startswith(k))]
    if len(tags) == 1:
        return tags[0]
    what = "with full access " if need_console else ""
    if not tags:
        raise LookupError(f"no shared tag {what}stored" + (f" matches {sel!r}" if sel else "") +
                          " (import one with: libretag share import -)")
    raise LookupError("several tags match: " + ", ".join(f"{s.name} ({s.id})" for s in tags) + "; pick one with --tag")


# ---------------------------------------------------------------- vectors for the app's tests

def vectors() -> dict:
    from . import lt2
    k0 = bytes.fromhex(json.loads((Path(__file__).resolve().parents[2] / "tests/vectors/lt2.json").read_text())["k0"])
    keys, e = lt2.Keys(k0), 3
    name, type_, mark = "Bob's bike & co", "vehicle", "blue-circle"
    v, c, u, i = keys.view(e), keys.cmd(e), keys.usb_key, keys.id
    return {
        "k0": k0.hex(), "epoch": e, "name": name, "type": type_, "mark": mark,
        "view": build_link(name, type_, mark, e, v),
        "control": build_link(name, type_, mark, e, v, c),
        "full": build_link(name, type_, mark, e, v, c, u, i),
        "invalid": [
            PREFIX + f"v=1&e={e}&ve={v.hex()[:-2]}",                      # short V_e
            PREFIX + f"v=1&e={e}&ve={v.hex()}&uk={u.hex()}&id={i.hex()}",  # full access without C_e
            PREFIX + f"v=1&e={e}&ve={v.hex()}&ce={c.hex()}&uk={u.hex()}",  # without the id
            PREFIX + f"v=1&e={e}&ve={v.hex()}&ce={c.hex()[:10]}",          # broken C_e
            f"https://example.org/share?ve={v.hex()}",
        ],
    }


def main(argv: list[str] | None = None) -> None:
    argv = sys.argv[1:] if argv is None else argv
    if argv[:1] != ["vectors"]:
        sys.exit("usage: python -m libretag.share vectors [out]")
    out = Path(argv[1]) if len(argv) > 1 else Path(__file__).resolve().parents[2] / "tests/vectors/share.json"
    out.write_text(json.dumps(vectors(), indent=1) + "\n")
    print(f"wrote {out}")


if __name__ == "__main__":
    main()
