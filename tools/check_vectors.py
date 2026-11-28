#!/usr/bin/env python3
"""Every copy of a shared test vector file must equal its canonical source (CI check, stdlib only).

    python3 tools/check_vectors.py          # exit 1 and a list of differing copies
    python3 tools/check_vectors.py --fix    # overwrite the copies from the canonical files

A canonical file is listed in CANONICAL. Any other file in the tree with the same name is a copy
(build output, node_modules, .git and agent worktrees are skipped), so a new copy is checked too.
"""
from __future__ import annotations

import hashlib
import os
import shutil
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
CANONICAL = [
    "host/tests/vectors/lt2.json",
    "host/tests/vectors/road-events-v1.json",
    "host/tests/vectors/mailbox-v2.json",
    "docs/vectors/nostr-frames-v1.json",
    "docs/vectors/key-backup-v1.json",
    "docs/vectors/share-link-v1.json",
    "docs/vectors/lora-airtime-v1.json",
    "docs/vectors/quiet-mesh-v1.json",
]
SKIP_DIRS = {".git", ".claude", "build", "node_modules", ".gradle", "dist", ".venv", "__pycache__", ".idea"}


def copies(root: Path = ROOT) -> dict[str, list[Path]]:
    names = {Path(c).name: c for c in CANONICAL}
    found: dict[str, list[Path]] = {c: [] for c in CANONICAL}
    for d, dirs, files in os.walk(root):
        dirs[:] = [x for x in dirs if x not in SKIP_DIRS]
        for f in files:
            if f in names:
                p = Path(d) / f
                rel = p.relative_to(root).as_posix()
                if rel != names[f]:
                    found[names[f]].append(p)
    return found


def _h(p: Path) -> str:
    return hashlib.sha256(p.read_bytes()).hexdigest()


def check(root: Path = ROOT, fix: bool = False) -> list[str]:
    bad = []
    for canon, cps in copies(root).items():
        src = root / canon
        if not src.exists():
            bad.append(f"missing canonical {canon}")
            continue
        for c in cps:
            if _h(c) != _h(src):
                if fix:
                    shutil.copyfile(src, c)
                else:
                    bad.append(f"{c.relative_to(root).as_posix()} differs from {canon}")
    return bad


if __name__ == "__main__":
    problems = check(fix="--fix" in sys.argv[1:])
    for p in problems:
        print(p)
    if not problems:
        n = sum(len(v) for v in copies().values())
        print(f"vectors ok: {len(CANONICAL)} canonical files, {n} copies identical")
    sys.exit(1 if problems else 0)
