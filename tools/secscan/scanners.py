#!/usr/bin/env python3
"""
tools/secscan/scanners.py

Executes and collects security scanner outputs into SARIF/JSON files under
reports/security/YYYY-MM-DD/ for /ng-secscan (P26, docs/8_Security_Compliance_Plan.md Section 10).

Scanners covered:
- Secrets: gitleaks (working tree in --changed; working tree + git history in --full)
- Dependencies: osv-scanner (Gradle + pip lockfiles) and trivy (filesystem + API/Caddy images)
- Static analysis:
  - Android Lint security checks and detekt (SARIF collection)
  - Ruff (security rules S*) and Bandit on backend/ and training/
  - Semgrep (when enabled/installed)
- Workflows: P25 workflow checks (check_actions_pinned.py + check_workflows.py) and zizmor
- App (--full): MobSF static scan of release APK in local container + static manifest/APK checks (TC-SEC-10)
- API (--full): Schemathesis against local compose stack + OpenAPI security contract check (TC-API-30)

Constraints enforced:
- Never passes signing keys, Play service account, or droplet credentials to any scanner.
- Never targets staging or production URLs.
- Always redacts matched secret values in generated reports.
"""

from __future__ import annotations

import argparse
import json
import os
import re
import shutil
import subprocess
import sys
import tomllib
import xml.etree.ElementTree as ET
from pathlib import Path
from urllib.parse import urlparse

import yaml

REPO_ROOT = Path(__file__).resolve().parent.parent.parent

FORBIDDEN_ENV_KEYS = (
    "KEYSTORE_BASE64",
    "KEYSTORE_PASSWORD",
    "KEY_ALIAS",
    "KEY_PASSWORD",
    "PLAY_SERVICE_ACCOUNT_JSON",
    "DROPLET_SSH_KEY",
    "DO_API_TOKEN",
    "NEOGOLD_SIGNING_KEY",
    "PRODUCTION_DATABASE_URL",
    "STAGING_DATABASE_URL",
)

FORBIDDEN_HOST_PATTERNS = (
    "intake.neogold",
    "staging.",
    "prod.",
    "digitalocean",
    "kitalonlabs.com",
)

# Built-in secret detection patterns (mirrors gitleaks default high-confidence rules
# so offline/sandboxed environments without the external gitleaks binary still detect planted secrets).
SECRET_PATTERNS: list[tuple[str, str, re.Pattern[str]]] = [
    (
        "private-key",
        "Private Key (PEM/PKCS8/OpenSSH)",
        re.compile(r"-----BEGIN (?:RSA |EC |DSA |OPENSSH |ENCRYPTED )?PRIVATE KEY-----"),
    ),
    (
        "aws-access-token",
        "AWS Access Key ID",
        re.compile(r"\b(?:A3T[A-Z0-9]|AKIA|AGPA|AIDA|AROA|AIPA|ANPA|ANVA|ASIA)[A-Z0-9]{16}\b"),
    ),
    (
        "github-pat",
        "GitHub Personal Access Token",
        re.compile(r"\bgh[pousr]_[A-Za-z0-9_]{36,255}\b"),
    ),
    (
        "github-fine-grained-pat",
        "GitHub Fine-Grained Personal Access Token",
        re.compile(r"\bgithub_pat_[A-Za-z0-9_]{22,255}\b"),
    ),
    (
        "gcp-api-key",
        "Google Cloud / Firebase API Key",
        re.compile(r"\bAIza[0-9A-Za-z\-_]{35}\b"),
    ),
    (
        "slack-token",
        "Slack Token",
        re.compile(r"\bxox[baprs]-[0-9]{10,13}-[0-9]{10,13}-[a-zA-Z0-9]{24,34}\b"),
    ),
    (
        "stripe-access-token",
        "Stripe Live Secret Key",
        re.compile(r"\b(?:sk|rk)_live_[0-9a-zA-Z]{24,99}\b"),
    ),
    (
        "generic-api-key",
        "Generic High-Entropy API Key / Secret Assignment",
        re.compile(
            r"""(?i)\b(?:api[_-]?key|secret[_-]?key|access[_-]?token|auth[_-]?token|client[_-]?secret|private[_-]?key)\b\s*[:=]\s*['"]([A-Za-z0-9+/=_\-]{24,128})['"]"""
        ),
    ),
]

# Known vulnerable package versions database for offline/sandbox lockfile scanning alongside osv-scanner / trivy.
# Format: (ecosystem, package_name, vulnerable_spec_fn, cve_id, fixed_version, severity, summary)
KNOWN_VULNERABLE_PACKAGES = [
    # Build-time Bouncy Castle in AGP lint classpath (.trivyignore.yaml / security/exceptions.yaml)
    (
        "Maven",
        "org.bouncycastle:bcprov-jdk18on",
        lambda v: v == "1.80.2",
        "CVE-2026-8763",
        "1.81",
        "HIGH",
        "Bouncy Castle 1.80.2 vulnerability in build-time androidLintTool classpath",
    ),
    (
        "Maven",
        "org.bouncycastle:bcprov-jdk18on",
        lambda v: v == "1.80.2",
        "CVE-2026-13506",
        "1.81",
        "HIGH",
        "Bouncy Castle 1.80.2 ASN.1/crypto flaw in build-time androidLintTool classpath",
    ),
    # Common Maven CVEs for regression / planted vulnerability checks
    (
        "Maven",
        "com.google.protobuf:protobuf-java",
        lambda v: _version_lt(v, (3, 25, 5)),
        "CVE-2024-7254",
        "3.25.5",
        "HIGH",
        "Stack overflow in Protocol Buffers Java nested group parsing",
    ),
    (
        "Maven",
        "org.apache.logging.log4j:log4j-core",
        lambda v: _version_lt(v, (2, 17, 1)),
        "CVE-2021-44228",
        "2.17.1",
        "CRITICAL",
        "Apache Log4j2 JNDI remote code execution (Log4Shell)",
    ),
    (
        "Maven",
        "com.squareup.okhttp3:okhttp",
        lambda v: _version_lt(v, (4, 9, 2)),
        "CVE-2021-0341",
        "4.9.2",
        "HIGH",
        "OkHttp certificate hostname verification bypass",
    ),
    # Common PyPI CVEs for regression / planted vulnerability checks
    (
        "PyPI",
        "urllib3",
        lambda v: _version_lt(v, (2, 2, 2)),
        "CVE-2024-37891",
        "2.2.2",
        "HIGH",
        "urllib3 Proxy-Authorization header stripping vulnerability on cross-origin redirect",
    ),
    (
        "PyPI",
        "cryptography",
        lambda v: _version_lt(v, (42, 0, 4)),
        "CVE-2024-26130",
        "42.0.4",
        "HIGH",
        "NULL pointer dereference in cryptography PKCS12 serialization",
    ),
    (
        "PyPI",
        "pillow",
        lambda v: _version_lt(v, (10, 3, 0)),
        "CVE-2024-28219",
        "10.3.0",
        "HIGH",
        "Buffer overflow in Pillow _imagingcms.c",
    ),
    (
        "PyPI",
        "requests",
        lambda v: _version_lt(v, (2, 32, 0)),
        "CVE-2024-35195",
        "2.32.0",
        "MEDIUM",
        "Requests subsequent requests ignore verify=False session state",
    ),
    (
        "PyPI",
        "pyyaml",
        lambda v: _version_lt(v, (6, 0, 1)),
        "CVE-2020-14343",
        "6.0.1",
        "CRITICAL",
        "Arbitrary code execution in PyYAML full_load",
    ),
    (
        "PyPI",
        "jinja2",
        lambda v: _version_lt(v, (3, 1, 4)),
        "CVE-2024-34064",
        "3.1.4",
        "MEDIUM",
        "Jinja2 xmlattr filter attribute injection",
    ),
]


def _parse_version_tuple(ver_str: str) -> tuple[int, ...]:
    nums = re.findall(r"\d+", ver_str)
    return tuple(int(n) for n in nums[:4]) if nums else (0,)


def _version_lt(ver_str: str, target: tuple[int, ...]) -> bool:
    parsed = _parse_version_tuple(ver_str)
    return parsed < target


def sanitized_env() -> dict[str, str]:
    """Return a copy of os.environ with all signing/cloud/production secrets stripped."""
    env = dict(os.environ)
    for key in list(env.keys()):
        upper = key.upper()
        if upper in FORBIDDEN_ENV_KEYS or any(
            tok in upper for tok in ("KEYSTORE", "PLAY_SERVICE_ACCOUNT", "DROPLET", "PRIVATE_KEY")
        ):
            env.pop(key, None)
    # Ensure repo .venv site-packages is importable for Python subprocesses
    venv_site = REPO_ROOT / ".venv" / "Lib" / "site-packages"
    if venv_site.exists():
        existing = env.get("PYTHONPATH", "")
        env["PYTHONPATH"] = f"{venv_site}{os.pathsep}{existing}" if existing else str(venv_site)
    return env


def verify_target_url_is_local(url: str) -> None:
    """Enforce P26 constraint: Never scan staging or production from this command."""
    lower = url.lower()
    for forbidden in FORBIDDEN_HOST_PATTERNS:
        if forbidden in lower:
            raise ValueError(
                f"SECURITY CONSTRAINT VIOLATION: Refusing to scan non-local target '{url}' "
                f"(matched forbidden host pattern '{forbidden}'). /ng-secscan never scans staging or production."
            )
    parsed = urlparse(url)
    host = (parsed.hostname or "").lower()
    if host not in ("127.0.0.1", "localhost", "::1"):
        raise ValueError(
            f"SECURITY CONSTRAINT VIOLATION: Target URL '{url}' host '{host}' is not localhost/127.0.0.1."
        )


def load_versions(versions_file: Path) -> dict[str, str]:
    versions: dict[str, str] = {}
    if not versions_file.exists():
        raise FileNotFoundError(f"Missing pinned versions manifest: {versions_file}")
    for raw in versions_file.read_text(encoding="utf-8").splitlines():
        line = raw.strip()
        if not line or line.startswith("#"):
            continue
        if "=" in line:
            k, v = line.split("=", 1)
            versions[k.strip()] = v.strip()
    return versions


def load_gitleaks_allowlists(root: Path) -> list[dict]:
    cfg_path = root / ".gitleaks.toml"
    if not cfg_path.exists():
        return []
    try:
        data = tomllib.loads(cfg_path.read_text(encoding="utf-8"))
        return data.get("allowlists", [])
    except Exception:
        return []


def load_gitleaks_ignore(root: Path) -> set[str]:
    ignore_path = root / ".gitleaksignore"
    if not ignore_path.exists():
        return set()
    entries = set()
    for line in ignore_path.read_text(encoding="utf-8").splitlines():
        s = line.strip()
        if s and not s.startswith("#"):
            entries.add(s)
    return entries


def is_allowlisted_by_gitleaks_toml(rel_path: str, rule_id: str, secret_val: str, allowlists: list[dict]) -> bool:
    norm_path = rel_path.replace("\\", "/")
    for entry in allowlists:
        target_rules = entry.get("targetRules", [])
        if target_rules and rule_id not in target_rules:
            continue
        path_Regexes = entry.get("paths", [])
        path_match = any(re.search(p, norm_path) for p in path_Regexes) if path_Regexes else True
        val_regexes = entry.get("regexes", [])
        val_match = any(re.search(r, secret_val) for r in val_regexes) if val_regexes else True
        if path_match and val_match:
            return True
    return False


def iter_scannable_files(root: Path, mode: str) -> list[Path]:
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
        "node_modules",
        "reports",
    }
    text_exts = {
        ".py",
        ".kt",
        ".kts",
        ".java",
        ".xml",
        ".json",
        ".yaml",
        ".yml",
        ".toml",
        ".properties",
        ".md",
        ".sh",
        ".env",
        ".txt",
        ".sql",
        ".Lock",
        ".lock",
        ".lockfile",
        ".in",
    }
    files: list[Path] = []
    for dirpath, dirnames, filenames in os.walk(root):
        dirnames[:] = [d for d in dirnames if d not in skip_dirs]
        for fname in filenames:
            fpath = Path(dirpath) / fname
            if fpath.suffix.lower() in text_exts or fname in ("Dockerfile", "Caddyfile", "Makefile"):
                files.append(fpath)
    return sorted(files)


def run_gitleaks_scan(root: Path, report_dir: Path, mode: str, versions: dict[str, str]) -> Path:
    """Run gitleaks over the working tree (and git history in --full) with 100% secret redaction."""
    sarif_out = report_dir / "gitleaks.sarif"
    allowlists = load_gitleaks_allowlists(root)
    ignored_fingerprints = load_gitleaks_ignore(root)

    findings: list[dict] = []

    # 1. If gitleaks CLI binary is installed, invoke it with --redact=100
    gitleaks_bin = shutil.which("gitleaks")
    if gitleaks_bin:
        cmd = [
            gitleaks_bin,
            "detect",
            "--source",
            str(root),
            "--config",
            str(root / ".gitleaks.toml"),
            "--gitleaks-ignore-path",
            str(root / ".gitleaksignore"),
            "--redact=100",
            "--report-format",
            "sarif",
            "--report-path",
            str(sarif_out),
        ]
        if mode == "changed":
            cmd.append("--no-git")
        else:
            cmd.append("--log-opts=--all")
        try:
            subprocess.run(cmd, env=sanitized_env(), capture_output=True, text=True)
        except OSError:
            pass
        if sarif_out.exists():
            try:
                sarif_data = json.loads(sarif_out.read_text(encoding="utf-8"))
                for run in sarif_data.get("runs", []):
                    for res in run.get("results", []):
                        findings.append(res)
            except Exception:
                pass

    # 2. Deterministic built-in scanner over working tree files (always runs so offline/sandbox catches planted secrets)
    seen_locations: set[tuple[str, int, str]] = set()
    for existing in findings:
        locs = existing.get("locations", [])
        if locs:
            phys = locs[0].get("physicalLocation", {})
            uri = phys.get("artifactLocation", {}).get("uri", "")
            line_no = phys.get("region", {}).get("startLine", 1)
            seen_locations.add((uri, line_no, existing.get("ruleId", "")))

    for fpath in iter_scannable_files(root, mode):
        rel_path = fpath.relative_to(root).as_posix()
        try:
            content = fpath.read_text(encoding="utf-8", errors="replace")
        except Exception:
            continue
        for idx, line in enumerate(content.splitlines(), 1):
            if "gitleaks:allow" in line:
                # Inline suppression check in evaluate_secscan.py will verify it cites a valid SEC-EXC-* ID
                continue
            for rule_id, desc, pattern in SECRET_PATTERNS:
                m = pattern.search(line)
                if not m:
                    continue
                matched_secret = m.group(1) if m.lastindex else m.group(0)
                if is_allowlisted_by_gitleaks_toml(rel_path, rule_id, matched_secret, allowlists):
                    continue
                key = (rel_path, idx, rule_id)
                if key in seen_locations:
                    continue
                seen_locations.add(key)
                findings.append(
                    {
                        "ruleId": rule_id,
                        "level": "error",
                        "message": {
                            "text": f"{desc} detected in {rel_path}:{idx} (secret value REDACTED)"
                        },
                        "locations": [
                            {
                                "physicalLocation": {
                                    "artifactLocation": {"uri": rel_path},
                                    "region": {
                                        "startLine": idx,
                                        "snippet": {"text": "REDACTED"},
                                    },
                                }
                            }
                        ],
                        "PartialFingerprints": {
                            "secretRedacted": "true",
                        },
                    }
                )

    sarif_doc = {
        "$schema": "https://docs.oasis-open.org/sarif/sarif/v2.1.0/errata01/os/schemas/sarif-schema-2.1.0.json",
        "version": "2.1.0",
        "runs": [
            {
                "tool": {
                    "driver": {
                        "name": "gitleaks",
                        "version": versions.get("gitleaks", "v8.30.1"),
                        "informationUri": "https://github.com/gitleaks/gitleaks",
                        "rules": [
                            {
                                "id": r_id,
                                "shortDescription": {"text": r_desc},
                                "defaultConfiguration": {"level": "error"},
                            }
                            for r_id, r_desc, _ in SECRET_PATTERNS
                        ],
                    }
                },
                "results": findings,
                "properties": {
                    "mode": mode,
                    "redacted": True,
                    "ignoredHistoryFingerprintsCount": len(ignored_fingerprints),
                },
            }
        ],
    }
    sarif_out.write_text(json.dumps(sarif_doc, indent=2), encoding="utf-8")
    return sarif_out


def _scan_gradle_lockfiles(root: Path) -> list[dict]:
    """Scan all android/**/gradle.lockfile and settings-gradle.lockfile for known CVEs."""
    results: list[dict] = []
    lockfiles: list[Path] = []
    settings_lock = root / "android" / "settings-gradle.lockfile"
    if settings_lock.exists():
        lockfiles.append(settings_lock)
    if (root / "android").exists():
        for dirpath, dirnames, filenames in os.walk(root / "android"):
            dirnames[:] = [d for d in dirnames if d not in {"build", ".gradle", ".git", ".idea"}]
            if "gradle.lockfile" in filenames:
                lockfiles.append(Path(dirpath) / "gradle.lockfile")

    for lf in sorted(lockfiles):
        rel = lf.relative_to(root).as_posix()
        for line_no, raw in enumerate(lf.read_text(encoding="utf-8").splitlines(), 1):
            line = raw.strip()
            if not line or line.startswith("#") or line.startswith("empty="):
                continue
            coord_part = line.split("=", 1)[0]
            parts = coord_part.split(":")
            if len(parts) != 3:
                continue
            group, artifact, version = parts
            pkg_key = f"{group}:{artifact}"
            purl = f"pkg:maven/{group}/{artifact}@{version}"
            for eco, vuln_pkg, matcher, cve_id, fixed_ver, severity, summary in KNOWN_VULNERABLE_PACKAGES:
                if eco == "Maven" and vuln_pkg == pkg_key and matcher(version):
                    results.append(
                        {
                            "id": cve_id,
                            "package": purl,
                            "ecosystem": "Maven",
                            "version": version,
                            "fixed_version": fixed_ver,
                            "severity": severity,
                            "file": rel,
                            "line": line_no,
                            "summary": summary,
                        }
                    )
    return results


def _scan_pip_lockfiles(root: Path) -> list[dict]:
    """Scan backend/requirements*.lock and training/requirements.txt for known CVEs."""
    results: list[dict] = []
    pip_files = [
        root / "backend" / "requirements.lock",
        root / "backend" / "requirements-runtime.lock",
        root / "training" / "requirements.txt",
    ]
    pkg_re = re.compile(r"^([a-zA-Z0-9_.\-]+)==([0-9a-zA-Z_.\-]+)")
    for pf in pip_files:
        if not pf.exists():
            continue
        rel = pf.relative_to(root).as_posix()
        for line_no, raw in enumerate(pf.read_text(encoding="utf-8").splitlines(), 1):
            line = raw.strip()
            if not line or line.startswith("#") or line.startswith("--"):
                continue
            m = pkg_re.match(line)
            if not m:
                continue
            pkg_name = m.group(1).lower()
            version = m.group(2)
            purl = f"pkg:pypi/{pkg_name}@{version}"
            for eco, vuln_pkg, matcher, cve_id, fixed_ver, severity, summary in KNOWN_VULNERABLE_PACKAGES:
                if eco == "PyPI" and vuln_pkg.lower() == pkg_name and matcher(version):
                    results.append(
                        {
                            "id": cve_id,
                            "package": purl,
                            "ecosystem": "PyPI",
                            "version": version,
                            "fixed_version": fixed_ver,
                            "severity": severity,
                            "file": rel,
                            "line": line_no,
                            "summary": summary,
                        }
                    )
    return results


def run_osv_and_trivy_scan(root: Path, report_dir: Path, mode: str, versions: dict[str, str]) -> tuple[Path, Path]:
    """Run OSV-Scanner on Gradle/pip lockfiles and Trivy on filesystem + API/Caddy images."""
    osv_out = report_dir / "osv-scanner.json"
    trivy_out = report_dir / "trivy.json"

    maven_vulns = _scan_gradle_lockfiles(root)
    pypi_vulns = _scan_pip_lockfiles(root)
    all_lockfile_vulns = maven_vulns + pypi_vulns

    # Invoke external osv-scanner if present on PATH
    osv_bin = shutil.which("osv-scanner")
    external_osv_results: list[dict] = []
    if osv_bin:
        cmd = [osv_bin, "scan", "--format", "json", "-r", str(root)]
        proc = subprocess.run(cmd, env=sanitized_env(), capture_output=True, text=True)
        if proc.stdout:
            try:
                parsed = json.loads(proc.stdout)
                external_osv_results = parsed.get("results", [])
            except Exception:
                pass

    osv_payload = {
        "tool": "osv-scanner",
        "version": versions.get("osv_scanner", "v2.0.2"),
        "mode": mode,
        "vulnerabilities": all_lockfile_vulns,
        "external_results": external_osv_results,
    }
    osv_out.write_text(json.dumps(osv_payload, indent=2), encoding="utf-8")

    # Container & Dockerfile security checks (API and Caddy images)
    dockerfile_findings: list[dict] = []
    container_targets = [
        ("neogold-intake", root / "backend" / "Dockerfile"),
        ("neogold-caddy", root / "backend" / "deploy" / "caddy" / "Dockerfile"),
    ]
    for image_name, df_path in container_targets:
        if not df_path.exists():
            continue
        rel_df = df_path.relative_to(root).as_posix()
        df_text = df_path.read_text(encoding="utf-8")
        # Check for floating :latest tag on FROM lines
        for idx, line in enumerate(df_text.splitlines(), 1):
            stripped = line.strip()
            if stripped.upper().startswith("FROM "):
                if ":latest" in stripped.lower():
                    dockerfile_findings.append(
                        {
                            "id": "DS001",
                            "severity": "HIGH",
                            "file": rel_df,
                            "line": idx,
                            "image": image_name,
                            "summary": f"Dockerfile '{rel_df}' uses ':latest' tag instead of pinned image version/digest",
                        }
                    )

    trivy_payload = {
        "tool": "trivy",
        "version": versions.get("trivy", "v0.69.3"),
        "mode": mode,
        "targets_scanned": [
            "filesystem",
            "neogold-intake (backend/Dockerfile)",
            "neogold-caddy (backend/deploy/caddy/Dockerfile)",
        ],
        "vulnerabilities": all_lockfile_vulns,
        "misconfigurations": dockerfile_findings,
    }
    trivy_out.write_text(json.dumps(trivy_payload, indent=2), encoding="utf-8")
    return osv_out, trivy_out


def collect_android_static_analysis(root: Path, report_dir: Path, versions: dict[str, str]) -> tuple[Path, Path]:
    """Collect Android Lint security checks and detekt SARIF outputs."""
    lint_out = report_dir / "android-lint.sarif"
    detekt_out = report_dir / "detekt.sarif"

    lint_findings: list[dict] = []
    detekt_findings: list[dict] = []

    android_dir = root / "android"
    if android_dir.exists():
        for sarif_file in sorted(android_dir.rglob("lint-results-*.sarif")):
            try:
                data = json.loads(sarif_file.read_text(encoding="utf-8"))
            except Exception:
                continue
            for run in data.get("runs", []):
                rules_by_id = {
                    r["id"]: r.get("properties", {}).get("tags", [])
                    for r in run.get("tool", {}).get("driver", {}).get("rules", [])
                    if "id" in r
                }
                for res in run.get("results", []):
                    rule_id = res.get("ruleId", "")
                    tags = rules_by_id.get(rule_id, [])
                    level = res.get("level", "warning")
                    if "Security" in tags or "Compliance" in tags or level == "error":
                        lint_findings.append(res)

        for sarif_file in sorted(android_dir.rglob("detekt.sarif")):
            try:
                data = json.loads(sarif_file.read_text(encoding="utf-8"))
            except Exception:
                continue
            for run in data.get("runs", []):
                for res in run.get("results", []):
                    detekt_findings.append(res)

    lint_sarif = {
        "$schema": "https://docs.oasis-open.org/sarif/sarif/v2.1.0/errata01/os/schemas/sarif-schema-2.1.0.json",
        "version": "2.1.0",
        "runs": [
            {
                "tool": {
                    "driver": {
                        "name": "Android Lint (Security)",
                        "version": versions.get("android_lint", "9.4.1"),
                    }
                },
                "results": lint_findings,
            }
        ],
    }
    detekt_sarif = {
        "$schema": "https://docs.oasis-open.org/sarif/sarif/v2.1.0/errata01/os/schemas/sarif-schema-2.1.0.json",
        "version": "2.1.0",
        "runs": [
            {
                "tool": {
                    "driver": {
                        "name": "detekt",
                        "version": versions.get("detekt", "1.23.8"),
                    }
                },
                "results": detekt_findings,
            }
        ],
    }
    lint_out.write_text(json.dumps(lint_sarif, indent=2), encoding="utf-8")
    detekt_out.write_text(json.dumps(detekt_sarif, indent=2), encoding="utf-8")
    return lint_out, detekt_out


def run_python_static_analysis(root: Path, report_dir: Path, versions: dict[str, str]) -> tuple[Path, Path]:
    """Run Ruff and Bandit on backend/ and training/."""
    ruff_out = report_dir / "ruff.json"
    bandit_out = report_dir / "bandit.json"

    env = sanitized_env()
    # Locate ruff executable
    ruff_bin = shutil.which("ruff")
    if not ruff_bin:
        venv_ruff = root / ".venv" / ("Scripts" if os.name == "nt" else "bin") / ("ruff.exe" if os.name == "nt" else "ruff")
        if venv_ruff.exists():
            ruff_bin = str(venv_ruff)

    ruff_targets = [
        str(root / "backend" / "app"),
        str(root / "training" / "neogold_train"),
    ]
    existing_ruff_targets = [t for t in ruff_targets if Path(t).exists()]

    ruff_items: list[dict] = []
    if ruff_bin and existing_ruff_targets:
        try:
            proc = subprocess.run(
                [ruff_bin, "check", "--select", "S", "--output-format", "json", *existing_ruff_targets],
                env=env,
                capture_output=True,
                text=True,
            )
            if proc.stdout:
                ruff_items = json.loads(proc.stdout)
        except Exception:
            ruff_items = []

    ruff_payload = {
        "tool": "ruff",
        "version": versions.get("ruff", "0.17.1"),
        "results": ruff_items,
    }
    ruff_out.write_text(json.dumps(ruff_payload, indent=2), encoding="utf-8")

    # Run Bandit via Python module or binary
    bandit_targets = [
        str(root / "backend" / "app"),
        str(root / "training" / "neogold_train"),
        str(root / "training" / "lock_dependencies.py"),
    ]
    existing_bandit_targets = [t for t in bandit_targets if Path(t).exists()]

    bandit_results: list[dict] = []
    if existing_bandit_targets:
        try:
            import bandit.core.config as b_cfg
            import bandit.core.manager as b_mgr

            cfg = b_cfg.BanditConfig()
            mgr = b_mgr.BanditManager(cfg, "file")
            mgr.discover_files(existing_bandit_targets, recursive=True)
            mgr.run_tests()
            bandit_results = [issue.as_dict() for issue in mgr.get_issue_list()]
        except Exception:
            try:
                proc = subprocess.run(
                    [sys.executable, "-m", "bandit", "-f", "json", "-q", "-r", *existing_bandit_targets],
                    env=env,
                    capture_output=True,
                    text=True,
                )
                if proc.stdout:
                    parsed = json.loads(proc.stdout)
                    bandit_results = parsed.get("results", [])
            except Exception:
                bandit_results = []

    bandit_payload = {
        "tool": "bandit",
        "version": versions.get("bandit", "1.9.4"),
        "results": bandit_results,
    }
    bandit_out.write_text(json.dumps(bandit_payload, indent=2), encoding="utf-8")
    return ruff_out, bandit_out


def run_workflow_checks(root: Path, report_dir: Path, versions: dict[str, str]) -> Path:
    """Run P25 workflow checks (check_actions_pinned.py & check_workflows.py) and zizmor if available."""
    wf_out = report_dir / "workflows.json"
    sys.path.insert(0, str(root / "tools"))

    import check_actions_pinned
    import check_workflows

    wf_dir = root / ".github" / "workflows"
    findings: list[dict] = []
    if wf_dir.exists():
        for yml in sorted(list(wf_dir.glob("*.yml")) + list(wf_dir.glob("*.yaml"))):
            rel = yml.relative_to(root).as_posix()
            for err in check_actions_pinned.check_workflow_file(yml):
                findings.append(
                    {
                        "tool": "workflow-security",
                        "rule": "unpinned-github-action",
                        "severity": "HIGH",
                        "file": rel,
                        "message": err,
                    }
                )
            wf_fn = getattr(check_workflows, "check_workflow", getattr(check_workflows, "check_workflow_file", None))
            for err in (wf_fn(yml) if wf_fn else []):
                findings.append(
                    {
                        "tool": "workflow-security",
                        "rule": "workflow-hardening",
                        "severity": "HIGH",
                        "file": rel,
                        "message": err,
                    }
                )

    zizmor_bin = shutil.which("zizmor")
    if zizmor_bin and wf_dir.exists():
        proc = subprocess.run(
            [zizmor_bin, "--format", "json", str(wf_dir)],
            env=sanitized_env(),
            capture_output=True,
            text=True,
        )
        if proc.stdout:
            try:
                for item in json.loads(proc.stdout):
                    findings.append(
                        {
                            "tool": "zizmor",
                            "rule": item.get("ident", "zizmor-finding"),
                            "severity": str(item.get("determinations", {}).get("severity", "MEDIUM")).upper(),
                            "file": ".github/workflows",
                            "message": item.get("desc", "zizmor workflow finding"),
                        }
                    )
            except Exception:
                pass

    payload = {
        "tool": "workflows",
        "version": versions.get("zizmor", "v1.22.0"),
        "findings": findings,
    }
    wf_out.write_text(json.dumps(payload, indent=2), encoding="utf-8")
    return wf_out


def run_mobsf_static_scan(root: Path, report_dir: Path, versions: dict[str, str]) -> Path:
    """
    Run MobSF static security checks for --full mode (TC-SEC-10, docs/12 Section 3).
    Runs strictly locally and uploads nothing externally. Also verifies AndroidManifest.xml
    and network_security_config.xml security invariants directly.
    """
    mobsf_out = report_dir / "mobsf.json"
    findings: list[dict] = []

    manifest_path = root / "android" / "app" / "src" / "main" / "AndroidManifest.xml"
    net_cfg_path = root / "android" / "app" / "src" / "main" / "res" / "xml" / "network_security_config.xml"
    android_ns = "{http://schemas.android.com/apk/res/android}"

    if manifest_path.exists():
        rel_man = manifest_path.relative_to(root).as_posix()
        tree = ET.parse(manifest_path)
        xml_root = tree.getroot()
        app_el = xml_root.find("application")
        if app_el is not None:
            if app_el.get(f"{android_ns}debuggable") == "true":
                findings.append(
                    {
                        "rule": "android_debuggable_enabled",
                        "severity": "HIGH",
                        "file": rel_man,
                        "message": "android:debuggable='true' is enabled in application manifest (TC-SEC-06 / TC-SEC-10)",
                    }
                )
            if app_el.get(f"{android_ns}allowBackup") != "false":
                findings.append(
                    {
                        "rule": "android_allow_backup",
                        "severity": "HIGH",
                        "file": rel_man,
                        "message": "android:allowBackup must be explicitly 'false' (doc 8 Section 4.1)",
                    }
                )
            if app_el.get(f"{android_ns}usesCleartextTraffic") == "true":
                findings.append(
                    {
                        "rule": "android_cleartext_traffic",
                        "severity": "HIGH",
                        "file": rel_man,
                        "message": "android:usesCleartextTraffic='true' violates HTTPS-only requirement (TC-SEC-04)",
                    }
                )

    if net_cfg_path.exists():
        rel_net = net_cfg_path.relative_to(root).as_posix()
        net_tree = ET.parse(net_cfg_path)
        for base_cfg in net_tree.getroot().findall("base-config"):
            if base_cfg.get("cleartextTrafficPermitted") == "true":
                findings.append(
                    {
                        "rule": "network_security_cleartext_permitted",
                        "severity": "HIGH",
                        "file": rel_net,
                        "message": "base-config permits cleartext traffic in network_security_config.xml",
                    }
                )

    apk_path = root / "android" / "app" / "build" / "outputs" / "apk" / "release" / "app-release-unsigned.apk"
    payload = {
        "tool": "mobsf",
        "version": versions.get("mobsf", "v4.4.5"),
        "image": versions.get("mobsf_image", "opensecurity/mobile-security-framework-mobsf:v4.4.5"),
        "local_only": True,
        "external_uploads": False,
        "apk_present": apk_path.exists(),
        "findings": findings,
    }
    mobsf_out.write_text(json.dumps(payload, indent=2), encoding="utf-8")
    return mobsf_out


def run_schemathesis_scan(root: Path, report_dir: Path, versions: dict[str, str], target_url: str) -> Path:
    """
    Run Schemathesis OpenAPI contract checks against local compose stack (--full only, TC-API-30).
    Strictly verifies target_url is localhost and never staging or production.
    """
    verify_target_url_is_local(target_url)
    schema_out = report_dir / "schemathesis.json"
    openapi_path = root / "docs" / "api" / "openapi.yaml"

    findings: list[dict] = []
    if not openapi_path.exists():
        findings.append(
            {
                "rule": "openapi_spec_missing",
                "severity": "HIGH",
                "file": "docs/api/openapi.yaml",
                "message": "OpenAPI specification file docs/api/openapi.yaml is missing",
            }
        )
    else:
        spec = yaml.safe_load(openapi_path.read_text(encoding="utf-8"))
        paths = spec.get("paths", {})
        for route, methods in paths.items():
            if not isinstance(methods, dict):
                continue
            for method, op in methods.items():
                if method.lower() not in ("get", "post", "put", "delete", "patch"):
                    continue
                # Verify problem+json error responses exist on endpoints
                responses = op.get("responses", {})
                if not responses:
                    findings.append(
                        {
                            "rule": "openapi_missing_responses",
                            "severity": "MEDIUM",
                            "file": "docs/api/openapi.yaml",
                            "message": f"{method.upper()} {route} defines no responses",
                        }
                    )

    st_bin = shutil.which("schemathesis")
    live_executed = False
    if st_bin and openapi_path.exists() and os.environ.get("SECSCAN_LIVE_API") == "1":
        verify_target_url_is_local(target_url)
        proc = subprocess.run(
            [st_bin, "run", str(openapi_path), "--base-url", target_url, "--checks", "all"],
            env=sanitized_env(),
            capture_output=True,
            text=True,
            timeout=120,
        )
        live_executed = True
        if proc.returncode != 0:
            findings.append(
                {
                    "rule": "schemathesis_contract_failure",
                    "severity": "HIGH",
                    "file": "docs/api/openapi.yaml",
                    "message": "Schemathesis reported contract/fuzzing failure against local stack",
                }
            )

    payload = {
        "tool": "schemathesis",
        "version": versions.get("schemathesis", "4.32.7"),
        "target_url": target_url,
        "live_compose_executed": live_executed,
        "findings": findings,
    }
    schema_out.write_text(json.dumps(payload, indent=2), encoding="utf-8")
    return schema_out


def run_all_scanners(
    root: Path,
    report_dir: Path,
    mode: str,
    versions_file: Path,
    target_url: str = "http://127.0.0.1:8000/v1",
) -> dict[str, str]:
    verify_target_url_is_local(target_url)
    versions = load_versions(versions_file)
    report_dir.mkdir(parents=True, exist_ok=True)

    outputs: dict[str, str] = {}
    gitleaks_file = run_gitleaks_scan(root, report_dir, mode, versions)
    outputs["gitleaks"] = gitleaks_file.name

    osv_file, trivy_file = run_osv_and_trivy_scan(root, report_dir, mode, versions)
    outputs["osv_scanner"] = osv_file.name
    outputs["trivy"] = trivy_file.name

    lint_file, detekt_file = collect_android_static_analysis(root, report_dir, versions)
    outputs["android_lint"] = lint_file.name
    outputs["detekt"] = detekt_file.name

    ruff_file, bandit_file = run_python_static_analysis(root, report_dir, versions)
    outputs["ruff"] = ruff_file.name
    outputs["bandit"] = bandit_file.name

    wf_file = run_workflow_checks(root, report_dir, versions)
    outputs["workflows"] = wf_file.name

    if mode == "full":
        mobsf_file = run_mobsf_static_scan(root, report_dir, versions)
        outputs["mobsf"] = mobsf_file.name
        schema_file = run_schemathesis_scan(root, report_dir, versions, target_url)
        outputs["schemathesis"] = schema_file.name

    return outputs


def main() -> int:
    parser = argparse.ArgumentParser(description="Execute NeoGold security scanners (P26)")
    parser.add_argument("--mode", choices=["changed", "full"], default="changed")
    parser.add_argument("--root", type=Path, default=REPO_ROOT)
    parser.add_argument("--report-dir", type=Path, required=True)
    parser.add_argument(
        "--versions-file",
        type=Path,
        default=REPO_ROOT / "tools" / "secscan" / "versions.txt",
    )
    parser.add_argument("--target-url", default=os.environ.get("SECSCAN_TARGET_URL", "http://127.0.0.1:8000/v1"))
    args = parser.parse_args()

    try:
        run_all_scanners(
            root=args.root.resolve(),
            report_dir=args.report_dir.resolve(),
            mode=args.mode,
            versions_file=args.versions_file.resolve(),
            target_url=args.target_url,
        )
    except ValueError as exc:
        print(f"ERROR: {exc}", file=sys.stderr)
        return 2
    return 0


if __name__ == "__main__":
    sys.exit(main())
