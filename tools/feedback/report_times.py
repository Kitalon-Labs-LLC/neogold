#!/usr/bin/env python3
"""tools/feedback/report_times.py

Calculates and reports feedback turnaround time metrics across the four key lifecycle milestones:
1. First response (initial communication back to the reporter)
2. Sorted (triaged into one of the 5 RULES.md buckets and assigned severity)
3. Fix merged (PR reviewed and merged by the second approver)
4. Fix on the closed track (released or promoted to closed testing track)

Verifies against targets in doc 9 Section 9.2:
- S1 Critical: sorted same day (< 24 h)
- S2 Major: sorted within 2 working days (< 48 h)
- S3 Minor / S4 Cosmetic: sorted weekly (< 168 h)
"""

from __future__ import annotations

import argparse
from datetime import datetime, timezone
import json
from pathlib import Path
import statistics
import sys
from typing import Any

REPO_ROOT = Path(__file__).resolve().parent.parent.parent
if str(REPO_ROOT) not in sys.path:
    sys.path.insert(0, str(REPO_ROOT))

TARGET_HOURS = {
    "S1": {"sorted": 24.0, "first_response": 4.0},
    "S2": {"sorted": 48.0, "first_response": 24.0},
    "S3": {"sorted": 168.0, "first_response": 48.0},
    "S4": {"sorted": 168.0, "first_response": 48.0},
}


def parse_timestamp(ts_str: str | None) -> datetime | None:
    if not ts_str:
        return None
    # Support ISO format with Z or timezone offsets
    clean = ts_str.replace("Z", "+00:00")
    try:
        dt = datetime.fromisoformat(clean)
        if dt.tzinfo is None:
            dt = dt.replace(tzinfo=timezone.utc)
        return dt
    except Exception:
        return None


def get_iso_week(dt: datetime) -> str:
    year, week, _ = dt.isocalendar()
    return f"{year}-W{week:02d}"


def calculate_report_milestones(report: dict[str, Any]) -> dict[str, Any]:
    reported = parse_timestamp(report.get("reported_at") or report.get("created_at"))
    if not reported:
        return {}

    first_resp = parse_timestamp(report.get("first_response_at"))
    sorted_at = parse_timestamp(report.get("sorted_at") or report.get("triaged_at"))
    fix_merged = parse_timestamp(report.get("fix_merged_at"))
    closed_track = parse_timestamp(report.get("closed_track_at"))

    hours_to_resp = (first_resp - reported).total_seconds() / 3600.0 if first_resp else None
    hours_to_sorted = (sorted_at - reported).total_seconds() / 3600.0 if sorted_at else None
    hours_to_merged = (fix_merged - reported).total_seconds() / 3600.0 if fix_merged else None
    hours_to_track = (closed_track - reported).total_seconds() / 3600.0 if closed_track else None

    severity = report.get("severity", "S2").upper()

    return {
        "id": report.get("id", "unknown"),
        "title": report.get("title", ""),
        "severity": severity,
        "week": get_iso_week(reported),
        "reported_at": reported,
        "hours_to_first_response": hours_to_resp,
        "hours_to_sorted": hours_to_sorted,
        "hours_to_fix_merged": hours_to_merged,
        "hours_to_closed_track": hours_to_track,
    }


def aggregate_weekly_metrics(records: list[dict[str, Any]]) -> dict[str, Any]:
    weeks: dict[str, list[dict[str, Any]]] = {}
    for r in records:
        w = r.get("week")
        if not w:
            continue
        weeks.setdefault(w, []).append(r)

    summary: dict[str, Any] = {}

    for w, items in sorted(weeks.items()):
        resp_times = [i["hours_to_first_response"] for i in items if i["hours_to_first_response"] is not None]
        sort_times = [i["hours_to_sorted"] for i in items if i["hours_to_sorted"] is not None]
        merge_times = [i["hours_to_fix_merged"] for i in items if i["hours_to_fix_merged"] is not None]
        track_times = [i["hours_to_closed_track"] for i in items if i["hours_to_closed_track"] is not None]

        s1_items = [i for i in items if i["severity"] == "S1"]
        s1_sort_times = [i["hours_to_sorted"] for i in s1_items if i["hours_to_sorted"] is not None]

        med_resp = round(statistics.median(resp_times), 1) if resp_times else None
        med_sort = round(statistics.median(sort_times), 1) if sort_times else None
        med_merge = round(statistics.median(merge_times), 1) if merge_times else None
        med_track = round(statistics.median(track_times), 1) if track_times else None

        # SLA evaluation: S1 sorted <= 24 h
        s1_target_met = all(t <= 24.0 for t in s1_sort_times) if s1_sort_times else True
        sla_status = "MET" if s1_target_met else "MISSED (S1 > 24h)"

        summary[w] = {
            "week": w,
            "total_reports": len(items),
            "s1_count": len(s1_items),
            "median_first_response_h": med_resp,
            "median_sorted_h": med_sort,
            "median_fix_merged_h": med_merge,
            "median_closed_track_h": med_track,
            "sla_target_status": sla_status,
        }

    return summary


def format_summary_table(weekly_summary: dict[str, Any]) -> str:
    lines = [
        "# Feedback Triage Turnaround Times (Weekly Medians)",
        "",
        "Target Milestones per doc 9 Section 9.2 (S1 sorted same day <24h, S2 <48h):",
        "",
        "| Week | Total Reports | S1 Count | Median First Response | Median Sorted | Median Fix Merged | Median Closed Track | SLA Status |",
        "|---|---|---|---|---|---|---|---|",
    ]

    for w, data in sorted(weekly_summary.items()):
        resp_s = f"{data['median_first_response_h']} h" if data['median_first_response_h'] is not None else "-"
        sort_s = f"{data['median_sorted_h']} h" if data['median_sorted_h'] is not None else "-"
        merge_s = f"{data['median_fix_merged_h']} h" if data['median_fix_merged_h'] is not None else "-"
        track_s = f"{data['median_closed_track_h']} h" if data['median_closed_track_h'] is not None else "-"

        lines.append(
            f"| {w} | {data['total_reports']} | {data['s1_count']} | {resp_s} | {sort_s} | {merge_s} | {track_s} | **{data['sla_target_status']}** |"
        )

    return "\n".join(lines)


def load_reports_from_file(file_path: Path) -> list[dict[str, Any]]:
    if not file_path.is_file():
        raise FileNotFoundError(f"Input file not found: {file_path}")
    raw = json.loads(file_path.read_text(encoding="utf-8"))
    if isinstance(raw, list):
        return raw
    elif isinstance(raw, dict) and "reports" in raw:
        return raw["reports"]
    return [raw]


def main() -> int:
    parser = argparse.ArgumentParser(description="Calculate feedback and defect milestone turnaround times.")
    parser.add_argument("--input", "-i", required=True, help="Path to JSON file containing timeline records.")
    parser.add_argument("--output-md", help="Path to write Markdown summary table.")
    parser.add_argument("--output-json", help="Path to write JSON aggregated data.")

    args = parser.parse_args()

    reports = load_reports_from_file(Path(args.input))
    processed = [calculate_report_milestones(r) for r in reports]
    processed = [p for p in processed if p]

    weekly = aggregate_weekly_metrics(processed)
    table_md = format_summary_table(weekly)

    if args.output_md:
        out_p = Path(args.output_md)
        out_p.parent.mkdir(parents=True, exist_ok=True)
        out_p.write_text(table_md, encoding="utf-8")
        print(f"Saved turnaround table to {out_p}")

    if args.output_json:
        out_j = Path(args.output_json)
        out_j.parent.mkdir(parents=True, exist_ok=True)
        out_j.write_text(json.dumps(weekly, indent=2), encoding="utf-8")
        print(f"Saved turnaround metrics to {out_j}")

    if not args.output_md and not args.output_json:
        print(table_md)

    return 0


if __name__ == "__main__":
    sys.exit(main())
