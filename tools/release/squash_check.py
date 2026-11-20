#!/usr/bin/env python3
"""Privacy gate for a public LibreTag tree (stdlib only). Run it on the squashed branch before any push.

    python3 tools/release/squash_check.py [REF]          # default REF: public-squash
    python3 tools/release/squash_check.py HEAD --tree-only

Checks the tree of REF (not the working copy) and, unless --tree-only, that REF is a single root
commit whose author and committer carry no personal address. Exit 1 on any finding.

Two kinds of patterns:
- generic ones below (coordinates around the owner's real area, e-mail addresses, MAC addresses,
  secret keys, local paths, forbidden files, image GPS metadata, patch authors);
- private ones from a file outside the repo, one case-insensitive regex per line (# comments):
  $LIBRETAG_PRIVATE_PATTERNS, default ~/.config/libretag/private-patterns.txt. They name the
  things that must never appear (hostnames, real repeater hashes, the owner's address...), so the
  list itself is never committed. Without the file the check fails unless --no-private is given.

Allowed exceptions live in tools/release/squash-check.allow: `<path glob> <regex>` per line; a
finding is skipped when its path matches the glob and its line matches the regex.
"""
from __future__ import annotations

import argparse
import fnmatch
import os
import re
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
ALLOW_FILE = Path(__file__).with_name("squash-check.allow")

# (name, regex, git-grep pathspec excludes)
GENERIC = [
    # the owner's real area: Bratislava and surroundings (lat 48.0-48.29, lon 16.9-17.49), decimal and e7
    ("coordinate near the owner's area", r"\b48[.,][0-2][0-9]{2,}|\b1(6[.,]9|7[.,][0-4])[0-9]{2,}"),
    ("e7 coordinate near the owner's area", r"\b48[0-2][0-9]{6}\b|\b1(69|7[0-4])[0-9]{6}\b"),
    ("geohash cell of the owner's area", r"\bu2s[0-9bcdefghjkmnpqrstuvwxyz]{0,8}\b"),
    ("e-mail address", r"[A-Za-z0-9._%+-]+@[A-Za-z0-9-]+(\.[A-Za-z0-9-]+)*\.[A-Za-z]{2,}"),
    ("MAC / BSSID", r"\b([0-9A-Fa-f]{2}[:-]){5}[0-9A-Fa-f]{2}\b"),
    ("Nostr secret key", r"\bnsec1[02-9ac-hj-np-z]{20,}"),
    ("encrypted Nostr key / bunker URL", r"ncryptsec1|bunker://[0-9a-f]{64}"),
    ("private key block", r"BEGIN [A-Z ]*PRIVATE KEY"),
    ("local home path", r"/Users/(?!YOU\b)[A-Za-z0-9_-]+|/home/(?!user\b|YOU\b)[a-z][a-z0-9_-]+/"),
    ("tailnet / private host", r"\.ts\.net\b|\btailscale\b|\b100\.(6[4-9]|[7-9][0-9]|1[01][0-9]|12[0-7])\.[0-9]+\.[0-9]+"),
    ("signing secret", r"(storePassword|keyPassword)\s*[=:]\s*['\"][^'\"$]"),
]
# e-mail / MAC values that are fine anywhere
OK_EMAIL = re.compile(r"(@([a-z0-9-]+\.)*example\.(com|org|net)|@libretag\.invalid|libretag@cypherpunk\.today|^this@)", re.I)
OK_EMAIL_LINE = re.compile(r"\bthis@[A-Za-z]")


def ok_mac(m: str) -> bool:
    b = [int(x, 16) for x in re.split("[:-]", m)]
    if b[0] & 0x02:  # locally administered: synthetic in our tests
        return True
    s = m.lower().replace("-", ":")
    return "00:00" in s or s.startswith("aa:bb:cc") or s.endswith("11:22:33") or s == "00:00:00:00:00:00"


FORBIDDEN_FILES = [
    ("private business note", "docs/monetization.md"),
    ("internal audit (private)", "docs/audit-*.md"),
    ("build output", "dist/*"),
    ("release binary in the tree", "*.apk"), ("release binary in the tree", "*.aab"),
    ("release binary in the tree", "*.uf2"), ("release binary in the tree", "*.bin"),
    ("keystore", "*.jks"), ("keystore", "*.keystore"), ("keystore", "*.p12"),
    ("signing properties", "*keystore.properties"), ("signing properties", "*signing.properties"),
    ("tracker key file", "*k0.hex"), ("Nostr key file", "*.nsec"),
    ("database", "*.sqlite"), ("database", "*.db"), ("log", "*.log"),
    ("environment file", "*.env"), ("environment file", ".env*"),
]
PATCH_FROM_OK = re.compile(r"^From: LibreTag <dev@libretag\.invalid>$")


def git(*a: str, inp: bytes | None = None) -> bytes:
    return subprocess.run(["git", "-C", str(ROOT), *a], check=True, capture_output=True, input=inp).stdout


def load_allow() -> list[tuple[str, re.Pattern]]:
    out = []
    if ALLOW_FILE.exists():
        for line in ALLOW_FILE.read_text().splitlines():
            line = line.strip()
            if not line or line.startswith("#"):
                continue
            glob, rx = line.split(None, 1)
            out.append((glob, re.compile(rx)))
    return out


def load_private(no_private: bool) -> list[str]:
    p = Path(os.environ.get("LIBRETAG_PRIVATE_PATTERNS", "~/.config/libretag/private-patterns.txt")).expanduser()
    if not p.exists():
        if no_private:
            return []
        sys.exit(f"no private pattern file at {p} (set LIBRETAG_PRIVATE_PATTERNS, or --no-private)")
    return [l.strip() for l in p.read_text().splitlines() if l.strip() and not l.lstrip().startswith("#")]


def grep(ref: str, rx: str, icase: bool = False) -> list[tuple[str, int, str]]:
    args = ["grep", "-I", "-n", "-P"] + (["-i"] if icase else []) + ["-e", rx, ref, "--"]
    r = subprocess.run(["git", "-C", str(ROOT), *args], capture_output=True)
    if r.returncode not in (0, 1):
        sys.exit(f"git grep failed for {rx!r}: {r.stderr.decode()}")
    out = []
    for line in r.stdout.decode("utf-8", "replace").splitlines():
        _, path, num, text = line.split(":", 3)
        out.append((path, int(num), text))
    return out


def allowed(allow, path: str, text: str) -> bool:
    return any(fnmatch.fnmatch(path, g) and rx.search(text) for g, rx in allow)


def short(text: str, rx: str, icase: bool) -> str:
    m = re.search(rx, text, re.I if icase else 0)
    s = m.group(0) if m else text
    # never echo secrets in full
    return (s[:12] + "…") if len(s) > 16 else s


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("ref", nargs="?", default="public-squash")
    ap.add_argument("--author-ok", action="append", default=[], help="extra address allowed as commit author/committer (not in the tree)")
    ap.add_argument("--tree-only", action="store_true", help="skip the single-commit and author checks")
    ap.add_argument("--no-private", action="store_true", help="run without the private pattern file")
    a = ap.parse_args()
    allow = load_allow()
    findings: list[str] = []

    def add(kind, path, num, what):
        findings.append(f"{path}:{num}: {kind}: {what}")

    for kind, rx in GENERIC:
        for path, num, text in grep(a.ref, rx):
            if allowed(allow, path, text):
                continue
            for m in re.finditer(rx, text):
                v = m.group(0)
                if kind == "e-mail address" and (OK_EMAIL.search(v) or OK_EMAIL_LINE.search(text)):
                    continue
                if kind == "MAC / BSSID" and ok_mac(v):
                    continue
                add(kind, path, num, (v[:12] + "…") if len(v) > 16 else v)
                break
    for i, rx in enumerate(load_private(a.no_private)):
        for path, num, text in grep(a.ref, rx, icase=True):
            if not allowed(allow, path, text):
                add(f"private pattern #{i + 1}", path, num, short(text, rx, True))

    files = git("ls-tree", "-r", "--name-only", a.ref).decode().splitlines()
    for f in files:
        for kind, glob in FORBIDDEN_FILES:
            if fnmatch.fnmatch(f, glob) or fnmatch.fnmatch(Path(f).name, glob):
                add(kind, f, 0, "file must not be published")
        if f.startswith("firmware/zephcore-patches/") and f.endswith(".patch"):
            second = git("show", f"{a.ref}:{f}").decode("utf-8", "replace").split("\n", 2)[1]
            if not PATCH_FROM_OK.match(second):
                add("patch author", f, 2, "From: must be the project identity")

    exif = shutil.which("exiftool")
    imgs = [f for f in files if f.lower().endswith((".jpg", ".jpeg", ".png", ".webp", ".heic", ".tif", ".tiff"))]
    if imgs and not exif:
        findings.append("exiftool not found: image metadata not checked (brew install exiftool)")
    elif imgs:
        with tempfile.TemporaryDirectory() as d:
            for f in imgs:
                p = Path(d) / Path(f).name
                p.write_bytes(git("show", f"{a.ref}:{f}"))
                meta = subprocess.run([exif, "-s", "-s", "-s", "-GPSLatitude", "-GPSLongitude", "-GPSPosition",
                                       "-Artist", "-Author", "-Creator", "-OwnerName", "-SerialNumber",
                                       "-CameraSerialNumber", "-Make", "-Model", "-UserComment", str(p)],
                                      capture_output=True, text=True).stdout.strip()
                if meta:
                    add("image metadata", f, 0, meta.replace("\n", " | ")[:80])

    if not a.tree_only:
        parents = git("rev-list", "--parents", "-n", "1", a.ref).decode().split()
        count = int(git("rev-list", "--count", a.ref).decode())
        if count != 1 or len(parents) != 1:
            findings.append(f"{a.ref}: must be a single root commit (has {count} commits in its history)")
        ident = git("log", "-1", "--format=%an <%ae>%n%cn <%ce>", a.ref).decode()
        for line in ident.splitlines():
            email = line[line.rfind("<") + 1:-1]
            if not OK_EMAIL.search(email) and not email.endswith("@users.noreply.github.com") and email not in a.author_ok:
                findings.append(f"{a.ref}: author/committer address {email!r} is not a project or noreply address")

    for line in findings:
        print(line)
    print(f"squash-check {a.ref}: {len(files)} files, {len(findings)} finding(s)", file=sys.stderr)
    return 1 if findings else 0


if __name__ == "__main__":
    sys.exit(main())
