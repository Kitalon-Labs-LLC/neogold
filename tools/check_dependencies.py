#!/usr/bin/env python3
"""
tools/check_dependencies.py

Enforces P25 / ADR dependency rule:
1. No new dependency without an ADR (checked against quality/dependency-allowlist.txt).
   Covers resolved Gradle runtime classpath per module and pip lockfile.
2. HTTP client isolation (P01 / Doc 8 Section 6.1):
   Only :sync:research (and :app aggregator) may have HTTP client dependencies in their runtime classpath.
   Engines, core modules, and screening feature modules must never have an HTTP client.
"""

import glob
import os
import re
import sys
from pathlib import Path

HTTP_CLIENT_PATTERNS = ("okhttp", "ktor", "volley", "httpclient", "httpcomponents")
RUNTIME_CONF_SUBSTRINGS = ("runtimeclasspath",)


def load_allowlist(allowlist_path: Path) -> set[str]:
    allowed = set()
    if not allowlist_path.exists():
        return allowed
    for line in allowlist_path.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if not line or line.startswith("#"):
            continue
        allowed.add(line)
    return allowed


def is_runtime_entry(configurations_str: str) -> bool:
    confs = [c.strip().lower() for c in configurations_str.split(",")]
    return any(any(rc in c for rc in RUNTIME_CONF_SUBSTRINGS) for c in confs)


def check_gradle_dependencies(repo_root: Path, allowlist: set[str]) -> list[str]:
    violations = []
    lockfiles = sorted(repo_root.glob("android/**/gradle.lockfile")) + sorted(repo_root.glob("tools/**/gradle.lockfile"))

    for lockfile in lockfiles:
        module_path = lockfile.parent.relative_to(repo_root).as_posix()
        is_sync_research = "sync/research" in module_path
        is_app = module_path == "android/app"

        for line in lockfile.read_text(encoding="utf-8").splitlines():
            line = line.strip()
            if not line or line.startswith("#"):
                continue
            parts = line.split("=")
            if len(parts) < 2:
                continue
            dep_coord = parts[0]
            confs_str = parts[1]

            coord_parts = dep_coord.split(":")
            if len(coord_parts) < 2:
                continue
            group, artifact = coord_parts[0], coord_parts[1]
            dep_id = f"gradle:{group}:{artifact}"

            # Only check allowlist for runtime dependencies (or all resolved dependencies in lockfile)
            if dep_id not in allowlist:
                violations.append(f"Unapproved Gradle dependency in {module_path}: {dep_id} (missing from quality/dependency-allowlist.txt)")

            # Check HTTP client isolation on runtime classpath
            if is_runtime_entry(confs_str):
                group_lower = group.lower()
                artifact_lower = artifact.lower()
                for pattern in HTTP_CLIENT_PATTERNS:
                    if pattern in group_lower or pattern in artifact_lower:
                        if not (is_sync_research or is_app):
                            violations.append(
                                f"HTTP client violation in {module_path}: {group}:{artifact} found in runtime classpath, "
                                f"but only :sync:research may depend on HTTP clients (P01 / Doc 8 Section 6.1)"
                            )
                        break
    return violations


def check_pip_dependencies(repo_root: Path, allowlist: set[str]) -> list[str]:
    violations = []
    lockfile = repo_root / "backend" / "requirements.lock"
    if not lockfile.exists():
        return violations

    for line in lockfile.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if not line or line.startswith("#"):
            continue
        m = re.match(r"^([a-zA-Z0-9_\-]+)", line)
        if m:
            pkg_name = m.group(1).lower()
            dep_id = f"pip:{pkg_name}"
            if dep_id not in allowlist:
                violations.append(f"Unapproved pip dependency in backend/requirements.lock: {dep_id}")
    return violations


def check_npm_dependencies(repo_root: Path, allowlist: set[str]) -> list[str]:
    violations = []
    npm_locks = list(repo_root.glob("**/package-lock.json"))
    for lock in npm_locks:
        if "node_modules" in lock.as_posix():
            continue
        # No npm in repo currently
        pass
    return violations


def main() -> int:
    repo_root = Path(__file__).resolve().parent.parent
    allowlist_path = repo_root / "quality" / "dependency-allowlist.txt"

    if not allowlist_path.exists():
        print(f"FAIL: Allowlist not found at {allowlist_path}", file=sys.stderr)
        return 1

    allowlist = load_allowlist(allowlist_path)
    errors = []
    errors.extend(check_gradle_dependencies(repo_root, allowlist))
    errors.extend(check_pip_dependencies(repo_root, allowlist))
    errors.extend(check_npm_dependencies(repo_root, allowlist))

    if errors:
        print("DEPENDENCY CHECK FAILED:", file=sys.stderr)
        for err in errors:
            print(f"  - {err}", file=sys.stderr)
        return 1

    print("DEPENDENCY CHECK PASSED: All dependencies in allowlist and HTTP client isolation verified.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
