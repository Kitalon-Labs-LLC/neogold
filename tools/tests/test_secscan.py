"""
tools/tests/test_secscan.py

Unit and integration tests for P26 (/ng-secscan, tools/secscan/*, security/exceptions.yaml,
reports/security/ledger.md, and TC-SEC-10 MobSF static scan step).
"""

from __future__ import annotations

import json
import os
import sys
from datetime import date
from pathlib import Path

import pytest
import yaml

REPO_ROOT = Path(__file__).resolve().parent.parent.parent
sys.path.insert(0, str(REPO_ROOT / "tools" / "secscan"))

import evaluate_secscan  # noqa: E402
import scanners  # noqa: E402


def _write_minimal_workspace(tmp_path: Path) -> Path:
    """Create a minimal workspace skeleton for isolated scanner tests."""
    (tmp_path / "tools" / "secscan").mkdir(parents=True, exist_ok=True)
    (tmp_path / "tools" / "secscan" / "versions.txt").write_text(
        (REPO_ROOT / "tools" / "secscan" / "versions.txt").read_text(encoding="utf-8"),
        encoding="utf-8",
    )
    (tmp_path / ".gitleaks.toml").write_text(
        (REPO_ROOT / ".gitleaks.toml").read_text(encoding="utf-8"),
        encoding="utf-8",
    )
    (tmp_path / ".gitleaksignore").write_text(
        (REPO_ROOT / ".gitleaksignore").read_text(encoding="utf-8"),
        encoding="utf-8",
    )
    (tmp_path / "security").mkdir(parents=True, exist_ok=True)
    (tmp_path / "security" / "exceptions.yaml").write_text(
        yaml.safe_dump(
            {
                "schema_version": 1,
                "exceptions": [
                    {
                        "id": "SEC-EXC-0001",
                        "tool": "bandit",
                        "rule": "B311",
                        "aliases": ["S311"],
                        "severity": "LOW",
                        "file": "backend/app/sample.py",
                        "reason": "Deterministic test seed",
                        "approver": "@sowad",
                        "created_at": "2026-10-31",
                        "expires_at": "2027-01-15",
                    }
                ],
            }
        ),
        encoding="utf-8",
    )
    (tmp_path / "docs" / "api").mkdir(parents=True, exist_ok=True)
    (tmp_path / "docs" / "api" / "openapi.yaml").write_text(
        "openapi: 3.1.0\ninfo:\n  title: Test\n  version: 1.0.0\npaths: {}\n",
        encoding="utf-8",
    )
    return tmp_path


def test_versions_manifest_pins_every_required_scanner():
    versions = scanners.load_versions(REPO_ROOT / "tools" / "secscan" / "versions.txt")
    required = {
        "gitleaks",
        "osv_scanner",
        "trivy",
        "android_lint",
        "detekt",
        "ruff",
        "bandit",
        "semgrep",
        "zizmor",
        "mobsf",
        "schemathesis",
        "deepsec_trial",
    }
    missing = required - set(versions.keys())
    assert not missing, f"Missing pinned versions in tools/secscan/versions.txt: {missing}"


def test_real_repository_exceptions_and_ledger_pass(tmp_path):
    report_dir = tmp_path / "reports" / "security" / "2026-10-31"
    ledger_path = tmp_path / "reports" / "security" / "ledger.md"
    scan_dt = date(2026, 10, 31)

    outputs = scanners.run_all_scanners(
        root=REPO_ROOT,
        report_dir=report_dir,
        mode="full",
        versions_file=REPO_ROOT / "tools" / "secscan" / "versions.txt",
    )
    assert "gitleaks" in outputs
    assert "osv_scanner" in outputs
    assert "trivy" in outputs
    assert "mobsf" in outputs
    assert "schemathesis" in outputs

    code, summary = evaluate_secscan.evaluate_scan(
        root=REPO_ROOT,
        report_dir=report_dir,
        exceptions_path=REPO_ROOT / "security" / "exceptions.yaml",
        ledger_path=ledger_path,
        scan_date=scan_dt,
        mode="full",
    )
    assert code == 0, f"Expected clean full scan on repo, got summary: {summary}"
    assert summary["passed"] is True
    assert summary["counts"]["unaccepted_high_or_critical"] == 0
    assert summary["counts"]["expired_exceptions"] == 0
    assert summary["counts"]["invalid_exceptions"] == 0
    assert summary["counts"]["unreferenced_inline_suppressions"] == 0

    ledger_text = ledger_path.read_text(encoding="utf-8")
    assert "**Critical:** 7 days | **High:** 30 days | **Medium:** 90 days" in ledger_text
    assert "SEC-EXC-0001" in ledger_text
    assert "SEC-EXC-0002" in ledger_text
    assert "SEC-EXC-0003" in ledger_text


def test_planted_fake_secret_fails_scan_and_is_redacted_in_reports(tmp_path):
    ws = _write_minimal_workspace(tmp_path)
    secret_dir = ws / "backend" / "app"
    secret_dir.mkdir(parents=True, exist_ok=True)

    fake_aws = "AKIA" + "IOSFODNN7EXAMPLE"
    fake_gh = "ghp_" + "1234567890abcdef1234567890abcdef123456"
    (secret_dir / "leaked_config.py").write_text(
        f'AWS_KEY = "{fake_aws}"\nGITHUB_TOKEN = "{fake_gh}"\n',
        encoding="utf-8",
    )

    report_dir = ws / "reports" / "security" / "2026-10-31"
    ledger_path = ws / "reports" / "security" / "ledger.md"

    scanners.run_all_scanners(
        root=ws,
        report_dir=report_dir,
        mode="changed",
        versions_file=ws / "tools" / "secscan" / "versions.txt",
    )
    code, summary = evaluate_secscan.evaluate_scan(
        root=ws,
        report_dir=report_dir,
        exceptions_path=ws / "security" / "exceptions.yaml",
        ledger_path=ledger_path,
        scan_date=date(2026, 10, 31),
        mode="changed",
    )

    assert code == 1
    assert summary["passed"] is False
    assert summary["critical_incident_stop"] is True
    assert summary["counts"]["unaccepted_critical"] >= 2

    # Verify 100% secret redaction across every generated report file
    for rfile in list(report_dir.glob("*")) + [ledger_path]:
        text = rfile.read_text(encoding="utf-8")
        assert fake_aws not in text, f"Unredacted AWS secret leaked into {rfile.name}"
        assert fake_gh not in text, f"Unredacted GitHub token leaked into {rfile.name}"


def test_planted_vulnerable_dependency_fails_scan(tmp_path):
    ws = _write_minimal_workspace(tmp_path)
    (ws / "backend").mkdir(parents=True, exist_ok=True)
    # Plant vulnerable urllib3==2.0.7 (CVE-2024-37891, HIGH)
    (ws / "backend" / "requirements.lock").write_text(
        "urllib3==2.0.7 \\\n    --hash=sha256:0000000000000000000000000000000000000000000000000000000000000000\n",
        encoding="utf-8",
    )
    (ws / "android" / "app").mkdir(parents=True, exist_ok=True)
    # Plant vulnerable log4j-core 2.14.1 (CVE-2021-44228, CRITICAL)
    (ws / "android" / "app" / "gradle.lockfile").write_text(
        "org.apache.logging.log4j:log4j-core:2.14.1=releaseRuntimeClasspath\nempty=\n",
        encoding="utf-8",
    )

    report_dir = ws / "reports" / "security" / "2026-10-31"
    ledger_path = ws / "reports" / "security" / "ledger.md"

    scanners.run_all_scanners(
        root=ws,
        report_dir=report_dir,
        mode="changed",
        versions_file=ws / "tools" / "secscan" / "versions.txt",
    )
    code, summary = evaluate_secscan.evaluate_scan(
        root=ws,
        report_dir=report_dir,
        exceptions_path=ws / "security" / "exceptions.yaml",
        ledger_path=ledger_path,
        scan_date=date(2026, 10, 31),
        mode="changed",
    )

    assert code == 1
    assert summary["passed"] is False
    unaccepted_rules = {f["rule"] for f in summary["unaccepted_high_or_critical"]}
    assert "CVE-2024-37891" in unaccepted_rules
    assert "CVE-2021-44228" in unaccepted_rules


def test_expired_and_over_90_day_exceptions_fail_scan(tmp_path):
    ws = _write_minimal_workspace(tmp_path)
    exc_path = ws / "security" / "exceptions.yaml"
    exc_path.write_text(
        yaml.safe_dump(
            {
                "schema_version": 1,
                "exceptions": [
                    {
                        "id": "SEC-EXC-0010",
                        "tool": "trivy",
                        "rule": "CVE-2026-0001",
                        "severity": "HIGH",
                        "file": "backend/requirements.lock",
                        "reason": "Expired exception test",
                        "approver": "@sowad",
                        "created_at": "2026-07-01",
                        "expires_at": "2026-09-15",
                    },
                    {
                        "id": "SEC-EXC-0011",
                        "tool": "trivy",
                        "rule": "CVE-2026-0002",
                        "severity": "HIGH",
                        "file": "backend/requirements.lock",
                        "reason": "Over 90 days exception test",
                        "approver": "@sowad",
                        "created_at": "2026-10-31",
                        "expires_at": "2027-03-01",
                    },
                ],
            }
        ),
        encoding="utf-8",
    )

    valid, expired_errs, val_errs = evaluate_secscan.load_and_validate_exceptions(
        exc_path, date(2026, 10, 31)
    )
    assert valid == {}
    assert len(expired_errs) == 1
    assert "SEC-EXC-0010" in expired_errs[0]
    assert len(val_errs) == 1
    assert "SEC-EXC-0011" in val_errs[0]
    assert "maximum allowed is 90 days" in val_errs[0]


def test_inline_suppression_requires_valid_exception_id(tmp_path):
    ws = _write_minimal_workspace(tmp_path)
    py_dir = ws / "backend" / "app"
    py_dir.mkdir(parents=True, exist_ok=True)
    kt_dir = ws / "android" / "app" / "src"
    kt_dir.mkdir(parents=True, exist_ok=True)

    # 1. Uncited inline suppressions must fail
    (py_dir / "bad.py").write_text("import random\nrng = random.Random(1)  # nosec B311\n", encoding="utf-8")
    (kt_dir / "Bad.kt").write_text('@SuppressLint("TrustAllX509TrustManager")\nfun bad() {}\n', encoding="utf-8")

    valid, _, _ = evaluate_secscan.load_and_validate_exceptions(
        ws / "security" / "exceptions.yaml", date(2026, 10, 31)
    )
    errors = evaluate_secscan.check_inline_suppressions(ws, valid)
    assert len(errors) == 2
    assert any("backend/app/bad.py:2" in e for e in errors)
    assert any("android/app/src/Bad.kt:1" in e for e in errors)

    # 2. Citing valid SEC-EXC-0001 passes
    (py_dir / "bad.py").write_text(
        "import random\nrng = random.Random(1)  # nosec B311  # SEC-EXC-0001\n",
        encoding="utf-8",
    )
    (kt_dir / "Bad.kt").write_text(
        '// SEC-EXC-0001\n@SuppressLint("TrustAllX509TrustManager")\nfun bad() {}\n',
        encoding="utf-8",
    )
    errors_after = evaluate_secscan.check_inline_suppressions(ws, valid)
    assert errors_after == []


def test_staging_and_production_urls_are_rejected_and_secrets_stripped(monkeypatch):
    with pytest.raises(ValueError, match="SECURITY CONSTRAINT VIOLATION"):
        scanners.verify_target_url_is_local("https://intake.neogold.kitalonlabs.com/v1")
    with pytest.raises(ValueError, match="SECURITY CONSTRAINT VIOLATION"):
        scanners.verify_target_url_is_local("https://staging.example.org/v1")

    scanners.verify_target_url_is_local("http://127.0.0.1:8000/v1")
    scanners.verify_target_url_is_local("http://localhost:8000/v1")

    monkeypatch.setenv("KEYSTORE_BASE64", "secret-keystore")
    monkeypatch.setenv("PLAY_SERVICE_ACCOUNT_JSON", '{"private_key": "secret"}')
    monkeypatch.setenv("DROPLET_SSH_KEY", "ssh-rsa-secret")
    env = scanners.sanitized_env()
    assert "KEYSTORE_BASE64" not in env
    assert "PLAY_SERVICE_ACCOUNT_JSON" not in env
    assert "DROPLET_SSH_KEY" not in env


def test_tc_sec_10_mobsf_step_detects_debuggable_and_cleartext_manifest(tmp_path):
    """TC-SEC-10 groundwork: MobSF static step detects debuggable=true, allowBackup=true, and cleartext."""
    ws = _write_minimal_workspace(tmp_path)
    man_dir = ws / "android" / "app" / "src" / "main"
    man_dir.mkdir(parents=True, exist_ok=True)
    (man_dir / "AndroidManifest.xml").write_text(
        '<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="com.example">\n'
        '  <application android:debuggable="true" android:allowBackup="true" android:usesCleartextTraffic="true" />\n'
        "</manifest>\n",
        encoding="utf-8",
    )
    report_dir = ws / "reports" / "security" / "2026-10-31"
    report_dir.mkdir(parents=True, exist_ok=True)
    versions = scanners.load_versions(ws / "tools" / "secscan" / "versions.txt")
    mobsf_file = scanners.run_mobsf_static_scan(ws, report_dir, versions)
    data = json.loads(mobsf_file.read_text(encoding="utf-8"))
    rules = {f["rule"] for f in data["findings"]}
    assert "android_debuggable_enabled" in rules
    assert "android_allow_backup" in rules
    assert "android_cleartext_traffic" in rules

