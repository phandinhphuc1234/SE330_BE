"""Run the real maintenance shell with a fake Docker CLI; never touch a daemon."""
import os
from pathlib import Path
import shutil
import subprocess

import pytest

ROOT = Path(__file__).resolve().parents[3]
SCRIPT = ROOT / "deploy/scripts/cleanup-reviewed-library-images.sh"
BASH = shutil.which("bash")
pytestmark = pytest.mark.skipif(not BASH or os.name == "nt", reason="Linux shell safety harness; runs in CI")


def execute(tmp_path, mode="ok", changed=False):
    state = tmp_path / "state"
    state.mkdir()
    be = "ghcr.io/phandinhphuc1234/se330-be@sha256:c2a4630a553224b87402b4d5f2ac32c615f5dab503c6e7c19af683c958bd1562"
    rag = "ghcr.io/phandinhphuc1234/se330-rag@sha256:4ea6573ac9cd624c4e472d2f7f0344ca02194a4dfae745bf7b6b3140b73a158e"
    (state / "current-image").write_text("changed" if changed else be)
    (state / "current-rag-image").write_text(rag)
    bin_dir = tmp_path / "bin"
    bin_dir.mkdir()
    trace = tmp_path / "mutations"
    docker = bin_dir / "docker"
    docker.write_text('''#!/usr/bin/env bash
set -eu
last="${@: -1}"
if [[ "$1 $2" == 'ps -aq' ]]; then
  [[ "$MODE" != ps-failed ]] || exit 1
  echo container1
elif [[ "$1" == inspect ]]; then
  if [[ "$MODE" == used ]]; then printf 'sha256:3fdc5421%056d\\n' 0
  else printf 'sha256:%064d\\n' 1; fi
elif [[ "$1 $2" == 'image inspect' ]]; then
  if [[ "$4" == *RepoDigests* ]]; then
    if [[ "$MODE" == foreign && "$last" == sha256:ef8f9111* ]]; then echo 'other/repo@sha256:bad'
    else echo "ghcr.io/phandinhphuc1234/se330-be@$last"; fi
  elif [[ "$last" == *c2a4630a* ]]; then printf 'sha256:%064d\\n' 1
  elif [[ "$last" == *4ea6573a* ]]; then printf 'sha256:%064d\\n' 2
  elif [[ "$MODE" == missing && "$last" == ef8f9111 ]]; then exit 1
  elif [[ "$last" == sha256:* ]]; then echo "$last"
  else printf 'sha256:%s%056d\\n' "$last" 0; fi
elif [[ "$1 $2" == 'image rm' ]]; then
  [[ "$3" == --no-prune ]] || exit 90
  printf '%s\\n' "$last" >> "$TRACE"
elif [[ "$1 $2" == 'system df' ]]; then echo allocation
else exit 91
fi
''')
    docker.chmod(0o700)
    curl = bin_dir / "curl"
    curl.write_text('#!/usr/bin/env bash\necho \'{"status":"UP"}\'\n')
    curl.chmod(0o700)
    env = os.environ | {"PATH": str(bin_dir) + os.pathsep + os.environ["PATH"],
                        "LIBRARY_MAINTENANCE_STATE_DIR": str(state), "MODE": mode, "TRACE": str(trace)}
    result = subprocess.run([BASH, str(SCRIPT)], env=env, capture_output=True, text=True, timeout=15)
    return result, trace.read_text().splitlines() if trace.exists() else []


def test_removes_only_exact_reviewed_refs(tmp_path):
    result, removed = execute(tmp_path)
    assert result.returncode == 0, result.stderr
    assert len(removed) == len(set(removed)) == 17
    assert all(ref.startswith("ghcr.io/phandinhphuc1234/se330-be@sha256:") for ref in removed)
    assert "UP" in result.stdout


@pytest.mark.parametrize("mode", ["used", "foreign", "missing", "ps-failed"])
def test_any_prevalidation_failure_prevents_all_removal(tmp_path, mode):
    result, removed = execute(tmp_path, mode)
    assert result.returncode != 0
    assert not removed


def test_changed_deployment_prevents_all_removal(tmp_path):
    result, removed = execute(tmp_path, changed=True)
    assert result.returncode != 0
    assert "Backend changed" in result.stderr
    assert not removed


def test_no_broad_or_forced_prune():
    source = SCRIPT.read_text()
    assert "docker system prune" not in source
    assert "docker volume" not in source
    assert "docker container" not in source
    assert "docker image rm --no-prune" in source
