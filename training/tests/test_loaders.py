"""
Tests for bench, public, and partner export data loaders.
"""

import csv
import hashlib
from pathlib import Path
import pytest
from neogold_train.loaders import (
    BenchPhantomLoader,
    PublicDatasetLoader,
    PartnerExportLoader,
    LicenseRestrictionError,
)
from neogold_train.parity import find_workspace_root


def test_bench_loader_with_manifest_verification():
    root = find_workspace_root()
    bench_dir = root / "testdata" / "bench"
    loader = BenchPhantomLoader(bench_dir)
    sessions = loader.load(check_manifest=True)

    assert len(sessions) > 100, f"Expected >100 bench sessions, got {len(sessions)}"
    first = sessions[0]
    assert first.tsb_mg_dl > 0.0
    assert first.participant_id.startswith("BENCH-P")
    assert first.get_site_lab("sternum") is not None
    assert first.get_site_lab("forehead") is not None


def test_public_loader_license_enforcement(tmp_path: Path):
    loader = PublicDatasetLoader()

    # Excluded dataset must raise LicenseRestrictionError
    with pytest.raises(LicenseRestrictionError, match="cannot be used"):
        loader.check_license("mendeley_mashhad_2023", allow_research_experimental=False)

    # Approved dataset must pass
    loader.check_license("neogold_bench_phantoms", allow_research_experimental=False)

    # Create dummy CSV and test loading with research flag
    test_csv = tmp_path / "public.csv"
    with open(test_csv, "w", newline="", encoding="utf-8") as f:
        writer = csv.writer(f)
        writer.writerow(["participant_id", "session_id", "tsb_mg_dl", "sternum_l", "sternum_a", "sternum_b"])
        writer.writerow(["P001", "S001", "12.5", "62.0", "10.0", "26.0"])

    sessions = loader.load_tabular_csv(test_csv, "mendeley_mashhad_2023", allow_research_experimental=True)
    assert len(sessions) == 1
    assert sessions[0].tsb_mg_dl == 12.5
    assert sessions[0].get_site_lab("sternum") is not None


def test_partner_export_loader(tmp_path: Path):
    export_dir = tmp_path / "export"
    export_dir.mkdir()

    sessions_csv = export_dir / "sessions.csv"
    captures_csv = export_dir / "captures.csv"
    tsb_csv = export_dir / "tsb_measurements.csv"

    with open(sessions_csv, "w", newline="", encoding="utf-8") as f:
        writer = csv.writer(f)
        writer.writerow(["session_id", "participant_id", "status", "age_hours", "gestational_age_weeks"])
        writer.writerow(["S1", "P1", "COMPLETE", "48.0", "38"])
        writer.writerow(["S2", "P2", "RECEIVING", "24.0", "37"])  # Incomplete, should be ignored

    with open(captures_csv, "w", newline="", encoding="utf-8") as f:
        writer = csv.writer(f)
        writer.writerow(["capture_id", "session_id", "site", "quality_status", "window_l", "window_a", "window_b"])
        writer.writerow(["C1", "S1", "STERNUM", "PASSED", "60.0", "10.0", "25.0"])
        writer.writerow(["C2", "S1", "FOREHEAD", "PASSED", "61.0", "9.5", "25.5"])

    with open(tsb_csv, "w", newline="", encoding="utf-8") as f:
        writer = csv.writer(f)
        writer.writerow(["tsb_id", "participant_id", "session_id", "tsb_mg_dl", "method"])
        writer.writerow(["T1", "P1", "S1", "11.2", "LAB"])

    # Create manifest
    manifest_file = export_dir / "manifest.sha256"
    lines = []
    for f in [sessions_csv, captures_csv, tsb_csv]:
        sha = hashlib.sha256(f.read_bytes()).hexdigest()
        lines.append(f"{sha}  {f.name}\n")
    manifest_file.write_text("".join(lines), encoding="utf-8")

    loader = PartnerExportLoader(export_dir)
    records = loader.load(check_manifest=True)

    assert len(records) == 1
    rec = records[0]
    assert rec.session_id == "S1"
    assert rec.participant_id == "P1"
    assert rec.tsb_mg_dl == 11.2
    assert rec.get_site_lab("sternum") is not None
    assert rec.get_site_lab("forehead") is not None
