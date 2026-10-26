# NeoGold: Feature Backlog (MoSCoW)

| Field | Value |
|---|---|
| Document | 3 of 13 |
| Version | 0.3.2 (low-end device targets, retention, register number rules, shared-phone privacy; 0.3.1 adds NG-055 in-app update prompt; 0.3.2 adds NG-056 guardian consent) |
| Date | 10 October 2026 |
| Owner | Sowad Al-Mughni, Kitalon Labs |
| Scope | Releases v0.1 to v1.0, with later items parked |
| Depends on | `2_PRD.md` |

---

## How to Read This Backlog

**Priority**
- **Must:** v1.0 cannot ship without it. Mostly safety, offline screening, and research data integrity.
- **Should:** high value; ships in v1.0 if time allows, otherwise first in v1.1.
- **Could:** nice to have; picked up only when Must and Should are done.
- **Won't (this release):** explicitly out of scope, with the reason recorded so it does not creep back in.

**Size** (solo builder effort)
- **S:** under 2 days
- **M:** 3 to 5 days
- **L:** 1 to 2 weeks

**Release target**
- **v0.1** prototype (calibration pipeline only)
- **v0.5** research build for partner study
- **v1.0** open release

---

## Epic Summary

| Epic | Description | Must | Should | Could |
|---|---|---|---|---|
| E1 Foundation | Project setup, onboarding, safety notice, device check, in-app updates | 5 | 2 | 1 |
| E2 Calibration | Card design, batch verification, production kit, device color profile, card integrity | 4 | 2 | 1 |
| E3 Capture and Quality | Guided camera, processing control and metadata, quality gate | 5 | 1 | 1 |
| E4 Analysis | Color correction, skin window extraction, estimation, uncertainty | 4 | 1 | 1 |
| E5 Rules and Triage | Guardian consent, safety questions, rule engine, triage mapping, result screen | 7 | 2 | 1 |
| E6 Screening Log and Data | Local log, image handling, export, delete, traceability, shared-phone privacy | 6 | 2 | 1 |
| E7 Research Mode | Study access, consent gate, blinded capture, paired TSB, encryption, export | 7 | 2 | 0 |
| E8 Open Source and Transparency | Training pipeline, model card, docs, licensing, clinical sign-off record | 5 | 1 | 1 |
| **Total** | | **43** | **13** | **7** |

---

## MUST HAVE

| ID | Epic | Item | Description | Size | Release | Depends on | PRD ref |
|---|---|---|---|---|---|---|---|
| NG-001 | E1 | Project scaffold | Multi-module Kotlin project, Jetpack Compose, manual dependency wiring (ADR-017), Clean Architecture (reuse ScanLite module pattern), CI build | M | v0.1 | None | Section 13 |
| NG-002 | E1 | Language selection | Bangla and English at first launch and in settings; all strings externalized | S | v1.0 | NG-001 | FR-01, NFR-06 |
| NG-003 | E1 | Safety notice | Three-screen notice with explicit "I understand"; re-shown after major model updates | S | v1.0 | NG-002 | FR-02 |
| NG-004 | E1 | Device check | Detect RAM, Android version, and available camera controls (recorded, used when present, never required); show brand-specific battery settings steps for TECNO, Infinix, itel, Xiaomi, Samsung | M | v0.5 | NG-001 | FR-03, NFR-12 |
| NG-005 | E1 | Accessibility baseline | Results never conveyed by color alone; 4.5:1 contrast; large touch targets usable with gloves | S | v1.0 | NG-021 | NFR-07 |
| NG-006 | E2 | Card design v1 | Card with skin-range and gray patches, duplicate patches in opposite corners (to detect HDR and local processing), corner markers, punched skin window, print specification | M | v0.1 | None | Section 9a |
| NG-007 | E2 | Camera qualification and color calibration | Three card photos: repeatability, duplicate-patch uniformity, corrected-color accuracy; pass qualifies the phone for screening, fail limits it to research; store device color profile | L | v0.1 | NG-006, NG-009 | FR-04 |
| NG-039 | E2 | Card batch QR verification | Scan card QR; verify Ed25519 batch signature offline against a trust list; load batch reference colors; enforce expiry; unverified cards limited to research mode | M | v0.5 | NG-006, NG-045 | FR-04b, Section 9a |
| NG-045 | E2 | Card Production Kit | Print spec, matte lamination and window spec, batch measurement protocol (spectrophotometer and interim chart-transfer method), batch tolerance rules, QR signing CLI | M | v0.5 | NG-006 | Section 9a |
| NG-008 | E3 | Guided capture overlay | Camera screen with card placement guide and distance cue | M | v0.1 | NG-001 | FR-10 |
| NG-009 | E3 | Camera control and metadata | CameraX with Camera2 interop; turn off scene modes, effects, and extensions (HDR, night, beauty) where the phone allows; lock AE and AWB when available; record capture metadata (ISO, exposure time, AE/AWB state, hardware level) | L | v0.1 | NG-001 | FR-11 |
| NG-010 | E3 | Card detection and patch extraction | Locate card via fiducials; extract patch colors; reject partial or angled cards beyond tolerance | L | v0.1 | NG-006, NG-009 | FR-12 |
| NG-011 | E3 | Quality gate | Checks for card present, patches readable, glare, blur, under and over exposure, skin region size | L | v0.5 | NG-010, NG-014 | FR-13 |
| NG-012 | E3 | Retake guidance and fallback | Plain-language reason for each failure; after three failures, recommend blood test | S | v1.0 | NG-011 | FR-14 |
| NG-013 | E4 | Color correction | Map image to a standard color space using card patches plus device profile | L | v0.1 | NG-007, NG-010 | FR-16 |
| NG-014 | E4 | Skin window extraction | Extract the skin region from the card's punched window after perspective correction; reject windows showing cloth, shadow, or fingers (no neural network) | M | v0.5 | NG-010 | FR-17 |
| NG-015 | E4 | On-device estimation | Extract chromaticity features; run regression model with LiteRT; no network call | L | v1.0 | NG-013, NG-014, NG-032 | FR-18 |
| NG-016 | E4 | Uncertainty output | Return an estimated range; widen toward referral when uncertainty or image quality is marginal | M | v1.0 | NG-015 | FR-19 |
| NG-017 | E5 | Screening inputs | Birth date and time (compute age in hours), gestational age in weeks | S | v0.5 | NG-001 | FR-05, FR-06 |
| NG-056 | E5 | Guardian consent step | Before the safety questions, the health worker confirms the parent or guardian agreed to the screening; stored on the session without a name; "No" ends the screen with no photo (PRD D12) | S | v1.0 | NG-017 | FR-37 |
| NG-018 | E5 | Safety questions | Jaundice onset under 24 hours, danger signs, prior phototherapy | S | v1.0 | NG-017 | FR-07 |
| NG-019 | E5 | Rule engine | Runs before camera; urgent referral for early jaundice or danger signs; "cannot screen" for prior phototherapy, gestational age under 35 weeks, or age over 14 days | M | v1.0 | NG-018, NG-020 | FR-08 |
| NG-020 | E5 | Versioned triage configuration | Thresholds and bands in a signed, versioned config file reviewed by a clinical advisor; never hardcoded | M | v1.0 | None | FR-09 |
| NG-021 | E5 | Result screen | Four triage actions with color, icon, and text; estimated range; model version; blood test reminder | M | v1.0 | NG-016, NG-020 | FR-21, FR-22 |
| NG-022 | E5 | Experimental label | Triage result shown behind a clear "experimental, research use" label until Phase B validation is published | S | v1.0 | NG-021 | Section 13 |
| NG-023 | E6 | Local screening log | Room database of screenings; no name required; optional facility register number with identifier blocking (birth registration, national ID, mobile number patterns); register number erased at 60 days, records at 180 days (configurable); Home lists rechecks due | M | v1.0 | NG-021 | FR-23, FR-36, PRD Section 11 |
| NG-024 | E6 | Image discard in screening mode | Images deleted from memory and storage after analysis unless in research mode | S | v1.0 | NG-015 | FR-24, NFR-08 |
| NG-025 | E6 | Version traceability | Every record stores app, model, config, and device model versions | S | v0.5 | NG-001 | NFR-10 |
| NG-026 | E6 | Export and delete all data | User can export the log or wipe all local data | S | v1.0 | NG-023 | FR-33 |
| NG-027 | E6 | About and model card screen | Model card summary, validation population, limitations, license, version | S | v1.0 | NG-033 | FR-22, FR-32 |
| NG-057 | E6 | Shared-phone data protection | Exclude all app data from Android cloud backup and device transfer; block screenshots on screens showing register numbers or research data; no register numbers in notifications | S | v0.5 | NG-001 | NFR-13 |
| NG-028 | E7 | Research mode access | Scanning the signed study QR plus a PIN unlocks research mode (doc 8 Section 7.1) | M | v0.5 | NG-001 | FR-25 |
| NG-029 | E7 | Consent gate | Paper consent form ID required before any research capture | S | v0.5 | NG-028 | FR-26 |
| NG-030 | E7 | Blinded capture | Estimate hidden from user during study sessions | S | v0.5 | NG-028 | FR-27 |
| NG-031 | E7 | Paired TSB entry | TSB value and draw time, same session or later by participant code | M | v0.5 | NG-028 | FR-28 |
| NG-032 | E8 | Reproducible training pipeline | Scripts and notebooks that rebuild the model from data; documented environment; exports LiteRT model | L | v0.5 | NG-013 | NFR-11 |
| NG-033 | E8 | Model card | Data sources, population, metrics with confidence intervals, known failure modes, intended use | M | v1.0 | NG-032 | FR-32 |
| NG-034 | E7 | Encrypted research storage | Images and records encrypted at rest; keys in Android Keystore | M | v0.5 | NG-028 | FR-29, NFR-08 |
| NG-035 | E7 | Crash-safe research writes | Atomic writes; no record lost on crash or battery death | M | v0.5 | NG-034 | NFR-09 |
| NG-036 | E7 | De-identified export | CSV plus images to local storage or USB, encrypted to the study's export public key; no identifiers beyond participant code | M | v0.5 | NG-034 | FR-30 |
| NG-037 | E8 | Repository docs and licensing | README, MIT license for code, CC BY 4.0 for card and docs, card print guide, contribution guide | M | v1.0 | NG-006 | Section 1 |
| NG-038 | E8 | Retraining and localization guide | How to retrain on local data and add a language, for NGO forks | M | v1.0 | NG-032, NG-002 | Persona 4 |
| NG-053 | E8 | Clinical sign-off record and hazard log | `CLINICAL_SIGNOFF.md` with the clinical lead's signed approval of config and wording; hazard log; release pipeline refuses to enable triage without a clinically signed config | S | v1.0 | NG-020 | Section 9b |

---

## SHOULD HAVE

| ID | Epic | Item | Description | Size | Release | Depends on | PRD ref |
|---|---|---|---|---|---|---|---|
| NG-040 | E3 | Multi-site capture | Optional forehead capture combined with sternum for a multi-site estimate | M | v1.0 | NG-015 | FR-15 |
| NG-041 | E7 | Sclera capture in research mode | Flash and no-flash image pair of the sclera, stored for future model comparison | M | v0.5 | NG-009, NG-028 | Section 9 |
| NG-042 | E7 | Study server upload | Enrollment by scanning a signed study QR plus one-time code, then upload of research data over Wi-Fi to the study server (doc 7 API); background retries plus a manual "Upload now" button, because budget Android skins kill background work | L | v1.0 | NG-036, doc 7 | FR-31, NFR-12 |
| NG-043 | E2 | Card integrity check | Detect dirty, faded, or wet cards against stored profile; prompt replacement | M | v1.0 | NG-007 | Section 15 |
| NG-044 | E2 | Re-calibration prompt | Prompt device re-calibration every 30 days or after OS update | S | v1.0 | NG-007 | FR-04 |
| NG-046 | E1 | Supported device list | Public list of tested phones, shown in-app during compatibility check | S | v1.0 | NG-004 | NFR-03 |
| NG-047 | E5 | "What to tell the mother" card | Short, plain Bangla script for each triage action, including one sentence saying a phone app helps decide whether a blood test is needed (data protection ordinance section 13(6), doc 8 Section 13.1) | S | v1.0 | NG-021 | J3 |
| NG-048 | E5 | Recheck notification | Local notification when a recheck comes due, with no baby details; secondary to the Home due list because OEM battery managers may block it | M | v1.0 | NG-023 | J2, FR-36 |
| NG-049 | E6 | Opt-in crash reporting | Privacy-preserving crash reports, off by default | S | v1.0 | NG-001 | FR-34 |
| NG-055 | E1 | In-app update prompt | Google Play In-App Updates: flexible prompt by default, immediate (blocking) update when a release carries update priority 4 or 5, used for clinical-safety fixes (doc 12 Section 6) | S | v1.0 | NG-001 | Doc 8 Section 12.3 |
| NG-054 | E6 | App lock | Optional lock using the phone's screen lock (PIN, pattern, fingerprint) for shared family phones | S | v1.0 | NG-001 | FR-35 |
| NG-050 | E8 | Validation results page | Publish Phase B results in the repository and in the app's About screen | S | v2.0 | Partner study | Section 12 |
| NG-051 | E4 | Ward lighting check | Detect non-uniform light (mixed window and fluorescent) and warn before capture | M | v1.0 | NG-011 | Section 15 |

---

## COULD HAVE

| ID | Epic | Item | Description | Size | Release |
|---|---|---|---|---|---|
| NG-060 | E5 | Audio guidance in Bangla | Spoken result and instructions for low-literacy contexts | M | v1.1 |
| NG-061 | E3 | RAW (DNG) capture | Use RAW where the phone supports it for better color fidelity | M | v1.1 |
| NG-062 | E8 | Signed model updates | Download signed model and config updates when online; verify before use | L | v1.1 |
| NG-063 | E6 | Repeat screening trend | Show estimates across screenings for the same register number | M | v1.1 |
| NG-064 | E1 | Community languages | Hindi, Urdu, Swahili via contributed translation files | S | v1.1 |
| NG-065 | E4 | Card-free sclera screening | Sclera-based screening mode, only after validation on Bangladeshi data | L | v2.0 |
| NG-066 | E2 | Hour-specific chart | Plot estimate against age in hours on a threshold chart for the clinician | M | v1.1 |

---

## WON'T HAVE (this release)

| Item | Reason |
|---|---|
| Parent or caregiver home mode | Higher risk of false reassurance; requires regulatory oversight; competitors already target it |
| Home-printed or unverified cards | Color varies by printer, paper, and run; no color printers in target settings; a wrong card gives a silently wrong estimate |
| iOS app | Target users carry Android; would split a solo builder's effort |
| Login, accounts, or cloud sync of screenings | Screening must work offline with no identity; reduces privacy risk |
| EHR, DHIS2, or registry integration | Out of scope until a partner deployment needs it |
| Screening after phototherapy | Accuracy drops in published studies |
| Screening under 35 weeks gestation | Not covered by major validation studies; route to blood test |
| Diagnosis or treatment recommendations | NeoGold supports referral only |
| End-to-end deep network trained from scratch | Not enough paired data; engineered features plus regression is more reliable at this data size |
| Ads, paywall, subscriptions | Contradicts the project's purpose and grant terms |

---

## Suggested Build Order

**v0.1 Prototype (calibration pipeline):**
NG-001, NG-006, NG-008, NG-009, NG-010, NG-007, NG-013

Exit criteria: same card photo under three lighting conditions produces corrected patch colors within a defined tolerance of each other.

**v0.5 Research build (partner study ready):**
NG-004, NG-045, NG-039, NG-011, NG-014, NG-017, NG-025, NG-057, NG-028, NG-029, NG-030, NG-031, NG-034, NG-035, NG-036, NG-041, NG-032

Exit criteria: a full research session (consent ID, capture, blinded, TSB entry, export) completes on three devices with zero data loss in crash tests.

**v1.0 Open release:**
All remaining Must items (including NG-053), then Should items in order of NG-051, NG-043, NG-044, NG-046, NG-047, NG-054, NG-040, NG-048, NG-042, NG-049, NG-055.

Exit criteria: journey J2 under 60 seconds on a reference-class phone and working (slower is acceptable) on a 2 GB Android Go phone; airplane-mode test passes for every screening function; model card published; clinically signed triage config recorded in `CLINICAL_SIGNOFF.md`; at least one verified card batch in circulation.
