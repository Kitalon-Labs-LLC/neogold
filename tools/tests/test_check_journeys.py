"""Unit and integration tests for NeoGold journey release gate (P29).

Verifies release gate logic under valid passing and failing conditions:
- Valid reports for D1 to D4
- 20 of 20 error-free runs on P1 journeys
- NFR-02 budgets on D1 and D2 (J2 < 60 s, capture-to-result < 10 s)
- Rejection of invalid harness rates or missing reports
"""

from __future__ import annotations

import json
from pathlib import Path
import pytest

from tools.check_journeys import run_release_gate, check_device_report

REPO_ROOT = Path(__file__).resolve().parent.parent.parent


def make_synthetic_report(
    device_id: str,
    p1_runs: int = 20,
    p1_error_free: int = 20,
    j2_total_p95: float = 42.0,
    j2_c2r_p95: float = 3.5,
    is_valid: bool = True,
) -> dict:
    """Helper to generate mock report payload."""
    return {
        "build": {
            "version": "1.0.0",
            "commit": "abcdef1234567890abcdef1234567890abcdef12",
            "config_hash": "a" * 64,
            "triage_enabled": True,
        },
        "device": {
            "id": device_id,
            "name": f"Mock {device_id}",
            "class": "Entry",
            "ram_mb": 4096,
            "android_version": "14",
        },
        "date": "2026-11-02",
        "priority": "P1",
        "total_runs": p1_runs,
        "total_error_free_runs": p1_error_free,
        "overall_error_free_rate": p1_error_free / p1_runs if p1_runs > 0 else 0.0,
        "harness_failure_rate": 0.01,
        "is_valid": is_valid,
        "journeys": {
            "J1": {
                "id": "J1",
                "name": "First Launch",
                "prd_source": "PRD 6 J1",
                "priority": "P1",
                "runs": p1_runs,
                "error_free_runs": p1_error_free,
                "error_free_rate": p1_error_free / p1_runs if p1_runs > 0 else 0.0,
                "failures": [] if p1_error_free == p1_runs else [{"type": "crash", "message": "fail"}],
                "steps": {},
                "total_time": {"budget_seconds": 90.0, "p50_seconds": 30.0, "p95_seconds": 45.0, "budget_met": True},
            },
            "J2": {
                "id": "J2",
                "name": "Core Screening",
                "prd_source": "PRD 6 J2",
                "priority": "P1",
                "runs": p1_runs,
                "error_free_runs": p1_error_free,
                "error_free_rate": p1_error_free / p1_runs if p1_runs > 0 else 0.0,
                "failures": [] if p1_error_free == p1_runs else [{"type": "wrong_screen", "message": "fail"}],
                "steps": {
                    "capture_to_result": {
                        "budget_seconds": 10.0,
                        "p50_seconds": j2_c2r_p95 * 0.7,
                        "p95_seconds": j2_c2r_p95,
                        "budget_met": j2_c2r_p95 <= 10.0,
                    }
                },
                "total_time": {
                    "budget_seconds": 60.0,
                    "p50_seconds": j2_total_p95 * 0.7,
                    "p95_seconds": j2_total_p95,
                    "budget_met": j2_total_p95 <= 60.0,
                },
            },
        },
    }


def test_check_device_report_nominal_pass():
    """Verify clean 20/20 report on D1 passes all checks."""
    rep = make_synthetic_report("D1", 20, 20, j2_total_p95=45.0, j2_c2r_p95=3.8)
    ok, errs = check_device_report(rep, "D1", min_runs=20)
    assert ok is True
    assert not errs


def test_check_device_report_fails_on_app_errors():
    """Verify failing run flags errors."""
    rep = make_synthetic_report("D1", 20, 19)
    ok, errs = check_device_report(rep, "D1", min_runs=20)
    assert ok is False
    assert any("had 1 failure" in e for e in errs)


def test_check_device_report_fails_on_nfr02_violation():
    """Verify D1/D2 report fails when capture_to_result > 10 s or J2 > 60 s."""
    rep = make_synthetic_report("D1", 20, 20, j2_total_p95=65.0, j2_c2r_p95=3.5)
    ok, errs = check_device_report(rep, "D1", min_runs=20)
    assert ok is False
    assert any("NFR-02 violation: J2 full journey" in e for e in errs)

    rep2 = make_synthetic_report("D2", 20, 20, j2_total_p95=40.0, j2_c2r_p95=11.2)
    ok2, errs2 = check_device_report(rep2, "D2", min_runs=20)
    assert ok2 is False
    assert any("capture_to_result step p95" in e for e in errs2)


def test_release_gate_full_matrix_pass(tmp_path):
    """Verify full release gate passes when all D1 to D4 have 20/20 valid reports."""
    for dev in ("D1", "D2", "D3", "D4"):
        rep = make_synthetic_report(dev, 20, 20, j2_total_p95=40.0, j2_c2r_p95=3.5)
        (tmp_path / f"{dev}-20261102.json").write_text(json.dumps(rep), encoding="utf-8")

    ok, errs = run_release_gate(tmp_path, ["D1", "D2", "D3", "D4"], min_runs=20)
    assert ok is True
    assert not errs


def test_release_gate_fails_when_device_missing(tmp_path):
    """Verify gate fails if D4 is missing."""
    for dev in ("D1", "D2", "D3"):
        rep = make_synthetic_report(dev, 20, 20)
        (tmp_path / f"{dev}-20261102.json").write_text(json.dumps(rep), encoding="utf-8")

    ok, errs = run_release_gate(tmp_path, ["D1", "D2", "D3", "D4"], min_runs=20)
    assert ok is False
    assert any("Missing required journey report for device D4" in e for e in errs)
