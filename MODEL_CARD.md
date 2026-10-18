# NeoGold Bilirubin Estimation Model Card

| Field | Value |
|---|---|
| Model Version | `1.0.0-synthetic` |
| Algorithm | `ridge_regression_split_conformal` |
| Color Space | `CIELAB_D65` |
| Trained Date | `2026-10-18` |
| Min App Version | `1.0.0` |
| Target Units | `mg/dL` |

---

## 1. Intended Use and Clinical Framing (PRD D11)

> **CLINICAL NOTICE (PRD D11):** NeoGold provides decision support for trained health workers. It suggests a next step; the health worker decides, using their own clinical judgment. It does not diagnose and is not a medical device. Every screening result must be confirmed with a laboratory blood test.

> [!CAUTION]
> **NON-CLINICAL BENCH MODEL NOTICE:** This model was trained on calibrated synthetic bench phantoms (`testdata/bench/`) to demonstrate reproducible execution, golden parity, and split-conformal calibration. It has **NO CLINICAL MEANING** and must never be used to guide patient triage.

## 2. Model Architecture and Features
- **Model Family:** Per-site ridge regression with cross-validated alpha regularization.
- **Uncertainty Estimation:** Split-conformal calibration yielding finite-sample valid 90% prediction intervals.
- **Feature Vector:** Extracted from corrected skin window CIELAB (D65):
  `l_star, a_star, b_star, b_over_l, a_over_b`.
- **Multi-site Combination:** Weighted linear combination of sternum (0.60) and forehead (0.40).

---

## 3. Training and Evaluation Data Summary
- **Total Samples:** 225
- **Total Participants:** 150
- **Data Split:** 60% Train, 20% Calibration, 20% Test (grouped strictly by participant, zero leakage).
- **Populations Represented:** Synthetic bench phantoms (Fitzpatrick IV-VI)
- **Device Classes Tested:** Benchmark camera Helio G85, Floor Android Go 2GB, Redmi 14C

---

## 4. Evaluation Performance (Held-out Test Cohort)

| Metric | Test Set Value | Target / Specification |
|---|---|---|
| **Mean Absolute Error (MAE)** | **0.82 mg/dL** | < 2.0 mg/dL |
| **Root Mean Squared Error (RMSE)** | **1.03 mg/dL** | < 2.5 mg/dL |
| **Pearson Correlation ($r$)** | **0.975** | $\ge 0.85$ (BiliCam: 0.91) |
| **90% Interval Coverage (TC-EST-05)** | **97.7%** | $\ge 85.0\%$ target |
| **Mean Interval Width** | **4.02 mg/dL** | Conformal residual quantile |
| **Bland-Altman Mean Bias** | **-0.14 mg/dL** | $\approx 0.0$ mg/dL |
| **Bland-Altman 95% LoA** | **[-2.16, +1.87] mg/dL** | $\pm 1.96 \text{ SD}$ |

### 4.1 Subgroup Performance by Skin Tone

| Skin Tone Group | Sample Count | MAE (mg/dL) | Pearson $r$ | 90% Coverage |
|---|---|---|---|---|
| Fitzpatrick IV | 14 | 0.81 | 0.985 | 100.0% |
| Fitzpatrick V | 16 | 0.89 | 0.973 | 93.8% |
| Fitzpatrick VI | 14 | 0.77 | 0.976 | 100.0% |

### 4.2 Subgroup Performance by Device Hardware

| Device Model | Sample Count | MAE (mg/dL) | Pearson $r$ | 90% Coverage |
|---|---|---|---|---|
| Entry-Redmi14C | 16 | 0.89 | 0.973 | 93.8% |
| Floor-AndroidGo-2GB | 14 | 0.81 | 0.985 | 100.0% |
| Reference-HelioG85 | 14 | 0.77 | 0.976 | 100.0% |

---

## 5. Scope, Constraints, and Limitations
- **Preterm Exclusion:** Gestational age < 35 weeks must not be screened (hard rule check routes to blood test).
- **Post-phototherapy Exclusion:** Neonates who received prior phototherapy are out of scope (skin photobleaching invalidates colorimetry).
- **Age Limits:** Neonates older than 14 days require formal medical review for conjugate hyperbilirubinemia.
- **Card Verification:** Captures without a verified physical NeoGold calibration card are rejected by the quality gate.

---

## 6. Retraining and Verification
To reproduce this model byte-for-byte from scratch:
```bash
make reproduce
```
Refer to `training/RETRAINING.md` for instructions on retraining on local partner datasets.
