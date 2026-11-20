"""All copies of the shared test vectors are identical to their canonical files (tools/check_vectors.py)."""
import importlib.util
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location("check_vectors", ROOT / "tools" / "check_vectors.py")
cv = importlib.util.module_from_spec(spec)
spec.loader.exec_module(cv)


def test_vector_copies_identical():
    assert cv.check() == []


def test_android_bridge_carries_its_copies():
    found = cv.copies()
    for name in ("host/tests/vectors/lt2.json", "host/tests/vectors/mailbox-v2.json",
                 "docs/vectors/nostr-frames-v1.json"):
        assert any("android-bridge" in p.as_posix() for p in found[name]), name


def test_check_detects_a_stale_copy(tmp_path):
    (tmp_path / "host/tests/vectors").mkdir(parents=True)
    (tmp_path / "x").mkdir()
    for c in cv.CANONICAL:
        (tmp_path / c).parent.mkdir(parents=True, exist_ok=True)
        (tmp_path / c).write_text("{}")
    (tmp_path / "x" / "lt2.json").write_text('{"old": 1}')
    assert cv.check(tmp_path) == ["x/lt2.json differs from host/tests/vectors/lt2.json"]
    assert cv.check(tmp_path, fix=True) == [] and cv.check(tmp_path) == []
