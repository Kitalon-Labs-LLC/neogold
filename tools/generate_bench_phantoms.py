#!/usr/bin/env python3
"""
Deterministic Bench Phantom Dataset Generator (TC-OPS-07, TC-EST-05).
Generates synthetic bench phantom captures across newborn skin tones (Fitzpatrick IV-VI)
and bilirubin concentrations (2.0 - 22.0 mg/dL), writing testdata/bench/bench_captures.json
and its cryptographic manifest testdata/bench/manifest.sha256.

Guaranteed bit-for-bit deterministic output via fixed pseudo-random seed.
"""

from __future__ import annotations
import hashlib
import json
from pathlib import Path
import numpy as np


def generate_bench_phantoms(
    output_path: Path,
    manifest_path: Path,
    num_participants: int = 150,
    seed: int = 42,
) -> dict:
    rng = np.random.RandomState(seed)

    # Photorealistic skin baseline centers by skin tone category
    skin_groups = {
        "Fitzpatrick IV": {"l_mean": 64.0, "l_std": 4.0, "a_mean": 10.0, "b_base": 20.0},
        "Fitzpatrick V":  {"l_mean": 52.0, "l_std": 4.5, "a_mean": 11.5, "b_base": 18.0},
        "Fitzpatrick VI": {"l_mean": 42.0, "l_std": 3.5, "a_mean": 9.5,  "b_base": 15.0},
    }
    group_names = list(skin_groups.keys())

    devices = ["Reference-HelioG85", "Floor-AndroidGo-2GB", "Entry-Redmi14C"]

    sessions = []
    sample_count = 0

    for p_idx in range(1, num_participants + 1):
        participant_id = f"BENCH-P{p_idx:04d}"
        group_name = group_names[(p_idx - 1) % len(group_names)]
        grp = skin_groups[group_name]

        device = devices[p_idx % len(devices)]
        ga_weeks = int(rng.choice([35, 36, 37, 38, 39, 40, 41]))
        age_hours = round(float(rng.uniform(12.0, 144.0)), 1)

        # Baseline participant skin melanin variation
        part_l = float(rng.normal(grp["l_mean"], grp["l_std"]))
        part_a = float(rng.normal(grp["a_mean"], 1.5))

        # 1 to 2 sessions per participant (nominal total ~250-300 sessions)
        num_sessions = 2 if (p_idx % 2 == 0) else 1

        for s_idx in range(1, num_sessions + 1):
            session_id = f"SESS-{participant_id}-{s_idx}"
            # True bilirubin between 2.0 and 22.0 mg/dL with realistic skew
            tsb = round(float(rng.gamma(shape=4.0, scale=2.5) + 1.5), 1)
            tsb = max(2.0, min(22.0, tsb))

            # Yellowness b* correlates strongly with bilirubin
            # b* = b_base + k * TSB + noise
            sternum_b = grp["b_base"] + (0.95 * tsb) + float(rng.normal(0.0, 0.8))
            sternum_l = part_l - (0.15 * tsb) + float(rng.normal(0.0, 0.5))
            sternum_a = part_a + float(rng.normal(0.0, 0.4))

            # Forehead slightly higher yellowness and slightly lower lightness in newborns
            forehead_b = grp["b_base"] + (0.98 * tsb) + 0.5 + float(rng.normal(0.0, 0.8))
            forehead_l = part_l - (0.18 * tsb) - 0.5 + float(rng.normal(0.0, 0.5))
            forehead_a = part_a + 0.3 + float(rng.normal(0.0, 0.4))

            sess_record = {
                "session_id": session_id,
                "participant_id": participant_id,
                "tsb_mg_dl": tsb,
                "age_hours": age_hours + (s_idx - 1) * 24.0,
                "gestational_age_weeks": ga_weeks,
                "skin_tone_group": group_name,
                "device_model": device,
                "card_batch_id": "BATCH-2026-001",
                "site_captures": [
                    {
                        "capture_id": f"CAP-{session_id}-ST",
                        "site": "sternum",
                        "window_lab": [
                            round(sternum_l, 8),
                            round(sternum_a, 8),
                            round(sternum_b, 8),
                        ],
                        "quality_status": "PASSED",
                    },
                    {
                        "capture_id": f"CAP-{session_id}-FH",
                        "site": "forehead",
                        "window_lab": [
                            round(forehead_l, 8),
                            round(forehead_a, 8),
                            round(forehead_b, 8),
                        ],
                        "quality_status": "PASSED",
                    },
                ],
            }
            sessions.append(sess_record)
            sample_count += 1

    payload = {
        "version": "1.0.0",
        "description": "Deterministic bench phantom captures for reproducible training pipeline (TC-OPS-07, TC-EST-05).",
        "notice": "SYNTHETIC BENCH PHANTOMS ONLY. NO CLINICAL MEANING. NOT FOR MEDICAL USE.",
        "sampleCount": sample_count,
        "participantCount": num_participants,
        "sessions": sessions,
    }

    # Format JSON with sorted keys and 2-space indent for stable hashes
    content_bytes = json.dumps(payload, indent=2, sort_keys=True).encode("utf-8")
    output_path.parent.mkdir(parents=True, exist_ok=True)
    with open(output_path, "wb") as f:
        f.write(content_bytes)

    # Compute SHA-256 digest
    sha256 = hashlib.sha256(content_bytes).hexdigest()
    manifest_line = f"{sha256}  {output_path.name}\n"
    manifest_path.parent.mkdir(parents=True, exist_ok=True)
    with open(manifest_path, "w", encoding="utf-8") as f:
        f.write(manifest_line)

    return {"sampleCount": sample_count, "participantCount": num_participants, "sha256": sha256}


def main():
    root = Path(__file__).resolve().parent.parent
    bench_dir = root / "testdata" / "bench"
    output_json = bench_dir / "bench_captures.json"
    manifest = bench_dir / "manifest.sha256"

    print("Generating deterministic bench phantom dataset...")
    info = generate_bench_phantoms(output_json, manifest)
    print(f"Generated {info['sampleCount']} samples ({info['participantCount']} participants).")
    print(f"SHA-256: {info['sha256']}")


if __name__ == "__main__":
    main()
