# NeoGold: Claude Code Prompts

| Field | Value |
|---|---|
| Document | 15 (added after the 13-document set) |
| Version | 0.1 |
| Date | 11 October 2026 |
| Owner | Sowad Al-Mughni, Kitalon Labs |
| Depends on | `14_Build_Plan.md`, `CLAUDE.md`, documents 2 to 13 |

One prompt per work package, P00 to P22, in build order. Each prompt is self-contained: paste it into a Claude Code session in the repository root, or run it as a slash command from the starter kit (`/ng-p03` runs P03). Every prompt assumes `CLAUDE.md` is present and follows its session workflow: read, plan, **wait for approval**, build with tests, review with subagents, open a PR.

Document paths in the prompts are relative to the repository (`docs/4_System_Architecture.md`, and so on).

---

## P00. Repository Bootstrap

````markdown
# P00: Repository bootstrap

Phase 0, Foundation. Depends on: nothing. Backlog: NG-001, NG-037. Tests: TC-OPS-08.

## Read first
- CLAUDE.md (all of it)
- docs/14_Build_Plan.md Sections 1, 2, 6
- docs/12_Deployment_Runbook.md Sections 2 and 3, Appendices A and B
- docs/8_Security_Compliance_Plan.md Sections 10 and 13.4

## Goal
A repository that every later work package can build on: the agreed layout, licenses, contribution rules, security policy, and a minimal CI that runs today and grows with the project.

## Build
1. Create the layout in CLAUDE.md "Repository layout" with a README in each top-level folder saying what goes there and which work package fills it.
2. Licenses: `LICENSE` (MIT) for code; `card/LICENSE` and `docs/LICENSE` (CC BY 4.0). Root README: what NeoGold is (intended use from CLAUDE.md, word for word), current status (v1.0 closed research build), how to build, and the line "Install only from Google Play; the signing certificate fingerprint is published here at release."
3. `CONTRIBUTING.md`: the session workflow, branch naming, PR checklist, and the fork rule from doc 8 Section 13.4 (forks that change thresholds, models, or cards use a different name and icon).
4. `SECURITY.md` per doc 8 Section 10: private vulnerability reporting, 3 working-day acknowledgment, 90-day disclosure, a placeholder contact address for me to fill in.
5. `.github/dependabot.yml` for Gradle, pip, Docker, and GitHub Actions (weekly).
6. Leave the full workflows in `docs/ci-templates/` as reference; later packages enable them. Create `.github/workflows/repo-ci.yml` that runs only what works today: `python3 tools/trace.py docs` and ShellCheck on `tools/*.sh`, with actions pinned to the SHAs used in doc 12 Appendix A and `permissions: contents: read`.
7. `.gitignore` for Android, Python, keystores (`*.jks`, `*.keystore`), `.env`, and `local.properties`.
8. `.github/ISSUE_TEMPLATE/bug.yml` from doc 11 Section 8.
9. A CI license check script `tools/check_licenses.py` (placeholder that passes until Gradle and pip dependencies exist; P16 completes it).

## Constraints
- Do not create any Android or backend code in this package.
- Do not edit anything in `docs/`.

## Done when
- `repo-ci.yml` passes on the PR.
- `python3 tools/trace.py docs` prints full FR, NFR, H, T, and Gate coverage.
- TC-OPS-08 partially satisfied (licenses present); note what P16 adds.

## Stop and ask me if
- Any file in the starter kit conflicts with this layout.
````

---

## P01. Android Skeleton and Build Guardrails

````markdown
# P01: Android skeleton and build guardrails

Phase 0, Foundation. Depends on: P00. Backlog: NG-001. Tests: TC-SEC-01, TC-SEC-02 (static part), TC-SEC-04.

## Read first
- docs/4_System_Architecture.md Sections 4.1 to 4.3, 4.6, 12, 14, 15
- docs/8_Security_Compliance_Plan.md Sections 6.1 and 5.3
- docs/12_Deployment_Runbook.md Section 2 (versioning) and Appendix B.2 to B.4
- docs/6_UI_UX_Guidelines.md Section 2 (tokens only)

## Goal
An Android Gradle project with every module from doc 4 Section 4.2, empty but wired, and build-time guardrails that make the privacy and size rules impossible to break by accident.

## Build
1. Gradle version catalog; Kotlin 2.x; AGP current stable; JDK 21 toolchain; `minSdk` 26, `targetSdk` 36; ABI splits `arm64-v8a`, `armeabi-v7a`; R8 full mode; dependency locking on with committed `gradle.lockfile` files; Gradle dependency verification metadata.
2. Modules: `:app`, `:feature:onboarding`, `:feature:calibration`, `:feature:screening`, `:feature:result`, `:feature:log`, `:feature:research`, `:feature:settings`, `:engine:camera`, `:engine:vision`, `:engine:colorscience`, `:engine:estimation`, `:engine:triage`, `:engine:cardtrust`, `:core:model`, `:core:data`, `:core:security`, `:core:designsystem`, `:core:common`, `:core:testing`, `:sync:research`. Pure engines are Kotlin JVM modules with no Android plugin.
3. Convention plugins in `build-logic/` for Android library, JVM library, Compose, Hilt, and test setup, so modules stay one screen long.
4. Hilt, Compose, Material 3, a placeholder theme from doc 6 Section 2 tokens, and a single-activity app with Compose Navigation and empty destinations for S01 to S25 (including S10a) and R01 to R09.
5. `applicationId` `com.kitalonlabs.neogold`; `versionName` and `versionCode` exactly as in doc 12 Section 2, plus the `printVersionName` task.
6. Manifest per doc 8 Section 6.1: `allowBackup="false"`, both backup rule files excluding everything, network security config (cleartext off, system CAs only), no exported components but the launcher. Permissions only as each package needs them; for now just `CAMERA`.
7. Guardrails as Gradle checks wired into `check`:
   - A module dependency rule: only `:sync:research` may depend on OkHttp or any HTTP client; any `:engine:*` or `:feature:*` dependency on one fails the build.
   - Pure engine modules may not depend on any `androidx` or `android` artifact.
   - `tools/check_manifest.py` on the merged release manifest; `tools/check_network_config.py` on the network config; `tools/check_16kb.sh` on the release APK.
8. detekt and Android Lint with warnings as errors for security checks; a baseline only if a generated file needs one.
9. Enable `android-ci.yml` from `docs/ci-templates/` with the tasks that exist now; leave clearly marked TODO steps for tasks later packages add (`verifyGoldenVectors`, `triageDifferentialTest`, `verifyConfigSchema`).

## Constraints
- No engine logic yet; empty modules with one placeholder test each.
- Ask before adding any library beyond: Kotlin stdlib, coroutines, Compose, Material 3, Navigation, Hilt, Lifecycle, JUnit, Truth, Turbine, Robolectric, detekt.

## Tests
- A JVM test in each pure engine module proving it has no Android classes on its classpath.
- TC-SEC-01: `check_manifest.py` passes on the merged manifest.
- TC-SEC-02 static: `check_network_config.py` passes.
- TC-SEC-04: `check_16kb.sh` passes on the release APK (no native libraries yet).

## Done when
- `./gradlew check assembleRelease` passes locally and in CI.
- Adding OkHttp to `:engine:triage` makes the build fail with a clear message (show this in the PR, then revert).
- Release APK download size reported in the PR.

## Stop and ask me if
- AGP or Kotlin versions force a `minSdk` or `targetSdk` change.
````

---

## P02. Card v1 and QR Format Specs

````markdown
# P02: Card v1 design and QR format specifications

Phase 1, Specs and engines. Depends on: P00. Backlog: NG-006, NG-045. This package writes specifications and a generator; it ends at a review gate.

## Read first
- docs/2_PRD.md Section 9a (card strategy and design rules)
- docs/4_System_Architecture.md Sections 5.2, 5.3, 5.4, 7
- docs/7_API_Documentation.md Section 4.1 (signed study profile)
- docs/8_Security_Compliance_Plan.md Sections 4 (keys K4, K5, K10) and 5.2

## Goal
The two specs the document set does not yet contain, precise enough that the vision engine, the card trust engine, the signing tool, and a print shop can all work from them.

## Build
1. `card/spec/card-v1.md`:
   - Physical size (propose a size that fits a newborn's chest and a phone frame at 15 to 25 cm), material, matte lamination, punched skin window size and position.
   - Four corner markers: family (ArUco 4x4 or AprilTag 36h11; list both options with size impact), IDs, size, quiet zone.
   - Patch set of 16 to 24 patches: a neutral gray ramp (at least 6 steps), skin and yellow tones covering light to dark skin with low to high yellowness, and a few saturated references. Duplicate gray and skin patches in opposite corners (doc 4 Section 5.2). Give each patch an ID, nominal CIELAB (D65) target, and position in card millimetres.
   - QR position and size, print tolerances, and the batch acceptance tolerance (in Delta E 2000) that TC-CARD-10 will test.
   - The canonical warped image size (doc 4 suggests 1200 by 760 px) and each patch's and the window's sampling rectangle in that image.
2. `card/spec/qr-formats.md`:
   - **Card batch payload:** format version, batch ID (pattern from `docs/api/openapi.yaml`), issuer key ID, issue and expiry dates, per-patch measured CIELAB in a compact fixed-point encoding, Ed25519 signature over all preceding bytes. Specify the exact byte layout, endianness, and the QR mode and error-correction level, and show that it fits the chosen QR version.
   - **Study profile payload:** the fields in doc 7 Section 4.1 (`study_code`, `display_name`, `partner`, `host` or none, `timestamp_precision`, `export_public_key`, `expires_at`), key purpose `STUDY_ISSUER`, Ed25519 signature.
   - **Trust list** (`config/trust-list.json`): key IDs, public keys, purposes (`CARD_ISSUER`, `STUDY_ISSUER`), validity dates, revoked batch IDs and study codes, and its own JSON Schema.
   - Test vectors: one valid payload of each kind with a test key (clearly marked test-only), and three invalid ones each.
3. `card/generator/`: a small script (Python, no network) that renders card v1 as SVG and print-ready PDF from `card-v1.md` data in a machine-readable form (`card/spec/card-v1.json`), with a placeholder QR. Commit one generated SVG for review.

## Constraints
- No Android code. No real keys; test keys are generated in tests and named `TEST_ONLY_*`.
- Use only Ed25519 for signatures (Tink-compatible), as doc 4 Section 4.3 says.

## Done when
- Both specs, the JSON schema, the test vectors, and the generated SVG are in the PR.
- The PR description includes a one-page summary I can send to the print partner.

## Stop and ask me
- **Always, at the end:** this package ends at a review gate. Do not start P05 or P08 until I approve the specs.
- Before choosing between ArUco and AprilTag if the size difference matters for the card layout.
````

---

## P03. Triage Engine and Signed Config

````markdown
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
````

---

## P04. Color Science Engine

````markdown
# P04: Color science engine

Phase 1, Specs and engines. Depends on: P01. Backlog: NG-013. Tests: TC-EST-02, TC-EST-03, plus standard-conformance tests below.

## Read first
- docs/4_System_Architecture.md Sections 5.2 and 5.4
- docs/9_Test_Plan_Strategy.md Section 5 (bench rig)
- card/spec/card-v1.md if P02 is approved (patch IDs); otherwise use placeholder patch sets in tests

## Goal
Pure Kotlin color math the rest of the pipeline trusts: linearization from the gray ramp, correction fitting, XYZ and CIELAB, Delta E 2000, trimmed statistics.

## Build
1. `:engine:colorscience`:
   - Trimmed mean (drop brightest and darkest 10%) over a pixel region; inner-region selection (60% for patches, 70% for the window).
   - Per-channel monotonic linearization fitted from observed gray patches to measured gray reflectances (monotone piecewise-linear or a monotone spline; justify the choice in a short ADR).
   - 3 x 3 least-squares correction from linearized RGB to XYZ (D65), and a second-order root-polynomial correction behind a flag for later evaluation.
   - XYZ to CIELAB (D65), CIELAB to LCh, and CIEDE2000.
   - Correction residual (mean Delta E 2000 on patches) for the quality gate.
2. All powers, roots, and exponentials through `StrictMath`.
3. A small matrix and least-squares implementation (no external linear-algebra library) with tests for ill-conditioned inputs.

## Tests
- CIEDE2000 against the published Sharma, Wu, and Dalal (2005) test pairs: all within 1e-4.
- Round trips: sRGB to XYZ to Lab and back within 1e-9 on a grid.
- Correction recovers a known synthetic distortion (random 3 x 3 plus tone curve) to under 0.5 Delta E on held-out patches.
- TC-EST-03: a colored ring at the window edge does not change the skin value.
- Determinism: identical results across repeated runs (prepares TC-EST-07).

## Done when
- `./gradlew :engine:colorscience:test` passes; coverage report attached.
- Golden vectors for P06 and P07 written to `testdata/golden/colorscience/` with a SHA-256 manifest.

## Stop and ask me if
- The gray-ramp fit is unstable with fewer than 6 gray patches; that changes the card spec.
````

---

## P05. Card Trust Engine and Signing Tool

````markdown
# P05: Card trust engine and the neogold-sign tool

Phase 1, Specs and engines. Depends on: P01, approved P02. Backlog: NG-039, NG-045. Tests: TC-CARD-01 to TC-CARD-06, TC-RSH-02, TC-RSH-03.

## Read first
- card/spec/qr-formats.md and its test vectors (from P02)
- docs/4_System_Architecture.md Section 7
- docs/8_Security_Compliance_Plan.md Section 4 (K4, K5, K10, trust list rules, offline signing machine)
- docs/7_API_Documentation.md Section 4.1

## Goal
Offline verification of card batches and study profiles on the phone, and the offline tool that signs them.

## Build
1. `:engine:cardtrust` (pure Kotlin, Tink for Ed25519 verification):
   - Parse card batch and study profile payloads per `qr-formats.md`; reject unknown format versions.
   - Verify signatures against the trust list, honoring key purpose, validity dates, and revocation lists.
   - Return typed results: `Verified`, `Expired`, `Revoked`, `UnknownIssuer`, `WrongPurpose`, `BadSignature`, `Malformed`, with the batch reference values when verified.
2. `card/tools/neogold-sign` (Kotlin JVM CLI or Python; pick one and justify): `keygen`, `card-batch`, `study-profile`, `verify`. It refuses to run if the machine has a network interface up, unless `--i-understand-this-is-online` is passed for tests. Every signature appends a line to a signing log file.
3. `config/trust-list.json` containing only test keys for now, plus a test that fails a release build if any `TEST_ONLY` key is present (wire into the release workflow later in P16).

## Tests
- TC-CARD-01 to TC-CARD-06 and TC-RSH-02, TC-RSH-03, using the P02 test vectors.
- Cross-implementation: payloads signed by `neogold-sign` verify in `:engine:cardtrust`, and the reverse fails for tampered bytes.
- Fuzz the parser with random bytes: no crash, always `Malformed`.

## Done when
- `./gradlew :engine:cardtrust:test` passes and the CLI round trip test passes in CI.
- privacy-security-reviewer subagent has reviewed the diff.

## Stop and ask me if
- The payload does not fit the QR version chosen in P02.
````

---

## P06. Estimation Engine and Model Format

````markdown
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
````

---

## P07. Training Pipeline

````markdown
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
````

---

## P08. Vision Engine

````markdown
# P08: Vision engine (markers, sampling, quality gate)

Phase 3, Camera and vision. Depends on: approved P02, P04. Backlog: NG-010, NG-011, NG-014. Tests: TC-CAP-02 to TC-CAP-12, TC-CAP-17, TC-CARD-07 (bench).

## Read first
- card/spec/card-v1.md
- docs/4_System_Architecture.md Sections 4.3 (computer vision row), 5.2, 5.3, 5.6
- docs/10_Test_Cases.md Section 4
- docs/9_Test_Plan_Strategy.md Sections 5 and 6

## Goal
From one decoded frame: find the card, warp it to the canonical image, sample every patch and the skin window, and return pass or a list of quality failure reasons with the exact codes in doc 4 Section 5.3.

## Build
1. **Spike first (one session, then stop):** build two throwaway variants of marker detection, OpenCV Android from Maven Central (`org.opencv:opencv`, objdetect ArUco) and the AprilTag C library through JNI. For each, report: download size added per ABI, detection rate and time on the synthetic set at three scales and five angles, and 16 KB alignment status. Recommend one. **Stop for my decision.**
2. After the decision, `:engine:vision`:
   - Marker detection on a downscaled frame (long edge about 1600 px), homography, warp to the canonical size from the card spec.
   - Patch and window sampling via `:engine:colorscience` trimmed means.
   - Quality checks in doc 4 Section 5.3 order, each returning its reason code: `NO_CARD`, `CARD_ANGLE`, `NONUNIFORM_PATCHES`, `BLUR`, `GLARE`, `UNDEREXPOSED`, `OVEREXPOSED`, `UNEVEN_LIGHT`, `HIGH_RESIDUAL`, `WINDOW_NOT_SKIN`. Thresholds come from the config `qualityThresholds`, never constants in code.
   - Skin plausibility (chromaticity range, uniformity, no hard edges).
3. A synthetic card renderer for tests (from `card/spec/card-v1.json`): rotation, scale, perspective, blur, glare spots, exposure, uneven light, objects in the window.
4. `testdata/bad/` and `testdata/golden/` manifests; bench images are added by me as I capture them.

## Constraints
- Memory: one frame at a time, buffers reused; report peak allocation in tests.
- Native code only for marker detection; everything else Kotlin.

## Tests
- TC-CAP-02, 05, 06, 07, 10, 11, 12, 17 as JVM or Robolectric tests on synthetic images; TC-CAP-03, 04, 08, 09 and TC-CARD-07 (home-printed copy of a genuine QR) as instrumented or bench tests with placeholders until bench images exist (mark them clearly as pending bench data, not skipped silently).

## Done when
- Synthetic suite passes; the bad set is 100% rejected with labeled reasons.
- Size added to the APK reported per ABI; `check_16kb.sh` passes.
- safety-reviewer subagent has reviewed the quality gate.

## Stop and ask me
- After the spike (step 1).
````

---

## P09. Camera, Device Check, and Qualification

````markdown
# P09: Camera engine, device check, and camera qualification

Phase 3, Camera and vision. Depends on: P08. Backlog: NG-004, NG-007, NG-008, NG-009, NG-044. Tests: TC-DEV-01 to TC-DEV-10, TC-CAP-01, TC-CAP-15.

## Read first
- docs/4_System_Architecture.md Sections 4.5, 5.1, 5.6, ADR-012
- docs/2_PRD.md FR-03, FR-04, FR-10, FR-11
- docs/6_UI_UX_Guidelines.md Sections 5 (S05 to S08, S15, S16), 7.3, 9
- docs/1_Market_Competitor_Analysis.md Section 13 (devices and brands)

## Goal
Reliable capture on budget phones: preview with live card detection, processing controls used where available and never required, recorded metadata, and the empirical qualification test that decides whether a phone may screen.

## Build
1. `:engine:camera` with CameraX and `Camera2Interop`: turn off scene modes and effects where the phone allows, converge then lock AE and AWB where supported, short burst capture, `CaptureResult` metadata (ISO, exposure, lock states, gains, hardware level). Frames are decoded downsampled; nothing is written to disk in screening mode.
2. JPEG handling for research mode: re-encode keeping only image segments and an Exif block with orientation (doc 8 Section 6.4).
3. Device check (S05): RAM, low-RAM flag, camera hardware level, brand-specific battery guidance for TECNO, Infinix, itel, Xiaomi, Samsung, Symphony, and Walton with deep links where Android allows.
4. Qualification (S07, S08): three photos of the card, repeatability under 1.5, duplicate-patch uniformity under 2.0, accuracy under 3.0 (values from config), result `QUALIFIED` or `RESEARCH_ONLY` stored in `device_profile`; re-qualification after 30 days or an OS build change.
5. A debug-only capture screen showing detection overlay and quality reasons, excluded from release builds.

## Tests
- TC-DEV-01 to TC-DEV-10 (instrumented where possible; device and bench cases scripted with Maestro and listed for the device matrix in P22).
- TC-CAP-01, TC-CAP-15.

## Done when
- Captures work on at least D1, D2, and D4 (I will run them; give me a Maestro flow and a checklist).
- No image file appears on disk after a screening capture (scripted check).

## Stop and ask me if
- A matrix phone cannot run CameraX at all.
````

---

## P10. v0.1 Integration and Performance

````markdown
# P10: v0.1 integration, capture to estimate, and performance

Phase 3, Camera and vision. Depends on: P03, P05, P06, P09. Backlog: NG-025. Tests: TC-PERF-01 to TC-PERF-04, TC-EST-04, TC-EST-07 (two ABIs).

## Read first
- docs/4_System_Architecture.md Sections 4.4, 5.6, 8
- docs/9_Test_Plan_Strategy.md Sections 8 (v0.1 exit) and 11
- docs/2_PRD.md Section 12 Phase A

## Goal
The v0.1 milestone: on a real phone, card QR scan, capture, correction, estimate, and a triage result on a debug screen, within the time and memory budgets.

## Build
1. A domain-layer pipeline use case wiring card trust, camera, vision, color science, estimation, and triage, with the screening state machine from doc 4 Section 4.4 (UI renders state only).
2. Debug screen (debug builds only) showing every intermediate value and the versions record (NFR-10).
3. Macrobenchmark module with trace sections per stage of doc 4 Section 5.6; a script that runs 30 captures and reports p50 and p95 per phone.
4. Two-ABI determinism run of the golden vectors (TC-EST-07).

## Done when
- Doc 9 Section 8 v0.1 exit criteria met and recorded in `docs/test-reports/v0.1.md`, including camera qualification results for all six matrix phones and an honest note that the model has no clinical meaning yet.
- TC-PERF-01 to 04 measured on D1, D2, D4.

## Stop and ask me if
- p95 capture to result exceeds 10 s on D1 or D2; propose cuts before optimizing blindly.
````

---

## P11. Local Data Layer

````markdown
# P11: Local data layer (Room on SQLCipher, Tink files, retention)

Phase 4, Data and security. Depends on: P01. Backlog: NG-023 (storage), NG-034, NG-035, NG-057 (storage). Tests: TC-LOG-04, TC-LOG-05, TC-LOG-07, TC-LOG-11, TC-SEC-07, TC-SEC-08, TC-REL-08.

## Read first
- docs/5_Database_Schema.md Section 2 (all) and Section 5
- docs/8_Security_Compliance_Plan.md Sections 4 (K7, K8), 5.1, 6.1 (backup rows)
- docs/2_PRD.md Section 11, D7, D8, D12

## Goal
Every on-device table in doc 5 Section 2, encrypted at rest, with retention and deletion that cannot be skipped, and a database that survives crashes and Keystore loss without corrupting or leaking.

## Build
1. `:core:security`: Keystore AES-256-GCM master keys (StrongBox if present, TEE otherwise, no user-auth requirement), raw 256-bit SQLCipher key wrapped by Keystore and passed in the `x'<hex>'` form, Tink `StreamingAead` keyset wrapped by a separate Keystore key, Keystore-loss detection with a typed error.
2. `:core:data`: Room on SQLCipher with every table, column, index, and constraint in doc 5 Section 2 (including `guardian_consent_confirmed`, outbox `revision` and `dependency_requeues`, card batch `qr_payload` and `server_status`, research study `time_zone` and `data_generation`); `exportSchema = true`; DataStore keys from doc 5 Section 2.4.
3. Encrypted file store for research images: write to temp, `fsync`, rename; never a plaintext file.
4. Retention jobs (doc 5 Section 2.5): register number erasure at 60 days, screening purge at 180 days (30 to 365), abandoned sessions, research local retention with the server confirmation hook left as an interface for P20; run daily via WorkManager and on every app start.
5. Delete all data (FR-33): database, files, keys; recreate empty.
6. Keystore loss path: clear message, reset stores, keep working (doc 8 Section 5.1).

## Tests
- The listed TC IDs; plus migration test harness ready for future versions (`MigrationTestHelper`), and a crash-during-write test (kill the process 50 times during research saves on an emulator, zero partial rows).

## Done when
- Schema v1 exported and committed.
- privacy-security-reviewer subagent has reviewed the diff.

## Stop and ask me
- **At the end:** schema v1 is a review gate, because it can only migrate forward once study phones install it.
````

---

## P12. Integrity, Privacy Controls, App Lock, Crash Handling

````markdown
# P12: Integrity, privacy controls, app lock, and crash handling

Phase 4, Data and security. Depends on: P11. Backlog: NG-049, NG-054, NG-057. Tests: TC-PRIV-01 to TC-PRIV-05, TC-SEC-03, TC-SEC-09.

## Read first
- docs/4_System_Architecture.md Section 12
- docs/8_Security_Compliance_Plan.md Sections 6.1, 6.3, 6.5, 6.6
- docs/6_UI_UX_Guidelines.md Section 11

## Goal
The app refuses to run modified clinical assets, protects screens on shared phones, locks if the facility wants it, and records crashes without personal data.

## Build
1. Bundle manifest: build-time task that hashes model, triage config, card trust list, and study trust list and signs the manifest with K3 (from a Gradle property in CI; a test key locally); startup verification that disables screening on any mismatch.
2. `FLAG_SECURE` helper applied to every screen showing a register number or research data; `setRecentsScreenshotEnabled(false)` on API 33 and up; `filterTouchesWhenObscured` on confirmation and consent buttons for API 26 to 30.
3. Optional app lock with `BiometricPrompt` allowing device credentials, with the `KeyguardManager` fallback on API 28 and 29; re-lock after 5 minutes in the background.
4. In-house crash handler: class names and stack frames only, exception messages dropped, encrypted local file, "Share crash report" through the share sheet on next launch; nothing sent automatically.
5. Release logging: R8 rules that strip `android.util.Log`; a Timber release tree that drops below `WARN`.
6. Update `tools/check_manifest.py` allow-lists only if a permission or component is added, and doc 8 Section 6.1 in the same PR.

## Tests
- The listed TC IDs; TC-PRIV-03 as an instrumented backup test where possible.

## Done when
- TC-SEC-09 shown in the PR: a re-signed APK with a changed config asset disables screening.
- privacy-security-reviewer subagent has reviewed the diff.
````

---

## P13. Design System, Onboarding, Settings, Bangla

````markdown
# P13: Design system, onboarding, settings, and Bangla

Phase 5, App shell. Depends on: P09, P11. Backlog: NG-002, NG-003, NG-005, NG-027, NG-038 (localization guide), NG-046, NG-055. Tests: TC-ONB-01 to TC-ONB-06, TC-UI-04 to TC-UI-08, TC-A11Y-01 to TC-A11Y-05, TC-DEV-10.

## Read first
- docs/6_UI_UX_Guidelines.md Sections 1 to 5, 8, 10, 11, 12, 13, 14
- docs/2_PRD.md Personas, FR-01, FR-02, FR-32, NFR-06, NFR-07
- docs/4_System_Architecture.md Section 4.6

## Goal
The visual and language foundation every screen uses, plus the first-run flow (S01 to S08) and settings (S23 to S25).

## Build
1. `:core:designsystem`: tokens from doc 6 Section 2 (colors with the verified contrast pairs, Noto Sans and Noto Sans Bengali, spacing, shapes, 48 dp minimum targets), the `Ng*` components in doc 6 Section 4, dark theme per Section 13.
2. Strings: every string from doc 6 Section 10.4 in `values/` and `values-bn/`; Western digits for clinical numbers; a Gradle string lint that fails on hardcoded strings, missing keys in either language, and any banned word in doc 6 Section 10.2 (English and Bangla).
3. Screens S01 to S08 wired to P09's device check and qualification; S23 settings (language, retention 30 to 365, keep screening log, app lock, crash report opt-in); S24 About and model card; S25 delete all (calls P11).
4. In-app update prompt (NG-055) with Play In-App Updates: flexible by default, immediate for priority 4 or 5; tested with `FakeAppUpdateManager`.
5. Supported device list (NG-046) shown on S05.
6. `docs/LOCALIZATION.md`: how a fork adds a language.

## Tests
- The listed TC IDs; screenshot tests at 320 dp and 360 dp, font scales 1.0, 1.3, 2.0, both languages, light and dark.

## Done when
- String lint passes; contrast test passes for every token pair.
- Bangla strings marked "draft for nurse panel" in a reviewer file the panel can sign.
````

---

## P14. Research Mode

````markdown
# P14: Research mode

Phase 6, Research. Depends on: P05, P11, P12, P13. Backlog: NG-028, NG-029, NG-030, NG-031, NG-041. Tests: TC-RSH-01 to TC-RSH-16.

## Read first
- docs/2_PRD.md J4, FR-25 to FR-29
- docs/6_UI_UX_Guidelines.md Sections 5 (R01 to R09), 6.2
- docs/8_Security_Compliance_Plan.md Sections 6.2, 6.4, 7.1
- docs/5_Database_Schema.md research tables
- docs/7_API_Documentation.md Section 4.1 (study profile fields)

## Goal
Everything a study nurse needs to run a blinded validation session, with no data ever lost or shown when it should not be.

## Build
1. R01 unlock: scan the signed study QR (P05 verify), show study name, partner, and host for confirmation, set PIN. Store the study in `research_study`.
2. PIN: minimum 6 digits, reject repeated digits, sequences, and the top-100 list; Argon2id m=19 MiB, t=2, p=1 (fall back to m=12 MiB, t=3 if D4 takes over 1 s), then HMAC-SHA256 with a Keystore key; failure counter written before each check; lockout 1 minute at 5, 15 minutes at 10, then 1 hour each; no wipe; re-lock after 5 minutes in the background or screen off.
3. R02 to R06: participant code and consent form ID (formats from doc 7 Section 7), blinded capture protocol (sternum, forehead, sclera flash and no-flash), TSB entry with method and draw time, later entry by participant code, linking to a session later (outbox row re-queued as a new revision), session complete.
4. Blinding: the estimate is never rendered, never in the accessibility tree, never logged.
5. Crash-safe writes using P11's transactional store.
6. Card status for each session: `VERIFIED`, `EXPIRED`, or `UNVERIFIED` from P05.

## Tests
- TC-RSH-01 to TC-RSH-16; TC-RSH-14 and TC-RSH-15 scripted for the device matrix.

## Done when
- A full research session completes on an emulator and on D1, offline.
- safety-reviewer (blinding) and privacy-security-reviewer (PIN, storage) subagents have reviewed the diff.
````

---

## P15. Encrypted Export and Decrypt Tool

````markdown
# P15: Encrypted export and the decrypt tool

Phase 6, Research. Depends on: P14. Backlog: NG-036. Tests: TC-EXP-01 to TC-EXP-04.

## Read first
- docs/8_Security_Compliance_Plan.md Section 5.2 and key K10
- docs/7_API_Documentation.md Sections 7 and 10 (`decrypt-export`)
- docs/2_PRD.md FR-30

## Goal
A study can run with no server: the phone writes an export that only the study team can read, and a tool on the coordinator's own machine decrypts it with per-participant date shifting.

## Build
1. Export format: a ZIP containing per-file Tink streaming-AEAD ciphertext, a header with the data key wrapped by Tink HPKE (X25519, HKDF-SHA256, AES-256-GCM) to the study's export public key from the signed profile, a CSV schema version, and a SHA-256 manifest. Document it in `docs/specs/export-format.md`.
2. Phone side: export through the Storage Access Framework picker; no identifiers beyond participant code; progress and resume for large exports.
3. `tools/neogold-decrypt/` (Python or Kotlin JVM CLI, runs offline on the coordinator's machine): decrypts, verifies hashes, applies one random date offset per participant within plus or minus 180 days by default (`--no-date-shift` requires an explicit flag and prints a warning), writes CSV and images.

## Tests
- TC-EXP-01 to TC-EXP-04; a tampered ciphertext fails with a clear error; a wrong key fails.

## Done when
- Round trip from phone export to decrypted CSV in CI with synthetic data.
- privacy-security-reviewer subagent has reviewed the diff.

## Stop and ask me if
- Tink HPKE is not available in the Tink Android version we use; propose the alternative before writing it.
````

---

## P16. Release Pipeline and Security Gates

````markdown
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
````

---

## P17. Study Intake API Core

````markdown
# P17: Study Intake API core (backend)

Phase 8, Backend. Depends on: P00. Backlog: NG-042. Tests: TC-API-01 to TC-API-16, TC-API-25, TC-API-31.

## Read first
- docs/7_API_Documentation.md Sections 0 to 9 and 12 (all of them, carefully)
- docs/api/openapi.yaml (source of truth)
- docs/5_Database_Schema.md Section 3
- docs/8_Security_Compliance_Plan.md Sections 7 and 8.3

## Goal
The FastAPI service that enrolls phones and accepts their signed requests exactly as doc 7 specifies, with tests for every authentication and idempotency rule.

## Build
1. `backend/`: Python 3.12, FastAPI, Pydantic v2 models generated or hand-written from `openapi.yaml` with `extra="forbid"`, SQLAlchemy 2, Alembic migrations reproducing doc 5 Section 3 DDL exactly (no `pgcrypto`), locked dependencies with hashes.
2. ASGI middleware recording t0 at header arrival; device assertion verification in the exact order of doc 7 Section 4.3 Phase A, with study-status and app-version checks skipped for `GET /device`, `GET /sessions/{id}`, and `DELETE /device`; `aud` and `htu` from a configured `PUBLIC_BASE_URL`; `auth_nonce` with `expires_at = exp + 30 s`.
3. Idempotency on `(device_id, key)` with an `IN_PROGRESS` row before processing and only final outcomes stored (doc 7 Section 5.1); enrollment idempotency on the `enrollment_code` row; atomic code consumption.
4. Endpoints: `/health`, `/enrollments` (attestation challenge = SHA-256 of the code; leaf key must match), `/device` GET and DELETE, `/card-batches/{id}`, `/participants/{id}` with aliases.
5. RFC 9457 problem responses with the error catalog in doc 7 Section 6.2; no stack traces or body content in responses.
6. Token-bucket rate limits (20 burst, 1 per second per device; 10 failed enrollments per hour per client IP using `X-Forwarded-For` trusted only from `FORWARDED_ALLOW_IPS`).
7. `backend/tools/diff_spec.py`: compares FastAPI's generated schema with `openapi.yaml` and fails on any difference in paths, status codes, or schemas.

## Tests
- pytest with Testcontainers PostgreSQL 16: the listed TC IDs, including the ten authentication cases in doc 7 Section 12.3 (port them from the prototype behavior described there).

## Done when
- `pytest -q` and `diff_spec.py` pass in `backend-ci.yml` (enable it from `docs/ci-templates/`).
- privacy-security-reviewer subagent has reviewed the diff.

## Stop and ask me if
- Any part of `openapi.yaml` is ambiguous or contradicts doc 7 prose. Report it; do not guess.
````

---

## P18. Intake Images, Admin CLI, Export

````markdown
# P18: Intake images, session completion, admin CLI, and export

Phase 8, Backend. Depends on: P17. Backlog: NG-042. Tests: TC-API-17 to TC-API-24, TC-API-26 to TC-API-30, TC-API-32, TC-API-33.

## Read first
- docs/7_API_Documentation.md Sections 3, 5.3, 5.4, 7, 8, 10
- docs/5_Database_Schema.md Sections 3.4 to 3.6
- docs/8_Security_Compliance_Plan.md Sections 8.3, 11

## Goal
Manifest-first sessions, verified images, safe completion under concurrency, and every admin command the runbook relies on.

## Build
1. `PUT /sessions/{id}` manifest (card status rules, `has_image`, local-date fields, semantic validation in doc 7 Section 7) and `GET /sessions/{id}`.
2. `PUT .../image`: header-phase checks before the body (session exists, capture listed, declared digest equals manifest hash, size), streaming hash, JPEG segment allow-list, Pillow pixel limit, write to `/data/images/.tmp` then `fsync` and rename, session row locked `FOR UPDATE` before counting missing images, a nightly backstop job.
3. `PUT /tsb-measurements/{id}` with the one allowed null-to-session link.
4. Day-precision server dates only; no per-session audit events.
5. `neogold-admin` (inside the container): every command in doc 7 Section 10, including `site add --test`, `participant withdraw`, `restore mark` (Unix time), `audit since`, `selftest` (with `NEOGOLD_ALLOW_NEWER_SCHEMA`), `export` (excludes test sites and withdrawn participants, per-participant date shift, SHA-256 manifest), `retention check` and `purge` using the `neogold_purge` role. Each writes an `ADMIN` audit row.

## Tests
- The listed TC IDs, including TC-API-18 (100 concurrent last-image races) and TC-API-21 (decompression bomb).

## Done when
- All backend tests pass; Schemathesis run against a local stack shows no 5XX.
- privacy-security-reviewer subagent has reviewed the diff.
````

---

## P19. Deployment Stack and Backend CI

````markdown
# P19: Deployment stack and backend CI

Phase 8, Backend. Depends on: P18. Backlog: NG-042. Tests: TC-OPS-04, TC-OPS-05, TC-OPS-11, TC-SEC-11 to TC-SEC-13.

## Read first
- docs/12_Deployment_Runbook.md Sections 7 and 8 and Appendices C and D
- docs/8_Security_Compliance_Plan.md Sections 5.4, 8
- backend/deploy/ (validated templates from the starter kit)

## Goal
The backend runs on the staging droplet exactly as the runbook says, deploys and rolls back by digest, and restores from backup without undoing admin actions.

## Build
1. `backend/Dockerfile`: non-root user 10001, read-only friendly, `neogold-admin` on the path, healthcheck endpoint.
2. Use `backend/deploy/` as given (compose, Caddyfile, Caddy Dockerfile, `neogold-deploy`, `smoke.sh`, `neogold-backup`, `neogold-bootstrap`, `neogold-admin` wrapper, systemd units). Change them only to fix a real defect, and re-run ShellCheck, `docker compose config`, and `caddy validate` after any change.
3. `backend-ci.yml` with the `schema-compat` job, image build with SBOM and provenance, and the secret-free scan job.
4. `docs/test-reports/staging-setup.md`: the checklist I follow on the droplet (doc 12 Section 7.2), with commands to copy.

## Tests
- TC-OPS-04 automated in CI (schema-compat) and as a staging procedure; TC-OPS-05 restore drill steps; TC-OPS-11 first-time setup; TC-SEC-11 to 13 as a staging checklist with expected outputs.

## Done when
- CI builds and scans the image; I complete the staging checklist with your instructions and the smoke test passes.

## Stop and ask me
- Before anything touches the droplet. You prepare; I run the commands.
````

---

## P20. Research Sync Client and Enrollment

````markdown
# P20: Research sync client and study server enrollment

Phase 9, Sync. Depends on: P14, P17 (a running staging server for the E2E cases). Backlog: NG-042. Tests: TC-SYNC-01 to TC-SYNC-20, TC-API-07.

## Read first
- docs/7_API_Documentation.md Sections 3, 4, 5, 6, 11 (all)
- docs/api/openapi.yaml
- docs/5_Database_Schema.md outbox, research_study, card_batch tables
- docs/6_UI_UX_Guidelines.md R07, R08, R09 and the `upload_*`, `enroll_*` strings

## Goal
Research data reaches the study server without loss, duplication, or wasted bandwidth, on phones whose battery managers kill background work.

## Build
1. `:sync:research` only (the one module allowed an HTTP client): OkHttp, kotlinx.serialization, request bytes serialized once and hashed for `bsh`.
2. Device key in Android Keystore (P-256, StrongBox if present), attestation challenge from the enrollment code, DER to JOSE signature conversion, per-request ES256 JWT, clock offset from server time with one retry on `CLOCK_SKEW` or `AUTH_EXPIRED`.
3. Enrollment R08 (signed study QR, code, settings check against the profile) and Leave study R09 (delete only the device key and `server_device_id`).
4. Outbox processor: order (`GET /device` first, batch, participant, manifest, images, TSB), idempotency keys, the retry table in doc 7 Section 5.2, dependency re-queue cap of 3, card-batch rejection to `UNVERIFIED`, unknown codes by `retryable`, client-bug re-queue after an app update.
5. One unique WorkManager work; Upload now replaces a waiting run with an expedited one; foreground worker with `dataSync` type and `FOREGROUND_SERVICE_DATA_SYNC` on API 34 and up; `IN_FLIGHT` reset on start; Wi-Fi only.
6. Reconciliation before local deletion and on a `data_generation` change: re-send every held item (doc 7 Section 5.4).
7. Contract tests: every request model validated against `openapi.yaml`; every spec example deserializes.

## Tests
- TC-SYNC-01 to TC-SYNC-20 (MockWebServer for most; staging for 12, 14, 15, 17, 19); TC-API-07.

## Done when
- A full study day (5 participants, 20 sessions, 120 images, linked TSB values) uploads from D1 to staging over a throttled link with zero loss and zero duplicates.
- privacy-security-reviewer subagent has reviewed the diff.
````

---

## P21. Screening Flow (Built, Hidden in v1.0)

````markdown
# P21: Screening flow, results, log, and due list

Phase 10, Screening. Depends on: P10, P13. Backlog: NG-012, NG-017, NG-018, NG-019 (UI), NG-021, NG-022, NG-023, NG-024, NG-026, NG-040, NG-043, NG-047, NG-048, NG-051, NG-056. Tests: TC-UI-01 to TC-UI-03, TC-UI-09 to TC-UI-14, TC-LOG-01 to TC-LOG-03, TC-LOG-06, TC-LOG-08 to TC-LOG-10, TC-LOG-12, TC-REL-01 to TC-REL-07, TC-TRI-26 (UI side), TC-CARD-08, TC-CARD-09, TC-CAP-13, TC-CAP-14, TC-CAP-16.

## Read first
- docs/2_PRD.md J1 to J3, FR-05 to FR-24, FR-36, FR-37, D10 to D12
- docs/6_UI_UX_Guidelines.md Sections 3, 5, 6.1, 7.1 to 7.4, 8, 9, 10
- docs/4_System_Architecture.md Sections 4.4 and 6
- docs/8_Security_Compliance_Plan.md Section 14 (H2, H3, H10, H11)

## Goal
The full screening flow from Home to saved result, exactly as designed, shipped behind `triageEnabled` so v1.0 study builds never show a result.

## Build
1. S09 Home with the experimental label and the rechecks-due list (works with notifications blocked).
2. S10a guardian consent (no default; "No" ends the screen, nothing saved), S10 birth time with "Phone time now" and the clock checks (before build time, automatic time off, research offset drift), gestational age stepper with no pre-filled value, S11 to S13 safety questions with no defaults, S14 rule outcomes.
3. S15 to S19 capture, retake guidance per reason, optional forehead photo, analyzing.
4. S20 result: "Suggested next step", action with color, icon, border, and words, "You decide, using your clinical judgment.", range bar, "Always confirm with a blood test.", versions line; "What to tell the mother" card (NG-047).
5. S21 and S22 log and session detail (what was shown, reconstructable), register number field with validation, log export (register numbers excluded by default), recheck notification without baby details (NG-048).
6. `triageEnabled` false: the screening entry point is hidden in the study build and every path that would show a result shows the blood test message instead (TC-TRI-26).

## Tests
- The listed TC IDs as Compose UI tests and Maestro flows; screenshot tests for S10a, S10, S20 at 320 dp in both languages.

## Done when
- With the synthetic config and `triageEnabled: true` in a debug build, all flows pass; with `triageEnabled: false` in the release build, no result is reachable.
- safety-reviewer subagent has reviewed the diff, especially wording, defaults, and gating.
````

---

## P22. Device Matrix and Release Candidates

````markdown
# P22: Device-matrix verification and the v1.0 closed release candidate

Phase 10, Release v1.0. Depends on: all earlier packages. Backlog: NG-046. Tests: everything marked E2E, R, P, M, or OPS that is not yet passing, including TC-PERF-05 to TC-PERF-07, TC-USE-01 to TC-USE-05, TC-OPS-06 (halt drill), and TC-OPS-09 (fork drill).

## Read first
- docs/9_Test_Plan_Strategy.md (all)
- docs/10_Test_Cases.md (all levels E2E, R, P, M, OPS)
- docs/12_Deployment_Runbook.md Sections 4.3, 9, 12
- docs/13_Release_Notes.md Section 6

## Goal
Evidence that v1.0 works on the phones people own, collected into a test summary report, and a release candidate on Play closed testing for the study sites.

## Build
1. Maestro flows for J1, J4, enrollment, Upload now, and export; reliability scripts (`adb` kill loops, storage fill, clock changes) and a manual power-loss checklist.
2. Run plans per device D1 to D7, with what I run by hand and what runs scripted; collect results into `docs/test-reports/v1.0.md` from the template, including pass, fail, and blocked counts by area, open defects by severity, gates S1 to S9, and the line that software testing does not show clinical safety.
3. Usability session kit for the nurse panel (TC-USE-01 to 03): tasks, teach-back script in Bangla and English, and a results sheet.
4. Fill the v1.0.0 research build release notes (doc 13 Section 6) from the signed records and versions; check the 500-character limit.
5. Run `tools/trace.py docs` and list any test case still not passing with a reason.

## Done when
- Doc 9 Section 8 exit criteria for v1.0 closed release are met or each gap is listed with an owner.
- The release candidate is on the closed testing track after both approvals.

## Stop and ask me
- Before promoting to the closed track, with the test summary report attached.
````
