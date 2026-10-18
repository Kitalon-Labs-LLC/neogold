"""
Evaluation metrics calculation: MAE, Pearson r, Bland-Altman agreement,
split conformal 90% prediction interval coverage (TC-EST-05), and subgroup breakdowns.
"""

from __future__ import annotations
import math
from typing import Dict, List, Optional, Tuple
import numpy as np
from scipy import stats
from neogold_train.features import (
    extract_vector,
    standardize,
    predict_linear,
    combine_sites,
)
from neogold_train.loaders.base import PairedSubjectSession
from neogold_train.train import SiteModelParams


def compute_basic_metrics(y_true: np.ndarray, y_pred: np.ndarray) -> Dict[str, float]:
    """Computes MAE, RMSE, Pearson r, and R2."""
    if len(y_true) == 0:
        return {"sampleCount": 0, "mae": 0.0, "rmse": 0.0, "correlationPearson": 0.0}

    diff = y_pred - y_true
    mae = float(np.mean(np.abs(diff)))
    rmse = float(np.sqrt(np.mean(diff ** 2)))

    if len(y_true) >= 2 and np.std(y_true) > 1e-9 and np.std(y_pred) > 1e-9:
        r, _ = stats.pearsonr(y_true, y_pred)
        r = float(r)
    else:
        r = 0.0

    return {
        "sampleCount": len(y_true),
        "mae": round(mae, 4),
        "rmse": round(rmse, 4),
        "correlationPearson": round(r, 4),
    }


def compute_bland_altman(y_true: np.ndarray, y_pred: np.ndarray) -> Dict[str, float]:
    """
    Computes Bland-Altman agreement metrics: mean bias and 95% limits of agreement (1.96 * SD).
    Bias = (y_pred - y_true).
    """
    if len(y_true) < 2:
        return {
            "meanBias": 0.0,
            "sdBias": 0.0,
            "limitsOfAgreementLower": 0.0,
            "limitsOfAgreementUpper": 0.0,
        }

    diff = y_pred - y_true
    mean_bias = float(np.mean(diff))
    sd_bias = float(np.std(diff, ddof=1))
    loa_low = mean_bias - 1.96 * sd_bias
    loa_high = mean_bias + 1.96 * sd_bias

    return {
        "meanBias": round(mean_bias, 4),
        "sdBias": round(sd_bias, 4),
        "limitsOfAgreementLower": round(loa_low, 4),
        "limitsOfAgreementUpper": round(loa_high, 4),
    }


def compute_coverage(
    y_true: np.ndarray,
    lower_bounds: np.ndarray,
    upper_bounds: np.ndarray,
) -> Dict[str, float]:
    """
    Computes empirical coverage of the prediction intervals (TC-EST-05).
    """
    if len(y_true) == 0:
        return {"coverage90": 0.0, "meanIntervalWidth": 0.0}

    covered = (y_true >= lower_bounds) & (y_true <= upper_bounds)
    emp_coverage = float(np.mean(covered))
    widths = upper_bounds - lower_bounds
    mean_width = float(np.mean(widths))

    return {
        "coverage90": round(emp_coverage, 4),
        "meanIntervalWidth": round(mean_width, 4),
    }


def evaluate_site(
    model: SiteModelParams,
    sessions: List[PairedSubjectSession],
) -> Dict[str, any]:
    """Evaluates single-site model performance."""
    y_true = []
    y_pred = []
    lower = []
    upper = []

    q90 = model.residual_quantile_90
    for sess in sessions:
        lab = sess.get_site_lab(model.site)
        if lab is None:
            continue
        vec = extract_vector(lab, model.feature_list)
        std_vec = standardize(vec, model.standardization_means, model.standardization_scales)
        y_hat = predict_linear(std_vec, model.ridge_coefficients, model.intercept)

        y_true.append(sess.tsb_mg_dl)
        y_pred.append(y_hat)
        lower.append(y_hat - q90)
        upper.append(y_hat + q90)

    y_t = np.array(y_true, dtype=np.float64)
    y_p = np.array(y_pred, dtype=np.float64)
    low_arr = np.array(lower, dtype=np.float64)
    up_arr = np.array(upper, dtype=np.float64)

    res = {}
    res.update(compute_basic_metrics(y_t, y_p))
    res["blandAltman"] = compute_bland_altman(y_t, y_p)
    res.update(compute_coverage(y_t, low_arr, up_arr))
    return res


def evaluate_multisite(
    models: Dict[str, SiteModelParams],
    weights: Dict[str, float],
    sessions: List[PairedSubjectSession],
) -> Dict[str, any]:
    """Evaluates multi-site weighted combination performance across sessions."""
    y_true = []
    y_pred = []
    lower = []
    upper = []

    for sess in sessions:
        site_preds = {}
        for site_name, site_model in models.items():
            lab = sess.get_site_lab(site_name)
            if lab is None:
                continue
            vec = extract_vector(lab, site_model.feature_list)
            std_vec = standardize(vec, site_model.standardization_means, site_model.standardization_scales)
            est = predict_linear(std_vec, site_model.ridge_coefficients, site_model.intercept)
            site_preds[site_name] = (est, site_model.residual_quantile_90)

        if not site_preds:
            continue

        comb_est, comb_q90 = combine_sites(site_preds, weights)
        y_true.append(sess.tsb_mg_dl)
        y_pred.append(comb_est)
        lower.append(comb_est - comb_q90)
        upper.append(comb_est + comb_q90)

    y_t = np.array(y_true, dtype=np.float64)
    y_p = np.array(y_pred, dtype=np.float64)
    low_arr = np.array(lower, dtype=np.float64)
    up_arr = np.array(upper, dtype=np.float64)

    res = {}
    res.update(compute_basic_metrics(y_t, y_p))
    res["blandAltman"] = compute_bland_altman(y_t, y_p)
    res.update(compute_coverage(y_t, low_arr, up_arr))
    return res


def evaluate_subgroups(
    models: Dict[str, SiteModelParams],
    weights: Dict[str, float],
    sessions: List[PairedSubjectSession],
) -> Dict[str, Dict[str, any]]:
    """
    Evaluates multi-site performance partitioned across:
    - skin_tone_group (Fitzpatrick IV, V, VI)
    - device_model
    - gestational_age_band (<37 weeks vs >=37 weeks)
    """
    subgroups = {}

    # 1. By skin tone group
    by_skin: Dict[str, List[PairedSubjectSession]] = {}
    for s in sessions:
        grp = s.skin_tone_group or "Unknown"
        by_skin.setdefault(grp, []).append(s)

    subgroups["bySkinTone"] = {
        grp: evaluate_multisite(models, weights, s_list)
        for grp, s_list in sorted(by_skin.items())
    }

    # 2. By device model
    by_dev: Dict[str, List[PairedSubjectSession]] = {}
    for s in sessions:
        dev = s.device_model or "Unknown"
        by_dev.setdefault(dev, []).append(s)

    subgroups["byDevice"] = {
        dev: evaluate_multisite(models, weights, s_list)
        for dev, s_list in sorted(by_dev.items())
    }

    # 3. By gestational age band
    by_ga: Dict[str, List[PairedSubjectSession]] = {}
    for s in sessions:
        if s.gestational_age_weeks is not None:
            band = "Preterm (35-36 wk)" if s.gestational_age_weeks < 37 else "Term (37+ wk)"
        else:
            band = "Unknown"
        by_ga.setdefault(band, []).append(s)

    subgroups["byGestationalAge"] = {
        band: evaluate_multisite(models, weights, s_list)
        for band, s_list in sorted(by_ga.items())
    }

    return subgroups
