#!/usr/bin/env python3
"""
tools/check_bundle_size.py

Enforces P25 / Doc 4 Section 5.6 / Doc 9 Section 11 / PRD NFR-04:
Download size budget is strictly under 25 MB (26,214,400 bytes).
Checks a release App Bundle (.aab) or APK file. If bundletool is available,
it queries the download size across device configurations; otherwise it verifies
the raw compressed artifact size against the 25 MB ceiling.
"""

import argparse
import json
import os
import shutil
import subprocess
import sys
from pathlib import Path

MAX_DOWNLOAD_BYTES = 25 * 1024 * 1024  # 25 MB (26,214,400 bytes)


def check_artifact_size(artifact_path: Path, max_bytes: int = MAX_DOWNLOAD_BYTES) -> tuple[bool, str]:
    if not artifact_path.exists():
        return False, f"Artifact not found: {artifact_path}"

    file_bytes = artifact_path.stat().st_size
    size_mb = file_bytes / (1024 * 1024)

    # If bundletool is installed and artifact is .aab, attempt bundletool calculation
    bundletool = shutil.which("bundletool")
    if bundletool and artifact_path.suffix == ".aab":
        try:
            # Generate APKS in temp directory
            import tempfile

            with tempfile.TemporaryDirectory() as tmp_dir:
                apks_path = Path(tmp_dir) / "output.apks"
                subprocess.run(
                    [bundletool, "build-apks", f"--bundle={artifact_path}", f"--output={apks_path}", "--mode=default"],
                    check=True,
                    capture_output=True,
                )
                res = subprocess.run(
                    [bundletool, "get-size", "total", f"--apks={apks_path}", "--dimensions=ABI,SDK"],
                    check=True,
                    capture_output=True,
                    text=True,
                )
                # bundletool get-size prints min,max bytes per config
                lines = [l.strip() for l in res.stdout.splitlines() if l.strip()]
                # Find maximum size reported in output
                max_observed = file_bytes
                for line in lines:
                    parts = line.split(",")
                    if len(parts) >= 2:
                        try:
                            val = int(parts[-1])
                            if val > max_observed:
                                max_observed = val
                        except ValueError:
                            pass
                file_bytes = max_observed
                size_mb = file_bytes / (1024 * 1024)
        except Exception:
            # Fall back to raw file size
            pass

    if file_bytes > max_bytes:
        return (
            False,
            f"SIZE BUDGET EXCEEDED: {artifact_path.name} is {size_mb:.2f} MB ({file_bytes:,} bytes), "
            f"exceeding the 25 MB ceiling ({max_bytes:,} bytes) (Doc 4 Section 5.6, NFR-04)",
        )

    return (
        True,
        f"SIZE BUDGET PASSED: {artifact_path.name} is {size_mb:.2f} MB ({file_bytes:,} bytes) <= 25 MB limit.",
    )


def main() -> int:
    parser = argparse.ArgumentParser(description="Check Android release bundle / APK download size budget")
    parser.add_argument("artifact", nargs="?", default="android/app/build/outputs/bundle/release/app-release.aab")
    parser.add_argument("--budget-mb", type=float, default=25.0)
    args = parser.parse_args()

    artifact_path = Path(args.artifact)
    max_bytes = int(args.budget_mb * 1024 * 1024)

    passed, msg = check_artifact_size(artifact_path, max_bytes=max_bytes)
    if passed:
        print(msg)
        return 0
    else:
        print(f"FAIL: {msg}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
