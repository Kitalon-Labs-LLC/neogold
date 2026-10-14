# NeoGold: Test Plan and Strategy

| Field | Value |
|---|---|
| Document | 9 of 13 |
| Version | 0.1.2 (organization Play account and second approver confirmed; v1.0 is a closed research release, PRD D10) |
| Date | 11 October 2026 |
| Owner | Sowad Al-Mughni, Kitalon Labs |
| Depends on | `2_PRD.md` v0.3.1, `4_System_Architecture.md` v0.2.4, `7_API_Documentation.md` v0.2, `8_Security_Compliance_Plan.md` v0.2.1 |
| Companion | `10_Test_Cases.md` (cases and traceability), `11_Bug_Defect_Log.md` (defects) |
| Status | Awaiting founder review |

Confidence tags: **[Certain]** verified or computed, **[Likely]** strong inference, **[Guessing]** needs measurement.

---

## 0. Read This First

1. **Software testing cannot show that NeoGold is clinically safe.** It can show that the app does what docs 2 to 8 say, and that every safety control works. Whether the estimate is close enough to a blood test in Bangladeshi newborns is answered only by the partner's validation study (Section 12). The test summary for v1.0 must say so in its first line.
2. **The target phones are not in cloud device farms.** Entry Transsion, Symphony, and Walton phones are unlikely to be offered by hosted test labs [Guessing; check before paying for one]. The physical device matrix (Section 4.2) is the primary test bed, so budget for buying or borrowing six phones.
3. **The validation study's sensitivity claim needs enough babies above the treatment threshold.** If NeoGold catches 95% of them, the study needs about 56 such babies before the lower 95% confidence bound clears 85%, and about 154 before it clears 90% [Certain, exact binomial calculation in Section 12.2]. A study with 20 positives cannot support "95% sensitivity" in any honest sense.
4. **v1.0 never reaches the production track** (PRD D10): it ships on Play closed testing to the study sites. When a public release comes, its first production release cannot be a staged rollout [Certain, Play Console help], so the testing tracks remain the staging.

---

## 1. Scope and Objectives

**In scope:** the Android app (screening and research modes), the on-device engines, the signed configuration and trust lists, the Study Intake Service and its API, the CI/CD pipeline, card verification behavior, and the operational procedures in doc 12.

**Out of scope for this plan:** clinical accuracy claims (Section 12 describes the software's part only), card printing quality (covered by the Card Production Kit, NG-045), and partner infrastructure beyond what doc 12 deploys.

| Objective | Evidence |
|---|---|
| Every requirement is verified | Each FR, NFR, and safety control maps to at least one test case; the coverage script in Section 10 fails CI otherwise |
| Every hazard control works | Each hazard H1 to H11 (doc 8 Section 14) has a passing verification case |
| No triage mistake from code | Exhaustive triage tests and a differential test against an independent reference implementation (TC-TRI-24) |
| The app works on the phones people own | Device matrix results for every release |
| No data loss in research mode | Crash, kill, and power-loss tests with zero lost records (PRD Phase B metric) |
| Security claims hold | Release gates S1 to S9 (doc 8 Section 13.3) |

---

## 2. Risk-Based Focus

Test effort follows harm, not code size.

| Risk | Source | Test focus | Main levels |
|---|---|---|---|
| False reassurance from the triage | H1, H7, H8 | Every rule and boundary, config integrity, expiry, differential testing | Unit, CI gates |
| Wrong age or gestational age | H2, H3 | Input validation, clock checks, usability of the birth time screen | Unit, UI, usability |
| Bad image accepted | H4 to H6 | Bad image set, bench lighting and angle tests, counterfeit cards | Bench, device |
| Phone camera distorts color | H5 | Camera qualification on every matrix phone | Device, bench |
| Result misread | H11 | Visual language, wording, TalkBack, small screens | UI, accessibility, usability |
| Research data lost or duplicated | NFR-09, doc 7 Section 5 | Outbox, idempotency, concurrency, reconciliation, power loss | Integration, device, chaos |
| Data exposure | T1, T2, T10, T15 | Encryption, backup exclusion, FLAG_SECURE, timestamps, export shifting | Security, integration |
| Low-end phone failure | NFR-02 to NFR-05 | Time, memory, size, battery on the 2 GB floor | Performance, device |
| Missed rechecks | H10, NFR-12 | Due list with notifications and background work blocked | Device |

---

## 3. Test Levels and Types

| Level | What it covers | Tools | Runs |
|---|---|---|---|
| Unit (JVM) | Engines (`:engine:colorscience`, `:engine:estimation`, `:engine:triage`, `:engine:cardtrust`), validators, ViewModels | JUnit, Truth, Turbine, kotlinx-coroutines-test | Every pull request |
| Golden-vector parity | Kotlin pipeline against the Python training pipeline on the same inputs, tolerance 1e-4 (doc 4 Section 11) | Gradle task over `testdata/golden/` | Every pull request |
| Differential triage | 10,000 generated cases through the Kotlin engine and an independent Python reference written from doc 4 Section 6 | Gradle task, pytest | Every pull request touching `:engine:triage` or config |
| Integration (device modules) | Room on SQLCipher, migrations, Tink files, outbox with a fake server, WorkManager | Robolectric, `MigrationTestHelper`, `work-testing`, OkHttp `MockWebServer` | Every pull request |
| Contract | Client request models against `openapi.yaml` schemas; spec examples deserialize; server matches spec | JSON Schema validator on JVM, FastAPI schema diff | Every pull request |
| Backend | API behavior, auth, idempotency, validation, admin CLI, race tests | pytest, httpx, Testcontainers PostgreSQL 16 | Every backend pull request |
| API fuzzing | Every endpoint against the spec | Schemathesis against staging | Every release candidate |
| UI (instrumented) | Compose screens, navigation, strings, accessibility semantics | Compose UI test, Espresso where needed, emulators API 26, 30, 34, 36 and an Android Go image | Nightly and release candidate |
| End-to-end on device | Full journeys J1 to J3 and research sessions on real phones | Maestro flows plus manual scripts | Release candidate, on the device matrix |
| Bench optical | Color accuracy, repeatability, angle, lighting, counterfeit cards | Bench rig (Section 5) | Each model or camera code change, each release candidate |
| Performance | Time to result, memory, size, battery, startup | Macrobenchmark, Perfetto, `dumpsys meminfo`, `batterystats`, bundletool size report | Release candidate |
| Reliability and chaos | Process death, power loss, storage full, wrong clock, killed background work | Scripts with `adb`, manual power pull | Release candidate |
| Security | MASVS checklist, manifest and network checks, Keystore loss, server hardening | CI scripts, MobSF, nmap, testssl.sh, manual | Every release (gates S1 to S9) |
| Accessibility and localization | TalkBack, font scale, 320 dp, contrast, Bangla rendering, banned words | Accessibility Scanner, Compose checks, string lint | Release candidate |
| Usability | Nurses complete tasks and explain results | Moderated sessions, teach-back | Before v1.0 and after major UI changes |
| Field pilot | Real wards, real light, real phones | Partner sites under ethics approval | v0.5 onward |
| Clinical validation | Estimate against blood TSB | Partner protocol | Phase B (PRD Section 12) |

---

## 4. Environments

### 4.1 Software environments

| Environment | Purpose | Data |
|---|---|---|
| Developer machine and CI | Unit, integration, contract, backend tests | Synthetic only |
| Emulators | UI tests across API levels and Android Go | Synthetic only |
| Staging server | DigitalOcean BLR1 dedicated droplet (doc 4 Section 10.2a) | Synthetic only, never newborn data |
| Production study server | Partner-hosted in Bangladesh | Real study data; only the production smoke test in doc 12 Section 7.4 touches it, on a test site excluded from exports |

### 4.2 Device matrix

Final models come from the partner device survey (PRD Section 16, open item 4); classes are fixed now (doc 1 Section 13.5).

| ID | Class | Android | RAM | Role in testing |
|---|---|---|---|---|
| D1 | Transsion entry (TECNO Spark, Infinix Hot or Smart, or itel) | 13 to 15 | 3 to 4 GB | Reference class for NFR-02; HiOS or XOS background limits |
| D2 | Xiaomi entry (Redmi A or C series) | 14 or 15 | 3 to 4 GB | HyperOS background limits; second reference-class phone |
| D3 | Samsung Galaxy A06 | 14 or 15 | 4 GB | Common in programs; One UI behavior |
| D4 | Android Go floor | 11 to 14 (Go edition) | 2 GB | Memory and time floor (NFR-03, NFR-04b) |
| D5 | Older phone | 10 or 11 | 3 GB | API 29 and 30 behavior, older camera stack |
| D6 | Local assembly (Symphony or Walton) | Any current | 2 to 4 GB | Locally procured hardware |
| D7 | Any phone with a 16 KB page size image (emulator or supported device) | 15 or later | Any | 16 KB native library check (doc 8 Section 6.1) |

Every release candidate runs the full end-to-end suite on D1 to D4, and the smoke suite on D5 and D6. Camera qualification and bench tests run on all six physical phones.

---

## 5. Bench Test Rig

[Likely adequate for a first study; the partner's lab may improve it.]

| Part | Specification | Purpose |
|---|---|---|
| Light sources | High color-rendering LED panels at about 2700 K, 4000 K, and 6500 K, plus a mixed setup (window daylight with a tube light) | Lighting robustness (H6) |
| Reference target | A ColorChecker chart (doc 2 Section 9a interim transfer method) | Correction accuracy independent of the NeoGold card |
| NeoGold cards | At least two measured batches, one expired batch, one deliberately counterfeit print (genuine QR on a home printer) | Card trust and residual checks (H4, T4) |
| Skin-tone phantoms | Printed or painted patches covering light to dark skin and low to high yellowness, each measured by the outsourced spectrophotometer lab | Repeatability and color accuracy across tones; phantoms test color, not bilirubin |
| Jig | Phone mount at fixed distance with card tilt at 0, 10, 20, and 30 degrees | Angle check (doc 4 Section 5.3) |
| Bad image set | Glare, blur, missing card, partial card, card tilted past limit, underexposed, overexposed, mixed light, cloth, finger, shadow, and empty window in the skin window | PRD metric: 100% rejected |

Bench images, measured reference values, and phantom measurements live in `testdata/bench/` with a manifest of SHA-256 hashes, so any change in results can be traced to code, not data.

---

## 6. Test Data

| Set | Content | Source | Rules |
|---|---|---|---|
| `testdata/golden/` | Images with expected features and estimates | Bench captures | Regenerated only with a reviewed pull request |
| `testdata/bad/` | The bad image set | Bench captures | Every image labeled with the expected failure reason |
| `testdata/triage/` | Synthetic triage config with round-number thresholds, and the generated vectors | Generated | Never uses the clinical config, so tests do not change when thresholds change |
| `testdata/api/` | Request and response fixtures, crafted JPEGs (GPS, XMP, MPF, thumbnails, decompression bomb) | Generated | Synthetic |
| Public datasets | Neonatal skin images with bilirubin values used for model evaluation (doc 1 Sources) | Third parties | License checked; not committed to the repository |
| Study data | Real research data | Partner | Never leaves the partner environment; never used in CI |

No real newborn image, register number, or participant code ever enters the repository, a bug report, or a CI log.

---

## 7. Automation and CI

| Trigger | Runs | Blocking |
|---|---|---|
| Pull request | Lint, detekt, Ruff, Bandit, unit, golden vectors, differential triage, integration, contract, backend tests, secrets scan, coverage check (Section 10) | Yes |
| Nightly on `main` | Instrumented UI tests on emulators, dependency and image vulnerability scans | Failures open a defect automatically |
| Release candidate tag | Everything above, plus Schemathesis against staging, MobSF, manifest, network, and 16 KB checks, SBOM, clinical sign-off check, device matrix runs (manual trigger with results attached) | Yes |

Pipeline definitions are in doc 12 Section 3.

**Flaky tests** are quarantined within one day with a linked defect, and never simply re-run until green. A quarantined test in `:engine:triage` or `:engine:cardtrust` blocks release until fixed.

---

## 8. Entry and Exit Criteria

| Release | Entry | Exit |
|---|---|---|
| v0.1 Prototype | Scaffold builds; bench rig assembled | Pipeline runs end to end on 3 phones; golden-vector parity passes; bad image set 100% rejected; camera qualification measured on all six matrix phones |
| v0.5 Research build | v0.1 exit met; partner device survey done | All P1 cases for research mode, sync, export, security pass; zero records lost in 50 kill tests and 10 power-pull tests per phone; research build on Play closed testing for the partner |
| v1.0 internal track | v0.5 exit met; G1 and G2 met (doc 8 Section 13.3) | All P1 and P2 cases pass on D1 to D4; no open S1 or S2 defects; performance budgets met on D1 and D2; gates S1 to S9 pass |
| v1.0 closed testing (partner nurses) | Internal track exit met | Usability targets met (Section 13); 14 days with no S1 and no unresolved S2. No minimum tester count: Kitalon Labs publishes from an organization account, and Play's 12-tester rule applies only to new personal accounts [Certain] (confirmed by the founder, 11 October 2026) |
| v1.0 closed research release (PRD D10) | Closed testing exit met; partner ethics approval in hand | Research build with `triageEnabled` false on the closed track for all study sites |
| Later public release | v1.0 study results; **G1, G2, and G3 met** (doc 8 Section 13.3) | Production checklist in doc 12 Section 9 complete |
| v1.0.x patch | Fix verified; regression suite for the touched area passes | Staged rollout criteria in doc 12 Section 5 |

**Suspension criteria:** testing of a build stops when the build fails startup integrity checks, when a triage engine test fails, or when an S1 defect is found; it resumes on a new build.

---

## 9. Defect Management

### 9.1 Severity

| Severity | Definition | Examples |
|---|---|---|
| **S1 Critical** | Could change a triage action, hide a referral, show a result when none should be shown, lose or expose research data, or break a security control | Wrong boundary in the triage engine; result shown with a `DRAFT` config; outbox drops an item; register number in a notification |
| **S2 Major** | A core flow fails with no workaround, or a requirement is not met on a matrix phone | Camera crashes on D4; upload never completes; Bangla text truncated on the result screen |
| **S3 Minor** | Works with a workaround, or a non-core function fails | Export filter ignores a date; wrong icon in settings |
| **S4 Cosmetic** | Appearance or wording that does not change meaning | Spacing, alignment |

**Clinical safety flag.** Any defect that could affect what a health worker does for a baby is flagged `Clinical: Yes`, linked to a hazard ID, and treated as S1 regardless of how rare it is. The clinical lead sees every flagged defect.

### 9.2 Handling

| Severity | Triage | Fix target | Release impact |
|---|---|---|---|
| S1 | Same day | Before any further release; if already in the field, doc 12 Section 6 | Blocks release; may halt a rollout |
| S2 | 2 working days | Next release | Blocks the v1.0 closed release and any public release |
| S3 | Weekly | Planned | Does not block |
| S4 | Weekly | When convenient | Does not block |

Defects are logged in `11_Bug_Defect_Log.md` (mirrored to GitHub issues once the repository is public). Bug reports never contain newborn data.

---

## 10. Traceability and Coverage

- Every test case in doc 10 lists the requirement, hazard, threat, or gate it verifies.
- A CI script parses doc 10 and fails if any `FR-`, `NFR-`, hazard `H`, or gate `S` in docs 2 and 8 has no test case, or if a test case points to an ID that does not exist.
- The traceability matrix at the end of doc 10 is generated by that script, not typed by hand.

---

## 11. Performance Method

| Measure | Method | Budget |
|---|---|---|
| Capture to result | Trace sections from shutter to result screen; 30 runs per phone after a warm-up | p50 under 4 s, p95 under 10 s on D1 and D2 (NFR-02, doc 4 Section 5.6) |
| Full screening journey | Stopwatch with a trained user, 10 runs | Under 60 s (NFR-02) |
| Peak memory | `dumpsys meminfo` and Perfetto during analysis | Under 120 MB (NFR-04b) |
| Size | bundletool download size per device configuration | Download under 25 MB, install under 40 MB (NFR-04) |
| Battery | `batterystats` over 20 screenings from a full charge | Under 1% per screening (NFR-05) |
| 2 GB floor | Same measures on D4 | No out-of-memory kills; results reported, budget set after v0.5 data |

Results are reported per phone with the median and the 95th percentile, never as a single average.

---

## 12. Clinical Performance Verification (Software's Part)

### 12.1 What the software must provide

- Blinded capture: the estimate is never shown or spoken in research mode (FR-27, TC-RSH-10).
- Complete pairing: every research session has its TSB value linked or explicitly marked unlinked (doc 7 Section 7).
- Traceability: app, model, config, card batch, and device versions on every record (NFR-10).
- Zero lost records (PRD Phase B).
- Exports the statistician can analyze without asking for identifiers (doc 8 Section 3).

### 12.2 Sample size reality for the partner protocol

Exact (Clopper-Pearson) lower 95% confidence bound on sensitivity when NeoGold detects 95% of babies above the treatment threshold [Certain, computed 11 October 2026]:

| Babies above threshold | Detected | Lower bound |
|---|---|---|
| 20 | 19 | 75.1% |
| 40 | 38 | 83.1% |
| 60 | 57 | 86.1% |
| 100 | 95 | 88.7% |
| 150 | 142 | 89.8% |
| 200 | 190 | 91.0% |

- Lower bound of at least 85%: about 56 babies above threshold.
- Lower bound of at least 90%: about 154.
- Specificity: with 65% observed, about 108 babies below threshold keep the lower bound above 55%.
- How many babies must be enrolled depends on how many exceed the threshold at the sites [Guessing; the partner's past data sets this]. The Fitzpatrick V and VI subgroup target (PRD Section 12) needs similar numbers within the subgroup.

### 12.3 Analysis the software team supports

Sensitivity and specificity against the clinical lead's thresholds, Bland-Altman agreement between estimate and TSB, performance by device class and card batch, and capture success within two attempts. The results are published whatever they show (PRD Phase A).

---

## 13. Usability and Field Testing

| Activity | Participants | Pass criteria |
|---|---|---|
| Moderated usability, round 1 (v0.5) | 5 to 8 nurses or health workers from the partner sites [Likely enough to find most major problems per round] | 90% complete a full screening unaided; every participant states the correct next step for all four results (teach-back) |
| Wording check | Nurse wording panel (PRD Section 9b) | All Bangla strings approved and signed |
| Round 2 (v1.0 closed testing) | New participants, 5 to 8 | Same targets; no participant mistakes "No further action from this screen" for "baby is well" |
| Field pilot | Partner sites | Capture success within two attempts of 90% or more (PRD Phase B) |

---

## 14. Roles

| Role | Who | Responsibility |
|---|---|---|
| Test lead and developer | Founder | This plan, automation, device and bench testing, release decisions with the clinical lead |
| Clinical lead | Bangladeshi consultant neonatologist (PRD Section 9b) | Signs triage config and wording; reviews every clinically flagged defect; joins the go or no-go for each release |
| Nurse panel | Partner staff | Usability sessions and wording |
| Partner IT | Partner | Production server tests in doc 12 |
| Second approver | [name], Kitalon Labs | Reviews every pull request that touches `:engine:*`, `config/`, `tools/`, or CI; approves every release; can halt a rollout |
| Independent reviewer | External, before the first production study (doc 8 Section 10) | Security review |

A developer testing his own code is still the weakest point in this plan. The mitigations are the second approver's review of every change to the engines, config, and pipeline; automation that does not depend on judgment; the differential triage test; the independent security review; and the clinical lead's veto.

---

## 15. Deliverables and Reporting

- A test summary report for each release in `docs/test-reports/`, with: build and versions; device results; pass, fail, and blocked counts by area; open defects by severity; gates S1 to S9; and a plain statement of what testing does not show (Section 0, item 1).
- Bench and performance raw data committed with hashes.
- The v1.0 report is attached to the GitHub release.

---

## 16. Open Issues for Review

1. **Device budget:** buy six matrix phones or borrow them from partner staff for test days.
2. **2 GB budget:** set the time-to-result budget for D4 after v0.5 measurements.
3. **Phantoms:** choose who makes and measures the skin-tone phantoms; the outsourced lab that measures card batches is the natural choice.

---

## Sources

- [Google Play Console help: staged rollouts (first release cannot be staged)](https://support.google.com/googleplay/android-developer/answer/6346149?hl=en)
- [Closed testing requirement for new personal accounts (12 testers, 14 days)](https://extendsclass.com/blog/google-plays-closed-testing-requirement-what-developers-need-to-know-in-2026)
- [OWASP MASVS](https://mas.owasp.org/MASVS/)
