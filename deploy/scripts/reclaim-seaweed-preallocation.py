"""Offline, backed-up same-length truncation of the reviewed beyond-EOF extents.

The shell wrapper must stop the only storage writer first. No file is deleted,
renamed or shortened relative to its measured logical size. CLI root is fixed.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import stat
import sys
import tarfile

LIVE_ROOT = Path("/var/lib/docker/volumes/quanlythuvien_rag_seaweedfs_data/_data")
EXPECTED = {**{f"{n}.dat": 8 for n in range(1, 5)}, "5.dat": 6680,
            "6.dat": 6984, "7.dat": 568, "library-private_8.dat": 86904,
            **{f"library-private_{n}.dat": 8 for n in (9, 10, 12, 13, 14)},
            "library-private_11.dat": 151696, "rag-artifacts_15.dat": 46920,
            "rag-artifacts_16.dat": 368912, "rag-artifacts_17.dat": 137320}


def digest(path):
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def inventory(root):
    result = {}
    total = 0
    for path in sorted(root.rglob("*")):
        info = path.lstat()
        if stat.S_ISDIR(info.st_mode):
            continue
        if not stat.S_ISREG(info.st_mode) or info.st_nlink != 1:
            raise ValueError("Non-regular, symlink or multiply-linked file refused")
        total += info.st_size
        if total > 32 * 1024 * 1024 or len(result) >= 5000:
            raise ValueError("Reviewed small-corpus backup bounds exceeded")
        result[path.relative_to(root).as_posix()] = {
            "size": info.st_size, "sha256": digest(path), "inode": info.st_ino,
            "uid": info.st_uid, "gid": info.st_gid, "mode": stat.S_IMODE(info.st_mode),
            "mtimeNs": info.st_mtime_ns, "allocated": getattr(info, "st_blocks", 0) * 512,
        }
    return result


def logical_signature(snapshot):
    return {name: {key: row[key] for key in ("size", "sha256", "uid", "gid", "mode", "mtimeNs")}
            for name, row in snapshot.items()}


def verify_archive(archive, snapshot):
    seen = set()
    with tarfile.open(archive, "r:gz") as source:
        for member in source.getmembers():
            if member.isdir():
                continue
            if not member.isfile() or member.name not in snapshot or member.name in seen:
                raise ValueError("Unexpected backup member")
            row = snapshot[member.name]
            stream = source.extractfile(member)
            with stream:
                checksum = hashlib.file_digest(stream, "sha256").hexdigest()
            if member.size != row["size"] or checksum != row["sha256"]:
                raise ValueError("Backup byte verification failed")
            seen.add(member.name)
    if seen != set(snapshot):
        raise ValueError("Backup is incomplete")


def run(root, backup, expected=EXPECTED):
    if root.is_symlink() or root.resolve() != root:
        raise ValueError("Resolved storage root differs")
    before = inventory(root)
    dat = {name: row["size"] for name, row in before.items() if name.endswith(".dat")}
    if dat != expected:
        raise ValueError("Reviewed data-file set or logical length changed")
    state = {"backupVerified": False, "dataVerified": True, "completed": False}
    state_path = backup / "recovery-state.json"

    def save_state():
        state_path.write_text(json.dumps(state, indent=2), encoding="utf-8")

    save_state()
    try:
        archive = backup / "seaweedfs-logical-backup.tar.gz"
        with tarfile.open(archive, "x:gz", format=tarfile.PAX_FORMAT) as target:
            for path in sorted(root.rglob("*")):
                target.add(path, arcname=path.relative_to(root).as_posix(), recursive=False)
        verify_archive(archive, before)
        if logical_signature(inventory(root)) != logical_signature(before):
            raise ValueError("Storage changed while offline backup was made")
        state.update(backupVerified=True, backupSha256=digest(archive),
                     fileCount=len(before), datFileCount=len(dat))
        (backup / "before-files.json").write_text(json.dumps(before, indent=2), encoding="utf-8")
        save_state()
        for name in expected:
            path = root / name
            row = before[name]
            fd = os.open(path, os.O_RDWR | getattr(os, "O_NOFOLLOW", 0))
            try:
                current = os.fstat(fd)
                if current.st_ino != row["inode"] or current.st_size != row["size"] or digest(path) != row["sha256"]:
                    raise ValueError("Data file identity changed")
                state["dataVerified"] = False
                save_state()
                # Same length only: release allocated extents past EOF, not content.
                os.ftruncate(fd, current.st_size)
                os.fsync(fd)
                os.utime(fd, ns=(current.st_atime_ns, current.st_mtime_ns))
                if os.fstat(fd).st_size != row["size"] or digest(path) != row["sha256"]:
                    raise ValueError("Data file byte verification failed")
            finally:
                os.close(fd)
        after = inventory(root)
        if logical_signature(after) != logical_signature(before):
            raise ValueError("Whole-volume logical verification failed")
        state.update(dataVerified=True, completed=True,
                     allocatedBefore=sum(before[name]["allocated"] for name in expected),
                     allocatedAfter=sum(after[name]["allocated"] for name in expected))
        state["reclaimedBytes"] = state["allocatedBefore"] - state["allocatedAfter"]
        (backup / "after-files.json").write_text(json.dumps(after, indent=2), encoding="utf-8")
        save_state()
        return state
    except BaseException:
        # Never restart automatically after an unverified data change.
        try:
            state["dataVerified"] = logical_signature(inventory(root)) == logical_signature(before)
        except Exception:
            state["dataVerified"] = False
        save_state()
        raise


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, required=True)
    parser.add_argument("--backup", type=Path, required=True)
    args = parser.parse_args()
    try:
        if sys.platform != "linux" or args.root != LIVE_ROOT or not args.backup.is_dir():
            raise ValueError("Linux fixed production root and private backup directory required")
        os.umask(0o077)
        print(json.dumps(run(args.root, args.backup)))
    except Exception as error:
        print(json.dumps({"status": "FAILED", "errorType": type(error).__name__}))
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
