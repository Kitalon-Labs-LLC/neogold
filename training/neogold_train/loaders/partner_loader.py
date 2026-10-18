"""
Loader for the partner's de-identified export format (doc 7 Section 10, doc 8 Section 5.2).
Loads CSV exports from Study Intake Service and joins participants, sessions, captures, and TSB measurements.
"""

from __future__ import annotations
import csv
import hashlib
from pathlib import Path
from typing import Dict, List, Optional
from neogold_train.colorscience import Lab
from neogold_train.loaders.base import PairedSubjectSession, SiteCaptureRecord


class PartnerExportLoader:
    """
    Parses and joins de-identified export bundles produced by neogold-admin export.
    """

    def __init__(self, export_dir: Path):
        self.export_dir = export_dir.resolve()
        self.sessions_csv = self.export_dir / "sessions.csv"
        self.captures_csv = self.export_dir / "captures.csv"
        self.tsb_csv = self.export_dir / "tsb_measurements.csv"
        self.manifest_file = self.export_dir / "manifest.sha256"

    def verify_manifest(self) -> bool:
        if not self.manifest_file.is_file():
            return False

        with open(self.manifest_file, "r", encoding="utf-8") as f:
            lines = f.readlines()

        for line in lines:
            line = line.strip()
            if not line or line.startswith("#"):
                continue
            parts = line.split()
            if len(parts) >= 2:
                expected_sha = parts[0].strip().lower()
                rel_path = parts[1].strip()
                target_file = self.export_dir / rel_path
                if not target_file.is_file():
                    raise FileNotFoundError(f"Manifest referenced file missing: {target_file}")
                h = hashlib.sha256()
                with open(target_file, "rb") as mf:
                    while chunk := mf.read(65536):
                        h.update(chunk)
                if h.hexdigest().lower() != expected_sha:
                    raise ValueError(f"Checksum mismatch for export file: {rel_path}")
        return True

    def load(self, check_manifest: bool = True) -> List[PairedSubjectSession]:
        if check_manifest and self.manifest_file.is_file():
            self.verify_manifest()

        if not self.sessions_csv.is_file():
            raise FileNotFoundError(f"Export sessions.csv missing in {self.export_dir}")
        if not self.captures_csv.is_file():
            raise FileNotFoundError(f"Export captures.csv missing in {self.export_dir}")
        if not self.tsb_csv.is_file():
            raise FileNotFoundError(f"Export tsb_measurements.csv missing in {self.export_dir}")

        # 1. Load TSB measurements mapped by session_id (and participant_id)
        # session_id -> list of tsb values
        tsb_by_session: Dict[str, float] = {}
        with open(self.tsb_csv, "r", encoding="utf-8") as f:
            reader = csv.DictReader(f)
            for row in reader:
                sess_id = row.get("session_id")
                val_str = row.get("tsb_mg_dl")
                if sess_id and val_str:
                    try:
                        tsb_by_session[sess_id] = float(val_str)
                    except ValueError:
                        continue

        # 2. Load captures mapped by session_id
        # session_id -> list of SiteCaptureRecord
        captures_by_session: Dict[str, List[SiteCaptureRecord]] = {}
        with open(self.captures_csv, "r", encoding="utf-8") as f:
            reader = csv.DictReader(f)
            for row in reader:
                sess_id = row.get("session_id")
                if not sess_id:
                    continue
                q_status = row.get("quality_status", "PASSED")
                if q_status != "PASSED":
                    continue

                site_str = row.get("site", "STERNUM").lower()
                # Read window L*, a*, b*
                try:
                    wl = float(row.get("window_l") or row.get("l_star") or 0.0)
                    wa = float(row.get("window_a") or row.get("a_star") or 0.0)
                    wb = float(row.get("window_b") or row.get("b_star") or 0.0)
                except ValueError:
                    continue

                rec = SiteCaptureRecord(
                    capture_id=row.get("capture_id", f"CAP-{sess_id}"),
                    site=site_str,
                    window_lab=Lab(wl, wa, wb),
                    quality_status=q_status,
                )
                captures_by_session.setdefault(sess_id, []).append(rec)

        # 3. Load sessions and assemble paired records
        paired_sessions: List[PairedSubjectSession] = []
        with open(self.sessions_csv, "r", encoding="utf-8") as f:
            reader = csv.DictReader(f)
            for row in reader:
                sess_id = row.get("session_id")
                if not sess_id or sess_id not in tsb_by_session:
                    continue
                # Only complete sessions
                status = row.get("status", "COMPLETE")
                if status != "COMPLETE":
                    continue

                caps = captures_by_session.get(sess_id, [])
                if not caps:
                    continue

                age_h = None
                if row.get("age_hours"):
                    try:
                        age_h = float(row["age_hours"])
                    except ValueError:
                        pass

                ga_w = None
                if row.get("gestational_age_weeks"):
                    try:
                        ga_w = int(row["gestational_age_weeks"])
                    except ValueError:
                        pass

                paired = PairedSubjectSession(
                    session_id=sess_id,
                    participant_id=row.get("participant_id", sess_id),
                    tsb_mg_dl=tsb_by_session[sess_id],
                    site_captures=caps,
                    age_hours=age_h,
                    gestational_age_weeks=ga_w,
                    skin_tone_group=row.get("skin_tone_group"),
                    device_model=row.get("device_model"),
                    card_batch_id=row.get("card_batch_id"),
                )
                paired_sessions.append(paired)

        return paired_sessions
