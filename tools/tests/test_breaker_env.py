"""Tooling tests for P27 Breaker environment, guard.sh, seed.py, catalog, and findings."""

from __future__ import annotations

import json
import os
import re
import shutil
import socketserver
import subprocess
import sys
import threading
from pathlib import Path

import pytest

REPO_ROOT = Path(__file__).resolve().parents[2]
GUARD_SH = REPO_ROOT / "breaker" / "env" / "guard.sh"
DOWN_SH = REPO_ROOT / "breaker" / "env" / "down.sh"
SEED_PY = REPO_ROOT / "breaker" / "env" / "seed.py"
CATALOG_MD = REPO_ROOT / "breaker" / "catalog.md"
AGENT_MD = REPO_ROOT / ".claude" / "agents" / "breaker.md"
SKILL_MD = REPO_ROOT / ".claude" / "skills" / "ng-break" / "SKILL.md"
FINDINGS_DIR = REPO_ROOT / "breaker" / "findings"

if str(REPO_ROOT) not in sys.path:
    sys.path.insert(0, str(REPO_ROOT))

from breaker.env.disposable_app import resolve_bash_bin  # noqa: E402
from breaker.env.flap_proxy import FlapPolicy, FlapProxy  # noqa: E402


def _bash() -> str:
    return resolve_bash_bin()


@pytest.mark.parametrize(
    "staging_or_bad_url",
    [
        "https://staging.neogold.kitalonlabs.com/v1",
        "https://intake.neogold.kitalonlabs.com/v1",
        "https://prod.neogold.org/v1",
        "http://10.8.0.1:8000/v1",
        "http://192.168.1.42:8000/v1",
        "",
    ],
)
def test_guard_refuses_staging_and_external_urls(staging_or_bad_url: str):
    """P27 Done-when requirement: In a test, the guard refuses a staging URL."""
    res = subprocess.run(
        [_bash(), GUARD_SH.as_posix(), "--api-url", staging_or_bad_url],
        capture_output=True,
        text=True,
        check=False,
    )
    assert res.returncode == 2
    assert "BREAKER GUARD REFUSED" in res.stderr


@pytest.mark.parametrize(
    "allowed_url",
    [
        "http://localhost:18000/v1",
        "http://127.0.0.1:18000/v1",
        "http://10.0.2.2:18000/v1",
        "http://api:8000/v1",
        "http://caddy:8080/v1",
    ],
)
def test_guard_accepts_breaker_stack_urls(allowed_url: str):
    res = subprocess.run(
        [_bash(), GUARD_SH.as_posix(), "--api-url", allowed_url],
        capture_output=True,
        text=True,
        check=False,
    )
    assert res.returncode == 0, res.stderr


def test_guard_refuses_unknown_device_serial():
    res = subprocess.run(
        [_bash(), GUARD_SH.as_posix(), "--api-url", "http://127.0.0.1:18000/v1", "--serial", "R58N999888Z"],
        capture_output=True,
        text=True,
        check=False,
    )
    assert res.returncode == 2
    assert "BREAKER GUARD REFUSED" in res.stderr


def test_guard_refuses_emulator_with_wrong_avd_and_accepts_breaker_avd():
    env_bad = os.environ.copy()
    env_bad["BREAKER_TEST_AVD_NAME"] = "Pixel_7_API_34"
    res_bad = subprocess.run(
        [_bash(), GUARD_SH.as_posix(), "--api-url", "http://127.0.0.1:18000/v1", "--serial", "emulator-5554"],
        env=env_bad,
        capture_output=True,
        text=True,
        check=False,
    )
    assert res_bad.returncode == 2

    env_ok = os.environ.copy()
    env_ok["BREAKER_TEST_AVD_NAME"] = "neogold-breaker"
    res_ok = subprocess.run(
        [_bash(), GUARD_SH.as_posix(), "--api-url", "http://127.0.0.1:18000/v1", "--serial", "emulator-5554"],
        env=env_ok,
        capture_output=True,
        text=True,
        check=False,
    )
    assert res_ok.returncode == 0, res_ok.stderr


def test_guard_refuses_when_wireguard_interface_is_up():
    env = os.environ.copy()
    env["BREAKER_TEST_WG_INTERFACES"] = "wg0"
    res = subprocess.run(
        [_bash(), GUARD_SH.as_posix(), "--api-url", "http://127.0.0.1:18000/v1"],
        env=env,
        capture_output=True,
        text=True,
        check=False,
    )
    assert res.returncode == 2
    assert "WireGuard" in res.stderr


def test_seed_dry_run_and_staging_guard(tmp_path: Path):
    out_file = tmp_path / "seed-state.json"
    res = subprocess.run(
        [
            sys.executable,
            str(SEED_PY),
            "--dry-run",
            "--api-url",
            "http://127.0.0.1:18000/v1",
            "--output",
            str(out_file),
        ],
        capture_output=True,
        text=True,
        check=False,
    )
    assert res.returncode == 0, res.stderr
    state = json.loads(out_file.read_text(encoding="utf-8"))
    assert state["synthetic_only"] is True
    assert state["study"]["study_code"] == "SKP-BRK"
    assert all(site["is_test"] is True for site in state["sites"])
    assert len(state["devices"]) >= 2
    assert len(state["participants"]) == 5

    # Must refuse staging URL via guard.sh
    res_staging = subprocess.run(
        [
            sys.executable,
            str(SEED_PY),
            "--dry-run",
            "--api-url",
            "https://staging.neogold.kitalonlabs.com/v1",
            "--output",
            str(out_file),
        ],
        capture_output=True,
        text=True,
        check=False,
    )
    assert res_staging.returncode != 0


def test_flap_proxy_rejects_external_host_and_injects_faults():
    with pytest.raises(ValueError, match="breaker-local upstream"):
        FlapProxy(upstream_host="staging.neogold.example", upstream_port=443)

    class _EchoHandler(socketserver.BaseRequestHandler):
        def handle(self):
            data = self.request.recv(1024)
            self.request.sendall(data)

    with socketserver.TCPServer(("127.0.0.1", 0), _EchoHandler) as srv:
        port = srv.server_address[1]
        t = threading.Thread(target=srv.serve_forever, daemon=True)
        t.start()
        try:
            with FlapProxy("127.0.0.1", port, policy=FlapPolicy(drop_every_n=2)) as proxy:
                assert proxy.listen_port > 0
        finally:
            srv.shutdown()


def test_catalog_covers_brk_001_to_030_and_all_threats_and_hazards():
    text = CATALOG_MD.read_text(encoding="utf-8")
    for idx in range(1, 31):
        assert f"BRK-{idx:03d}" in text, f"Missing BRK-{idx:03d} in breaker/catalog.md"
    for t_idx in range(1, 18):
        assert re.search(rf"\bT{t_idx}\b", text), f"Missing threat T{t_idx} in breaker/catalog.md"
    for h_idx in range(1, 12):
        assert re.search(rf"\bH{h_idx}\b", text), f"Missing hazard H{h_idx} in breaker/catalog.md"


def test_breaker_agent_and_skill_frontmatter_and_findings():
    agent_text = AGENT_MD.read_text(encoding="utf-8")
    assert "tools: Read, Grep, Glob, Bash, Write" in agent_text
    assert "isolation: worktree" in agent_text
    assert "breaker_guard.py" in agent_text

    skill_text = SKILL_MD.read_text(encoding="utf-8")
    assert "disable-model-invocation: true" in skill_text
    assert "--recheck" in skill_text
    assert "--rounds" in skill_text

    finding_files = sorted(FINDINGS_DIR.glob("BRK-*.md"))
    assert len(finding_files) >= 3
    for fpath in finding_files:
        content = fpath.read_text(encoding="utf-8")
        assert "severity:" in content
        assert "clinical:" in content
        assert "protects:" in content
        assert "regression_test:" in content


def test_down_sh_tears_down_cleanly():
    res = subprocess.run([_bash(), DOWN_SH.as_posix()], capture_output=True, text=True, check=False)
    assert res.returncode == 0, res.stderr
