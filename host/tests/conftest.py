"""Test isolation: nothing under test may touch the real ~/.config/nostrmesh (status file, ledger,
MeshCore Open identity). HOME points to a throwaway directory before any nostrmesh module is imported,
so the module-level defaults (config.CONFIG_DIR, DEFAULT_STATUS_PATH, localapi.DEFAULT_IDENTITY) land
there; each test also gets its own copy of those paths."""
import os
import tempfile

import pytest

_HOME = tempfile.mkdtemp(prefix="libretag-test-home-")
os.environ["HOME"] = _HOME


@pytest.fixture(autouse=True)
def _isolated_config_dir(tmp_path, monkeypatch):
    from nostrmesh import config, localapi

    home = tmp_path / "home"
    cfg_dir = home / ".config" / "nostrmesh"
    monkeypatch.setenv("HOME", str(home))
    monkeypatch.setattr(config, "CONFIG_DIR", cfg_dir)
    monkeypatch.setattr(config, "DEFAULT_STATUS_PATH", str(cfg_dir / "status.json"))
    monkeypatch.setattr(localapi, "DEFAULT_IDENTITY", cfg_dir / "meshcore-open.json")
    monkeypatch.setattr(localapi.Identity.__init__, "__defaults__", (cfg_dir / "meshcore-open.json",))
