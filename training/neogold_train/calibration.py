"""
Split conformal prediction calibration for 90% prediction intervals (doc 4 Section 5.5).
Calculates finite-sample valid residual quantiles on held-out calibration partition.
"""

from __future__ import annotations
import math
from typing import List, Tuple
import numpy as np
from neogold_train.features import standardize, predict_linear, extract_vector
from neogold_train.loaders.base import PairedSubjectSession
from neogold_train.train import SiteModelParams


def calibrate_site_conformal(
    model: SiteModelParams,
    cal_sessions: List[PairedSubjectSession],
    coverage_level: float = 0.90,
) -> float:
    """
    Computes the split-conformal residual quantile on held-out calibration data for coverage_level (e.g. 0.90).
    Guarantees marginal coverage P(y in [y_hat - q, y_hat + q]) >= coverage_level.
    """
    residuals = []
    for sess in cal_sessions:
        lab = sess.get_site_lab(model.site)
        if lab is None:
            continue
        vec = extract_vector(lab, model.feature_list)
        std_vec = standardize(vec, model.standardization_means, model.standardization_scales)
        y_hat = predict_linear(std_vec, model.ridge_coefficients, model.intercept)
        residuals.append(abs(sess.tsb_mg_dl - y_hat))

    if not residuals:
        raise ValueError(f"No valid calibration samples for site '{model.site}'")

    n = len(residuals)
    residuals_arr = np.sort(residuals)

    # Standard split conformal finite-sample quantile calculation
    # index k = ceil((n + 1) * coverage_level)
    k = math.ceil((n + 1) * coverage_level)
    # Clip to [1, n]
    k_idx = min(n, max(1, k)) - 1  # 0-indexed

    q = float(residuals_arr[k_idx])
    model.residual_quantile_90 = round(q, 8)
    return model.residual_quantile_90
