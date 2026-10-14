---
description: "P16: Release Pipeline and Security Gates (NeoGold work package; plan first, then wait for approval)"
---
# P16: Release pipeline and security gates (v0.5)

Phase 7, Release v0.5. Depends on: P12, P15. Backlog: NG-055 (release side). Tests: TC-OPS-01 to TC-OPS-03, TC-OPS-10, TC-SEC-05, TC-SEC-06, TC-SEC-10, TC-SEC-14, TC-OPS-08 (completion).

## Read first
- docs/12_Deployment_Runbook.md Sections 2, 3, 4, 6, 9 and Appendices A and B
- docs/8_Security_Compliance_Plan.md Sections 4 (K1 to K3), 10, 13.3
- docs/9_Test_Plan_Strategy.md Sections 7 and 8

## Goal
A tag produces a signed, checked bundle on the Play internal track, and nothing reaches a tester without passing every gate.

## Build
1. Bring `docs/ci-templates/android-release.yml` into `.github/workflows/`, now that every Gradle task it calls exists. Keep the action SHAs from doc 12 Appendix A unless Dependabot has newer verified ones.
2. Signing config reading the upload key and the K3 manifest key from Gradle properties passed by CI; nothing on disk after the job (always-run cleanup step).
3. Gradle Play Publisher configured for the internal track, `--artifact-dir` upload of the already-checked bundle, and `updatePriority` from a property.
4. CycloneDX SBOM for Android; `tools/check_licenses.py` completed (fails on unknown or copyleft licences until reviewed); gitleaks job; the release refuses any `TEST_ONLY` key in the trust list.
5. A `docs/test-reports/TEMPLATE.md` for the test summary report (doc 9 Section 15).
6. A documented manual step for MobSF (S1) with where to record the result.

## Tests
- TC-OPS-01: a tag with a `DRAFT` config and `triageEnabled: true` fails; with `triageEnabled: false` passes; a mismatched sign-off hash fails.
- TC-OPS-02, TC-OPS-03, TC-OPS-10 (the promotion check runs on Play; give me exact steps).
- TC-SEC-05, 06, 10, 14.

## Done when
- A `v0.5.0` release candidate tag reaches the internal track from CI with both approvals.
- privacy-security-reviewer subagent has reviewed the workflow.

## Stop and ask me
- Before the first real upload to Play; I approve in the `release` environment.
