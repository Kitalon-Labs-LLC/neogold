"""
Tests for TC-EST-01: Parity between Python training pipeline and on-device Kotlin engines.
"""

from pathlib import Path
import pytest
from neogold_train.parity import (
    verify_colorscience_conversions_parity,
    verify_patch_correction_parity,
    verify_estimation_parity,
    verify_all_golden_parity,
    find_workspace_root,
)


def test_tc_est_01_color_conversions_parity():
    root = find_workspace_root()
    conv_path = root / "testdata" / "golden" / "colorscience" / "golden_color_conversions.json"
    assert conv_path.is_file(), f"Missing golden file: {conv_path}"
    checked = verify_colorscience_conversions_parity(conv_path, tolerance=1e-4)
    assert checked > 20, f"Expected >20 checked conversions, got {checked}"


def test_tc_est_01_patch_correction_parity():
    root = find_workspace_root()
    patch_path = root / "testdata" / "golden" / "colorscience" / "golden_patch_correction.json"
    assert patch_path.is_file(), f"Missing golden file: {patch_path}"
    checked = verify_patch_correction_parity(patch_path, tolerance=1e-4)
    assert checked > 15, f"Expected >15 checked patch vectors, got {checked}"


def test_tc_est_01_estimation_parity():
    root = find_workspace_root()
    est_path = root / "testdata" / "golden" / "estimation" / "golden_estimation.json"
    model_path = root / "testdata" / "models" / "model.synthetic.json"
    assert est_path.is_file(), f"Missing golden estimation file: {est_path}"
    assert model_path.is_file(), f"Missing synthetic model: {model_path}"
    checked = verify_estimation_parity(est_path, model_path, tolerance=1e-4)
    assert checked >= 4, f"Expected at least 4 vectors checked, got {checked}"


def test_tc_est_01_verify_all():
    results = verify_all_golden_parity()
    assert "colorscience_conversions" in results
    assert "patch_correction" in results
    assert "estimation" in results
