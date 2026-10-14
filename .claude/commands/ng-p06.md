---
description: "P06: Estimation Engine and Model Format (NeoGold work package; plan first, then wait for approval)"
---
# P06: Estimation engine and model format

Phase 1, Specs and engines. Depends on: P04. Backlog: NG-015, NG-016. Tests: TC-EST-01, TC-EST-05, TC-EST-06, TC-EST-07.

## Read first
- docs/4_System_Architecture.md Sections 5.5 and 12
- docs/2_PRD.md Section 9 and FR-15, FR-18, FR-19
- docs/10_Test_Cases.md Section 5

## Goal
On-phone estimation from corrected color features to an estimate with a 90% interval, from a versioned `model.json` the training pipeline writes.

## Build
1. `config/schema/model.schema.json`: model version, training data summary, feature list with names and order, per-site standardization means and scales, ridge coefficients and intercept, split-conformal residual quantile for 90%, multi-site weights, and minimum app version.
2. `:engine:estimation`: feature extraction from corrected window Lab (mean L*, a*, b*, b*/L*, a*/b* per site, as doc 4 Section 5.5 lists; the feature list in `model.json` is authoritative), standardization, dot product, interval from the stored quantile, multi-site weighted combination, and typed errors for a missing or invalid model.
3. `testdata/models/model.synthetic.json`, clearly not clinical, for tests.
4. `verifyGoldenVectors` Gradle task comparing Kotlin features and estimates with `testdata/golden/` vectors (from P04, and later from P07) at 1e-4.

## Tests
- TC-EST-01 (golden parity), TC-EST-05 (coverage harness on synthetic data now; real coverage reported by P07), TC-EST-06 (invalid model disables estimation), TC-EST-07 groundwork (identical integer-tenth outputs; the two-ABI run happens in P10).

## Done when
- `./gradlew :engine:estimation:test verifyGoldenVectors` passes.
- The model schema is ready for P07.

## Stop and ask me
- **At the end:** the model format is a review gate.
