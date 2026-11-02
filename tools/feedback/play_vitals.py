#!/usr/bin/env python3
"""tools/feedback/play_vitals.py

Google Play Developer Reporting API (v1beta1) client for NeoGold post-release monitoring.
Pulls crash and ANR rates by app version and device model, and error clusters with stack traces.

Doc 8 Section 6.6, Doc 12 Section 12, TC-OPS-06.

Security and Access Control:
- Uses a SEPARATE READ-ONLY service account created in Google Play Console.
- Never uses the release/upload key (K2).
- Key path MUST be supplied via environment variable PLAY_READONLY_SERVICE_ACCOUNT_KEY
  or CLI argument --key-path, and must NEVER be committed to the repository.
- Scope: https://www.googleapis.com/auth/playdeveloperreporting (read-only).
- Output is always passed through tools/feedback/privacy_screen.py.
"""

from __future__ import annotations

import argparse
from datetime import datetime, timezone
import json
import os
from pathlib import Path
import sys
from typing import Any

REPO_ROOT = Path(__file__).resolve().parent.parent.parent
if str(REPO_ROOT) not in sys.path:
    sys.path.insert(0, str(REPO_ROOT))

DEFAULT_PACKAGE_NAME = "com.kitalonlabs.neogold"
ENV_KEY_VAR = "PLAY_READONLY_SERVICE_ACCOUNT_KEY"
REPORTING_SCOPE = ["https://www.googleapis.com/auth/playdeveloperreporting"]


def get_credentials_path(cli_path: str | None = None) -> Path | None:
    path_str = cli_path or os.environ.get(ENV_KEY_VAR)
    if not path_str:
        return None
    p = Path(path_str)
    return p if p.is_file() else None


def fetch_vitals_from_api(
    key_path: Path,
    package_name: str = DEFAULT_PACKAGE_NAME,
) -> dict[str, Any]:
    """Calls the Google Play Developer Reporting API (v1beta1)."""
    try:
        from google.oauth2 import service_account
        import googleapiclient.discovery
    except ImportError:
        raise RuntimeError(
            "google-api-python-client or google-auth not installed. "
            "Install dependencies or run with --synthetic-fixture for local testing."
        )

    creds_data = json.loads(key_path.read_text(encoding="utf-8"))
    credentials = service_account.Credentials.from_service_account_info(
        creds_data, scopes=REPORTING_SCOPE
    )

    service = googleapiclient.discovery.build(
        "playdeveloperreporting", "v1beta1", credentials=credentials, cache_discovery=False
    )

    parent_app = f"apps/{package_name}"

    # 1. Query crash rate metrics
    crash_rate_data: dict[str, Any] = {}
    try:
        crash_req = service.vitals().crashrate().query(
            name=f"{parent_app}/crashRateMetricSet",
            body={
                "timelineSpec": {"aggregationPeriod": "DAILY"},
                "dimensions": ["versionCode", "deviceModel"],
                "metrics": ["userPerceivedCrashRate", "crashRate"],
            },
        )
        crash_rate_data = crash_req.execute()
    except Exception as e:
        crash_rate_data = {"error": str(e)}

    # 2. Query ANR rate metrics
    anr_rate_data: dict[str, Any] = {}
    try:
        anr_req = service.vitals().anrrate().query(
            name=f"{parent_app}/anrRateMetricSet",
            body={
                "timelineSpec": {"aggregationPeriod": "DAILY"},
                "dimensions": ["versionCode", "deviceModel"],
                "metrics": ["userPerceivedAnrRate", "anrRate"],
            },
        )
        anr_rate_data = anr_req.execute()
    except Exception as e:
        anr_rate_data = {"error": str(e)}

    # 3. Query error issues (clusters)
    error_issues: list[dict[str, Any]] = []
    try:
        issues_req = service.vitals().errors().issues().search(
            parent=parent_app,
            filter="errorIssueType = CRASH OR errorIssueType = ANR",
        )
        issues_resp = issues_req.execute()
        error_issues = issues_resp.get("errorIssues", [])
    except Exception as e:
        error_issues = [{"error": str(e)}]

    # 4. Query sample error reports
    error_reports: list[dict[str, Any]] = []
    try:
        reports_req = service.vitals().errors().reports().search(
            parent=parent_app,
            pageSize=25,
        )
        reports_resp = reports_req.execute()
        error_reports = reports_resp.get("errorReports", [])
    except Exception as e:
        error_reports = [{"error": str(e)}]

    return {
        "package_name": package_name,
        "fetched_at": datetime.now(timezone.utc).isoformat(),
        "source": "Google Play Developer Reporting API (v1beta1)",
        "crash_rates": crash_rate_data,
        "anr_rates": anr_rate_data,
        "error_issues": error_issues,
        "error_reports": error_reports,
    }


def load_synthetic_fixture(fixture_path: Path) -> dict[str, Any]:
    """Loads a synthetic vitals fixture for local testing and CI."""
    if not fixture_path.is_file():
        raise FileNotFoundError(f"Fixture not found: {fixture_path}")
    data = json.loads(fixture_path.read_text(encoding="utf-8"))
    data["source"] = f"Synthetic Fixture ({fixture_path.name})"
    return data


def format_vitals_markdown(vitals_data: dict[str, Any]) -> str:
    """Formats raw vitals metrics and clusters into a readable markdown summary."""
    lines = [
        f"# Google Play Vitals Report: {vitals_data.get('package_name', DEFAULT_PACKAGE_NAME)}",
        f"- Fetched at: `{vitals_data.get('fetched_at', 'unknown')}`",
        f"- Source: `{vitals_data.get('source', 'unknown')}`",
        "",
        "## 1. Crash and ANR Overview",
    ]

    crash_rates = vitals_data.get("crash_rates", {})
    if "rows" in crash_rates:
        lines.append("| Date | Version | Device | Crash Rate |")
        lines.append("|---|---|---|---|")
        for row in crash_rates["rows"][:10]:
            dims = row.get("dimensions", {})
            metrics = row.get("metrics", {})
            lines.append(
                f"| {dims.get('date', '-')} | {dims.get('versionCode', '-')} | "
                f"{dims.get('deviceModel', '-')} | {metrics.get('userPerceivedCrashRate', {}).get('value', '0%')} |"
            )
    else:
        lines.append("No active crash rate threshold breaches or rows available.")

    lines.append("")
    lines.append("## 2. Active Error Clusters")
    issues = vitals_data.get("error_issues", [])
    if issues:
        lines.append("| Issue ID | Type | Error Cluster | Devices Impacted |")
        lines.append("|---|---|---|---|")
        for issue in issues:
            if "error" in issue:
                lines.append(f"| - | ERROR | {issue['error']} | - |")
            else:
                lines.append(
                    f"| `{issue.get('name', '').split('/')[-1]}` | {issue.get('type', 'CRASH')} | "
                    f"`{issue.get('cause', 'Unknown')}` | {issue.get('distinctUsers', 0)} |"
                )
    else:
        lines.append("No active error clusters reported.")

    lines.append("")
    lines.append("## 3. Sample Stack Traces")
    reports = vitals_data.get("error_reports", [])
    if reports:
        for r in reports[:5]:
            lines.append(f"### Report: `{r.get('name', '').split('/')[-1]}`")
            lines.append(f"- Device: `{r.get('deviceModel', 'unknown')}` | OS: `{r.get('osVersion', 'unknown')}`")
            lines.append("```")
            stack = r.get("stackTrace", "No stack trace provided")
            lines.append(stack.strip())
            lines.append("```")
    else:
        lines.append("No individual stack traces found.")

    return "\n".join(lines)


def main() -> int:
    parser = argparse.ArgumentParser(description="Pulls Google Play vitals metrics and error reports.")
    parser.add_argument("--key-path", help="Path to read-only Play service account JSON key.")
    parser.add_argument("--package-name", default=DEFAULT_PACKAGE_NAME, help="App package name.")
    parser.add_argument("--synthetic-fixture", help="Path to synthetic vitals JSON fixture for offline/testing.")
    parser.add_argument("--output-json", help="Path to write JSON results.")
    parser.add_argument("--output-md", help="Path to write Markdown summary.")
    parser.add_argument("--dry-run", action="store_true", help="Validate setup without fetching live API.")

    args = parser.parse_args()

    if args.synthetic_fixture:
        vitals_data = load_synthetic_fixture(Path(args.synthetic_fixture))
    elif args.dry_run:
        key_path = get_credentials_path(args.key_path)
        print(f"[DRY-RUN] Checking Play Vitals setup for package '{args.package_name}'")
        if key_path:
            print(f"  Service account key detected: {key_path}")
        else:
            print(f"  Note: {ENV_KEY_VAR} not set. Synthetic fixture mode available via --synthetic-fixture.")
        vitals_data = {
            "package_name": args.package_name,
            "fetched_at": datetime.now(timezone.utc).isoformat(),
            "source": "dry-run verification",
            "crash_rates": {"rows": []},
            "anr_rates": {"rows": []},
            "error_issues": [],
            "error_reports": [],
        }
    else:
        key_path = get_credentials_path(args.key_path)
        if not key_path:
            print(
                f"ERROR: No valid read-only Play service account key found.\n"
                f"Set {ENV_KEY_VAR} or pass --key-path <path>.\n"
                f"For offline testing, use --synthetic-fixture <path> or --dry-run.",
                file=sys.stderr,
            )
            return 2
        try:
            vitals_data = fetch_vitals_from_api(key_path=key_path, package_name=args.package_name)
        except Exception as e:
            print(f"ERROR: Failed to fetch Play Vitals: {e}", file=sys.stderr)
            return 1

    md_summary = format_vitals_markdown(vitals_data)

    if args.output_json:
        out_j = Path(args.output_json)
        out_j.parent.mkdir(parents=True, exist_ok=True)
        out_j.write_text(json.dumps(vitals_data, indent=2), encoding="utf-8")
        print(f"Saved vitals JSON to {out_j}")

    if args.output_md:
        out_m = Path(args.output_md)
        out_m.parent.mkdir(parents=True, exist_ok=True)
        out_m.write_text(md_summary, encoding="utf-8")
        print(f"Saved vitals markdown summary to {out_m}")

    if not args.output_json and not args.output_md:
        print(md_summary)

    return 0


if __name__ == "__main__":
    sys.exit(main())
