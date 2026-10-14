# NeoGold

NeoGold is a free, open-source Android app (Kotlin, Jetpack Compose) that estimates newborn jaundice risk from a phone photo of the baby's chest next to a signed color card, fully offline. A small per-study backend (FastAPI, PostgreSQL 16, Caddy, Docker Compose) receives consented research uploads only.

**Intended use (PRD D11):** decision support for trained health workers. The app suggests a next step; the health worker decides. It does not diagnose.

**Current release target (PRD D10):** v1.0 is a closed research build for the partner's validation study, distributed only on Play closed testing. The screening flow is built and tested but hidden (`triageEnabled: false` in the config).

---

## Source of truth

All design decisions live in `docs/`. Read the sections a task names before writing code. If code and docs disagree, stop and ask. If you change behavior, update the doc in the same pull request.

| Topic | Where |
|---|---|
| Requirements (FR, NFR), decisions D1 to D12 | `docs/2_PRD.md` |
| Backlog items (NG-xxx), build order | `docs/3_Feature_Backlog.md` |
| Modules, engines, triage algorithm, ADRs | `docs/4_System_Architecture.md` |
| Room and PostgreSQL schemas | `docs/5_Database_Schema.md` |
| Screens S01 to S25, R01 to R09, tokens, strings, banned words | `docs/6_UI_UX_Guidelines.md` |
| Study Intake API contract | `docs/7_API_Documentation.md`, `docs/api/openapi.yaml` (source of truth) |
| Keys, encryption, hardening, threats, hazards H1 to H11 | `docs/8_Security_Compliance_Plan.md` |
| Test strategy and the 230 test cases (TC-xxx) | `docs/9_Test_Plan_Strategy.md`, `docs/10_Test_Cases.md` |
| CI, deployment, backups, release procedure | `docs/12_Deployment_Runbook.md` |
| Work packages and order | `docs/14_Build_Plan.md` |

---

## Non-negotiable rules

### Clinical safety
- Never change clinical values: thresholds, rules, gestational bands, or clinical wording in `config/triage-config.json` or clinical strings. Only the clinical lead changes them, with a signed `CLINICAL_SIGNOFF.md` (PRD 9b). Tests use `testdata/triage/config.synthetic.json`.
- Triage compares integer tenths of mg/dL, rounding toward more care: threshold down, estimate and upper bound up, lower bound down. One reason code, the first rule that matched (doc 4 Section 6.2).
- Pure engines use `StrictMath` for powers, roots, and exponentials so both ABIs give bit-identical results.
- Never weaken, skip, quarantine, or delete a test to get CI green. A failing `TC-TRI`, `TC-EST`, or `TC-CARD` test blocks all other work.
- No silent defaults on clinical inputs (gestational age, safety questions, consent).
- UI strings never use the banned words in doc 6 Section 10.2, in English or Bangla. Every result is labeled as a suggested next step.

### Privacy
- No names, birth dates, phone numbers, national IDs, locations, or free-text fields. Age is stored in hours.
- Screening images are never written to disk.
- The screening path makes no network calls. Only `:sync:research` may depend on the HTTP client; a Gradle check enforces this.
- No analytics or third-party crash SDKs. No new Android permission or exported component without updating the allow-lists in `tools/check_manifest.py` and doc 8 Section 6.1 in the same PR.
- Never log personal values. Crash reports drop exception messages.
- Real newborn data never enters the repository, tests, logs, screenshots, or issues. Use synthetic or bench data.

### Security
- No secrets in the repository. Signing keys, keystores, and passwords come from CI secrets or offline machines only.
- GitHub Actions are pinned to full commit SHAs. Scanner jobs get no secrets.
- Server request bodies reject unknown fields (`extra = "forbid"`); the server follows the verification order in doc 7 Section 4.3 exactly.

### Engineering
- Engines (`:engine:colorscience`, `:engine:estimation`, `:engine:triage`, `:engine:cardtrust`) are pure Kotlin with no Android dependency and are tested on the JVM.
- Budgets: download under 25 MB, peak memory under 120 MB, capture to result p95 under 10 s on the reference phones (doc 4 Section 5.6).
- `minSdk` 26, `targetSdk` 36, ABIs `arm64-v8a` and `armeabi-v7a`. Every native library 16 KB aligned.
- Ask before adding any dependency. Prefer none. Architectural choices go into the ADR table (doc 4 Section 15).
- The on-device database migrates forward only. Server migrations are expand-only within a release.

---

## Repository layout

```
android/        Gradle project: :app, :feature:*, :engine:*, :core:*, :sync:research
backend/        Study Intake Service (FastAPI), Alembic migrations, admin CLI, deploy/
training/       Python training pipeline, pinned environment
card/           Card v1 design files, print spec, neogold-sign tool
config/         triage-config.json, trust-list.json, schemas
testdata/       golden/, bad/, triage/, api/, bench/ (synthetic and bench data only)
tools/          release gates and checks (doc 12 Appendix B)
docs/           the design documents; docs/api/openapi.yaml; docs/ci-templates/
.claude/        commands/ (one per work package), agents/ (reviewers)
```

---

## Commands

Fill this in as work packages create them; keep it current.

| Purpose | Command |
|---|---|
| Android unit tests | `cd android && ./gradlew testReleaseUnitTest` |
| Engine checks | `./gradlew verifyGoldenVectors triageDifferentialTest verifyConfigSchema` |
| Android lint | `./gradlew lintRelease detekt` |
| Backend tests | `cd backend && pytest -q` |
| Requirement coverage | `python3 tools/trace.py docs` |
| Release gates | `python3 tools/verify_clinical_signoff.py ...`, `tools/check_manifest.py`, `tools/check_network_config.py`, `tools/check_16kb.sh` |

---

## Session workflow

Every work package prompt (`/ng-pNN`) follows this:

1. Read the "Read first" sections in the prompt. Do not skim; the details are the requirements.
2. Plan: list files to create or change, tests by `TC-` ID, open questions, and risks. Stop and wait for approval.
3. Work on a branch `pNN-short-name`, in small commits.
4. For engines, write the tests first from doc 10, then the code.
5. Run the checks for the touched modules and `python3 tools/trace.py docs`.
6. Before finishing, run the `safety-reviewer` subagent on any change to engines, config, quality gate, or clinical UI, and the `privacy-security-reviewer` on any change to data, permissions, network, crypto, or backend. Fix what they find or report it.
7. Update docs and the Commands table if anything changed.
8. Open a pull request: what was built, `TC-` IDs now passing, doc changes, anything left open. The second approver reviews every PR.

## Definition of done

- All named tests exist, pass, and cite their `TC-` IDs in the test name or a comment.
- No new lint or detekt warnings; no TODOs without a linked issue.
- CI green, including the coverage check.
- Docs match the code.
- The PR description lists what a reviewer should check by hand.
