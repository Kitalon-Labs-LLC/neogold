"""
Common data models and interfaces for dataset loaders.
"""

from __future__ import annotations
from dataclasses import dataclass
from typing import Optional, List
from neogold_train.colorscience import Lab


@dataclass(frozen=True)
class SiteCaptureRecord:
    capture_id: str
    site: str  # "sternum" or "forehead"
    window_lab: Lab
    quality_status: str = "PASSED"


@dataclass(frozen=True)
class PairedSubjectSession:
    """
    A single measurement session containing paired TSB reference and one or more site captures.
    """
    session_id: str
    participant_id: str
    tsb_mg_dl: float
    site_captures: List[SiteCaptureRecord]
    age_hours: Optional[float] = None
    gestational_age_weeks: Optional[int] = None
    skin_tone_group: Optional[str] = None  # e.g. "Fitzpatrick IV", "Fitzpatrick V", "Fitzpatrick VI"
    device_model: Optional[str] = None
    card_batch_id: Optional[str] = None

    def get_site_lab(self, site: str) -> Optional[Lab]:
        site_norm = site.lower().strip()
        for c in self.site_captures:
            if c.site.lower().strip() == site_norm:
                return c.window_lab
        return None


class LicenseRestrictionError(RuntimeError):
    """Raised when attempting to load a dataset with unverified or non-redistributable license."""
    pass
