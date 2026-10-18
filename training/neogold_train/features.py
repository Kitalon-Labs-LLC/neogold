"""
Feature extraction and standardization routines matching :engine:estimation:FeatureExtractor
and EstimationMath.
"""

from __future__ import annotations
import math
from typing import Dict, List, Tuple
import numpy as np
from neogold_train.colorscience import Lab


FEATURE_L_STAR = "l_star"
FEATURE_A_STAR = "a_star"
FEATURE_B_STAR = "b_star"
FEATURE_B_OVER_L = "b_over_l"
FEATURE_A_OVER_B = "a_over_b"

DEFAULT_FEATURE_LIST = [
    FEATURE_L_STAR,
    FEATURE_A_STAR,
    FEATURE_B_STAR,
    FEATURE_B_OVER_L,
    FEATURE_A_OVER_B,
]


def extract_features(lab: Lab) -> Dict[str, float]:
    """
    Extracts named color features from corrected skin window CIELAB.
    Matches Kotlin FeatureExtractor.extractFeatures.
    """
    b_over_l = lab.b / lab.l if lab.l != 0.0 else 0.0
    a_over_b = lab.a / lab.b if lab.b != 0.0 else 0.0

    return {
        FEATURE_L_STAR: lab.l,
        FEATURE_A_STAR: lab.a,
        FEATURE_B_STAR: lab.b,
        FEATURE_B_OVER_L: b_over_l,
        FEATURE_A_OVER_B: a_over_b,
        # Aliases matching Kotlin FeatureExtractor
        "mean_l": lab.l,
        "mean_a": lab.a,
        "mean_b": lab.b,
        "b/L*": b_over_l,
        "a*/b*": a_over_b,
        "b_star/l_star": b_over_l,
        "a_star/b_star": a_over_b,
        "L*": lab.l,
        "a*": lab.a,
        "b*": lab.b,
    }


def extract_vector(lab: Lab, feature_list: List[str]) -> np.ndarray:
    """
    Extracts an ordered feature array corresponding to the model's authoritative featureList.
    """
    feat_map = extract_features(lab)
    vec = []
    for name in feature_list:
        if name not in feat_map:
            raise KeyError(f"Feature '{name}' requested by model cannot be extracted from Lab")
        vec.append(feat_map[name])
    return np.array(vec, dtype=np.float64)


def standardize(
    features: np.ndarray,
    means: List[float],
    scales: List[float],
) -> np.ndarray:
    """
    Standardizes raw features: z_i = (x_i - mean_i) / scale_i.
    Matches Kotlin EstimationMath.standardize.
    """
    feat = np.asarray(features, dtype=np.float64)
    m = np.asarray(means, dtype=np.float64)
    s = np.asarray(scales, dtype=np.float64)

    if feat.shape[-1] != m.shape[0] or feat.shape[-1] != s.shape[0]:
        raise ValueError(
            f"Dimension mismatch: features {feat.shape[-1]}, means {m.shape[0]}, scales {s.shape[0]}"
        )
    if np.any(s <= 0.0) or np.any(np.isnan(s)) or np.any(np.isinf(s)):
        raise ValueError("Standardization scales must be strictly positive and finite")

    return (feat - m) / s


def predict_linear(
    standardized: np.ndarray,
    coefficients: List[float],
    intercept: float,
) -> float:
    """
    Linear prediction: y = intercept + sum_i (z_i * w_i).
    Matches Kotlin EstimationMath.predictLinear.
    """
    z = np.asarray(standardized, dtype=np.float64)
    coef = np.asarray(coefficients, dtype=np.float64)
    return float(intercept + np.dot(z, coef))


def combine_sites(
    site_estimates: Dict[str, Tuple[float, float]],  # site -> (estimate, residualQuantile90)
    weights: Dict[str, float],
) -> Tuple[float, float]:
    """
    Combines sternum and forehead predictions using normalized weights.
    Matches Kotlin EstimationMath.combineSites.
    """
    if not site_estimates:
        raise ValueError("Cannot combine empty site estimates")

    if len(site_estimates) == 1:
        return next(iter(site_estimates.values()))

    total_weight = 0.0
    for site in site_estimates:
        w = weights.get(site)
        if w is None or w < 0.0 or math.isnan(w) or math.isinf(w):
            raise ValueError(f"Site '{site}' missing valid non-negative weight (found {w})")
        total_weight += w

    if total_weight <= 0.0:
        raise ValueError(f"Sum of active site weights must be strictly positive (got {total_weight})")

    weighted_est = 0.0
    weighted_q90 = 0.0
    for site, (est, q90) in site_estimates.items():
        nw = weights[site] / total_weight
        weighted_est += nw * est
        weighted_q90 += nw * q90

    return weighted_est, weighted_q90
