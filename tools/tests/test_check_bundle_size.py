"""
tools/tests/test_check_bundle_size.py

Proves tools/check_bundle_size.py correctly enforces the 25 MB budget.
"""

from pathlib import Path
from tools.check_bundle_size import check_artifact_size, MAX_DOWNLOAD_BYTES


def test_bundle_size_within_budget(tmp_path: Path):
    small_file = tmp_path / "app-release.aab"
    # Create 5 MB mock file
    small_file.write_bytes(b"\x00" * (5 * 1024 * 1024))
    passed, msg = check_artifact_size(small_file)
    assert passed is True
    assert "PASSED" in msg


def test_bundle_size_exceeds_budget(tmp_path: Path):
    oversized_file = tmp_path / "oversized.apk"
    # Create 26 MB mock file (exceeds 25 MB limit)
    oversized_file.write_bytes(b"\x00" * (26 * 1024 * 1024))
    passed, msg = check_artifact_size(oversized_file)
    assert passed is False
    assert "EXCEEDED" in msg


def test_nonexistent_file_fails(tmp_path: Path):
    missing = tmp_path / "missing.apk"
    passed, msg = check_artifact_size(missing)
    assert passed is False
    assert "not found" in msg.lower()
