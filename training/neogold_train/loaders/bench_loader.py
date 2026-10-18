"""
Loader for bench phantom captures in testdata/bench/.
Verifies SHA-256 against testdata/bench/manifest.sha256.
"""

from __future__ import annotations
import hashlib
import json
from pathlib import Path
from typing import List, Optional
from neogold_train.colorscience import Lab
from neogold_train.loaders.base import PairedSubjectSession, SiteCaptureRecord


def verify_manifest_hash(file_path: Path, manifest_path: Path) -> bool:
    """Verifies that file_path matches the declared hash in manifest_path."""
    if not manifest_path.is_file():
        raise FileNotFoundError(f"Manifest not found: {manifest_path}")
    if not file_path.is_file():
        raise FileNotFoundError(f"Data file not found: {file_path}")

    with open(manifest_path, "r", encoding="utf-8") as f:
        manifest_text = f.read().strip()

    expected_sha = None
    for line in manifest_text.splitlines():
        line = line.strip()
        if not line or line.startswith("#"):
            continue
        parts = line.split()
        if len(parts) >= 2 and parts[1].strip() == file_path.name:
            expected_sha = parts[0].strip().lower()
            break
        elif len(parts) == 1:
            expected_sha = parts[0].strip().lower()

    if not expected_sha:
        raise ValueError(f"Could not locate hash for {file_path.name} in {manifest_path}")

    h = hashlib.sha256()
    with open(file_path, "rb") as f:
        while chunk := f.read(65536):
            h.update(chunk)
    actual_sha = h.hexdigest().lower()

    if actual_sha != expected_sha:
        raise ValueError(
            f"Integrity check failed for {file_path.name}: expected {expected_sha}, got {actual_sha}"
        )
    return True


class BenchPhantomLoader:
    """
    Loads synthetic bench phantom captures from testdata/bench/.
    """

    def __init__(self, bench_dir: Path):
        self.bench_dir = bench_dir.resolve()
        self.data_file = self.bench_dir / "bench_captures.json"
        self.manifest_file = self.bench_dir / "manifest.sha256"

    def load(self, check_manifest: bool = True) -> List[PairedSubjectSession]:
        if not self.data_file.is_file():
            # If data file does not exist, check if generator can produce it deterministically
            from tools.generate_bench_phantoms import generate_bench_phantoms
            generate_bench_phantoms(self.data_file, self.manifest_file)

        if check_manifest:
            verify_manifest_hash(self.data_file, self.manifest_file)

        with open(self.data_file, "r", encoding="utf-8") as f:
            data = json.load(f)

        sessions: List[PairedSubjectSession] = []
        for sess in data.get("sessions", []):
            site_caps: List[SiteCaptureRecord] = []
            for cap in sess.get("site_captures", []):
                lab_arr = cap["window_lab"]
                site_caps.append(
                    SiteCaptureRecord(
                        capture_id=cap["capture_id"],
                        site=cap["site"],
                        window_lab=Lab(l=lab_arr[0], a=lab_arr[1], b=lab_arr[2]),
                        quality_status=cap.get("quality_status", "PASSED"),
                    )
                )

            sessions.append(
                PairedSubjectSession(
                    session_id=sess["session_id"],
                    participant_id=sess["participant_id"],
                    tsb_mg_dl=float(sess["tsb_mg_dl"]),
                    site_captures=site_caps,
                    age_hours=sess.get("age_hours"),
                    gestational_age_weeks=sess.get("gestational_age_weeks"),
                    skin_tone_group=sess.get("skin_tone_group"),
                    device_model=sess.get("device_model"),
                    card_batch_id=sess.get("card_batch_id"),
                )
            )

        return sessions
