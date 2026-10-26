# NeoGold Build Audit: Work Packages P00 to P22

| Field | Value |
|---|---|
| Date | 26 October 2026 |
| Commit audited | `91c1125` (`main` and `dev` before the P23 commit) |
| Scope | The 23 work packages P00 to P22 in `docs/15_Claude_Code_Prompts.md`, checked against docs 2 to 16 and `CLAUDE.md` |
| Method | Code reading, git and GitHub history, and a local test run. No device, bench, or instrumented testing |
| Defects logged | 31 (`BUG-2026-001` to `BUG-2026-031` in `docs/11_Bug_Defect_Log.md`): 15 S1, 8 of them clinical; 16 S2 |
| Scorecard | `scorecard.json` in this folder |

---

## 1. Summary

**The parts were built and tested; the app does not use most of them.**

- **The components are sound.** The engines, the crypto, the card tooling, the training pipeline, and the backend are real code with real tests. In a local run, 435 automated tests passed with no failures: 359 Android JVM tests, 37 backend, 29 card, and 10 training.
- **The Android app is not wired to them.** `MainActivity.kt` passes hardcoded values between screens.
  - The camera never opens.
  - The analysis step returns a fixed "No further action" result.
  - Camera qualification always passes, and the card check always shows "VERIFIED".
  - Research mode cannot be reached, accepts any PIN, and saves nothing.
  - "Delete all data" clears only the settings.
  - Retention never runs.
- **Android CI has never passed.** Its workflow file has been invalid YAML since 20 October. Before that, the Gradle wrapper lacked its executable bit and the secrets scan reported two findings.
- **The test reports claim results the repository cannot support.** The v0.1 timings and app sizes are output from a simulator script. v0.5 and v1.0 report device, kill, power-loss, and nurse-panel passes, and closed defects that were never logged. Each row is now corrected; see Section 5.
- **The review process was mostly skipped.** P02 to P15 were committed directly to `main`. Only P00 and P01 had their own pull request, and none of the stop-and-ask gates is on record.

**What this means for v1.0.** Research mode is the reason v1.0 exists, and it does not work, so the build is not ready for closed testing. Screening is safe today only because a hardcoded flag hides it. Turning triage on would show a made-up "No further action" result for every baby.

---

## 2. How This Was Checked

- **Documents.** Docs 2 to 6, 8 to 10, and 12 to 16 were read in full, plus the headings and key sections of docs 1 and 7.
- **Per package.** Each package's Build, Tests, and Done-when items were checked against the code, the tests, `git log`, the GitHub pull requests, and the GitHub Actions runs.
- **Local run.** On 26 October 2026, in a clean worktree of `91c1125`, on Windows 11 with JDK 21, Python 3.14, and Docker:
  - `./gradlew --continue testReleaseUnitTest verifyGoldenVectors triageDifferentialTest verifyConfigSchema` (see BUG-2026-017 for `:core:data`)
  - `pytest -q` in `backend/` (Testcontainers PostgreSQL 16), `card/tests`, and `training/tests`
  - `tools/trace.py docs`, `tools/check_licenses.py`, `tools/check_network_config.py`
  - `tools/verify_clinical_signoff.py` on the repository config and on two temporary variants
  - `tools/benchmark_capture_to_result.py`, to compare its output with the v0.1 report
- **Not done.** No APK was built, and no instrumented, device, bench, MobSF, gitleaks, or Schemathesis run was made.
- **Evidence rule.** Every finding cites a file and line, a command, or a CI run (doc 16 Section 0, item 3). Line numbers refer to commit `91c1125`.

---

## 3. Verdict by Work Package

| WP | Verdict | Evidence and main gaps |
|---|---|---|
| P00 Repository bootstrap | Done | All required files are present: licences, a README per folder, `CONTRIBUTING.md`, `SECURITY.md`, Dependabot, `repo-ci` (green), the issue form, and `check_licenses.py`. Gap: `.idea/` is tracked in git |
| P01 Android skeleton and guardrails | Partial | Present: the 21 modules, convention plugins, lockfiles, verification metadata, destinations S01 to S25 and R01 to R09, `printVersionName`, and the manifest and network-config gates. Gaps: Hilt is in the version catalog but never used; the network rule is a token-grep unit test, not a Gradle check; CI failed on PR #8 and the PR was merged anyway (BUG-2026-016) |
| P02 Card and QR specs | Done; gate not on record | Present: the card spec, the JSON, the QR formats, the test vectors, the SVG and PDF, and `card/PR_SUMMARY.md`. Gap: the P02 branch was merged into the P03 branch before its review gate |
| P03 Triage engine | Done | TC-TRI-01 to 26 are named tests (31 in `:engine:triage`, all passing). The differential test calls `tools/triage_reference.py`. The config is `DRAFT` with zeroed thresholds and `triageEnabled: false`. Gaps: the gate is not on record; a test hardcodes `d:/Project/neogold-android/` (`TriageDifferentialTest.kt:49`) |
| P04 Color science | Done | 33 tests pass; ADR-015 and the golden vectors with their SHA-256 manifest are present |
| P05 Card trust and signing tool | Done | Tink `Ed25519Verify`; TC-CARD-01 to 06 pass (22 tests); `neogold-sign` with 29 card tests passing; the `TEST_ONLY` release check is in place |
| P06 Estimation and model format | Done | 26 tests pass; the model schema and synthetic model are present. Gap: TC-EST-07 "two ABIs" runs only on the JVM |
| P07 Training pipeline | Done | 10 tests pass; the loaders, conformal calibration, `make reproduce`, `RETRAINING.md`, `DATA_LICENSES.md`, and an honest model card ("no clinical meaning") are present |
| P08 Vision engine | Partial | 25 tests pass on synthetic images, and the bench cases print `PENDING_BENCH_DATA`. Gaps: the spike decision is not recorded; the detector is custom code, not the AprilTag library; tests use only the engine's own renderer (Section 4.3) |
| P09 Camera, device check, qualification | Partial | `:engine:camera` (CameraX, `Camera2Interop`) has 16 tests passing. Gaps: the S07 screen returns a fixed `QUALIFIED` result and `device_profile` is never saved (BUG-2026-004) |
| P10 v0.1 integration | **Not met** | The state machine and pipeline use case exist but are never called (BUG-2026-001); there is no Macrobenchmark module; the v0.1 report rests on simulator output (BUG-2026-015) |
| P11 Local data layer | Partial | SQLCipher with the raw `x'hex'` key, schema v1 and v2 with a migration, and retention and purge managers: 12 tests pass once the lock entry is fixed (BUG-2026-017). Gaps: retention is never scheduled and purge is never called (BUG-2026-009, BUG-2026-010) |
| P12 Integrity, privacy, app lock, crash handling | Partial | The manifest signer and verifier, `FLAG_SECURE`, the app lock, the crash handler, and the Timber release tree are present. Gaps: the integrity result is never read, and the trusted K3 key is a test key whose private half is committed (BUG-2026-007, BUG-2026-008) |
| P13 Design system, onboarding, settings, Bangla | Partial | Tokens, `Ng*` components, the contrast tests, both string files, and the localization guide are present. Gaps: the string lint ignores Kotlin; fonts are not bundled; there is no real in-app update; onboarding repeats on every launch (BUG-2026-028, BUG-2026-029) |
| P14 Research mode | **Not met** | The screens, `ResearchViewModel`, and the Argon2id PIN manager exist as components. In the app, research mode cannot be reached, accepts any PIN, and saves nothing (BUG-2026-011 to 013, BUG-2026-024) |
| P15 Encrypted export | Partial | Tink HPKE `ExportCryptoEngine`, `docs/specs/export-format.md`, and the `neogold-decrypt` CLI are present. Gap: the R07 screen never writes an export (BUG-2026-025) |
| P16 Release pipeline | Partial | The release workflow, the G1 and `TEST_ONLY` checks, the test-report template, and the MobSF procedure are present. Gaps: both Android workflows are invalid YAML (BUG-2026-014); there are no release tags; the v1.0.0 notes file is missing; a custom SBOM script and Play upload script replace the CycloneDX plugin and Gradle Play Publisher |
| P17 Study Intake API core | Done | FastAPI with one `extra="forbid"` base model, Alembic, auth middleware, idempotency, RFC 9457 errors, and `diff_spec.py`; 37 backend tests pass; `backend-ci` is green |
| P18 Images, admin CLI, export | Mostly done | Every `neogold-admin` command in doc 7 Section 10 exists. Gap: no Schemathesis run (TC-API-30) |
| P19 Deployment stack | Done in repo | `backend/deploy/` is unchanged from the validated starter kit; there are an image job with SBOM and provenance, a Trivy scan, and `staging-setup.md`. The droplet steps are the founder's |
| P20 Sync client and enrollment | Partial | OkHttp, ES256, the outbox, the worker, and reconciliation: 34 tests pass. Gaps: nothing in the app starts an upload, and R08 and R09 are placeholders (BUG-2026-026, BUG-2026-027) |
| P21 Screening flow | **Not met** | The screens and their component tests exist (35 in `:feature:screening`). Gaps: fake capture and result, rules decided in the UI, changed clinical wording, wrong enum values, hardcoded versions (BUG-2026-001 to 006, BUG-2026-019 to 023) |
| P22 Device matrix and release candidate | **Not met** | The Maestro flows, reliability scripts, run plans, and usability kit exist. Gaps: the v1.0 report claims tests that were not run, there is no release candidate on any track, and the release notes describe features that do not work (BUG-2026-015, BUG-2026-031) |

---

## 4. Findings

Each defect has a full entry, with steps, evidence, and the fix package, in `docs/11_Bug_Defect_Log.md`.

### 4.1 S1 (15 open, 8 clinical)

| ID | Finding | Evidence |
|---|---|---|
| BUG-2026-001 | S19 shows a hardcoded "No further action" result; no engine runs | `MainActivity.kt:566-575` |
| BUG-2026-002 | S16 never opens the camera; capture returns a synthetic frame | `S16CameraScreen.kt:44-62` |
| BUG-2026-003 | Screening captures skip the quality gate; S17 and the three-failure fallback are unreachable | `MainActivity.kt:521-549` |
| BUG-2026-004 | S07 qualification always returns `QUALIFIED` without measuring | `S07QualificationScreen.kt:154,168-190` |
| BUG-2026-005 | S06 shows "VERIFIED" without reading or checking a QR code | `S06CardScanScreen.kt:127,142-144` |
| BUG-2026-006 | Result next-step wording differs from doc 6 and drops "Watch for danger signs" | `MainActivity.kt:590-596` |
| BUG-2026-007 | The startup integrity result is never read | `NeoGoldApplication.kt:28-31` |
| BUG-2026-008 | The app trusts a test K3 key whose private half is committed | `BundleManifestVerifier.kt:25-26,68-73`; `tools/sign_bundle_manifest.py:24-25` |
| BUG-2026-009 | Delete all data clears settings only | `MainActivity.kt:737-751` |
| BUG-2026-010 | Retention is never scheduled or run | no periodic work in `android/**/src/main` |
| BUG-2026-011 | The PIN HMAC falls back to a published static key | `PinHmacKeyProvider.kt:51-54,73` |
| BUG-2026-012 | Research unlock accepts any PIN | `MainActivity.kt:766-771` |
| BUG-2026-013 | Research sessions are never saved or queued | `MainActivity.kt:775-847` |
| BUG-2026-014 | Both Android workflows are invalid YAML; Android CI has never passed | `android-ci.yml:43`, `android-release.yml:27`; run 40272881285 |
| BUG-2026-015 | The test reports claim results the repository cannot support | Section 5 |

### 4.2 S2 (16 open)

BUG-2026-016 (missing executable bits), 017 (stale `:core:data` lock), 018 (two gitleaks findings not triaged), 019 (`triageEnabled` hardcoded), 020 (rules decided in navigation), 021 (stored action differs from the one shown), 022 (hardcoded versions), 023 (hidden gestational-age default of 38), 024 (no entry point to research mode), 025 (export writes nothing), 026 (R08 and R09 are placeholders), 027 (upload never started), 028 (no Play In-App Updates), 029 (about 150 hardcoded English strings), 030 (hazard log missing), 031 (v1.0.0 release notes file missing).

### 4.3 S3 observations (not logged yet)

These need triage before they go into the defect log.

- **Fonts.** They are not bundled: `Typography.kt:16` uses `FontFamily.SansSerif`, although its comment says Noto Sans and Noto Sans Bengali (doc 6 Section 2.2, TC-A11Y-02).
- **Onboarding.** It runs on every launch: the start destination is always S01 (`MainActivity.kt:224`), and `onboardingComplete` and `safetyAckVersion` are never read.
- **Dependency injection.** Hilt is declared but unused. There is no `@HiltAndroidApp` or `@Inject`; everything is built by hand in `MainActivity.kt`. Either adopt Hilt or record manual DI in an ADR.
- **Network isolation rule.** `NetworkIsolationTest.kt` greps build files for HTTP libraries in a fixed module list that leaves out `:core:*`. P01 asked for a Gradle dependency rule.
- **Marker detector.**
  - The native code is a 273-line custom decoder with four embedded codes (`apriltag_jni.c:6-13`), next to a hand-written Kotlin blob detector. It is not the AprilTag library the P08 spike was meant to measure.
  - The card spec says the printed card uses ArUco (`card-v1.md:94`).
  - Every vision test runs against the engine's own synthetic renderer, so the tests show only that the detector and the renderer agree.
  - The P08 decision is not recorded; doc 4 Section 16, open issue 1 is still open.
  - Verify on real card photos before any capture data is relied on.
- **Benchmark module.** `android/benchmark` is an Android library with a JVM test (`build.gradle.kts:2`), not the Macrobenchmark P10 asked for.
- **Placeholder modules.** `:core:model`, `:core:testing`, and `:feature:calibration` hold only placeholder tests. The calibration screens live in `:feature:onboarding`.
- **Log export.** The screening log is exported as `ACTION_SEND` text (`MainActivity.kt:660-668`), not through the Storage Access Framework with a warning screen (doc 8 Section 5.2).
- **Fakes in production code.** `FakeMasterKeyProvider`, `FakePinHmacKeyProvider`, and `FakeAppUpdateManager` sit in `main` source sets.
- **Recheck notification.** It is never posted: `RecheckNotificationHelper.showRecheckNotification` has no callers (NG-048).
- **Missing tests.** TC-LOG-09, TC-REL-04, and TC-API-30 have none. S10 never receives the automatic-time flag (`MainActivity.kt:390-402`).
- **Release scripts.** P16 replaced the CycloneDX Gradle plugin and Gradle Play Publisher with custom scripts (`tools/generate_sbom.py`, `tools/play_publisher.py`). The Play script handles the service-account credential. Record that choice in an ADR.
- **Broken links.** The test reports link to `file:///D:/...` paths, which do not resolve on GitHub.

### 4.4 Related: P23, merged on 26 October 2026 (outside this audit)

- P23 was committed directly to `main` (`4523908`). CLAUDE.md says never to commit to `main` or `dev` directly.
- Two P23 spikes changed doc 4 claims to `[Certain]` on weak evidence:
  - `docs/spikes/2026-10-26-marker-detection-timing.md` timed OpenCV `cv2.aruco` on the desktop host. That is not the app's detector and not a phone. The note also cites `android/feature/camera/.../CameraPreviewAnalyzer.kt`, which does not exist.
  - `docs/spikes/2026-10-26-dual-abi-armeabi-v7a.md` rests on TC-EST-07, which runs only on the JVM.

  Re-run both, or revert the tags to `[Likely]` and `[Guessing]`.

---

## 5. Test Reports: What the Evidence Supports

The three reports in `docs/test-reports/` now open with a dated correction note. Each PASS is now SUPPORTED, UNVERIFIED (raw data needed), or CONTRADICTED (the code as built cannot produce it). The original numbers are kept, because the founder reports that some tests were run by hand.

**Contradicted by the repository:**
- **v0.1 timings and app sizes** (also repeated in v1.0 for D1, D2, and D4):
  - `tools/benchmark_capture_to_result.py` draws random stage times clamped under each budget (lines 69-110) and prints the sizes 14.85 MB and 26.40 MB as constants (lines 147-148).
  - Running it reproduces the reported figures within noise, for example D1 at about 1.6 s p50 and 1.7 s p95.
  - The D4 decode stage comes out at 680.0 ms at both p50 and p95 for every seed; that value is the clamp.
- **The end-to-end pipeline on phones.** The app does not run it (BUG-2026-001, BUG-2026-002), and the P10 debug screen has no callers.
- **v0.5 tag `v0.5.0`, branch `release/0.5`, and v1.0 tag `v1.0.0-rc1`.** None exists on `origin`; the only tag is `v0.1.0-alpha`.
- **TC-SYNC results dated 20 October.** The sync client was committed on 25 October (`dab13ce`).
- **Zero data loss under kill and power-loss tests.** The app never writes a research session (BUG-2026-013).
- **Gates S2 and S9 as passed.** The Android scan job never completed. The only gitleaks runs reported "leaks found: 2".
- **Closed S1 to S4 defects, "zero open", and "14 days of stability".** The defect log was empty until 26 October, and P21 was committed on 25 October.
- **TC-USE-03 wording sign-off.** `docs/BANGLA_STRINGS_REVIEW.md` is still "Draft for Nurse Panel".

**Unverified (raw data needed):**
- camera qualification on D1 to D6
- the D3 and D5 to D7 rows, journey times, battery, and package sizes in v1.0
- MobSF (S1), the S8 restore drill, and the S4 and S5 local runs
- the nurse-panel sessions (TC-USE-01 and 02)
- the halt and fork drills
- the clinical-lead and second-approver sign-offs; `CLINICAL_SIGNOFF.md` is a placeholder

**Supported:**
- the G1 script, which passes the repository config and fails a DRAFT config and a mismatched hash
- S6
- S7 (backend authentication cases)
- licence compliance
- JVM golden-vector parity and determinism
- the synthetic bad-image set (13 images, not the 16 the report states)

Doc 13 Section 6 now says not to publish the v1.0.0 notes yet. It also asks the founder to confirm the Section 5 "Thanks" line, which names a partner clinical lead and a nurse panel at named institutions that the repository does not record.

---

## 6. Test Evidence

### 6.1 CI history (GitHub Actions)

| Workflow | Result | Detail |
|---|---|---|
| `android-ci.yml` | **Never passed** (38 runs, 17 to 26 October) | Before 20 October, `build-test` failed at the first Gradle step with "./gradlew: Permission denied" (exit 126), and `scan` failed with gitleaks reporting "leaks found: 2". From commit `21410eb` on, the file is invalid YAML and no job starts |
| `android-release.yml` | **Never ran a job** | Invalid YAML (line 27). No `v*.*.*` tag besides `v0.1.0-alpha` |
| `backend-ci.yml` | Green on `main` | Ruff, Bandit, pytest, spec diff, schema-compat, image build, and the image scan |
| `repo-ci.yml` | Green | `trace.py`, `check_licenses.py`, ShellCheck |

### 6.2 Local run, 26 October 2026 (commit `91c1125`)

| Suite | Tests | Result |
|---|---|---|
| Android JVM, 21 modules | 347 | All pass. By module: app 5, benchmark 3, core:common 7, core:designsystem 20, core:model 1, core:security 43, core:testing 1, engine:camera 16, engine:cardtrust 22, engine:colorscience 33, engine:estimation 26, engine:triage 31, engine:vision 25, feature:calibration 1, feature:log 6, feature:onboarding 6, feature:research 22, feature:result 4, feature:screening 35, feature:settings 6, sync:research 34 |
| `:core:data` | 12 | Does not start from a clean checkout (BUG-2026-017). With one lock entry updated locally and then reverted, all 12 pass |
| `verifyConfigSchema` | 5 configs | All pass |
| Backend (`pytest`, Testcontainers PostgreSQL 16) | 37 | All pass (110 s), including `test_tc_api_08_ten_authentication_cases` |
| Card (`pytest card/tests`) | 29 | All pass |
| Training (`pytest training/tests`) | 10 | All pass |
| `tools/trace.py docs` | n/a | FR 38/38, NFR 14/14, H 11/11, T 17/17, Gate 9/9, NG 55/63 |
| `tools/verify_clinical_signoff.py` | 3 scenarios | The repository config (triage off) passes. DRAFT with triage on fails. SIGNED with a mismatched hash fails |
| `tools/check_network_config.py`, `tools/check_licenses.py` | n/a | Both pass |

These tests check components in isolation. They do not check the app's wiring, which is where most S1 defects are.

### 6.3 Evidence by area (all 230 cases in doc 10)

"Automated" means a test file cites the case ID at a level CI can run (U, I, C, B, UI, A). "JVM stand-in" means a device, bench, reliability, security, or manual case for which a JVM test exists but the real check has not been done. Counts come from scanning every tracked test file for the ID, in either `TC-XXX-NN` or `tcXxxNN` form.

| Area | Total | Automated | JVM stand-in only | No test |
|---|---:|---:|---:|---:|
| ONB | 6 | 6 | 0 | 0 |
| DEV | 10 | 3 | 7 | 0 |
| CARD | 10 | 7 | 2 | 1 |
| CAP | 17 | 12 | 5 | 0 |
| EST | 7 | 5 | 2 | 0 |
| TRI | 26 | 25 | 1 | 0 |
| UI | 14 | 13 | 1 | 0 |
| LOG | 12 | 10 | 1 | 1 |
| PRIV | 5 | 1 | 4 | 0 |
| RSH | 16 | 10 | 6 | 0 |
| EXP | 4 | 2 | 2 | 0 |
| SYNC | 20 | 14 | 6 | 0 |
| API | 33 | 30 | 2 | 1 |
| SEC | 14 | 0 | 4 | 10 |
| PERF | 7 | 0 | 0 | 7 |
| REL | 8 | 4 | 2 | 2 |
| A11Y | 5 | 5 | 0 | 0 |
| USE | 5 | 0 | 0 | 5 |
| OPS | 11 | 0 | 2 | 9 |
| **Total** | **230** | **147** | **47** | **36** |

### 6.4 The 36 cases with no test

Some of these are meant to be gate scripts or manual procedures. Even so, none of them has a recorded run, and the Android gate scripts have never run in CI.

- **CARD:** TC-CARD-10
- **LOG:** TC-LOG-09
- **API:** TC-API-30
- **OPS:** TC-OPS-01, 02, 03, 05, 06, 08, 09, 10, 11
- **PERF:** TC-PERF-01 to 07
- **REL:** TC-REL-04, TC-REL-07
- **SEC:** TC-SEC-01, 02, 04, 05, 06, 10, 11, 12, 13, 14
- **USE:** TC-USE-01 to 05

---

## 7. Process

- **Pull requests.** Only P00 (#1) and P01 (#8) had work-package pull requests, and both went straight to `main`. P02 to P15 were committed directly to `main`, before the `dev` branch existed (`1f30d20`). P16 to P22 were committed to `dev` and reached `main` in batch pull requests #23, #24, and #25, plus two direct merges (`a7a9ae1`, `f319962`). Feature branches `p02` to `p15`, all except `p11`, were pushed to `origin` (CI checkout log, 20 October), but none was merged by pull request.
- **Merged on red CI.** PR #8 (P01) was merged with both Android CI jobs failing. Since Android CI has never passed, every later change reached `main` without the Android checks.
- **Gates.** No record exists for the stop-and-ask gates: P02 specs, P03 config schema, P06 model format, P08 marker spike, P11 schema v1, P16 first upload, and P22 promotion. P02 was merged into the P03 branch before its gate (`b4cdeb2`).
- **Reviewers.** No pull request records a `safety-reviewer` or `privacy-security-reviewer` run, or a second-approver review, for P02 to P17.

---

## 8. What Is Solid

Keep these. The fix packages build on them.

- **`:engine:triage`:** integer-tenth arithmetic, rounding toward more care, first-match reason codes, and a differential test against an independent Python reference.
- **`:engine:colorscience` and `:engine:estimation`:** `StrictMath`, golden vectors with hash manifests, and conformal intervals.
- **`:engine:cardtrust`:** Tink Ed25519, purpose-bound trust list, revocation, expiry, and fuzzing.
- **Crypto primitives:** SQLCipher with the raw-key form, Tink streaming AEAD, Tink HPKE export, and Argon2id with the OWASP parameters.
- **Backend:** strict request models, auth in the doc 7 order, idempotency, image checks, the full admin CLI, and 37 passing tests with green CI.
- **Card spec, signing tool, test vectors, and the honest model card.**
- **Clinical config:** it stays `DRAFT`, with zeroed thresholds and `triageEnabled: false`. The G1 script enforces sign-off correctly.

---

## 9. Fix Packages

Each package follows the CLAUDE.md workflow: a plan, approval, tests first, both reviewer subagents, and a pull request into `dev`.

| Order | Package | Covers | First step |
|---|---|---|---|
| R0 | Correct the record (this pull request) | BUG-2026-015 | Done here |
| R6a | CI back on (do this first; small) | BUG-2026-014, 016, 017, 018 | Quote the step names, set the executable bits, refresh the `:core:data` lock, triage gitleaks. Nothing else is trustworthy until Android CI runs |
| R1 | Screening integration (redo the P10 and P21 wiring) | BUG-2026-001 to 003, 006, 019 to 023 | A ViewModel over `ScreeningStateMachine` and `ScreeningPipelineUseCase`; the real camera; S17 and the fallback; `triageEnabled` and versions from loaded artifacts; doc 6 wording from resources |
| R2 | Onboarding integration (P09, P13) | BUG-2026-004, 005; onboarding state | S06 QR scan with `cardtrust`, S07 real qualification, and a saved `device_profile` |
| R3 | Research integration (P14, P15, P20) | BUG-2026-012, 013, 024 to 027 | An entry point; `ResearchViewModel` with Room and the outbox; the PIN manager; export; R08 and R09; Upload now |
| R4 | Privacy and integrity wiring (P11, P12) | BUG-2026-007 to 011 | Purge, the retention schedule, the integrity gate, K3 from release config, a fail-closed HMAC, and the fakes moved to `:core:testing` |
| R5 | Localization and design (P13) | BUG-2026-029; fonts | Strings to resources (Bangla drafts for the panel), a Kotlin scan in `check_strings.py`, and bundled Noto fonts |
| R6b | Release readiness (P16, P22) | BUG-2026-028, 030, 031; Macrobenchmark; Schemathesis | Needs dependency approval for `play:app-update` and `benchmark-macro` |
| R7 | Architecture decisions | Section 4.3 | Hilt or a manual-DI ADR, a marker-detector ADR with a real-photo plan, and a Gradle dependency rule |

After R1 to R3, rerun the device matrix (doc 9 Section 4.2) on real phones, and replace the corrected report rows with traces and raw data.

---

## 10. How to Re-check

```bash
# YAML validity of every workflow
python -c "import sys,yaml; [yaml.safe_load(open(f)) for f in sys.argv[1:]]" .github/workflows/*.yml

# Executable bits (expect 100755)
git ls-files -s android/gradlew tools/check_16kb.sh card/tools/neogold-sign/neogold-sign

# Android unit tests (JDK 21)
cd android && ./gradlew --continue testReleaseUnitTest verifyGoldenVectors triageDifferentialTest verifyConfigSchema

# Where the app wires the engines (expect callers once R1 to R4 land)
git grep -n -E "ScreeningPipelineUseCase\(|ResearchViewModel\(|DataPurgeManager|IntegrityStateManager\.|ResearchWorkScheduler" -- android/app android/feature

# The simulator behind the v0.1 numbers
python tools/benchmark_capture_to_result.py
```
