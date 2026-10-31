#!/usr/bin/env python3
"""
tools/secscan/evaluate_secscan.py

Evaluates scanner outputs in reports/security/YYYY-MM-DD/, enforces
security/exceptions.yaml rules and inline suppression citations, redacts any
residual secret strings in reports, and regenerates reports/security/ledger.md
(P26, docs/8_Security_Compliance_Plan.md Sections 10 & 12).
"""

from __future__ import annotations

import argparse
import json
import os
import re
import sys
from datetime import date, datetime, timedelta
from pathlib import Path

import yaml

REPO_ROOT = Path(__file__).resolve().parent.parent.parent

# Fix deadlines from docs/8_Security_Compliance_Plan.md Section 10
SLA_DAYS_BY_SEVERITY = {
    "CRITICAL": 7,
    "HIGH": 30,
    "MEDIUM": 90,
    "LOW": 90,
}

SEVERITY_RANK = {
    "CRITICAL": 0,
    "HIGH": 1,
    "MEDIUM": 2,
    "LOW": 3,
}

EXCEPTION_ID_RE = re.compile(r"\b(SEC-EXC-\d{4,})\b")

SECURITY_SUPPRESS_RULES = (
    "TrustAllX509TrustManager",
    "BadHostnameVerifier",
    "SetJavaScriptEnabled",
    "ExportedReceiver",
    "ExportedService",
    "ExportedContentProvider",
    "ExportedActivity",
    "WorldReadableFiles",
    "WorldWriteableFiles",
    "AllowBackup",
    "UsesCleartextTraffic",
    "HardcodedDebugMode",
    "PackageManagerGetSignatures",
    "TrulyRandom",
    "CustomX509TrustManager",
    "InsecureRandom",
    "WeakCryptography",
    "HardcodedCredentials",
    "SqlInjection",
)

INLINE_SUPPRESSION_PATTERNS: list[tuple[str, re.Pattern[str]]] = [
    ("bandit-nosec", re.compile(r"#\s*nosec\b", re.IGNORECASE)),
    ("ruff-security-noqa", re.compile(r"#\s*noqa\s*:\s*[^#\n]*\bS\d{3}\b", re.IGNORECASE)),
    ("gitleaks-allow", re.compile(r"gitleaks\s*:\s*allow\b", re.IGNORECASE)),
    ("trivy-ignore", re.compile(r"trivy\s*:\s*ignore\b", re.IGNORECASE)),
    ("semgrep-nosemgrep", re.compile(r"\bnosemgrep\b", re.IGNORECASE)),
    (
        "kotlin-security-suppress",
        re.compile(
            r"@Suppress(?:Lint)?\s*\([^)]*(?:"
            + "|".join(re.escape(r) for r in SECURITY_SUPPRESS_RULES)
            + r")[^)]*\)"
        ),
    ),
]

# Secret scrubbing regexes for report post-processing
REPORT_REDACT_PATTERNS = [
    re.compile(r"-----BEGIN (?:RSA |EC |DSA |OPENSSH |ENCRYPTED )?PRIVATE KEY-----[\s\S]*?-----END [A-Z ]+PRIVATE KEY-----"),
    re.compile(r"\b(?:A3T[A-Z0-9]|AKIA|AGPA|AIDA|AROA|AIPA|ANPA|ANVA|ASIA)[A-Z0-9]{16}\b"),
    re.compile(r"\bgh[pousr]_[A-Za-z0-9_]{36,255}\b"),
    re.compile(r"\bgithub_pat_[A-Za-z0-9_]{22,255}\b"),
    re.compile(r"\bAIza[0-9A-Za-z\-_]{35}\b"),
    re.compile(r"\bxox[baprs]-[0-9]{10,13}-[0-9]{10,13}-[a-zA-Z0-9]{24,34}\b"),
    re.compile(r"\b(?:sk|rk)_live_[0-9a-zA-Z]{24,99}\b"),
]


def parse_iso_date(val: str | date | None) -> date | None:
    if isinstance(val, date) and not isinstance(val, datetime):
        return val
    if not val or not isinstance(val, str):
        return None
    try:
        return date.fromisoformat(val.strip())
    except ValueError:
        return None


def normalize_rel_path(path_str: str, root: Path) -> str:
    norm = path_str.replace("\\", "/").strip()
    if norm.startswith("file:///"):
        norm = norm[len("file:///") :]
    elif norm.startswith("file://"):
        norm = norm[len("file://") :]
    root_posix = root.resolve().as_posix()
    if norm.lower().startswith(root_posix.lower() + "/"):
        norm = norm[len(root_posix) + 1 :]
    while norm.startswith("./"):
        norm = norm[2:]
    return norm


def redact_secrets_in_text(text: str) -> str:
    scrubbed = text
    for pat in REPORT_REDACT_PATTERNS:
        scrubbed = pat.sub("REDACTED", scrubbed)
    return scrubbed


def redact_report_directory(report_dir: Path) -> None:
    """Ensure no file in report_dir contains a raw matched secret string."""
    if not report_dir.exists():
        return
    for fpath in report_dir.rglob("*"):
        if fpath.is_file() and fpath.suffix.lower() in (".json", ".sarif", ".md", ".txt"):
            try:
                original = fpath.read_text(encoding="utf-8")
                scrubbed = redact_secrets_in_text(original)
                if scrubbed != original:
                    fpath.write_text(scrubbed, encoding="utf-8")
            except Exception:
                pass


def load_and_validate_exceptions(
    exceptions_path: Path,
    scan_date: date,
) -> tuple[dict[str, dict], list[str], list[str]]:
    """
    Load security/exceptions.yaml and validate schema and <=90 day expiry constraints.
    Returns (valid_exceptions_by_id, expired_errors, validation_errors).
    """
    if not exceptions_path.exists():
        return {}, [], [f"Missing exceptions file: {exceptions_path}"]

    raw = yaml.safe_load(exceptions_path.read_text(encoding="utf-8")) or {}
    entries = raw.get("exceptions")
    if not isinstance(entries, list):
        return {}, [], ["'exceptions' must be a list in security/exceptions.yaml"]

    by_id: dict[str, dict] = {}
    expired_errors: list[str] = []
    validation_errors: list[str] = []

    required_fields = ("id", "tool", "rule", "file", "reason", "approver", "expires_at")

    for idx, entry in enumerate(entries):
        if not isinstance(entry, dict):
            validation_errors.append(f"Entry #{idx + 1} in security/exceptions.yaml is not an object")
            continue

        missing = [f for f in required_fields if not str(entry.get(f, "")).strip()]
        exc_id = str(entry.get("id", f"ENTRY-{idx + 1}")).strip()
        if missing:
            validation_errors.append(f"{exc_id}: missing required fields: {', '.join(missing)}")
            continue

        if not EXCEPTION_ID_RE.fullmatch(exc_id):
            validation_errors.append(f"{exc_id}: id must match SEC-EXC-XXXX")
            continue

        if exc_id in by_id:
            validation_errors.append(f"{exc_id}: duplicate exception ID")
            continue

        expires_dt = parse_iso_date(entry.get("expires_at"))
        if not expires_dt:
            validation_errors.append(f"{exc_id}: invalid expires_at date '{entry.get('expires_at')}' (expected YYYY-MM-DD)")
            continue

        created_dt = parse_iso_date(entry.get("created_at")) or scan_date

        if expires_dt < scan_date:
            expired_errors.append(
                f"{exc_id} ({entry.get('tool')}:{entry.get('rule')} in {entry.get('file')}) "
                f"expired on {expires_dt.isoformat()} (scan date {scan_date.isoformat()})"
            )
            continue

        if (expires_dt - created_dt).days > 90:
            validation_errors.append(
                f"{exc_id}: expires_at ({expires_dt.isoformat()}) is {(expires_dt - created_dt).days} days "
                f"after created_at ({created_dt.isoformat()}); maximum allowed is 90 days"
            )
            continue

        if (expires_dt - scan_date).days > 90:
            validation_errors.append(
                f"{exc_id}: expires_at ({expires_dt.isoformat()}) is {(expires_dt - scan_date).days} days "
                f"from scan_date ({scan_date.isoformat()}); maximum allowed is 90 days"
            )
            continue

        norm_entry = dict(entry)
        norm_entry["id"] = exc_id
        norm_entry["tool"] = str(entry["tool"]).strip().lower()
        norm_entry["rule"] = str(entry["rule"]).strip()
        norm_entry["severity"] = str(entry.get("severity", "MEDIUM")).strip().upper()
        norm_entry["file"] = str(entry["file"]).replace("\\", "/").strip()
        norm_entry["created_at_date"] = created_dt
        norm_entry["expires_at_date"] = expires_dt
        by_id[exc_id] = norm_entry

    return by_id, expired_errors, validation_errors


def check_inline_suppressions(root: Path, valid_exceptions: dict[str, dict]) -> list[str]:
    """
    Scan source files for inline security suppressions (# nosec, # noqa: S*, gitleaks:allow,
    security @Suppress/@SuppressLint) and verify each cites a valid exception ID in security/exceptions.yaml.
    """
    errors: list[str] = []
    scan_roots = [
        root / "android",
        root / "backend",
        root / "training",
        root / "tools",
        root / ".github",
    ]
    skip_dirs = {
        ".git",
        ".gradle",
        ".idea",
        ".kotlin",
        ".pytest_cache",
        ".ruff_cache",
        ".venv",
        "__pycache__",
        "build",
        "secscan",
        "tests",
    }
    code_exts = {".py", ".kt", ".kts", ".java", ".sh", ".yml", ".yaml", ".xml"}

    for sroot in scan_roots:
        if not sroot.exists():
            continue
        for dirpath, dirnames, filenames in os.walk(sroot):
            dirnames[:] = [d for d in dirnames if d not in skip_dirs]
            for fname in filenames:
                fpath = Path(dirpath) / fname
                if fpath.suffix.lower() not in code_exts:
                    continue
                rel_path = fpath.relative_to(root).as_posix()
                try:
                    lines = fpath.read_text(encoding="utf-8", errors="replace").splitlines()
                except Exception:
                    continue
                for line_no, line in enumerate(lines, 1):
                    prev_line = lines[line_no - 2] if line_no >= 2 else ""
                    for kind, pat in INLINE_SUPPRESSION_PATTERNS:
                        if pat.search(line):
                            combined = f"{prev_line} {line}"
                            cited_ids = EXCEPTION_ID_RE.findall(combined)
                            if not cited_ids:
                                errors.append(
                                    f"{rel_path}:{line_no}: inline suppression ({kind}) does not cite an exception ID "
                                    f"(SEC-EXC-XXXX) from security/exceptions.yaml"
                                )
                            else:
                                for cid in cited_ids:
                                    if cid not in valid_exceptions:
                                        errors.append(
                                            f"{rel_path}:{line_no}: inline suppression ({kind}) cites unknown or expired "
                                            f"exception ID '{cid}'"
                                        )
    return errors


def _sarif_level_to_severity(level: str, tool_name: str) -> str:
    lvl = (level or "warning").lower()
    if tool_name.lower() == "gitleaks":
        return "CRITICAL"
    if lvl == "error":
        return "HIGH"
    if lvl == "warning":
        return "MEDIUM"
    return "LOW"


def collect_normalized_findings(root: Path, report_dir: Path) -> list[dict]:
    """Parse all SARIF and JSON outputs in report_dir into normalized finding dicts."""
    findings: list[dict] = []
    seen_keys: set[tuple[str, str, int]] = set()

    def add_finding(
        tool: str,
        rule: str,
        severity: str,
        file_path: str,
        line: int,
        message: str,
        package: str = "",
    ) -> None:
        norm_file = normalize_rel_path(file_path, root)
        sev = severity.upper()
        if sev not in SEVERITY_RANK:
            sev = "MEDIUM"
        dedup_key = (rule, norm_file, line)
        if dedup_key in seen_keys:
            return
        seen_keys.add(dedup_key)
        findings.append(
            {
                "tool": tool.lower(),
                "rule": rule,
                "severity": sev,
                "file": norm_file,
                "line": line,
                "package": package,
                "message": redact_secrets_in_text(message),
            }
        )

    # 1. SARIF files (gitleaks.sarif, android-lint.sarif, detekt.sarif, semgrep.sarif)
    for sarif_path in sorted(report_dir.glob("*.sarif")):
        try:
            data = json.loads(sarif_path.read_text(encoding="utf-8"))
        except Exception:
            continue
        for run in data.get("runs", []):
            driver_name = run.get("tool", {}).get("driver", {}).get("name", sarif_path.stem)
            tool_key = "gitleaks" if "gitleaks" in driver_name.lower() else (
                "android-lint" if "lint" in driver_name.lower() else driver_name.lower()
            )
            for res in run.get("results", []):
                rule_id = res.get("ruleId", "unknown-rule")
                sev = _sarif_level_to_severity(res.get("level", "warning"), tool_key)
                msg = res.get("message", {}).get("text", "")
                locs = res.get("locations", [])
                uri = ""
                line_no = 1
                if locs:
                    phys = locs[0].get("physicalLocation", {})
                    uri = phys.get("artifactLocation", {}).get("uri", "")
                    line_no = int(phys.get("region", {}).get("startLine", 1) or 1)
                add_finding(tool_key, rule_id, sev, uri, line_no, msg)

    # 2. osv-scanner.json and trivy.json
    for dep_file in ("trivy.json", "osv-scanner.json"):
        p = report_dir / dep_file
        if not p.exists():
            continue
        try:
            data = json.loads(p.read_text(encoding="utf-8"))
        except Exception:
            continue
        tool_name = data.get("tool", p.stem)
        for v in data.get("vulnerabilities", []):
            add_finding(
                tool=tool_name,
                rule=v.get("id", "UNKNOWN-CVE"),
                severity=v.get("severity", "HIGH"),
                file_path=v.get("file", ""),
                line=int(v.get("line", 1) or 1),
                message=v.get("summary", ""),
                package=v.get("package", ""),
            )
        for mis in data.get("misconfigurations", []):
            add_finding(
                tool=tool_name,
                rule=mis.get("id", "MISCONFIG"),
                severity=mis.get("severity", "HIGH"),
                file_path=mis.get("file", ""),
                line=int(mis.get("line", 1) or 1),
                message=mis.get("summary", ""),
            )

    # 3. bandit.json
    bandit_path = report_dir / "bandit.json"
    if bandit_path.exists():
        try:
            bdata = json.loads(bandit_path.read_text(encoding="utf-8"))
            for item in bdata.get("results", []):
                add_finding(
                    tool="bandit",
                    rule=item.get("test_id", "B000"),
                    severity=item.get("issue_severity", "MEDIUM"),
                    file_path=item.get("filename", ""),
                    line=int(item.get("line_number", 1) or 1),
                    message=item.get("issue_text", ""),
                )
        except Exception:
            pass

    # 4. ruff.json
    ruff_path = report_dir / "ruff.json"
    if ruff_path.exists():
        try:
            rdata = json.loads(ruff_path.read_text(encoding="utf-8"))
            for item in rdata.get("results", []):
                code = item.get("code", "S000")
                # Map Ruff S311 -> B311 equivalent if bandit already reported or match via alias
                loc = item.get("location", {})
                add_finding(
                    tool="ruff",
                    rule=code,
                    severity="MEDIUM" if code not in ("S311",) else "LOW",
                    file_path=item.get("filename", ""),
                    line=int(loc.get("row", 1) or 1),
                    message=item.get("message", ""),
                )
        except Exception:
            pass

    # 5. workflows.json, mobsf.json, schemathesis.json
    for extra_json in ("workflows.json", "mobsf.json", "schemathesis.json"):
        ep = report_dir / extra_json
        if not ep.exists():
            continue
        try:
            edata = json.loads(ep.read_text(encoding="utf-8"))
            tname = edata.get("tool", ep.stem)
            for f in edata.get("findings", []):
                add_finding(
                    tool=f.get("tool", tname),
                    rule=f.get("rule", "finding"),
                    severity=f.get("severity", "HIGH"),
                    file_path=f.get("file", ""),
                    line=int(f.get("line", 1) or 1),
                    message=f.get("message", ""),
                )
        except Exception:
            pass

    return findings


def match_exception(finding: dict, valid_exceptions: dict[str, dict]) -> dict | None:
    """Return the matching exception dict from security/exceptions.yaml if covered, else None."""
    f_tool = finding["tool"].lower()
    f_rule = finding["rule"]
    f_file = finding["file"].replace("\\", "/")

    tool_equivalents = {
        "trivy": {"trivy", "osv-scanner"},
        "osv-scanner": {"trivy", "osv-scanner"},
        "bandit": {"bandit", "ruff"},
        "ruff": {"bandit", "ruff"},
    }

    for exc in valid_exceptions.values():
        e_tool = exc["tool"].lower()
        allowed_tools = tool_equivalents.get(e_tool, {e_tool})
        if f_tool not in allowed_tools and e_tool != "*":
            continue

        e_rules = {exc["rule"]} | set(exc.get("aliases", []) or [])
        if f_rule not in e_rules:
            continue

        e_file = exc["file"].replace("\\", "/")
        # Allow gradle.lockfile exceptions on settings-gradle.lockfile to match module gradle.lockfile for same package
        if e_file == f_file or f_file.endswith("/" + e_file) or (
            exc.get("package") and exc.get("package") == finding.get("package")
        ):
            return exc

    return None


def generate_ledger_markdown(
    scan_date: date,
    mode: str,
    evaluated_findings: list[dict],
    valid_exceptions: dict[str, dict],
    expired_errors: list[str],
    validation_errors: list[str],
    inline_errors: list[str],
) -> str:
    """Generate reports/security/ledger.md with open findings by severity & age and fix deadlines."""
    lines: list[str] = [
        "# NeoGold Security-Debt & Vulnerability Ledger",
        "",
        f"- **Last Regenerated:** `{scan_date.isoformat()}`",
        f"- **Scan Mode:** `--{mode}`",
        "- **SLA Fix Deadlines (`docs/8_Security_Compliance_Plan.md` Section 10):** "
        "**Critical:** 7 days | **High:** 30 days | **Medium:** 90 days",
        "- **Exception Policy:** Max expiry 90 days (`security/exceptions.yaml`); inline suppressions must cite `SEC-EXC-XXXX`.",
        "",
    ]

    unaccepted = [f for f in evaluated_findings if not f.get("exception_id")]
    accepted = [f for f in evaluated_findings if f.get("exception_id")]

    counts = {sev: {"open": 0, "accepted": 0} for sev in ("CRITICAL", "HIGH", "MEDIUM", "LOW")}
    for f in evaluated_findings:
        sev = f["severity"]
        if f.get("exception_id"):
            counts[sev]["accepted"] += 1
        else:
            counts[sev]["open"] += 1

    lines.extend(
        [
            "## 1. Summary by Severity",
            "",
            "| Severity | Fix SLA Deadline | Unaccepted (Open) | Accepted (In `exceptions.yaml`) | Gate Behavior |",
            "| :--- | :--- | :---: | :---: | :--- |",
            f"| **CRITICAL** | 7 days | {counts['CRITICAL']['open']} | {counts['CRITICAL']['accepted']} | Fails scan + immediate incident stop (Doc 8 §12) |",
            f"| **HIGH** | 30 days | {counts['HIGH']['open']} | {counts['HIGH']['accepted']} | Fails scan unless in `security/exceptions.yaml` |",
            f"| **MEDIUM** | 90 days | {counts['MEDIUM']['open']} | {counts['MEDIUM']['accepted']} | Tracked in ledger with 90-day fix deadline |",
            f"| **LOW** | 90 days | {counts['LOW']['open']} | {counts['LOW']['accepted']} | Tracked in ledger |",
            "",
        ]
    )

    lines.extend(["## 2. Unaccepted Open Findings", ""])
    if not unaccepted:
        lines.append("_None. Zero unaccepted findings._")
        lines.append("")
    else:
        unaccepted_sorted = sorted(
            unaccepted,
            key=lambda x: (SEVERITY_RANK.get(x["severity"], 99), x["file"], x["rule"]),
        )
        lines.extend(
            [
                "| Severity | Tool | Rule | Location | Age (days) | Fix Deadline | Summary |",
                "| :--- | :--- | :--- | :--- | :---: | :---: | :--- |",
            ]
        )
        for f in unaccepted_sorted:
            sla_days = SLA_DAYS_BY_SEVERITY.get(f["severity"], 90)
            deadline = (scan_date + timedelta(days=sla_days)).isoformat()
            loc = f"`{f['file']}:{f['line']}`" if f.get("line") else f"`{f['file']}`"
            lines.append(
                f"| **{f['severity']}** | `{f['tool']}` | `{f['rule']}` | {loc} | 0d | `{deadline}` ({sla_days}d) | {f['message']} |"
            )
        lines.append("")

    lines.extend(["## 3. Accepted Security-Debt Ledger (`security/exceptions.yaml`)", ""])
    if not valid_exceptions:
        lines.append("_No active exceptions registered._")
        lines.append("")
    else:
        exc_sorted = sorted(
            valid_exceptions.values(),
            key=lambda e: (
                SEVERITY_RANK.get(e["severity"], 99),
                (e["created_at_date"] - scan_date).days,
                e["id"],
            ),
        )
        lines.extend(
            [
                "| Exception ID | Severity | Tool | Rule | File | Age (days) | Fix Deadline (Doc 8 §10) | Exception Expiry | Approver | Reason |",
                "| :--- | :--- | :--- | :--- | :--- | :---: | :---: | :---: | :--- | :--- |",
            ]
        )
        for exc in exc_sorted:
            created_dt: date = exc["created_at_date"]
            expires_dt: date = exc["expires_at_date"]
            age_days = max(0, (scan_date - created_dt).days)
            sla_days = SLA_DAYS_BY_SEVERITY.get(exc["severity"], 90)
            fix_deadline = (created_dt + timedelta(days=sla_days)).isoformat()
            lines.append(
                f"| `{exc['id']}` | **{exc['severity']}** | `{exc['tool']}` | `{exc['rule']}` | "
                f"`{exc['file']}` | {age_days}d | `{fix_deadline}` ({sla_days}d) | "
                f"`{expires_dt.isoformat()}` | `{exc['approver']}` | {exc['reason']} |"
            )
        lines.append("")

    lines.extend(["## 4. Policy & Guardrail Checks", ""])
    lines.append(f"- **Expired Exceptions:** {len(expired_errors)}")
    lines.append(f"- **Schema / >90-Day Expiry Violations:** {len(validation_errors)}")
    lines.append(f"- **Uncited Inline Suppressions:** {len(inline_errors)}")
    lines.append("")

    return "\n".join(lines)


def evaluate_scan(
    root: Path,
    report_dir: Path,
    exceptions_path: Path,
    ledger_path: Path,
    scan_date: date,
    mode: str = "changed",
) -> tuple[int, dict]:
    """
    Evaluate all scanner outputs in report_dir, write summary.json and ledger.md,
    and return (exit_code, summary_dict).
    """
    redact_report_directory(report_dir)

    valid_exceptions, expired_errors, validation_errors = load_and_validate_exceptions(
        exceptions_path, scan_date
    )
    inline_errors = check_inline_suppressions(root, valid_exceptions)
    findings = collect_normalized_findings(root, report_dir)

    evaluated_findings: list[dict] = []
    unaccepted_high_or_critical: list[dict] = []
    unaccepted_critical: list[dict] = []

    for f in findings:
        matched_exc = match_exception(f, valid_exceptions)
        item = dict(f)
        if matched_exc:
            item["exception_id"] = matched_exc["id"]
            item["expires_at"] = matched_exc["expires_at_date"].isoformat()
        else:
            item["exception_id"] = None
            if item["severity"] in ("CRITICAL", "HIGH"):
                unaccepted_high_or_critical.append(item)
            if item["severity"] == "CRITICAL":
                unaccepted_critical.append(item)
        evaluated_findings.append(item)

    ledger_md = generate_ledger_markdown(
        scan_date=scan_date,
        mode=mode,
        evaluated_findings=evaluated_findings,
        valid_exceptions=valid_exceptions,
        expired_errors=expired_errors,
        validation_errors=validation_errors,
        inline_errors=inline_errors,
    )
    ledger_path.parent.mkdir(parents=True, exist_ok=True)
    ledger_path.write_text(redact_secrets_in_text(ledger_md), encoding="utf-8")

    passed = (
        len(unaccepted_high_or_critical) == 0
        and len(expired_errors) == 0
        and len(validation_errors) == 0
        and len(inline_errors) == 0
    )

    summary = {
        "date": scan_date.isoformat(),
        "mode": mode,
        "passed": passed,
        "critical_incident_stop": len(unaccepted_critical) > 0,
        "counts": {
            "total_findings": len(evaluated_findings),
            "unaccepted_high_or_critical": len(unaccepted_high_or_critical),
            "unaccepted_critical": len(unaccepted_critical),
            "expired_exceptions": len(expired_errors),
            "invalid_exceptions": len(validation_errors),
            "unreferenced_inline_suppressions": len(inline_errors),
        },
        "findings": evaluated_findings,
        "unaccepted_high_or_critical": unaccepted_high_or_critical,
        "expired_exceptions": expired_errors,
        "invalid_exceptions": validation_errors,
        "unreferenced_inline_suppressions": inline_errors,
    }

    summary_path = report_dir / "summary.json"
    summary_path.write_text(redact_secrets_in_text(json.dumps(summary, indent=2)), encoding="utf-8")

    if unaccepted_critical:
        print(
            "CRITICAL SECURITY FINDING: STOP AND REPORT AT ONCE "
            "(docs/8_Security_Compliance_Plan.md Section 12 applies).",
            file=sys.stderr,
        )
    if unaccepted_high_or_critical:
        for uf in unaccepted_high_or_critical:
            print(
                f"FAIL [{uf['severity']}] {uf['tool']}:{uf['rule']} at {uf['file']}:{uf['line']} — {uf['message']}",
                file=sys.stderr,
            )
    for err in expired_errors:
        print(f"FAIL [EXPIRED EXCEPTION] {err}", file=sys.stderr)
    for err in validation_errors:
        print(f"FAIL [INVALID EXCEPTION] {err}", file=sys.stderr)
    for err in inline_errors:
        print(f"FAIL [INLINE SUPPRESSION] {err}", file=sys.stderr)

    return (0 if passed else 1), summary


def main() -> int:
    parser = argparse.ArgumentParser(description="Evaluate NeoGold security scan results and update ledger (P26)")
    parser.add_argument("--root", type=Path, default=REPO_ROOT)
    parser.add_argument("--report-dir", type=Path, required=True)
    parser.add_argument(
        "--exceptions",
        type=Path,
        default=REPO_ROOT / "security" / "exceptions.yaml",
    )
    parser.add_argument(
        "--ledger",
        type=Path,
        default=REPO_ROOT / "reports" / "security" / "ledger.md",
    )
    parser.add_argument("--date", default=date.today().isoformat())
    parser.add_argument("--mode", choices=["changed", "full"], default="changed")
    args = parser.parse_args()

    scan_dt = parse_iso_date(args.date)
    if not scan_dt:
        print(f"Invalid --date '{args.date}' (expected YYYY-MM-DD)", file=sys.stderr)
        return 2

    code, _ = evaluate_scan(
        root=args.root.resolve(),
        report_dir=args.report_dir.resolve(),
        exceptions_path=args.exceptions.resolve(),
        ledger_path=args.ledger.resolve(),
        scan_date=scan_dt,
        mode=args.mode,
    )
    return code


if __name__ == "__main__":
    sys.exit(main())

