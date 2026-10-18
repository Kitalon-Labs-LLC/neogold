"""
Ridge regression training per body site with cross-validated regularization.
Replicates feature standardization and linear inference matching :engine:estimation.
"""

from __future__ import annotations
from dataclasses import dataclass
from typing import Dict, List, Optional, Tuple
import numpy as np
from sklearn.linear_model import RidgeCV
from sklearn.model_selection import GroupKFold
from neogold_train.features import extract_vector, DEFAULT_FEATURE_LIST
from neogold_train.loaders.base import PairedSubjectSession


@dataclass
class SiteModelParams:
    site: str
    feature_list: List[str]
    standardization_means: List[float]
    standardization_scales: List[float]
    ridge_coefficients: List[float]
    intercept: float
    residual_quantile_90: float = 0.0
    best_alpha: float = 1.0


def extract_site_arrays(
    sessions: List[PairedSubjectSession],
    site: str,
    feature_list: List[str],
) -> Tuple[np.ndarray, np.ndarray, List[str]]:
    """
    Extracts raw feature matrix X, target array y, and participant_ids for a specific body site.
    """
    x_rows = []
    y_vals = []
    part_ids = []

    for sess in sessions:
        lab = sess.get_site_lab(site)
        if lab is None:
            continue
        feat_vec = extract_vector(lab, feature_list)
        x_rows.append(feat_vec)
        y_vals.append(sess.tsb_mg_dl)
        part_ids.append(sess.participant_id)

    if not x_rows:
        raise ValueError(f"No valid captures found for site '{site}' in provided sessions")

    return (
        np.array(x_rows, dtype=np.float64),
        np.array(y_vals, dtype=np.float64),
        part_ids,
    )


def fit_site_ridge_cv(
    sessions: List[PairedSubjectSession],
    site: str,
    feature_list: Optional[List[str]] = None,
    alphas: Optional[np.ndarray] = None,
    cv_folds: int = 5,
    seed: int = 42,
) -> SiteModelParams:
    """
    Fits ridge regression model for a single body site with GroupKFold cross-validation
    grouped by participant to avoid intra-subject leakage.
    """
    if feature_list is None:
        feature_list = list(DEFAULT_FEATURE_LIST)

    X, y, part_ids = extract_site_arrays(sessions, site, feature_list)
    n_samples, n_features = X.shape

    # 1. Feature standardization: z = (x - mean) / scale
    means = np.mean(X, axis=0)
    # Use sample standard deviation (ddof=1)
    scales = np.std(X, axis=0, ddof=1)
    # Ensure scales are strictly positive and non-zero
    scales = np.where(scales < 1e-6, 1.0, scales)

    Z = (X - means) / scales

    # 2. Regularization search with GroupKFold
    if alphas is None:
        alphas = np.logspace(-3, 3, 30)

    # Use GroupKFold if sufficient distinct participants, else standard KFold
    unique_parts = np.unique(part_ids)
    effective_folds = min(cv_folds, len(unique_parts))

    if effective_folds > 1:
        gkf = GroupKFold(n_splits=effective_folds)
        cv_generator = gkf.split(Z, y, groups=part_ids)
    else:
        cv_generator = None

    ridge = RidgeCV(
        alphas=alphas,
        cv=cv_generator,
        scoring="neg_mean_absolute_error",
    )
    ridge.fit(Z, y)

    coefs = [float(c) for c in ridge.coef_]
    intercept = float(ridge.intercept_)
    best_alpha = float(ridge.alpha_)

    return SiteModelParams(
        site=site,
        feature_list=feature_list,
        standardization_means=[float(m) for m in means],
        standardization_scales=[float(s) for s in scales],
        ridge_coefficients=coefs,
        intercept=intercept,
        best_alpha=best_alpha,
    )
