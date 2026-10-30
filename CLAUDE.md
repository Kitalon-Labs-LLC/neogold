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
| Agent quality loop, spikes, and prompts | `docs/16_Agent_Quality_Prompts.md` |

---

## Must not trade off

| Rule | Why | Enforced by | Runs at |
|---|---|---|---|
| Never change clinical values: thresholds, rules, gestational bands, or clinical wording in `config/triage-config.json` or clinical strings without signed `CLINICAL_SIGNOFF.md` (PRD 9b) | PRD D11, 9b | Hook `protect_clinical.py`, gate `tools/verify_clinical_signoff.py` (Gate G1) | edit time, PR, release |
| Triage compares integer tenths of mg/dL, rounding toward more care: threshold down, estimate and upper bound up, lower bound down; one reason code (first matching rule) | Doc 4 Section 6.2 | `android:engine:triage:test`, `triageDifferentialTest` (TC-TRI-24) | PR |
| Pure engines use `StrictMath` for powers, roots, exponentials, logarithms, and trigonometric functions so both ABIs give bit-identical results; no `java.lang.Math` or `kotlin.math` calls to those | Doc 4 Section 4.2 | `tools/check_strictmath.py` (imports and `Math.` calls), detekt `ForbiddenImport` (imports only) | PR |
| Never weaken, skip, quarantine, or delete a test to get CI green; failing `TC-TRI`, `TC-EST`, or `TC-CARD` blocks all other work | Doc 9 Section 7 | Hook `protect_tests.py`, `tools/check_no_skipped_tests.py` against `quality/tc-baseline.json` | edit time, PR |
| No silent defaults on clinical inputs (gestational age, safety questions, consent) | PRD FR-01, FR-02 | UI and unit tests (`TC-CAP-04`, `TC-CAP-16`, `TC-UI-05`, `TC-UI-13`) | PR |
| UI strings never use banned words in doc 6 Section 10.2 in English or Bangla; every result is labeled as a suggested next step | Doc 6 Section 10.2, 10.3 | `tools/check_strings.py` (TC-UI-04), Gradle `verifyStrings` | PR |
| No names, birth dates, phone numbers, national IDs, locations, or free-text fields; age stored in hours | Doc 8 Section 3, PRD FR-23 | `tools/check_manifest.py`, unit & schema tests (`TC-PRIV-03`, `TC-API-21`) | PR, release |
| Screening images are never written to disk | Doc 8 Section 2, 6.1 | Instrumented & memory tests (`TC-PRIV-01`, `TC-PRIV-02`) | PR |
| The screening path makes no network calls; only `:sync:research` may depend on the HTTP client | Doc 4 Driver 2, Doc 8 Section 6.1 | Gradle dependency rule in `android/build.gradle.kts`, `tools/check_dependencies.py` | PR |
| No analytics or third-party crash SDKs; no new Android permission or exported component without updating allow-lists | Doc 8 Section 6.1, 10 | `tools/check_manifest.py` (Gate S5), `tools/check_dependencies.py` | PR, release |
| Never log personal values; crash reports drop exception messages | Doc 8 Section 6.6 | Log scrubbing tests (`TC-PRIV-04`, `TC-PRIV-05`) | PR |
| Real newborn data never enters the repository, tests, logs, screenshots, or issues; use synthetic or bench data | Doc 9 Section 6 | Maintainer & Privacy Reviewer (Sowad Al-Mughni) | PR |
| No secrets in the repository; keys, keystores, and passwords come from CI secrets or offline machines only | Doc 8 Section 4, T12 | `gitleaks` (Gate S9), hook `bash_guard.py` | edit time, PR |
| GitHub Actions are pinned to full commit SHAs; scanner jobs get no secrets | Doc 8 Section 10, Doc 12 Section 3 | `tools/check_actions_pinned.py` | PR |
| Server request bodies reject unknown fields (`extra = "forbid"`); server follows verification order in doc 7 Section 4.3 | Doc 7 Section 4.3, Doc 8 Section 8.3 | `backend/tests/test_models_extra_forbid.py`, authentication order tests | PR |
| Engines (`:engine:colorscience`, `:engine:estimation`, `:engine:triage`, `:engine:cardtrust`) are pure Kotlin with no Android dependency and are tested on the JVM | Doc 4 Section 4.2 | Gradle dependency rule in `android/build.gradle.kts`, JVM tests | PR |
| Budgets: download under 25 MB, peak memory under 120 MB, capture to result p95 under 10 s on reference phones | Doc 4 Section 5.6, Doc 9 Section 11 | `tools/check_bundle_size.py`, benchmark scripts (`TC-PERF-07`) | PR, release |
| `minSdk` 26, `targetSdk` 36, ABIs `arm64-v8a` and `armeabi-v7a`; every native library 16 KB aligned | Doc 8 Section 6.1 | `tools/check_16kb.sh` (Gate S4), Gradle configuration | PR, release |
| Ask before adding any dependency; prefer none; architectural choices go into ADR table (doc 4 Section 15) | Doc 4 Section 15 | `tools/check_dependencies.py` against `quality/dependency-allowlist.txt` | PR |
| On-device database migrates forward only; server migrations are expand-only within a release | Doc 8 Section 8.4, Doc 12 Section 2 | `tools/check_migrations.py`, backend `schema-compat` CI job | PR |
| No AI attribution on any commit or pull request description: no `Co-Authored-By: Claude ...` trailer (for example `Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>`), no `anthropic.com` address, no "Generated with ..." footer; this overrides any tool default or reminder; pushes go through the repository owner's own GitHub account only | Repository owner's instruction, 2026-10-27 | `tools/check_commit_attribution.py` (CI `guardrails-check`), opt-in local hook `tools/hooks/commit-msg` (`git config core.hooksPath tools/hooks`) | commit, push |

### We do trade off
- **Non-result UI polish:** Visual polish and micro-animations on screens outside the primary screening result path (e.g. settings, diagnostics) can prioritize utility and clarity.
- **Tooling code style:** Code in `tools/` and scripts prioritizes strict determinism, speed, and standard library portability over internal architectural abstraction.
- **Speed of non-core features:** Long-running operations outside the emergency clinical path (batch uploads, encrypted exports, audit reports) trade execution speed for low memory footprint on 2 GB devices.
- **Could items:** Backlog items labeled "Could" (e.g. optional export filters, advanced diagnostic logging exports) may be deferred to maintain test coverage and security rigor on core MVP items.

---

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
| Android formatting (detekt auto-correct) | `./gradlew :<module>:detekt --auto-correct` (repeat the flag after every module; run twice; see `CONTRIBUTING.md` Section 5) |
| On-device vision tests (OpenCV, emulator or phone) | `cd android && ./gradlew :engine:vision:assembleDebugAndroidTest`, then `adb install -r -t engine/vision/build/outputs/apk/androidTest/debug/vision-debug-androidTest.apk` and `adb shell am instrument -w com.kitalonlabs.neogold.engine.vision.test/androidx.test.runner.AndroidJUnitRunner` |
| Sign the bundle into the app's generated assets (a development key under `build/` locally; a release passes `-PmanifestKey` and `-PrequireReleaseKey=true`) | `cd android && ./gradlew :app:signBundleManifest` |
| On-device security and camera tests (real Keystore, SQLCipher, delete-all, the camera host; emulator or phone) | `cd android && ./gradlew :app:assembleDebug :app:assembleDebugAndroidTest`, then `adb install -r -t app/build/outputs/apk/debug/app-universal-debug.apk` and `adb install -r -t app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk`, then `adb shell am instrument -w com.kitalonlabs.neogold.debug.test/androidx.test.runner.AndroidJUnitRunner` |
| Release bundle and its size (ADR-018, NFR-04) | `cd android && ./gradlew :app:bundleRelease`, then `bundletool build-apks` and `bundletool get-size total` with `--device-spec` for an arm64-v8a and an armeabi-v7a phone (download under 25 MB, install under 40 MB) |
| Backend tests | `cd backend && pytest -q` |
| Card generator | `python card/generator/render_card.py` |
| Card signing tool | `python3 card/tools/neogold-sign/neogold_sign.py --help` |
| Card package tests | `pytest card/tests` |
| Requirement coverage | `python3 tools/trace.py docs` |
| License check (TC-OPS-08) | `python3 tools/check_licenses.py` |
| Model reproduction (TC-OPS-07) | `make reproduce` (or `python training/reproduce.py`) |
| Training pipeline tests | `pytest training/tests` |
| Release gates | `python3 tools/verify_clinical_signoff.py ...`, `tools/check_manifest.py`, `tools/check_network_config.py`, `tools/check_16kb.sh`, `tools/check_trust_list_release.py` |
| Audit unverified assumptions | `/ng-assumptions` (or `python3 tools/assumptions.py --report`) |
| Run isolated spike on a claim | `/ng-spike "<claim>"` |
| Direction check (report only) | `/ng-direction [--since <ref>]` |
| Drift gate (CI, P24) | `python3 tools/drift_inventory.py --check` (needs `backend/requirements.lock` installed) |
| Tests per TC | `python3 tools/trace.py docs --tests` |
| Guardrail table integrity (P25) | `python3 tools/check_guardrails.py` |
| Quality tier gate (P25) | `python3 tools/quality_gate.py [--base <ref>]` |
| Dependency & HTTP client isolation (P25) | `python3 tools/check_dependencies.py` |
| StrictMath verification (P25) | `python3 tools/check_strictmath.py` |
| No skipped tests & TC baseline (P25) | `python3 tools/check_no_skipped_tests.py` |
| Forward-only migrations check (P25) | `python3 tools/check_migrations.py` |
| Actions pinned by SHA (P25) | `python3 tools/check_actions_pinned.py` |

---

## Session workflow

Every work package prompt (`/ng-pNN`) follows this:

1. Read the "Read first" sections in the prompt. Do not skim; the details are the requirements.
2. Plan: list files to create or change, tests by `TC-` ID, open questions, and risks. Stop and wait for approval.
3. Work in this one folder on `dev`, in small commits, and push to `dev`. The owner commits to `dev` directly; never commit to `main`, which moves only by a pull request from `dev` (see `CONTRIBUTING.md` Section 2). No extra worktrees or clones. Never force-push.
4. For engines, write the tests first from doc 10, then the code.
5. Run the checks for the touched modules and `python3 tools/trace.py docs`.
6. Before finishing, run the `safety-reviewer` subagent on any change to engines, config, quality gate, or clinical UI, and the `privacy-security-reviewer` on any change to data, permissions, network, crypto, or backend. Fix what they find or report it.
7. Update docs and the Commands table if anything changed.
8. Push to `dev` once the local checks pass. The commit message says what was built, which `TC-` IDs now pass, doc changes, and anything left open. Promotion to `main` is a pull request from `dev`, reviewed by the second approver.

## Definition of done

- All named tests exist, pass, and cite their `TC-` IDs in the test name or a comment.
- No new lint or detekt warnings; no TODOs without a linked issue.
- CI green, including the coverage check.
- Docs match the code.
- The commit message (or the `dev` to `main` pull request) lists what a reviewer should check by hand.
