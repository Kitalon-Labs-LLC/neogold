"""
Loader for public datasets with license verification against training/DATA_LICENSES.md.
"""

from __future__ import annotations
import csv
from pathlib import Path
from typing import Dict, List, Optional
from neogold_train.colorscience import Lab
from neogold_train.loaders.base import (
    PairedSubjectSession,
    SiteCaptureRecord,
    LicenseRestrictionError,
)


APPROVED_DATASETS = {
    "neogold_bench_phantoms": True,
}

EXCLUDED_DATASETS = {
    "mendeley_mashhad_2023": (
        "Dataset captured with USB digital microscope (Dino-Lite), not smartphone camera. "
        "Domain mismatch and redistribution rights unverified per training/DATA_LICENSES.md."
    ),
    "ucl_neoscb_ghana": (
        "Institutional academic dataset restricted to sclera research; "
        "not openly licensed for model redistribution per training/DATA_LICENSES.md."
    ),
}


class PublicDatasetLoader:
    """
    Loads public research datasets with mandatory license and domain validation.
    """

    def __init__(self, data_licenses_file: Optional[Path] = None):
        self.licenses_file = data_licenses_file

    def check_license(self, dataset_id: str, allow_research_experimental: bool = False):
        dataset_key = dataset_id.lower().strip()
        if dataset_key in APPROVED_DATASETS and APPROVED_DATASETS[dataset_key]:
            return

        if dataset_key in EXCLUDED_DATASETS:
            reason = EXCLUDED_DATASETS[dataset_key]
            if allow_research_experimental:
                print(f"[WARNING] Using excluded/unverified dataset '{dataset_id}': {reason}")
                return
            raise LicenseRestrictionError(
                f"Dataset '{dataset_id}' cannot be used to train redistributable models: {reason}"
            )

        # Unknown dataset
        if not allow_research_experimental:
            raise LicenseRestrictionError(
                f"Dataset '{dataset_id}' has not been audited or approved in training/DATA_LICENSES.md."
            )

    def load_tabular_csv(
        self,
        csv_path: Path,
        dataset_id: str,
        allow_research_experimental: bool = False,
    ) -> List[PairedSubjectSession]:
        self.check_license(dataset_id, allow_research_experimental=allow_research_experimental)

        if not csv_path.is_file():
            raise FileNotFoundError(f"Public dataset file not found: {csv_path}")

        sessions: List[PairedSubjectSession] = []
        with open(csv_path, "r", encoding="utf-8") as f:
            reader = csv.DictReader(f)
            for idx, row in enumerate(reader):
                participant_id = row.get("participant_id") or f"PUB-{dataset_id}-{idx:04d}"
                session_id = row.get("session_id") or f"SESS-{participant_id}"

                tsb = float(row.get("tsb_mg_dl") or row.get("tsb") or row.get("TSB") or 0.0)

                caps = []
                # Check for sternum Lab
                if "sternum_l" in row and "sternum_b" in row:
                    sl = float(row["sternum_l"])
                    sa = float(row.get("sternum_a", 0.0))
                    sb = float(row["sternum_b"])
                    caps.append(
                        SiteCaptureRecord(
                            capture_id=f"{session_id}-ST",
                            site="sternum",
                            window_lab=Lab(sl, sa, sb),
                        )
                    )

                # Check for forehead Lab
                if "forehead_l" in row and "forehead_b" in row:
                    fl = float(row["forehead_l"])
                    fa = float(row.get("forehead_a", 0.0))
                    fb = float(row["forehead_b"])
                    caps.append(
                        SiteCaptureRecord(
                            capture_id=f"{session_id}-FH",
                            site="forehead",
                            window_lab=Lab(fl, fa, fb),
                        )
                    )

                sessions.append(
                    PairedSubjectSession(
                        session_id=session_id,
                        participant_id=participant_id,
                        tsb_mg_dl=tsb,
                        site_captures=caps,
                        skin_tone_group=row.get("skin_tone_group"),
                        device_model=row.get("device_model"),
                    )
                )

        return sessions
