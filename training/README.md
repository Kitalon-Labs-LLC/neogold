# NeoGold Model Training Pipeline

| Field | Value |
|---|---|
| Module | `training/` |
| Work Package | `P07` (Training pipeline, Phase 2) |
| Standards | TC-OPS-07, TC-EST-05, TC-EST-01, PRD D11, NFR-11 |
| Status | Completed |

This directory contains the Python training pipeline, pinned environment, and reproduction tools for the on-device bilirubin estimation model.

## Quickstart

### 1. Reproduce Canonical Model (TC-OPS-07)
```bash
make reproduce
# or directly on Windows / non-make environments:
python training/reproduce.py
```
This runs the pipeline twice from scratch, verifies golden vector parity at $\le 10^{-4}$, trains per-site ridge regression models with split-conformal calibration for 90% prediction intervals, validates against `config/schema/model.schema.json`, and asserts byte-identical output across runs.

### 2. Run Test Suite
```bash
pytest training/tests -v
```

### 3. Docker (Network-Isolated Reproducibility)
```bash
make docker-reproduce
```

## Structure
- `neogold_train/`: Core training package:
  - `colorscience.py`: Color conversions, linearization, and patch corrections matching `:engine:colorscience`.
  - `features.py`: Feature extraction matching `:engine:estimation:FeatureExtractor`.
  - `parity.py`: Golden vector parity gate (TC-EST-01).
  - `split.py`: Participant-grouped dataset splitting (zero subject leakage).
  - `train.py`: Per-site cross-validated ridge regression.
  - `calibration.py`: Split-conformal calibration for 90% prediction intervals.
  - `metrics.py`: MAE, RMSE, Pearson $r$, Bland-Altman agreement, and subgroup metrics.
  - `export.py`: Schema validation, `model.json`, and `MODEL_CARD.md` generation.
  - `loaders/`:
    - `bench_loader.py`: Ingests `testdata/bench/` with SHA-256 manifest verification.
    - `public_loader.py`: Ingests public datasets with licensing auditing.
    - `partner_loader.py`: Ingests partner de-identified exports (`sessions.csv`, `captures.csv`, `tsb_measurements.csv`).
- `requirements.in`: High-level direct dependencies.
- `requirements.txt`: Lockfile with cryptographic SHA-256 hashes for offline builds.
- `DATA_LICENSES.md`: Licensing and pedigree audit for training data.
- `RETRAINING.md`: Step-by-step fork retraining guide for local clinical data (NG-038).
- `Dockerfile`: Pinned offline container definition.
