# NeoGold Model Retraining Guide

| Field | Value |
|---|---|
| Document | `training/RETRAINING.md` |
| Backlog | `NG-038` (retraining part) |
| Requirement | PRD Section 9, PRD D11, NFR-11 |
| Target Audience | Study teams, partner hospitals, and open-source forks adapting NeoGold to local populations |

---

## 1. Overview

NeoGold's bilirubin estimation engine (`:engine:estimation`) uses a lightweight, transparent machine learning architecture:
- Per-body-site **ridge regression** on engineered color features (corrected skin window $L^*, a^*, b^*, b^*/L^*, a^*/b^*$).
- **Split-conformal calibration** generating finite-sample valid 90% prediction intervals.
- Pure linear dot-product inference on Android without heavy neural network runtimes.

This guide explains how an independent fork or research partner retrains the model on local clinical data and packages the resulting `model.json` and model card.

---

## 2. Prerequisites and Environment Setup

### 2.1 Dependencies
Ensure you have Python 3.11+ installed. Install the pinned dependencies with exact SHA-256 hashes:

```bash
cd training
pip install --require-hashes -r requirements.txt
```

Alternatively, use Docker for network-isolated execution:

```bash
make docker-build
```

---

## 3. Data Preparation

NeoGold strictly prohibits intra-participant data leakage. When preparing local data, each subject must be assigned a stable pseudonymous `participant_id`.

### Option A: Partner De-identified Export (Recommended)
If data was collected using NeoGold's Research Mode and uploaded to a partner Study Intake Service, export the de-identified cohort:

```bash
neogold-admin export --study YOUR_STUDY_CODE --out ./study_export/
```

The export directory will contain:
- `sessions.csv`: Session metadata, gestational age, age in hours.
- `captures.csv`: Corrected skin window CIELAB values and quality status.
- `tsb_measurements.csv`: Reference blood serum bilirubin (mg/dL).
- `manifest.sha256`: Cryptographic checksums of all export files.

### Option B: Local Bench Phantoms / Custom CSV
If calibrating against a custom phantom or lab cohort, prepare a CSV matching `PublicDatasetLoader`:
- Columns: `participant_id`, `session_id`, `tsb_mg_dl`, `sternum_l`, `sternum_a`, `sternum_b`, `forehead_l`, `forehead_a`, `forehead_b`, `skin_tone_group`.
- Audit dataset licensing terms and record entries in `training/DATA_LICENSES.md`.

---

## 4. Retraining Procedure

Run the training pipeline specifying your dataset source:

```bash
# From repository root:
python -m neogold_train.cli train \
    --data-source partner \
    --partner-dir ./study_export/ \
    --output-model config/model.json \
    --output-card MODEL_CARD.md \
    --seed 42
```

### What the pipeline executes:
1. **Parity Check:** Verifies that Python color science math matches the on-device Kotlin engine against golden vectors (`testdata/golden/`) within $10^{-4}$.
2. **Participant-Grouped Split:** Splits the cohort into 60% Train, 20% Conformal Calibration, and 20% Held-Out Test. No baby seen in training is ever in calibration or test.
3. **Cross-Validated Ridge Fitting:** Optimizes regularization $\alpha$ for sternum and forehead models.
4. **Split-Conformal Calibration:** Computes the empirical residual quantile on the calibration set for the 90% prediction interval.
5. **Schema Validation:** Strictly validates the output `model.json` against `config/schema/model.schema.json`.
6. **Artifact Generation:** Writes `config/model.json`, `MODEL_CARD.md`, and `training/output/metrics.json`.

---

## 5. Verification and Acceptance Gates

Before deploying a retrained model:

### 1. Golden Vector Parity Gate (TC-EST-01)
```bash
python -m neogold_train.cli parity
```
All intermediate conversions, least-squares patch corrections, and estimation predictions must agree within $10^{-4}$.

### 2. Conformal Coverage Gate (TC-EST-05)
Check `training/output/metrics.json` and ensure that `coverage90` on the test partition is at least **85.0%**.

### 3. Bit-for-Bit Determinism (TC-OPS-07)
Run the reproduction target twice:
```bash
make reproduce
```
The pipeline must produce byte-identical `model.json` output across repeated runs.

---

## 6. Packaging into the Android App

Once verified:

1. The generated `model.json` stays in `config/`. The Android build signs it into the app's assets; there is no
   copy to make by hand and none is committed under `android/`.
2. Run the Android engine test suite to verify end-to-end integration:
   ```bash
   cd android
   ./gradlew :engine:estimation:test verifyGoldenVectors
   ```
3. Update `MODEL_CARD.md` with cohort demographics, device distribution, and clinical limitations.
4. If modifying clinical triage bands or decision rules, obtain clinical sign-off per `CLINICAL_SIGNOFF.md`.
