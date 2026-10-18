"""
Model export and documentation generator (TC-OPS-07, PRD D11, NG-033).
Exports schema-valid model.json, MODEL_CARD.md, and metrics.json with byte-for-byte determinism.
"""

from __future__ import annotations
import json
from pathlib import Path
from typing import Dict, List, Optional
import jsonschema
from neogold_train.train import SiteModelParams


def build_model_dict(
    sternum_model: SiteModelParams,
    forehead_model: SiteModelParams,
    test_metrics: Dict[str, any],
    sample_count: int,
    participant_count: int,
    is_synthetic_bench: bool = True,
    model_version: str = "1.0.0-synthetic",
    trained_date: str = "2026-10-18",
    multi_site_weights: Optional[Dict[str, float]] = None,
) -> Dict[str, any]:
    if multi_site_weights is None:
        multi_site_weights = {"sternum": 0.6, "forehead": 0.4}

    if is_synthetic_bench:
        description = (
            "SYNTHETIC NON-CLINICAL MODEL ONLY. NOT FOR CLINICAL USE. "
            "Generated from calibrated bench phantoms to prove deterministic pipeline execution, "
            "golden vector parity, and split-conformal 90% prediction intervals."
        )
        populations = ["Synthetic bench phantoms (Fitzpatrick IV-VI)"]
        devices = ["Benchmark camera Helio G85", "Floor Android Go 2GB", "Redmi 14C"]
    else:
        description = "NeoGold clinical bilirubin estimation model."
        populations = ["Consented neonatal cohort"]
        devices = ["Validated Android device matrix"]

    summary = {
        "sampleCount": int(sample_count),
        "participantCount": int(participant_count),
        "description": description,
        "trainedDate": trained_date,
        "populations": populations,
        "devices": devices,
        "metrics": {
            "mae": round(float(test_metrics["mae"]), 2),
            "correlationPearson": round(float(test_metrics["correlationPearson"]), 2),
            "coverage90": round(float(test_metrics["coverage90"]), 2),
        },
    }

    sites = {
        "sternum": {
            "standardizationMeans": [round(float(v), 8) for v in sternum_model.standardization_means],
            "standardizationScales": [round(float(v), 8) for v in sternum_model.standardization_scales],
            "ridgeCoefficients": [round(float(v), 8) for v in sternum_model.ridge_coefficients],
            "intercept": round(float(sternum_model.intercept), 8),
            "residualQuantile90": round(float(sternum_model.residual_quantile_90), 8),
        },
        "forehead": {
            "standardizationMeans": [round(float(v), 8) for v in forehead_model.standardization_means],
            "standardizationScales": [round(float(v), 8) for v in forehead_model.standardization_scales],
            "ridgeCoefficients": [round(float(v), 8) for v in forehead_model.ridge_coefficients],
            "intercept": round(float(forehead_model.intercept), 8),
            "residualQuantile90": round(float(forehead_model.residual_quantile_90), 8),
        },
    }

    model_dict = {
        "modelVersion": model_version,
        "minAppVersion": "1.0.0",
        "units": "mg/dL",
        "algorithm": "ridge_regression_split_conformal",
        "colorSpace": "CIELAB_D65",
        "trainingDataSummary": summary,
        "featureList": list(sternum_model.feature_list),
        "sites": sites,
        "multiSiteWeights": {
            "sternum": round(float(multi_site_weights["sternum"]), 2),
            "forehead": round(float(multi_site_weights["forehead"]), 2),
        },
    }

    return model_dict


def validate_against_schema(model_dict: Dict[str, any], schema_path: Path):
    with open(schema_path, "r", encoding="utf-8") as f:
        schema = json.load(f)
    jsonschema.validate(instance=model_dict, schema=schema)


def render_model_card(
    model_dict: Dict[str, any],
    metrics_report: Dict[str, any],
    output_path: Path,
):
    summary = model_dict["trainingDataSummary"]
    test_met = metrics_report["overallTest"]
    ba = test_met["blandAltman"]
    subgroups = metrics_report.get("subgroups", {})

    lines = [
        "# NeoGold Bilirubin Estimation Model Card",
        "",
        "| Field | Value |",
        "|---|---|",
        f"| Model Version | `{model_dict['modelVersion']}` |",
        f"| Algorithm | `{model_dict['algorithm']}` |",
        f"| Color Space | `{model_dict['colorSpace']}` |",
        f"| Trained Date | `{summary['trainedDate']}` |",
        f"| Min App Version | `{model_dict['minAppVersion']}` |",
        f"| Target Units | `{model_dict['units']}` |",
        "",
        "---",
        "",
        "## 1. Intended Use and Clinical Framing (PRD D11)",
        "",
        "> **CLINICAL NOTICE (PRD D11):** NeoGold provides decision support for trained health workers. "
        "It suggests a next step; the health worker decides, using their own clinical judgment. "
        "It does not diagnose and is not a medical device. Every screening result must be confirmed with a laboratory blood test.",
        "",
    ]

    if "SYNTHETIC" in summary["description"]:
        lines.extend([
            "> [!CAUTION]",
            "> **NON-CLINICAL BENCH MODEL NOTICE:** This model was trained on calibrated synthetic bench phantoms "
            "(`testdata/bench/`) to demonstrate reproducible execution, golden parity, and split-conformal calibration. "
            "It has **NO CLINICAL MEANING** and must never be used to guide patient triage.",
            "",
        ])

    lines.extend([
        "## 2. Model Architecture and Features",
        "- **Model Family:** Per-site ridge regression with cross-validated alpha regularization.",
        "- **Uncertainty Estimation:** Split-conformal calibration yielding finite-sample valid 90% prediction intervals.",
        "- **Feature Vector:** Extracted from corrected skin window CIELAB (D65):",
        f"  `{', '.join(model_dict['featureList'])}`.",
        "- **Multi-site Combination:** Weighted linear combination of sternum (0.60) and forehead (0.40).",
        "",
        "---",
        "",
        "## 3. Training and Evaluation Data Summary",
        f"- **Total Samples:** {summary['sampleCount']}",
        f"- **Total Participants:** {summary['participantCount']}",
        f"- **Data Split:** 60% Train, 20% Calibration, 20% Test (grouped strictly by participant, zero leakage).",
        f"- **Populations Represented:** {', '.join(summary['populations'])}",
        f"- **Device Classes Tested:** {', '.join(summary['devices'])}",
        "",
        "---",
        "",
        "## 4. Evaluation Performance (Held-out Test Cohort)",
        "",
        "| Metric | Test Set Value | Target / Specification |",
        "|---|---|---|",
        f"| **Mean Absolute Error (MAE)** | **{test_met['mae']:.2f} mg/dL** | < 2.0 mg/dL |",
        f"| **Root Mean Squared Error (RMSE)** | **{test_met['rmse']:.2f} mg/dL** | < 2.5 mg/dL |",
        f"| **Pearson Correlation ($r$)** | **{test_met['correlationPearson']:.3f}** | $\\ge 0.85$ (BiliCam: 0.91) |",
        f"| **90% Interval Coverage (TC-EST-05)** | **{test_met['coverage90'] * 100:.1f}%** | $\\ge 85.0\\%$ target |",
        f"| **Mean Interval Width** | **{test_met['meanIntervalWidth']:.2f} mg/dL** | Conformal residual quantile |",
        f"| **Bland-Altman Mean Bias** | **{ba['meanBias']:+.2f} mg/dL** | $\\approx 0.0$ mg/dL |",
        f"| **Bland-Altman 95% LoA** | **[{ba['limitsOfAgreementLower']:+.2f}, {ba['limitsOfAgreementUpper']:+.2f}] mg/dL** | $\\pm 1.96 \\text{{ SD}}$ |",
        "",
    ])

    # Subgroup breakdowns
    if "bySkinTone" in subgroups:
        lines.extend([
            "### 4.1 Subgroup Performance by Skin Tone",
            "",
            "| Skin Tone Group | Sample Count | MAE (mg/dL) | Pearson $r$ | 90% Coverage |",
            "|---|---|---|---|---|",
        ])
        for grp, m in subgroups["bySkinTone"].items():
            lines.append(
                f"| {grp} | {m['sampleCount']} | {m['mae']:.2f} | {m['correlationPearson']:.3f} | {m['coverage90']*100:.1f}% |"
            )
        lines.append("")

    if "byDevice" in subgroups:
        lines.extend([
            "### 4.2 Subgroup Performance by Device Hardware",
            "",
            "| Device Model | Sample Count | MAE (mg/dL) | Pearson $r$ | 90% Coverage |",
            "|---|---|---|---|---|",
        ])
        for dev, m in subgroups["byDevice"].items():
            lines.append(
                f"| {dev} | {m['sampleCount']} | {m['mae']:.2f} | {m['correlationPearson']:.3f} | {m['coverage90']*100:.1f}% |"
            )
        lines.append("")

    lines.extend([
        "---",
        "",
        "## 5. Scope, Constraints, and Limitations",
        "- **Preterm Exclusion:** Gestational age < 35 weeks must not be screened (hard rule check routes to blood test).",
        "- **Post-phototherapy Exclusion:** Neonates who received prior phototherapy are out of scope (skin photobleaching invalidates colorimetry).",
        "- **Age Limits:** Neonates older than 14 days require formal medical review for conjugate hyperbilirubinemia.",
        "- **Card Verification:** Captures without a verified physical NeoGold calibration card are rejected by the quality gate.",
        "",
        "---",
        "",
        "## 6. Retraining and Verification",
        "To reproduce this model byte-for-byte from scratch:",
        "```bash",
        "make reproduce",
        "```",
        "Refer to `training/RETRAINING.md` for instructions on retraining on local partner datasets.",
    ])

    output_path.parent.mkdir(parents=True, exist_ok=True)
    output_path.write_text("\n".join(lines).strip() + "\n", encoding="utf-8")
