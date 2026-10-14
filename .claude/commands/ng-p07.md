---
description: "P07: Training Pipeline (NeoGold work package; plan first, then wait for approval)"
---
# P07: Training pipeline (Python)

Phase 2, Training. Depends on: P04 golden vectors, approved P06 model schema. Backlog: NG-032, NG-033, NG-038 (retraining part). Tests: TC-OPS-07, TC-EST-05 (real coverage).

## Read first
- docs/4_System_Architecture.md Sections 5.4, 5.5, 11
- docs/2_PRD.md Sections 9 and NFR-11
- docs/1_Market_Competitor_Analysis.md Sources (public datasets)
- config/schema/model.schema.json

## Goal
A reproducible pipeline that rebuilds the model from data, writes `model.json` and a model card, and computes features exactly the way the phone does.

## Build
1. `training/` with a pinned environment (lockfile with hashes) and a Dockerfile; no network access at train time.
2. Feature extraction that reproduces `:engine:colorscience` and `:engine:estimation` from the same golden vectors (parity at 1e-4); fail the run if parity breaks.
3. Data loaders for: (a) bench phantom captures in `testdata/bench/`, (b) any public dataset whose licence allows training and redistribution of the derived model, checked and recorded in `training/DATA_LICENSES.md`, and (c) the partner's de-identified export format (doc 7 Section 10, export; doc 8 Section 5.2).
4. Ridge regression per site with cross-validated regularization, split conformal calibration for a 90% interval, metrics (MAE, correlation, Bland-Altman, coverage, by skin-tone group where labels exist).
5. Outputs: `model.json` (schema-valid), `MODEL_CARD.md` (data, metrics, limitations, intended use per PRD D11), and a metrics JSON. A `make reproduce` target that a second person can run (TC-OPS-07).
6. `training/RETRAINING.md`: how a fork retrains on local data (NG-038).

## Constraints
- Never commit data files; only manifests with hashes.
- If no usable public dataset exists, train on bench phantoms to prove the pipeline and say plainly in the model card that the model has no clinical meaning.

## Done when
- `make reproduce` produces byte-identical `model.json` twice in a row.
- Parity test passes; metrics and model card in the PR.

## Stop and ask me if
- A dataset's licence is unclear. Do not use it until I confirm.
