#!/usr/bin/env python3
"""NeoGold Journey Release Gate (P29).

Enforces the release gate for the release commit:
- Requires valid error-free session reports for matrix phones D1 to D4.
- Requires every P1 journey to be error-free in at least 20 of 20 runs (100% rate).
- Requires on D1 and D2:
    * J2 full journey under 60 s at p95 (NFR-02)
    * J2 capture to result step under 10 s at p95 (NFR-02)
"""

from __future__ import annotations

import argparse
import json
import re
import sys
from pathlib import Path
from typing import Any

SCRIPT_DIR = Path(__file__).resolve().parent
REPO_ROOT = SCRIPT_DIR.parent
if str(REPO_ROOT) not in sys.path:
    sys.path.insert(0, str(REPO_ROOT))

REQUIRED_RELEASE_DEVICES = ["D1", "D2", "D3", "D4"]
REFERENCE_DEVICES = ["D1", "D2"]


def get_app_version(repo_root: Path) -> str:
    """Read app version name from build logic."""
    build_plugin = repo_root / "android/build-logic/src/main/kotlin/com/kitalonlabs/neogold/buildlogic/NeoGoldAndroidApplicationPlugin.kt"
    if build_plugin.exists():
        m = re.search(r'APP_VERSION_NAME\s*=\s*"([^"]+)"', build_plugin.read_text(encoding="utf-8"))
        if m:
            return m.group(1)
    return "1.0.0"


def find_device_report(reports_dir: Path, device_id: str) -> Path | None:
    """Find newest JSON report file for a specific device ID."""
    if not reports_dir.exists():
        return None
    matches = list(reports_dir.glob(f"{device_id}-*.json"))
    if not matches:
        # Also check exact device_id.json
        exact = reports_dir / f"{device_id}.json"
        if exact.exists():
            return exact
        return None
    # Sort by filename descending (newest date first)
    matches.sort(key=lambda p: p.name, reverse=True)
    return matches[0]


def check_device_report(
    report: dict[str, Any],
    device_id: str,
    min_runs: int = 20,
) -> tuple[bool, list[str]]:
    """Verify validity, 20/20 P1 runs, and NFR-02 timing budgets for a report."""
    errors: list[str] = []

    # 1. Validity check
    if not report.get("is_valid", False):
        reason = report.get("invalid_reason", "Harness failure rate exceeded 5%")
        errors.append(f"Report for {device_id} is marked INVALID: {reason}")

    # 2. Priority P1 journeys: 20 of 20 runs error-free
    journeys: dict[str, Any] = report.get("journeys", {})
    if not journeys:
        errors.append(f"No journey results found in report for {device_id}")

    for jid, jdata in journeys.items():
        if jdata.get("priority") != "P1":
            continue

        runs = jdata.get("runs", 0)
        error_free = jdata.get("error_free_runs", 0)

        if runs < min_runs:
            errors.append(
                f"[{device_id}] Journey {jid} ran only {runs} times (minimum required: {min_runs})"
            )

        if error_free < runs:
            failure_count = runs - error_free
            failure_summary = "; ".join(f"{f['type']}: {f['message']}" for f in jdata.get("failures", []))
            errors.append(
                f"[{device_id}] Journey {jid} had {failure_count} failure(s) ({error_free}/{runs} error-free). Details: {failure_summary}"
            )

    # 3. NFR-02 budgets on reference class devices D1 and D2
    if device_id in REFERENCE_DEVICES:
        j2 = journeys.get("J2")
        if not j2:
            errors.append(f"[{device_id}] Core screening journey J2 is missing from report")
        else:
            # J2 total duration at p95 under 60 s
            total_time = j2.get("total_time", {})
            j2_p95 = total_time.get("p95_seconds", 999.0)
            if j2_p95 > 60.0:
                errors.append(
                    f"[{device_id}] NFR-02 violation: J2 full journey duration p95 is {j2_p95} s > 60.0 s"
                )

            # J2 capture to result step at p95 under 10 s
            steps = j2.get("steps", {})
            c2r = steps.get("capture_to_result", {})
            c2r_p95 = c2r.get("p95_seconds", 999.0)
            if c2r_p95 > 10.0:
                errors.append(
                    f"[{device_id}] NFR-02 violation: J2 capture_to_result step p95 is {c2r_p95} s > 10.0 s"
                )

    return len(errors) == 0, errors


def run_release_gate(
    reports_dir: Path,
    devices: list[str],
    min_runs: int = 20,
    allow_incomplete: bool = False,
) -> tuple[bool, list[str]]:
    """Run full release gate evaluation across required devices."""
    all_errors: list[str] = []
    found_devices: list[str] = []

    for dev_id in devices:
        report_file = find_device_report(reports_dir, dev_id)
        if not report_file:
            if not allow_incomplete:
                all_errors.append(
                    f"Missing required journey report for device {dev_id} in {reports_dir}"
                )
            continue

        found_devices.append(dev_id)
        try:
            payload = json.loads(report_file.read_text(encoding="utf-8"))
        except Exception as exc:
            all_errors.append(f"Failed to read JSON report {report_file}: {exc}")
            continue

        ok, dev_errors = check_device_report(payload, dev_id, min_runs=min_runs)
        if not ok:
            all_errors.extend(dev_errors)

    if allow_incomplete and not found_devices:
        all_errors.append(f"No device reports found in {reports_dir}")

    passed = len(all_errors) == 0
    return passed, all_errors


def main() -> int:
    parser = argparse.ArgumentParser(description="NeoGold Journey Release Gate (P29)")
    parser.add_argument("--reports-dir", help="Directory containing journey report JSONs")
    parser.add_argument("--version", help="Release version (defaults to app version)")
    parser.add_argument("--device", help="Check a specific device only (e.g. D1 or emulator)")
    parser.add_argument(
        "--allow-incomplete",
        action="store_true",
        help="Permit partial matrix evaluation during intermediate development runs",
    )
    parser.add_argument("--min-runs", type=int, default=20, help="Required runs per journey (default: 20)")

    args = parser.parse_args()

    version = args.version or get_app_version(REPO_ROOT)
    reports_dir = Path(args.reports_dir) if args.reports_dir else REPO_ROOT / "reports" / "journeys" / version

    target_devices = [args.device] if args.device else REQUIRED_RELEASE_DEVICES

    sys.stdout.write(f"Evaluating journey release gate for version {version} in {reports_dir}...\n")
    sys.stdout.write(f"Target devices: {', '.join(target_devices)}\n")

    passed, errors = run_release_gate(
        reports_dir=reports_dir,
        devices=target_devices,
        min_runs=args.min_runs,
        allow_incomplete=args.allow_incomplete,
    )

    if passed:
        sys.stdout.write("\n=======================================================\n")
        sys.stdout.write(" [PASS] ALL JOURNEY RELEASE GATE CRITERIA MET\n")
        sys.stdout.write(" - Valid reports verified with <= 5% harness flakiness\n")
        sys.stdout.write(f" - Every P1 journey passed {args.min_runs}/{args.min_runs} error-free runs\n")
        sys.stdout.write(" - NFR-02 budgets met on D1 and D2 (J2 < 60s, capture-to-result < 10s)\n")
        sys.stdout.write("=======================================================\n")
        return 0
    else:
        sys.stderr.write("\n=======================================================\n")
        sys.stderr.write(" [FAIL] JOURNEY RELEASE GATE CRITERIA NOT MET\n")
        sys.stderr.write("=======================================================\n")
        for err in errors:
            sys.stderr.write(f"  * {err}\n")
        sys.stderr.write("=======================================================\n")
        return 1


if __name__ == "__main__":
    sys.exit(main())

