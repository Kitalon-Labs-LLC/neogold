"""
End-to-end training pipeline orchestrator (TC-OPS-07, TC-EST-05, PRD D11).
Executes parity checks, data loading, grouped splitting, ridge regression,
split-conformal calibration, evaluation, and schema-valid export.
"""

from __future__ import annotations
import json
from pathlib import Path
from typing import Dict, Optional
from neogold_train.parity import verify_all_golden_parity, ParityError, find_workspace_root
from neogold_train.loaders.bench_loader import BenchPhantomLoader
from neogold_train.loaders.partner_loader import PartnerExportLoader
from neogold_train.split import split_by_participant
from neogold_train.train import fit_site_ridge_cv
from neogold_train.calibration import calibrate_site_conformal
from neogold_train.metrics import evaluate_multisite, evaluate_subgroups, evaluate_site
from neogold_train.export import build_model_dict, validate_against_schema, render_model_card


def run_training_pipeline(
    workspace_root: Optional[Path] = None,
    output_model_path: Optional[Path] = None,
    output_card_path: Optional[Path] = None,
    output_metrics_path: Optional[Path] = None,
    data_source: str = "bench",
    partner_export_dir: Optional[Path] = None,
    seed: int = 42,
    fail_on_parity: bool = True,
) -> Dict[str, any]:
    root = find_workspace_root(workspace_root)
    schema_path = root / "config" / "schema" / "model.schema.json"

    if output_model_path is None:
        output_model_path = root / "config" / "model.json"
    if output_card_path is None:
        output_card_path = root / "MODEL_CARD.md"
    if output_metrics_path is None:
        output_metrics_path = root / "training" / "output" / "metrics.json"

    # Step 1: Feature Extraction & Parity Gate (TC-EST-01)
    print("--- Step 1: Verifying Golden Vector Parity (TC-EST-01) ---")
    try:
        parity_res = verify_all_golden_parity(root, tolerance=1e-4)
        print(f"Parity verified successfully: {parity_res}")
    except ParityError as pe:
        print(f"[FATAL] Parity verification failed: {pe}")
        if fail_on_parity:
            raise

    # Step 2: Data Loading
    print("--- Step 2: Loading Training Dataset ---")
    if data_source == "bench":
        bench_dir = root / "testdata" / "bench"
        loader = BenchPhantomLoader(bench_dir)
        sessions = loader.load(check_manifest=True)
        is_synthetic = True
    elif data_source == "partner":
        if partner_export_dir is None:
            raise ValueError("partner_export_dir must be specified for partner data source")
        loader = PartnerExportLoader(partner_export_dir)
        sessions = loader.load(check_manifest=True)
        is_synthetic = False
    else:
        raise ValueError(f"Unsupported data source: {data_source}")

    sample_count = len(sessions)
    participant_count = len(set(s.participant_id for s in sessions))
    print(f"Loaded {sample_count} sessions across {participant_count} participants.")

    # Step 3: Grouped Splitting (zero participant leakage)
    print("--- Step 3: Grouped Splitting (60% Train, 20% Cal, 20% Test) ---")
    train_sess, cal_sess, test_sess = split_by_participant(
        sessions=sessions,
        train_ratio=0.60,
        cal_ratio=0.20,
        test_ratio=0.20,
        seed=seed,
    )
    print(
        f"Split: {len(train_sess)} train, {len(cal_sess)} calibration, {len(test_sess)} test sessions."
    )

    # Step 4: Per-Site Ridge Regression with CV
    print("--- Step 4: Fitting Ridge Regression Models per Body Site ---")
    sternum_model = fit_site_ridge_cv(train_sess, site="sternum", seed=seed)
    forehead_model = fit_site_ridge_cv(train_sess, site="forehead", seed=seed)
    print(f"Sternum alpha={sternum_model.best_alpha:.4f}, intercept={sternum_model.intercept:.4f}")
    print(f"Forehead alpha={forehead_model.best_alpha:.4f}, intercept={forehead_model.intercept:.4f}")

    # Step 5: Split Conformal Calibration (90% prediction interval)
    print("--- Step 5: Split Conformal Calibration (90% Interval) ---")
    q_sternum = calibrate_site_conformal(sternum_model, cal_sess, coverage_level=0.90)
    q_forehead = calibrate_site_conformal(forehead_model, cal_sess, coverage_level=0.90)
    print(f"Sternum q90={q_sternum:.4f} mg/dL, Forehead q90={q_forehead:.4f} mg/dL")

    # Step 6: Comprehensive Evaluation on Held-out Test Cohort
    print("--- Step 6: Evaluation Metrics on Held-out Test Partition ---")
    models = {"sternum": sternum_model, "forehead": forehead_model}
    weights = {"sternum": 0.60, "forehead": 0.40}

    test_overall = evaluate_multisite(models, weights, test_sess)
    sternum_test = evaluate_site(sternum_model, test_sess)
    forehead_test = evaluate_site(forehead_model, test_sess)
    subgroups = evaluate_subgroups(models, weights, test_sess)

    metrics_report = {
        "overallTest": test_overall,
        "sternumTest": sternum_test,
        "foreheadTest": forehead_test,
        "subgroups": subgroups,
        "splitCounts": {
            "train": len(train_sess),
            "calibration": len(cal_sess),
            "test": len(test_sess),
        },
    }

    print(f"Test MAE: {test_overall['mae']} mg/dL")
    print(f"Test Pearson r: {test_overall['correlationPearson']}")
    print(f"Test 90% Interval Coverage (TC-EST-05): {test_overall['coverage90'] * 100:.1f}%")
    print(f"Bland-Altman Mean Bias: {test_overall['blandAltman']['meanBias']:+.3f} mg/dL")

    # Step 7: Export model.json and validate against schema
    print("--- Step 7: Building and Validating model.json ---")
    model_version = "1.0.0-synthetic" if is_synthetic else "1.0.0"
    model_dict = build_model_dict(
        sternum_model=sternum_model,
        forehead_model=forehead_model,
        test_metrics=test_overall,
        sample_count=sample_count,
        participant_count=participant_count,
        is_synthetic_bench=is_synthetic,
        model_version=model_version,
        multi_site_weights=weights,
    )

    validate_against_schema(model_dict, schema_path)
    print("Schema validation against model.schema.json PASSED.")

    # Canonical formatting: sorted keys, 2-space indent, exact byte serialization
    output_model_path.parent.mkdir(parents=True, exist_ok=True)
    canonical_json_bytes = json.dumps(model_dict, indent=2, sort_keys=True).encode("utf-8")
    with open(output_model_path, "wb") as f:
        f.write(canonical_json_bytes)
        f.write(b"\n")
    print(f"Wrote {output_model_path} ({len(canonical_json_bytes)} bytes)")

    # Render MODEL_CARD.md
    render_model_card(model_dict, metrics_report, output_card_path)
    print(f"Wrote {output_card_path}")

    # Write metrics.json
    output_metrics_path.parent.mkdir(parents=True, exist_ok=True)
    with open(output_metrics_path, "w", encoding="utf-8") as f:
        json.dump(metrics_report, f, indent=2, sort_keys=True)
        f.write("\n")
    print(f"Wrote {output_metrics_path}")

    return {
        "model_path": output_model_path,
        "model_card_path": output_card_path,
        "metrics_path": output_metrics_path,
        "metrics": metrics_report,
        "model_dict": model_dict,
    }
