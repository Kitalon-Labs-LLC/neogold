"""
Tests for end-to-end training pipeline, split conformal coverage (TC-EST-05),
and byte-identical reproducibility (TC-OPS-07).
"""

import hashlib
import json
from pathlib import Path
import pytest
from neogold_train.pipeline import run_training_pipeline
from neogold_train.split import split_by_participant
from neogold_train.loaders.bench_loader import BenchPhantomLoader
from neogold_train.parity import find_workspace_root


def test_tc_est_05_conformal_coverage_and_pipeline(tmp_path: Path):
    root = find_workspace_root()
    model_path = tmp_path / "model.json"
    card_path = tmp_path / "MODEL_CARD.md"
    metrics_path = tmp_path / "metrics.json"

    res = run_training_pipeline(
        workspace_root=root,
        output_model_path=model_path,
        output_card_path=card_path,
        output_metrics_path=metrics_path,
        data_source="bench",
        seed=42,
    )

    assert model_path.is_file()
    assert card_path.is_file()
    assert metrics_path.is_file()

    # Check metrics and TC-EST-05 coverage (target >= 85%)
    metrics = res["metrics"]
    test_metrics = metrics["overallTest"]
    coverage = test_metrics["coverage90"]
    assert coverage >= 0.85, f"TC-EST-05 failed: expected coverage >= 85%, got {coverage * 100:.2f}%"

    assert test_metrics["mae"] < 2.0, f"Expected MAE < 2.0 mg/dL, got {test_metrics['mae']}"
    assert test_metrics["correlationPearson"] > 0.85, f"Expected Pearson r > 0.85, got {test_metrics['correlationPearson']}"

    # Verify Bland-Altman values
    ba = test_metrics["blandAltman"]
    assert abs(ba["meanBias"]) < 0.5, f"Mean bias too high: {ba['meanBias']}"
    assert ba["limitsOfAgreementLower"] < ba["limitsOfAgreementUpper"]

    # Verify subgroups
    subgroups = metrics["subgroups"]
    assert "bySkinTone" in subgroups
    assert "byDevice" in subgroups
    assert "byGestationalAge" in subgroups


def test_tc_ops_07_byte_identical_reproducibility(tmp_path: Path):
    root = find_workspace_root()
    model1 = tmp_path / "model1.json"
    model2 = tmp_path / "model2.json"
    card1 = tmp_path / "CARD1.md"
    card2 = tmp_path / "CARD2.md"
    metrics1 = tmp_path / "metrics1.json"
    metrics2 = tmp_path / "metrics2.json"

    run_training_pipeline(
        workspace_root=root,
        output_model_path=model1,
        output_card_path=card1,
        output_metrics_path=metrics1,
        seed=42,
    )

    run_training_pipeline(
        workspace_root=root,
        output_model_path=model2,
        output_card_path=card2,
        output_metrics_path=metrics2,
        seed=42,
    )

    sha1 = hashlib.sha256(model1.read_bytes()).hexdigest()
    sha2 = hashlib.sha256(model2.read_bytes()).hexdigest()

    assert sha1 == sha2, f"TC-OPS-07 failed: model.json hashes differ ({sha1} vs {sha2})"

    # Verify coefficient precision
    m1 = json.loads(model1.read_text(encoding="utf-8"))
    m2 = json.loads(model2.read_text(encoding="utf-8"))

    for site in ["sternum", "forehead"]:
        c1 = m1["sites"][site]["ridgeCoefficients"]
        c2 = m2["sites"][site]["ridgeCoefficients"]
        for idx in range(len(c1)):
            assert abs(c1[idx] - c2[idx]) < 1e-9, f"Site {site} coef {idx} differs beyond 1e-9"


def test_grouped_split_zero_leakage():
    root = find_workspace_root()
    loader = BenchPhantomLoader(root / "testdata" / "bench")
    sessions = loader.load(check_manifest=False)

    train_s, cal_s, test_s = split_by_participant(sessions, seed=42)

    train_parts = set(s.participant_id for s in train_s)
    cal_parts = set(s.participant_id for s in cal_s)
    test_parts = set(s.participant_id for s in test_s)

    assert len(train_parts.intersection(cal_parts)) == 0, "Leakage between train and calibration!"
    assert len(train_parts.intersection(test_parts)) == 0, "Leakage between train and test!"
    assert len(cal_parts.intersection(test_parts)) == 0, "Leakage between calibration and test!"
