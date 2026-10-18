"""
Golden vector parity verification gate between Python training pipeline and
on-device Kotlin engines (:engine:colorscience and :engine:estimation).
Verifies parity <= 1e-4 and fails the build/run if parity breaks.
"""

from __future__ import annotations
import json
from pathlib import Path
from typing import Optional
from neogold_train.colorscience import (
    Rgb,
    Xyz,
    Lab,
    srgb_to_linear,
    linear_to_srgb,
    linear_rgb_to_xyz,
    xyz_to_linear_rgb,
    srgb_to_xyz,
    xyz_to_srgb,
    xyz_to_lab,
    lab_to_xyz,
    ciede2000,
    fit_linearization,
    fit_correction_matrix_3x3,
    apply_correction_3x3,
)
from neogold_train.features import (
    extract_features,
    extract_vector,
    standardize,
    predict_linear,
    combine_sites,
)


class ParityError(RuntimeError):
    """Raised when Python color science or estimation math deviates from golden vectors beyond 1e-4."""
    pass


def find_workspace_root(start_dir: Optional[Path] = None) -> Path:
    curr = (start_dir or Path.cwd()).resolve()
    for p in [curr] + list(curr.parents):
        if (p / "testdata" / "golden").is_dir():
            return p
    return curr


def verify_colorscience_conversions_parity(
    golden_path: Path,
    tolerance: float = 1e-4,
) -> int:
    with open(golden_path, "r", encoding="utf-8") as f:
        data = json.load(f)

    checked = 0
    # 1. Sharma CIEDE2000 test pairs if present
    sharma_pairs = data.get("sharma_ciede2000_pairs", [])
    for idx, pair in enumerate(sharma_pairs):
        l1, a1, b1 = pair["lab1"]
        l2, a2, b2 = pair["lab2"]
        expected_de = pair["expected_delta_e"]
        actual_de = ciede2000(Lab(l1, a1, b1), Lab(l2, a2, b2))
        err = abs(actual_de - expected_de)
        if err > tolerance:
            raise ParityError(
                f"Sharma CIEDE2000 pair {idx} parity failed: expected {expected_de}, got {actual_de}, diff {err} > {tolerance}"
            )
        checked += 1

    # 2. Golden conversion vectors (srgb, linear_rgb, xyz_d65, lab_d65)
    for idx, row in enumerate(data.get("vectors", [])):
        r, g, b = row["srgb"]
        rgb = Rgb(r, g, b)
        exp_linear = row["linear_rgb"]
        exp_xyz = row["xyz_d65"]
        exp_lab = row["lab_d65"]

        # sRGB -> Linear
        calc_lin = srgb_to_linear(rgb)
        err_lin = max(abs(calc_lin.r - exp_linear[0]), abs(calc_lin.g - exp_linear[1]), abs(calc_lin.b - exp_linear[2]))
        if err_lin > tolerance:
            raise ParityError(f"sRGB to Linear row {idx} ({row['id']}) mismatch: diff {err_lin} > {tolerance}")

        # Linear -> XYZ
        calc_xyz = linear_rgb_to_xyz(calc_lin)
        err_xyz = max(abs(calc_xyz.x - exp_xyz[0]), abs(calc_xyz.y - exp_xyz[1]), abs(calc_xyz.z - exp_xyz[2]))
        if err_xyz > tolerance:
            raise ParityError(f"Linear to XYZ row {idx} ({row['id']}) mismatch: diff {err_xyz} > {tolerance}")

        # XYZ -> Lab
        calc_lab = xyz_to_lab(calc_xyz)
        err_lab = max(abs(calc_lab.l - exp_lab[0]), abs(calc_lab.a - exp_lab[1]), abs(calc_lab.b - exp_lab[2]))
        if err_lab > tolerance:
            raise ParityError(f"XYZ to Lab row {idx} ({row['id']}) mismatch: diff {err_lab} > {tolerance}")

        # Lab -> XYZ
        rt_xyz = lab_to_xyz(calc_lab)
        err_rt_xyz = max(abs(rt_xyz.x - exp_xyz[0]), abs(rt_xyz.y - exp_xyz[1]), abs(rt_xyz.z - exp_xyz[2]))
        if err_rt_xyz > tolerance:
            raise ParityError(f"Lab to XYZ round-trip row {idx} ({row['id']}) mismatch: diff {err_rt_xyz} > {tolerance}")

        # XYZ -> Linear -> sRGB
        rt_rgb = xyz_to_srgb(rt_xyz)
        err_rt_rgb = max(abs(rt_rgb.r - r), abs(rt_rgb.g - g), abs(rt_rgb.b - b))
        if err_rt_rgb > tolerance:
            raise ParityError(f"XYZ to sRGB round-trip row {idx} ({row['id']}) mismatch: diff {err_rt_rgb} > {tolerance}")

        checked += 5

    return checked


def verify_patch_correction_parity(
    golden_path: Path,
    tolerance: float = 1e-4,
) -> int:
    with open(golden_path, "r", encoding="utf-8") as f:
        data = json.load(f)

    patches = data["patches"]
    # Verify patch-by-patch values
    checked = 0
    for p in patches:
        # Check corrected Lab vs reference
        c_xyz = Xyz(*p["corrected_xyz"])
        c_lab = xyz_to_lab(c_xyz)
        exp_lab = p["corrected_lab"]
        err = max(abs(c_lab.l - exp_lab[0]), abs(c_lab.a - exp_lab[1]), abs(c_lab.b - exp_lab[2]))
        if err > tolerance:
            raise ParityError(f"Patch {p['id']} corrected Lab parity failed: diff {err} > {tolerance}")

        ref_lab = Lab(*p["reference_lab"])
        calc_de = ciede2000(c_lab, ref_lab)
        exp_de = p["residual_delta_e"]
        if abs(calc_de - exp_de) > tolerance:
            raise ParityError(f"Patch {p['id']} Delta E parity failed: exp {exp_de}, got {calc_de}")
        checked += 1

    # Check skin window features parity
    sw = data["skin_window"]
    sw_lab = Lab(*sw["corrected_lab"])
    features = extract_features(sw_lab)
    exp_features = sw["features"]
    for k, v in exp_features.items():
        err = abs(features[k] - v)
        if err > tolerance:
            raise ParityError(f"Skin window feature '{k}' parity failed: exp {v}, got {features[k]}, diff {err}")
        checked += 1

    return checked


def verify_estimation_parity(
    golden_path: Path,
    model_path: Path,
    tolerance: float = 1e-4,
) -> int:
    with open(golden_path, "r", encoding="utf-8") as f:
        data = json.load(f)
    with open(model_path, "r", encoding="utf-8") as f:
        model = json.load(f)

    feature_list = model["featureList"]
    sites = model["sites"]
    weights = model["multiSiteWeights"]

    checked = 0
    for vec in data["vectors"]:
        vec_id = vec["id"]
        lab = Lab(*vec["input_lab"])

        # Check raw feature extraction
        extracted = extract_features(lab)
        for k, v in vec["extracted_features"].items():
            err = abs(extracted[k] - v)
            if err > tolerance:
                raise ParityError(f"{vec_id} feature '{k}' failed parity: exp {v}, got {extracted[k]}")
            checked += 1

        feature_vec = extract_vector(lab, feature_list)
        site_preds = {}

        # Check sternum and forehead
        for site_key in ["sternum", "forehead"]:
            if site_key not in vec:
                continue
            site_data = vec[site_key]
            site_model = sites[site_key]

            std_feat = standardize(
                features=feature_vec,
                means=site_model["standardizationMeans"],
                scales=site_model["standardizationScales"],
            )

            for i, exp_z in enumerate(site_data["standardized_features"]):
                err = abs(std_feat[i] - exp_z)
                if err > tolerance:
                    raise ParityError(
                        f"{vec_id} {site_key} standardized feature [{i}] mismatch: exp {exp_z}, got {std_feat[i]}"
                    )
                checked += 1

            est = predict_linear(
                standardized=std_feat,
                coefficients=site_model["ridgeCoefficients"],
                intercept=site_model["intercept"],
            )
            q90 = site_model["residualQuantile90"]
            err_est = abs(est - site_data["estimate"])
            if err_est > tolerance:
                raise ParityError(f"{vec_id} {site_key} estimate mismatch: exp {site_data['estimate']}, got {est}")

            site_preds[site_key] = (est, q90)
            checked += 1

        # Check multi-site combined
        if "combined_multisite" in vec:
            comb_exp = vec["combined_multisite"]
            comb_est, comb_q90 = combine_sites(site_preds, weights)
            err_comb = abs(comb_est - comb_exp["estimate"])
            if err_comb > tolerance:
                raise ParityError(
                    f"{vec_id} combined estimate mismatch: exp {comb_exp['estimate']}, got {comb_est}"
                )
            checked += 1

    return checked


def verify_all_golden_parity(workspace_root: Optional[Path] = None, tolerance: float = 1e-4) -> dict[str, int]:
    root = find_workspace_root(workspace_root)
    cs_conv = root / "testdata" / "golden" / "colorscience" / "golden_color_conversions.json"
    cs_patch = root / "testdata" / "golden" / "colorscience" / "golden_patch_correction.json"
    est_vecs = root / "testdata" / "golden" / "estimation" / "golden_estimation.json"
    model_syn = root / "testdata" / "models" / "model.synthetic.json"

    results = {}
    if cs_conv.is_file():
        results["colorscience_conversions"] = verify_colorscience_conversions_parity(cs_conv, tolerance)
    if cs_patch.is_file():
        results["patch_correction"] = verify_patch_correction_parity(cs_patch, tolerance)
    if est_vecs.is_file() and model_syn.is_file():
        results["estimation"] = verify_estimation_parity(est_vecs, model_syn, tolerance)

    return results
