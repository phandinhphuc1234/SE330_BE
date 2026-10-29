"""Offline tests of real byte-preserving reclamation; never use Docker or S3."""
import ctypes
import importlib.util
import json
import os
from pathlib import Path
import sys

import pytest

ROOT = Path(__file__).resolve().parents[3]
spec = importlib.util.spec_from_file_location("seaweed_reclaim", ROOT / "deploy/scripts/reclaim-seaweed-preallocation.py")
reclaim = importlib.util.module_from_spec(spec)
spec.loader.exec_module(reclaim)
pytestmark = pytest.mark.skipif(sys.platform != "linux", reason="Real Linux file-allocation safety test; runs in CI")


def fixture(tmp_path):
    root = tmp_path / "data"
    root.mkdir()
    backup = tmp_path / "backup"
    backup.mkdir()
    data = b"public-test-header\x00" * 100
    path = root / "1.dat"
    path.write_bytes(data)
    with path.open("r+b") as stream:
        libc = ctypes.CDLL(None, use_errno=True)
        status = libc.fallocate(stream.fileno(), 1, ctypes.c_longlong(0), ctypes.c_longlong(8 * 1024 * 1024))
        assert status == 0, ctypes.get_errno()
    (root / "1.idx").write_bytes(b"index stays unchanged")
    return root, backup, data


def test_real_keep_size_allocation_is_reclaimed_without_data_or_metadata_change(tmp_path):
    root, backup, data = fixture(tmp_path)
    before = reclaim.inventory(root)
    assert before["1.dat"]["allocated"] >= 8 * 1024 * 1024
    result = reclaim.run(root, backup, expected={"1.dat": len(data)})
    assert result["completed"] and result["dataVerified"] and result["backupVerified"]
    assert result["reclaimedBytes"] > 7 * 1024 * 1024
    assert (root / "1.dat").read_bytes() == data
    assert reclaim.logical_signature(reclaim.inventory(root)) == reclaim.logical_signature(before)
    reclaim.verify_archive(backup / "seaweedfs-logical-backup.tar.gz", before)


def test_changed_lengths_refuse_before_backup_or_mutation(tmp_path):
    root, backup, data = fixture(tmp_path)
    before = reclaim.inventory(root)
    with pytest.raises(ValueError, match="logical length changed"):
        reclaim.run(root, backup, expected={"1.dat": len(data) + 1})
    assert reclaim.inventory(root) == before
    assert not list(backup.iterdir())


def test_symlink_refuses_without_modifying_target(tmp_path):
    root, backup, data = fixture(tmp_path)
    (root / "linked").symlink_to(root / "1.dat")
    with pytest.raises(ValueError, match="Non-regular"):
        reclaim.run(root, backup, expected={"1.dat": len(data)})
    assert (root / "1.dat").read_bytes() == data


def test_backup_verification_failure_prevents_reclamation(tmp_path, monkeypatch):
    root, backup, data = fixture(tmp_path)
    before = reclaim.inventory(root)
    def fail(*args):
        raise ValueError("simulated corrupt backup")
    monkeypatch.setattr(reclaim, "verify_archive", fail)
    with pytest.raises(ValueError, match="corrupt backup"):
        reclaim.run(root, backup, expected={"1.dat": len(data)})
    assert reclaim.inventory(root) == before
    state = json.loads((backup / "recovery-state.json").read_text())
    assert state["dataVerified"] and not state["backupVerified"]


def test_syscall_error_preserves_bytes_and_records_safe_restart_state(tmp_path, monkeypatch):
    root, backup, data = fixture(tmp_path)
    def fail(*args):
        raise OSError("simulated syscall failure")
    monkeypatch.setattr(os, "ftruncate", fail)
    with pytest.raises(OSError):
        reclaim.run(root, backup, expected={"1.dat": len(data)})
    assert (root / "1.dat").read_bytes() == data
    state = json.loads((backup / "recovery-state.json").read_text())
    assert state["backupVerified"] and state["dataVerified"] and not state["completed"]


def test_live_cli_is_fixed_root_and_no_file_removal():
    source = (ROOT / "deploy/scripts/reclaim-seaweed-preallocation.py").read_text()
    assert "args.root != LIVE_ROOT" in source
    assert "os.ftruncate(fd, current.st_size)" in source
    assert "os.remove(" not in source and ".unlink(" not in source


def test_unexpected_byte_change_prevents_safe_restart_but_retains_verified_backup(tmp_path, monkeypatch):
    root, backup, data = fixture(tmp_path)
    before = reclaim.inventory(root)
    original = os.ftruncate
    def corrupt(fd, size):
        original(fd, size)
        os.pwrite(fd, b"X", 0)
    monkeypatch.setattr(os, "ftruncate", corrupt)
    with pytest.raises(ValueError, match="byte verification failed"):
        reclaim.run(root, backup, expected={"1.dat": len(data)})
    state = json.loads((backup / "recovery-state.json").read_text())
    assert state["backupVerified"] and not state["dataVerified"] and not state["completed"]
    reclaim.verify_archive(backup / "seaweedfs-logical-backup.tar.gz", before)
