"""
Participant-grouped dataset splitting for train, calibration, and test sets.
Ensures zero participant leakage across splits (doc 4 Section 11).
"""

from __future__ import annotations
from typing import Dict, List, Tuple
from dataclasses import dataclass
import numpy as np
from neogold_train.loaders.base import PairedSubjectSession


@dataclass
class SplitDataset:
    train_sessions: List[PairedSubjectSession]
    calibration_sessions: List[PairedSubjectSession]
    test_sessions: List[PairedSubjectSession]


def split_by_participant(
    sessions: List[PairedSubjectSession],
    train_ratio: float = 0.60,
    cal_ratio: float = 0.20,
    test_ratio: float = 0.20,
    seed: int = 42,
) -> Tuple[List[PairedSubjectSession], List[PairedSubjectSession], List[PairedSubjectSession]]:
    """
    Splits sessions grouped by participant_id into train, calibration, and test partitions.
    Guarantees no participant appears in more than one partition.
    """
    total = train_ratio + cal_ratio + test_ratio
    train_ratio /= total
    cal_ratio /= total
    test_ratio /= total

    # Group sessions by participant
    by_part: Dict[str, List[PairedSubjectSession]] = {}
    for sess in sessions:
        by_part.setdefault(sess.participant_id, []).append(sess)

    unique_participants = sorted(by_part.keys())
    rng = np.random.RandomState(seed)
    shuffled_participants = list(unique_participants)
    rng.shuffle(shuffled_participants)

    n_parts = len(shuffled_participants)
    n_train = int(round(n_parts * train_ratio))
    n_cal = int(round(n_parts * cal_ratio))

    train_parts = set(shuffled_participants[:n_train])
    cal_parts = set(shuffled_participants[n_train:n_train + n_cal])
    test_parts = set(shuffled_participants[n_train + n_cal:])

    train_sessions = [s for s in sessions if s.participant_id in train_parts]
    cal_sessions = [s for s in sessions if s.participant_id in cal_parts]
    test_sessions = [s for s in sessions if s.participant_id in test_parts]

    return train_sessions, cal_sessions, test_sessions
