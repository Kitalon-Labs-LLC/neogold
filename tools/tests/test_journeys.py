"""Unit and integration tests for NeoGold journey runner and catalog (P29).

Verifies catalog structure, time budgets (NFR-02), runner error detection,
harness flakiness isolation, and schema conformity.
"""

from __future__ import annotations

import json
from pathlib import Path
import pytest

from tools.journeys.run import (
    load_yaml,
    run_benchmark,
    validate_report,
    calculate_percentile,
    compute_config_hash,
    resolve_device_meta,
)

REPO_ROOT = Path(__file__).resolve().parent.parent.parent
CATALOG_PATH = REPO_ROOT / "journeys" / "catalog.yaml"
SCHEMA_PATH = REPO_ROOT / "tools" / "journeys" / "schema.json"


def test_catalog_structure_and_journeys():
    """Verify catalog.yaml exists, loads, and defines all required journeys with time budgets."""
    assert CATALOG_PATH.exists(), f"Catalog not found at {CATALOG_PATH}"
    catalog = load_yaml(CATALOG_PATH)
    journeys = catalog.get("journeys", [])
    assert len(journeys) >= 10

    required_ids = {
        "J1",
        "J2",
        "J2-hidden",
        "J3",
        "J4",
        "upload-now",
        "encrypted-export",
        "app-lock-unlock",
        "delete-all-data",
        "leave-study",
    }
    found_ids = {j["id"] for j in journeys}
    missing = required_ids - found_ids
    assert not missing, f"Missing required journeys in catalog: {missing}"

    for j in journeys:
        jid = j["id"]
        assert "name" in j and j["name"], f"Journey {jid} missing name"
        assert "prd_source" in j, f"Journey {jid} missing prd_source"
        assert j.get("priority") in ("P1", "P2"), f"Journey {jid} invalid priority: {j.get('priority')}"
        assert "flow" in j and j["flow"], f"Journey {jid} missing flow"
        assert (REPO_ROOT / j["flow"]).exists(), f"Flow file {j['flow']} referenced by {jid} does not exist"
        assert float(j.get("time_budget_seconds", 0)) > 0, f"Journey {jid} budget must be positive"

        steps = j.get("steps", [])
        assert len(steps) >= 3, f"Journey {jid} must have at least 3 steps"
        for s in steps:
            assert "name" in s, f"Step in {jid} missing name"
            assert float(s.get("budget_seconds", 0)) > 0, f"Step {s['name']} in {jid} budget must be positive"


def test_nfr02_budgets_in_j2():
    """Verify NFR-02 performance budgets: full J2 under 60 s, capture to result under 10 s."""
    catalog = load_yaml(CATALOG_PATH)
    j2 = next((j for j in catalog["journeys"] if j["id"] == "J2"), None)
    assert j2 is not None, "J2 journey not found"
    assert j2["time_budget_seconds"] <= 60.0, "J2 full journey budget must be <= 60 s per NFR-02"

    c2r = next((s for s in j2["steps"] if s["name"] == "capture_to_result"), None)
    assert c2r is not None, "J2 capture_to_result step missing"
    assert c2r["budget_seconds"] <= 10.0, "J2 capture_to_result budget must be <= 10 s per NFR-02"


def test_calculate_percentile():
    """Verify p50 and p95 calculations."""
    vals = [1.0, 2.0, 3.0, 4.0, 5.0, 6.0, 7.0, 8.0, 9.0, 10.0]
    p50 = calculate_percentile(vals, 50.0)
    p95 = calculate_percentile(vals, 95.0)
    assert 5.0 <= p50 <= 6.0
    assert 9.0 <= p95 <= 10.0
    assert calculate_percentile([], 50.0) == 0.0


def test_device_meta_resolution():
    """Verify matrix device resolution."""
    dev_key, meta = resolve_device_meta("D1")
    assert dev_key == "D1"
    assert meta["ram_mb"] == 3072
    assert "HiOS" in meta["name"] or "Transsion" in meta["class"]

    dev_key, meta = resolve_device_meta("emulator-5554")
    assert dev_key == "emulator"
    assert "Emulator" in meta["class"]


def test_runner_nominal_execution_and_schema_validation(tmp_path):
    """Run benchmark on emulator and assert schema validation succeeds."""
    report = run_benchmark(
        catalog_path=CATALOG_PATH,
        device_arg="emulator",
        runs=5,
        priority="P1",
    )
    assert report["is_valid"] is True
    assert report["overall_error_free_rate"] == 1.0
    assert report["total_runs"] == 5 * len(report["journeys"])

    # Validate against JSON schema
    validate_report(report, SCHEMA_PATH)


@pytest.mark.parametrize("failure_type", ["crash", "anr", "wrong_screen", "app_log_error", "budget"])
def test_runner_injected_app_failures(failure_type):
    """Verify that crashes, ANRs, wrong screens, app errors, and budget overflows are flagged as failures."""
    report = run_benchmark(
        catalog_path=CATALOG_PATH,
        device_arg="D1",
        runs=3,
        journey_id="J2",
        inject_failure=failure_type,
    )
    j2_res = report["journeys"]["J2"]
    assert j2_res["error_free_runs"] < 3
    assert len(j2_res["failures"]) >= 1

    expected_type = "time_budget_exceeded" if failure_type == "budget" else failure_type
    assert any(f["type"] == expected_type for f in j2_res["failures"])


def test_runner_harness_failure_isolation():
    """Verify that harness failures > 5% mark report invalid."""
    report = run_benchmark(
        catalog_path=CATALOG_PATH,
        device_arg="D1",
        runs=10,
        journey_id="J2",
        harness_flake_rate=0.20,  # 20% harness flakiness > 5% threshold
    )
    assert report["is_valid"] is False
    assert "exceeds 5% threshold" in report.get("invalid_reason", "")

