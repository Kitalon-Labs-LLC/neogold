---
description: "P03: Triage Engine and Signed Config (NeoGold work package; plan first, then wait for approval)"
---
# P03: Triage engine and signed configuration

Phase 1, Specs and engines. Depends on: P01. Backlog: NG-019, NG-020, NG-053. Tests: TC-TRI-01 to TC-TRI-26.

## Read first
- docs/4_System_Architecture.md Section 6 (all of 6.1 to 6.3)
- docs/10_Test_Cases.md Section 0 (synthetic config) and Section 6 (every row)
- docs/8_Security_Compliance_Plan.md Section 14 (H1, H3, H7, H8)
- docs/2_PRD.md Sections 9b and FR-08, FR-09, FR-14, FR-20
- docs/12_Deployment_Runbook.md Section 4.1 and Appendix B.1
- tools/triage_reference.py (the independent Python reference)

## Goal
The pure Kotlin engine that turns inputs and an estimate into one of the actions, exactly as doc 4 Section 6.2 specifies, with tests that would catch any boundary mistake.

## Build
1. `config/schema/triage-config.schema.json` (JSON Schema 2020-12) for the structure in doc 4 Section 6.1, including `triageEnabled`, `validUntil`, `clinicalSignoff`, scope, hard rules R1 to R5, phototherapy threshold tables per gestational band, blood test and recheck zones, `maxIntervalWidth`, and quality thresholds. The schema requires every band to cover 0 to 336 hours and every gestational age from 35 to 44 to map to exactly one band.
2. `testdata/triage/config.synthetic.json` with exactly the values in doc 10 Section 0, and `config/triage-config.json` as a `DRAFT` with zeroed thresholds and `triageEnabled: false`.
3. `:engine:triage`:
   - Config parsing and validation (schema plus semantic checks).
   - Integer-tenth arithmetic with rounding toward more care (threshold down; estimate and upper bound up; lower bound down) and linear interpolation of thresholds in tenths.
   - Hard rules in order, quality fallback, the four zones in order, one first-match reason code.
   - `triageEnabled` false or a non-`SIGNED` config in a public build, or today after `validUntil`: no action is produced; return a typed "triage unavailable" result with the reason.
   - Output type with action, reason code, threshold used, interval, config version.
4. Gradle tasks: `verifyConfigSchema` (validates every JSON config in the repo) and `triageDifferentialTest` (runs 10,000 generated cases through the Kotlin engine and `tools/triage_reference.py`, zero disagreements allowed, fixed seed, plus every boundary from doc 10).
5. Adapt `tools/triage_reference.py` to read the JSON config instead of its built-in dictionary. Keep it independent: do not port Kotlin logic into it.
6. Wire `tools/verify_clinical_signoff.py` into `android-ci.yml` for release builds only (it accepts `triageEnabled: false`).

## Constraints
- No floating-point comparison anywhere in the decision path. Use `StrictMath` if any math is needed.
- Never edit clinical values in `config/triage-config.json`; it stays `DRAFT` with zeros.
- No Android dependencies.

## Tests (write first, from doc 10 Section 6)
- TC-TRI-01 to TC-TRI-26, one test per row, test names containing the ID, expected values copied from doc 10.
- Property tests: monotonic thresholds flagged; every GA maps to one band; tables cover 0 to 336 h.

## Done when
- `./gradlew :engine:triage:test verifyConfigSchema triageDifferentialTest` passes.
- Mutation check: temporarily change one comparison from `>=` to `>` and show in the PR that at least one named test fails; then revert.
- The safety-reviewer subagent has reviewed the diff.

## Stop and ask me
- **At the end:** the config schema is a review gate; I approve it before the clinical lead sees it.
- If any doc 10 expected value disagrees with doc 4 Section 6.2. Do not "fix" either; report it.
