# NeoGold: Test Cases

| Field | Value |
|---|---|
| Document | 10 of 13 |
| Version | 0.1 |
| Date | 11 October 2026 |
| Owner | Sowad Al-Mughni, Kitalon Labs |
| Depends on | `9_Test_Plan_Strategy.md` v0.1 and the requirement, hazard, and gate IDs in docs 2 to 8 |
| Status | Awaiting founder review |

---

## 0. How to Read This Document

**Columns.** *Verifies* lists the requirement (`FR-`, `NFR-`), backlog item (`NG-`), hazard (`H`), threat (`T`), or release gate (`Gate S`) the case proves. *Level* is where it runs. *Pri* is priority: **P1** blocks any release, **P2** blocks v1.0 production, **P3** is tracked.

| Level code | Meaning |
|---|---|
| U | JVM unit test |
| I | Integration test (Robolectric, Room, WorkManager, MockWebServer) |
| C | Contract test against `openapi.yaml` |
| B | Backend test (pytest, Testcontainers) |
| UI | Instrumented UI test on emulator |
| E2E | Scripted or manual journey on a matrix phone (doc 9 Section 4.2) |
| BN | Bench test with the rig (doc 9 Section 5) |
| P | Performance measurement |
| R | Reliability or chaos test |
| S | Security test |
| A | Accessibility or localization check |
| M | Moderated usability or manual review |
| OPS | Pipeline, deployment, or operations check |

**Synthetic triage config (`testdata/triage/config.synthetic.json`).** Triage cases use round-number thresholds that are **not clinical values**, so tests do not change when the clinical lead sets the real ones. GA 38 and above: 10.0 at 0 h, 12.0 at 24 h, 17.0 at 48 h, 20.0 at 96 h, 20.0 at 336 h. GA 36 to 37: 15.5 at 48 h. GA 35: 14.0 at 48 h. Blood test zone within 3.0 or at 15.0 and above; recheck zone within 5.0 for 24 h; maximum interval width 6.0. All values in mg/dL. Expected results below were computed with the independent reference implementation of doc 4 Section 6.2 [Certain, run 11 October 2026].

`E` is the estimate, `L` and `U` the interval bounds, `T` the threshold at the baby's age and gestational age. Each result has **one** reason code, the first rule that matched (doc 4 Section 6.2), so a case that meets both `ZONE_WITHIN3` and `ZONE_ABS15` expects `ZONE_WITHIN3`.

---

## 1. Onboarding and Settings (TC-ONB)

| ID | Title | Verifies | Steps | Expected | Level | Pri |
|---|---|---|---|---|---|---|
| TC-ONB-01 | Language at first launch | FR-01, NG-002 | Fresh install; open app | S01 offers Bangla and English; choice applies to every following screen | UI | P1 |
| TC-ONB-02 | Safety notice must be acknowledged | FR-02, NG-003 | Fresh install; try to skip or press back on S02 to S04 | Cannot reach Home without tapping "I understand" on the last page | UI | P1 |
| TC-ONB-03 | Notice returns after a major model update | FR-02 | Set `safety_ack_version` below the bundled model's; launch | Notice shown again before Home | UI | P1 |
| TC-ONB-04 | Language switch in settings | FR-01, NFR-06 | Switch Bangla to English and back in S23 | All strings change; clinical numbers stay in Western digits | UI | P2 |
| TC-ONB-05 | About and model card | FR-32, NG-027, NG-033 | Open S24 | Model card summary, data sources, limitations, app, model, and config versions, and the open-source license list are shown | UI | P2 |
| TC-ONB-06 | In-app update prompt | NG-055 | Use `FakeAppUpdateManager` with priority 2, then 5 | Priority 2 shows the flexible prompt; priority 4 or 5 starts the immediate flow | I | P2 |

## 2. Device Check and Camera Qualification (TC-DEV)

| ID | Title | Verifies | Steps | Expected | Level | Pri |
|---|---|---|---|---|---|---|
| TC-DEV-01 | Device check reports capabilities | FR-03, NG-004 | Run S05 on D1 to D6 | RAM, low-RAM flag, and camera hardware level recorded in `device_profile`; brand-specific battery steps shown (HiOS steps on TECNO) | E2E | P1 |
| TC-DEV-02 | Repeatability pass | FR-04, NG-007, H5 | Three qualification photos on the bench at 4000 K | Repeatability Delta E 2000 under 1.5; value stored | BN | P1 |
| TC-DEV-03 | Uniformity pass | FR-04, H5 | Same capture set | Duplicate-patch difference under 2.0 | BN | P1 |
| TC-DEV-04 | Accuracy pass | FR-04, H5 | Same capture set | Corrected patches within 3.0 of batch reference | BN | P1 |
| TC-DEV-05 | Local processing fails a phone | FR-04, H5 | Force HDR or a beauty filter on, or use a phone known to tone-map locally; qualify | Uniformity fails; result `RESEARCH_ONLY`; S08 shows `phone_research_only`; screening entry blocked; research mode allowed | BN | P1 |
| TC-DEV-06 | Missing AE or AWB lock does not fail a phone | FR-11, ADR-012 | Qualify a `LEGACY` camera that passes the three measured tests | Result `QUALIFIED`; lock states recorded as false | E2E | P1 |
| TC-DEV-07 | Re-qualification prompt | NG-044 | Advance clock 30 days; separately, change the OS build fingerprint | Prompt to re-qualify in both cases | I | P2 |
| TC-DEV-08 | Qualification is audited | NFR-10 | Qualify, then fail one phone | `DEVICE_QUALIFIED` and `DEVICE_RESEARCH_ONLY` events written with values only | I | P2 |
| TC-DEV-09 | Battery settings guidance | FR-03, NFR-12 | Open S05 on D1, D2, D3 | Correct manufacturer steps and a shortcut to the right settings page where Android allows it | E2E | P2 |
| TC-DEV-10 | Supported device list | NG-046 | Run the device check on a listed and an unlisted phone | Listed phone shows "tested"; unlisted phone runs qualification and is told it is untested | UI | P3 |

## 3. Card Verification (TC-CARD)

| ID | Title | Verifies | Steps | Expected | Level | Pri |
|---|---|---|---|---|---|---|
| TC-CARD-01 | Valid batch accepted | FR-04b, NG-039 | Scan a signed test batch QR | Verified; reference values loaded; `signature_valid` 1 | U | P1 |
| TC-CARD-02 | One changed byte rejected | FR-04b, H4, T4 | Flip one byte in the payload | Signature fails; screening blocked with `card_invalid`; research capture allowed with warning, recorded `UNVERIFIED` | U | P1 |
| TC-CARD-03 | Unknown issuer rejected | FR-04b, H4 | Sign with a key not in the trust list | Same as TC-CARD-02 | U | P1 |
| TC-CARD-04 | Key used for the wrong purpose | T5, T6 | Sign a card batch with a `STUDY_ISSUER` key | Rejected; a key is accepted only for its stated purpose | U | P1 |
| TC-CARD-05 | Expired batch | FR-04b, H4 | Scan a batch past `expires_at` | Screening blocked; research sessions recorded `EXPIRED` | U | P1 |
| TC-CARD-06 | Revoked batch | H4 | Add the batch ID to the trust list revocation list | Rejected | U | P1 |
| TC-CARD-07 | Copied QR on a home print | H4, T4 | Bench: genuine QR attached to a home-printed copy; capture | Quality gate fails with `HIGH_RESIDUAL` in at least 95% of attempts across lights [threshold to confirm in v0.5] | BN | P1 |
| TC-CARD-08 | Faded or dirty card | NG-043, H4 | Bench: aged card (UV exposure) and smudged card | Card integrity prompt to replace the card | BN | P2 |
| TC-CARD-09 | Card message wording | FR-04b | Trigger TC-CARD-02 in both languages | `card_invalid` shown in English and Bangla | UI | P3 |
| TC-CARD-10 | Batch acceptance | NG-006, NG-045, H4 | Measure a new print batch per the Card Production Kit; sign its QR with `neogold-sign` | Every patch within the kit's batch tolerance of the design values; signed QR verifies in the app (TC-CARD-01) | BN | P1 |

## 4. Capture and Quality Gate (TC-CAP)

| ID | Title | Verifies | Steps | Expected | Level | Pri |
|---|---|---|---|---|---|---|
| TC-CAP-01 | Capture only with the card in view | FR-10, NG-008 | Move card in and out of frame on S16 | Auto-capture fires only with all 4 markers; overlay shows placement | E2E | P1 |
| TC-CAP-02 | Missing card | FR-13, NG-011 | Bad set: no card | `NO_CARD` | U | P1 |
| TC-CAP-03 | Card angle | FR-13, H6 | Jig at 10, 20, and 30 degrees | 10 accepted; 30 rejected `CARD_ANGLE`; 20 recorded as the boundary behavior | BN | P1 |
| TC-CAP-04 | Duplicate patches disagree | FR-13, H5 | Spot light on one corner; separately, force HDR | `NONUNIFORM_PATCHES` | BN | P1 |
| TC-CAP-05 | Blur | FR-13 | Bad set: motion blur | `BLUR` | U | P1 |
| TC-CAP-06 | Glare | FR-13 | Bad set: glossy reflection over 2% of a patch | `GLARE` | U | P1 |
| TC-CAP-07 | Exposure | FR-13 | Bad set: under and over exposed | `UNDEREXPOSED`, `OVEREXPOSED` | U | P1 |
| TC-CAP-08 | Mixed light | FR-13, H6, NG-051 | Bench: window daylight on one side, tube light on the other | `UNEVEN_LIGHT` | BN | P1 |
| TC-CAP-09 | High correction residual | FR-13, FR-16 | Bench: card from a different batch than its QR | `HIGH_RESIDUAL` | BN | P1 |
| TC-CAP-10 | Window not skin | FR-17, NG-014 | Bad set: cloth, finger, shadow, empty window | `WINDOW_NOT_SKIN` for each | U | P1 |
| TC-CAP-11 | Whole bad set rejected | FR-13, PRD Section 12 | Run every image in `testdata/bad/` | 100% rejected, each with its labeled reason | U | P1 |
| TC-CAP-12 | Good set accepted | FR-13 | Run every image in `testdata/golden/` | At least 95% accepted [target to confirm in v0.5]; every rejection reviewed | U | P2 |
| TC-CAP-13 | Retake guidance matches the cause | FR-14, NG-012 | Trigger each failure on S17 | Plain-language message for that cause, in both languages | UI | P2 |
| TC-CAP-14 | Fallback after three failures | FR-14 | Fail three times | `BLOOD_TEST_TODAY` with reason `QUALITY_FALLBACK` | UI | P1 |
| TC-CAP-15 | Capture metadata recorded | FR-11, NG-009, NFR-10 | Capture on D1 and D5 | ISO, exposure, AE and AWB lock state, gains, and hardware level stored | I | P2 |
| TC-CAP-16 | Forehead plus sternum | FR-15, NG-040 | Add the optional forehead photo | Weighted multi-site estimate; skipping forehead still gives a result | U | P2 |
| TC-CAP-17 | Card detection and patch extraction | FR-12, NG-010 | Synthetic rendered card images at rotations 0 to 360 degrees, three scales, and mild perspective; then bench photos | Markers found in every synthetic case; extracted patch values within 0.5 Delta E 2000 of the rendered values; trimmed means from the inner 60% of each patch | U | P1 |

## 5. Color Correction and Estimation (TC-EST)

| ID | Title | Verifies | Steps | Expected | Level | Pri |
|---|---|---|---|---|---|---|
| TC-EST-01 | Kotlin matches Python | FR-16, FR-18, H7 | Run golden vectors through both pipelines | Features and estimates equal within 1e-4 | U | P1 |
| TC-EST-02 | Correction accuracy on a reference chart | FR-16, NG-013 | Bench: ColorChecker under three lights | Mean Delta E 2000 of corrected patches within tolerance; values reported per phone | BN | P1 |
| TC-EST-03 | Skin window uses the inner 70% | FR-17 | Synthetic image with a colored ring at the window edge | Ring does not change the skin value | U | P2 |
| TC-EST-04 | Estimation offline | FR-18, NFR-01, NG-015 | Airplane mode; full screening | Result produced; zero network requests recorded | E2E | P1 |
| TC-EST-05 | Interval coverage | FR-19, NG-016, H1 | Held-out public data through the calibrated model | Empirical coverage of the 90% interval at least 85% [tolerance to confirm]; reported with the model card | U | P1 |
| TC-EST-06 | Invalid model file | H7 | Corrupt `model.json` or break its schema | Screening disabled; blood test message | U | P1 |
| TC-EST-07 | Same result on both ABIs | NFR-03, NFR-10, H7 | Golden vectors on `arm64-v8a` and `armeabi-v7a` builds | Identical integer-tenth E, L, and U and the identical triage action for every vector (a 0.1 tolerance would hide a vector that rounds into a different tenth); engines use `StrictMath` | I | P1 |

## 6. Triage Engine (TC-TRI)

All rows use the synthetic config in Section 0 unless stated.

| ID | Title | Verifies | Inputs | Expected | Level | Pri |
|---|---|---|---|---|---|---|
| TC-TRI-01 | Danger signs | FR-08, NG-019 | Danger signs yes | `REFER_URGENT`, `R1`, before the camera opens | U | P1 |
| TC-TRI-02 | Jaundice in first 24 h | FR-08 | Onset under 24 h yes | `REFER_URGENT`, `R2`, before the camera | U | P1 |
| TC-TRI-03 | Gestational age boundary | FR-08, H3 | GA 34; then GA 35 | 34: `CANNOT_SCREEN_BLOOD_TEST`, `R3`. 35: screening continues | U | P1 |
| TC-TRI-04 | Prior phototherapy | FR-08 | Phototherapy yes | `CANNOT_SCREEN_BLOOD_TEST`, `R4` | U | P1 |
| TC-TRI-05 | 14-day boundary | FR-08 | Age 336.1 h; then 336.0 h | 336.1: `CANNOT_SCREEN_CLINICAL_REVIEW`, `R5`. 336.0: screening continues | U | P1 |
| TC-TRI-06 | Rule order | FR-08 | Danger signs yes and GA 34 | `REFER_URGENT`, `R1` (R1 wins) | U | P1 |
| TC-TRI-07 | Estimate exactly at threshold | FR-20, H1 | 48 h, GA 40 (T = 17.0); E 17.0, L 15.0, U 19.0 | `REFER_URGENT`, `ABOVE_THRESHOLD` | U | P1 |
| TC-TRI-08 | Just below threshold | FR-20, H1 | 48 h; E 16.9, L 14.9, U 18.9 | `BLOOD_TEST_TODAY`, `ZONE_WITHIN3` | U | P1 |
| TC-TRI-09 | Upper bound at T minus 3 | FR-20, H1 | 48 h; E 12.0, L 10.0, U 14.0; then E 11.9, L 9.9, U 13.9 | 14.0: `BLOOD_TEST_TODAY`, `ZONE_WITHIN3`. 13.9: `RECHECK` | U | P1 |
| TC-TRI-10 | Absolute 15 rule | FR-20, H1 | 96 h (T = 20.0); E 12.0, L 10.0, U 15.0; then E 11.9, L 9.9, U 14.9 | 15.0: `BLOOD_TEST_TODAY`, `ZONE_ABS15`. 14.9: `NO_FURTHER_ACTION_FROM_SCREEN` | U | P1 |
| TC-TRI-11 | Interval width | FR-19, FR-20, H1 | 48 h; L 3.0, E 6.0, U 9.1; then L 3.0, U 9.0 | Width 6.1: `BLOOD_TEST_TODAY`, `WIDE_INTERVAL`. Width 6.0: `NO_FURTHER_ACTION_FROM_SCREEN` | U | P1 |
| TC-TRI-12 | Recheck boundary | FR-20, NG-019 | 48 h; E 10.0, L 8.0, U 12.0 | `RECHECK`, `RECHECK_ZONE`; recheck due 24 h after capture | U | P1 |
| TC-TRI-13 | Below every zone | FR-20 | 48 h; E 10.0, L 8.1, U 11.9 | `NO_FURTHER_ACTION_FROM_SCREEN` | U | P1 |
| TC-TRI-14 | Threshold interpolation | FR-20, H2 | 36 h (T = 14.5); E 14.5, L 12.5, U 16.5; then E 14.4, L 12.4, U 16.4 | 14.5: `REFER_URGENT`. 14.4: `BLOOD_TEST_TODAY`, `ZONE_WITHIN3` | U | P1 |
| TC-TRI-15 | Quality fallback | FR-14 | Three failed captures | `BLOOD_TEST_TODAY`, `QUALITY_FALLBACK` | U | P1 |
| TC-TRI-16 | Band selection | FR-20, H3 | 48 h; E 15.5, L 13.5, U 17.5 with GA 37, then GA 38 | GA 37 (band GA 36 to 37, T = 15.5): `REFER_URGENT`. GA 38 (T = 17.0): `BLOOD_TEST_TODAY` | U | P1 |
| TC-TRI-17 | Release config covers every case | FR-09, NG-020, H3 | Schema and property check on the **clinical** config | Every GA from 35 to 44 maps to exactly one band; every band covers 0 to 336 h; non-monotonic tables flagged for the clinical lead | U | P1 |
| TC-TRI-18 | Draft config in a public build | FR-09, Gate S1, H7 | Public build with `clinicalSignoff.status = DRAFT` | No triage result anywhere; blood test message | U | P1 |
| TC-TRI-19 | Config signature broken | H7 | Change one threshold after signing | Startup check fails; screening disabled | I | P1 |
| TC-TRI-20 | Config past `validUntil` | H8 | Set device date after `validUntil` | Triage disabled; message to update the app | U | P1 |
| TC-TRI-21 | Sign-off hash enforced in CI | FR-09, NG-053 | Release build where config hash differs from `CLINICAL_SIGNOFF.md` | Build fails | OPS | P1 |
| TC-TRI-22 | Output traceability | NFR-10, NG-025 | Any result | Action, reason codes, threshold, interval, config, model, and card batch versions stored on the session | I | P1 |
| TC-TRI-23 | Fixed-point comparison | H1 | 49.6 h, GA 40 (T = 17.1); E 12.0, L 10.0, U 14.1. In double precision 17.1 minus 3.0 is 14.100000000000001, so a naive comparison misses the zone [Certain, checked] | Compared in integer tenths (141 against 141); `BLOOD_TEST_TODAY`, `ZONE_WITHIN3` | U | P1 |
| TC-TRI-25 | Rounding toward more care | H1 | 48 h (T = 17.0); E 16.95, L 14.0, U 18.0; then an interpolated threshold of 16.96 against E 16.9 | E rounds up to 17.0: `REFER_URGENT`. Threshold rounds down to 16.9: `REFER_URGENT` | U | P1 |
| TC-TRI-26 | Triage disabled | FR-09, H7 | Config with `triageEnabled` false and no sign-off | G1 passes; no triage result on any screen; blood test message; research mode unaffected | U | P1 |
| TC-TRI-24 | Differential test | H1, H7 | 10,000 generated cases (random GA, age, flags, E, L, U, including every boundary) through the Kotlin engine and the Python reference | Zero disagreements. As a check that the test has teeth: a naive floating-point engine with unrounded thresholds disagreed with the reference in 34 of 10,000 random cases, and chose less care in 28 of them [Certain, run 11 October 2026] | U | P1 |

## 7. Result Screen and Wording (TC-UI)

| ID | Title | Verifies | Steps | Expected | Level | Pri |
|---|---|---|---|---|---|---|
| TC-UI-01 | Four actions, four signals | FR-21, NFR-07, NG-021, H11 | Render each action | Each has its color, icon, border, and words; none relies on color alone | UI | P1 |
| TC-UI-02 | Experimental label | NG-022 | Every result in a public build | Label visible without scrolling at 360 dp | UI | P1 |
| TC-UI-03 | Version and blood test reminder | FR-22 | Every result | Model version, validation summary, and `confirm_blood` shown | UI | P1 |
| TC-UI-04 | Banned words | H11 | String lint on English and Bangla resources | None of the English or Bangla words in doc 6 Section 10.2 (safe, normal, healthy, fine, no jaundice, diagnosis, accurate, AI) | A | P1 |
| TC-UI-05 | Small screen and large text | NFR-07, H11 | 320 dp width, font scale 1.3 and 2.0, both languages | No truncation of action or next step; buttons reachable | UI | P1 |
| TC-UI-06 | Contrast | NFR-07 | Automated check of every token pair in doc 6 | 4.5:1 or more | A | P1 |
| TC-UI-07 | Touch targets | NFR-07 | Inspect all interactive elements | At least 48 dp | UI | P2 |
| TC-UI-08 | Screen reader order | NFR-07, H11 | TalkBack on S20 | Reads action, then next step, then reminder | A | P1 |
| TC-UI-09 | What to tell the mother | NG-047 | Open the card for each action | Plain Bangla script, including the sentence that a phone app helps decide whether a blood test is needed | M | P2 |
| TC-UI-10 | Phone time shown at birth entry | H2 | Open S10 | "Phone time now" line shows the phone's current date and time | UI | P1 |
| TC-UI-11 | Gestational age entry | FR-06, NG-017, H3 | Open S10 and try Next without touching the stepper | Next stays disabled until the health worker sets the weeks; no pre-filled value | UI | P1 |
| TC-UI-12 | Safety questions | FR-07, NG-018 | Answer S11 to S13 | Each question needs an explicit Yes or No (no default); the four danger signs are listed with pictures; answers stored on the session | UI | P1 |
| TC-UI-13 | Guardian consent at screening | FR-37, NG-056 | Start a screening; answer No on S10a; then start again and answer Yes | No: `consent_no` shown, no photo, nothing saved. Yes: flow continues and `guardian_consent_confirmed` is 1 on the saved session | UI | P1 |
| TC-UI-14 | Decision-support wording | FR-21, H11 | Render every result action in both languages | `result_suggested` above and `result_you_decide` below each action; matches doc 6 Section 10.3 | UI | P1 |

## 8. Screening Log, Retention, Register Number (TC-LOG)

| ID | Title | Verifies | Steps | Expected | Level | Pri |
|---|---|---|---|---|---|---|
| TC-LOG-01 | Session saved and filterable | FR-23, NG-023 | Save three sessions on different days; filter | Sessions listed with versions; date filter works | UI | P2 |
| TC-LOG-02 | Register numbers accepted | FR-23 | Enter `12/345`, `OPD-2027-15` | Accepted | U | P1 |
| TC-LOG-03 | Identifier patterns rejected | FR-23, T15 | Enter a 17-digit number, 10- and 13-digit numbers, `01712345678`, and 21 characters | Each rejected with `register_rejected`; nothing saved | U | P1 |
| TC-LOG-04 | Register number erased at 60 days | FR-23, PRD D7 | Advance clock 60 days; run job and app start | `register_number` null; `register_number_erased_at` set; `REGISTER_ERASURE` audited | I | P1 |
| TC-LOG-05 | Sessions purged at 180 days | PRD D7 | Advance 180 days; set retention to 30 and 365; try 29 and 366 | Purged on schedule; out-of-range values refused; purge audited with a count only | I | P1 |
| TC-LOG-06 | Log switched off | NFR-08 | `keep_screening_log` false; screen a baby | Result shown; no session row written | I | P2 |
| TC-LOG-07 | No screening image on disk | FR-24, NG-024, NFR-08 | Screen; list app files and media store | No image file from screening mode anywhere | I | P1 |
| TC-LOG-08 | Due list with notifications blocked | FR-36, NFR-12, H10 | Block notifications and background work; create a recheck | Home shows the recheck when due | E2E | P1 |
| TC-LOG-09 | Notification hides details | NFR-13, NG-048 | Trigger a recheck notification | Text is "A recheck is due", no register number or result | UI | P1 |
| TC-LOG-10 | Log export | FR-33, NG-026 | Export the log | Warning screen; register numbers excluded by default; file written through the system picker | UI | P2 |
| TC-LOG-11 | Delete all data | FR-33 | Delete all and confirm | Database, encrypted files, and keys removed; app restarts empty | I | P1 |
| TC-LOG-12 | What was shown can be reconstructed | T16, NFR-10 | Open a saved session in S22 | Action, reason codes, threshold, interval, versions, and card batch shown as at screening time | UI | P2 |

## 9. Shared Phones and Privacy (TC-PRIV)

| ID | Title | Verifies | Steps | Expected | Level | Pri |
|---|---|---|---|---|---|---|
| TC-PRIV-01 | App lock | FR-35, NG-054, T2 | Enable lock; reopen; background for 5 minutes | Screen lock prompt each time; works on API 26 and 30 fallback | E2E | P2 |
| TC-PRIV-02 | Screenshots blocked | NG-057, T2 | Screenshot on S21, S22, R02 to R07; check recents | Screenshot refused; recents thumbnail blank | E2E | P1 |
| TC-PRIV-03 | No backup or device transfer | NFR-13, NG-057, T17 | Run a backup with `bmgr`; run a device-to-device transfer | No NeoGold data in either | S | P1 |
| TC-PRIV-04 | No network without consent | FR-34, NFR-01 | Proxy capture during a full screening journey | Zero requests in screening mode | S | P1 |
| TC-PRIV-05 | Crash report content | FR-34, NG-049 | Force a crash with a register number in the exception message | Report has class names and frames only; message dropped; sharing only through the share sheet | I | P1 |

## 10. Research Mode (TC-RSH)

| ID | Title | Verifies | Steps | Expected | Level | Pri |
|---|---|---|---|---|---|---|
| TC-RSH-01 | Unlock with signed study QR | FR-25, NG-028 | Scan a test study QR; set PIN | Study name, partner, and host shown; research mode unlocked | UI | P1 |
| TC-RSH-02 | Forged study QR | FR-25, T6 | Change the host in the QR payload | Signature fails; unlock refused | U | P1 |
| TC-RSH-03 | Expired study profile | FR-25 | Profile past `expires_at` | Refused | U | P2 |
| TC-RSH-04 | Weak PINs refused | FR-25 | Try `000000`, `123456`, `121212`, and a top-100 PIN | Each refused | U | P1 |
| TC-RSH-05 | PIN lockout survives force-stop | FR-25, T1 | 5 wrong PINs, force-stop, reopen; continue to 10 and 15 | 1 minute, 15 minutes, then 1 hour per attempt; counter kept across force-stop; no wipe | I | P1 |
| TC-RSH-06 | PIN hash useless off the phone | T1 | Copy the database and salt to a computer; try all 6-digit PINs | No PIN verifies without the phone's Keystore HMAC key | S | P1 |
| TC-RSH-07 | Re-lock | FR-25 | Background research mode for 5 minutes; separately, turn screen off | PIN required again | UI | P2 |
| TC-RSH-08 | Consent ID required | FR-26, NG-029 | Start a session without a consent form ID; then with a malformed one | Capture blocked in both cases | UI | P1 |
| TC-RSH-09 | Participant code format | FR-26, T15 | Enter `SKP-0142`, then `12345678901234567` | First accepted; second refused | U | P1 |
| TC-RSH-10 | Blinded capture | FR-27, NG-030 | Complete a research session with TalkBack on and logcat captured | Estimate never on screen, never spoken, never in logs | E2E | P1 |
| TC-RSH-11 | TSB entry later by code | FR-28, NG-031 | Enter TSB two days later by participant code | Linked to the right participant; method required | UI | P1 |
| TC-RSH-12 | TSB linked to a session later | FR-28 | Enter TSB before the session exists; link afterwards | Outbox row re-queued as a new revision; server shows `linked` true | I | P1 |
| TC-RSH-13 | Research images encrypted | FR-29, NG-034 | Read research image files from app storage on a rooted test phone | Not JPEG bytes; decrypt only through the app | S | P1 |
| TC-RSH-14 | Kill during save | NFR-09, NG-035 | Kill the process at random points during 50 saves per phone | Zero partial or lost records | R | P1 |
| TC-RSH-15 | Power loss during save | NFR-09 | Pull the battery (or hold power-off) during 10 saves on a phone with a removable battery or a USB-powered rig | Zero partial or lost records | R | P1 |
| TC-RSH-16 | Sclera pair | NG-041 | Capture flash and no-flash sclera images | Both stored and linked to the session | E2E | P2 |

## 11. Export (TC-EXP)

| ID | Title | Verifies | Steps | Expected | Level | Pri |
|---|---|---|---|---|---|---|
| TC-EXP-01 | Encrypted USB export | FR-30, NG-036 | Export to USB; decrypt with `neogold-admin decrypt-export` | Files unreadable without the study key; after decryption, CSV rows and image hashes match | I | P1 |
| TC-EXP-02 | No identifiers | FR-30, NFR-08, T15 | Inspect a decrypted export | Participant code is the only link; no register numbers, names, or birth dates | S | P1 |
| TC-EXP-03 | Date shift | T15 | Decrypt and import an export; check dates | One random offset per participant; intervals between a participant's sessions and TSB unchanged | B | P1 |
| TC-EXP-04 | Export-only study | FR-30 | Study profile with no host; run a full study session and export, offline | Works without any server | E2E | P2 |

## 12. Sync and Outbox (TC-SYNC)

Fake server is OkHttp `MockWebServer` unless the level is E2E (staging).

| ID | Title | Verifies | Steps | Expected | Level | Pri |
|---|---|---|---|---|---|---|
| TC-SYNC-01 | Upload order | FR-31, NG-042 | Queue a new batch, participant, session with 6 images, TSB | Order: `GET /device`, batch, participant, manifest, images, TSB | I | P1 |
| TC-SYNC-02 | Paused study stops early | FR-31 | `GET /device` returns `PAUSED` | No image request sent; retry scheduled | I | P1 |
| TC-SYNC-03 | Timeout retry keeps the key | FR-31 | Drop the connection after the server processes a manifest | Retry carries the same `Idempotency-Key`; server replays; no duplicate | I | P1 |
| TC-SYNC-04 | Request in progress | FR-31 | Server answers `409 REQUEST_IN_PROGRESS` | Retry after 30 s with the same key | I | P2 |
| TC-SYNC-05 | Dependency re-queue limit | FR-31 | Server always answers `PARTICIPANT_UNKNOWN` | Participant re-sent; after 3 re-queues the session is `FAILED_PERMANENT` with the code shown | I | P1 |
| TC-SYNC-06 | Hash mismatch | FR-31 | Server answers `HASH_MISMATCH` three times | Same key and headers each time; then `FAILED_PERMANENT` | I | P2 |
| TC-SYNC-07 | Server rejects a card batch | FR-31 | `CARD_BATCH_INVALID` on the batch | Its sessions sent with `card_status UNVERIFIED` and no batch ID | I | P1 |
| TC-SYNC-08 | Unknown error code | FR-31 | Server returns a new code with `retryable` true, then false | Retried, then permanent | I | P2 |
| TC-SYNC-09 | Recovery after an app fix | FR-31 | Item failed with `VALIDATION_FAILED`; install a newer app version | Re-queued once with a new key | I | P2 |
| TC-SYNC-10 | One runner only | NFR-12 | Tap Upload now during a background run | No second runner; the active run continues | I | P1 |
| TC-SYNC-11 | Killed run recovers | NFR-12 | Kill the app with items `IN_FLIGHT`; reopen | Items reset to `PENDING` and uploaded | I | P1 |
| TC-SYNC-12 | Long backlog on a slow link | FR-31, NFR-12 | 50 images over a link throttled to 256 kbps | Foreground worker completes past the 10-minute worker limit; progress kept per item | E2E | P1 |
| TC-SYNC-13 | Reconciliation before deletion | FR-31, NFR-09 | Retention due; server returns `404` for one session | That session re-queued; others deleted only after `COMPLETE` | I | P1 |
| TC-SYNC-14 | Restore from backup | FR-31, NFR-09 | On staging, restore a backup taken before: a new session, a TSB value linked to an older complete session, an unlinked TSB value, and a second-phone participant alias; run `restore mark` | Phones re-send all four; server ends with every item; a second restore and `restore mark` still triggers re-sending (generation keeps increasing) | E2E | P1 |
| TC-SYNC-15 | Wi-Fi only | FR-31 | Mobile data only; then Wi-Fi | No upload on mobile data; upload on Wi-Fi | E2E | P2 |
| TC-SYNC-16 | Revocation keeps data | FR-31, T1 | Server answers `DEVICE_REVOKED` | Device key deleted; outbox and research data kept; USB export still works | I | P1 |
| TC-SYNC-17 | Phone clock 10 minutes fast | FR-31 | Set clock ahead; upload | `CLOCK_SKEW`, offset corrected, retry succeeds | E2E | P2 |
| TC-SYNC-18 | Local dates | FR-31, doc 7 Section 1 | Capture at 02:00 Dhaka time | `capture_date` is that Dhaka date, not the UTC date | U | P1 |
| TC-SYNC-19 | Same baby, two phones | FR-31 | Register `SKP-0142` with the same consent ID on two phones; upload sessions from both | Second phone's ID becomes an alias; all sessions under one participant | E2E | P1 |
| TC-SYNC-20 | No plain image on disk during upload | FR-29, T1 | Watch app storage during uploads | No decrypted image file ever written | S | P1 |

## 13. Study Intake API (TC-API)

| ID | Title | Verifies | Steps | Expected | Level | Pri |
|---|---|---|---|---|---|---|
| TC-API-01 | Health | NG-042 | `GET /health` | 200 with `server_time` and `api_version` | B | P2 |
| TC-API-02 | Enrollment succeeds once | NG-042, T8 | Enroll with a fresh code | 201; code marked used; device stored with thumbprint | B | P1 |
| TC-API-03 | Lost 201 replayed | NG-042 | Repeat the same request, key, and public key | Same 201 and device; no new device | B | P1 |
| TC-API-04 | Double submit | T8 | Two concurrent enrollments with the same code and different keys | Exactly one device; the other gets `ENROLLMENT_CODE_USED` | B | P1 |
| TC-API-05 | Bad codes and rate limit | T8 | Expired, used, and unknown codes; 11 failed attempts from one client IP within an hour; 10 successful enrollments from one IP | Correct codes; 11th failure gets 429; successes not limited | B | P1 |
| TC-API-06 | Attestation binding | T8 | Valid chain; wrong challenge; leaf key different from the JWK | `VERIFIED`; `FAILED`; `FAILED` | B | P2 |
| TC-API-07 | Settings mismatch refused by the app | FR-25 | Server `timestamp_precision` differs from the signed profile | App deletes the new key and shows `study_settings_mismatch` | I | P2 |
| TC-API-08 | Ten authentication cases | Gate S7, T9 | Run doc 7 Section 12.3 cases 1 to 10 | All ten give the expected result | B | P1 |
| TC-API-09 | Rejection before the body | T13 | Revoked device sends a 20 MB image | 403 returned without reading the body (bytes read under 64 KB) | B | P1 |
| TC-API-10 | Nonce lifetime | T9 | Token at `exp` plus 20 s after the purge job runs | Nonce still present; replay refused | B | P1 |
| TC-API-11 | URL binding behind Caddy | T9 | Full request through Caddy on staging | `htu` and `aud` match the public base URL; request accepted | E2E | P1 |
| TC-API-12 | Exempt calls | NG-042 | `GET /device`, `GET /sessions/{id}`, `DELETE /device` on a `CLOSED` study and an old app version | All succeed | B | P1 |
| TC-API-13 | Only final outcomes stored | NG-042 | Manifest gets `PARTICIPANT_UNKNOWN`; upload participant; retry manifest with the same key | Retry succeeds | B | P1 |
| TC-API-14 | Key reused with a new body | NG-042 | Same key, different body | `IDEMPOTENCY_KEY_REUSED` | B | P2 |
| TC-API-15 | Unknown fields rejected | T15 | Add an extra field to every request schema | 400 `VALIDATION_FAILED` for every one | C | P1 |
| TC-API-16 | Participant alias and conflict | NG-042 | Same code and consent ID from a second device; then a different consent ID | 200 with `canonical_participant_id`; then 409 `PARTICIPANT_CODE_TAKEN` | B | P1 |
| TC-API-17 | Manifest first and lost images | NG-042 | Image for an unlisted capture; manifest with `has_image` false for one capture | 404 `CAPTURE_NOT_IN_MANIFEST`; session completes without that image | B | P1 |
| TC-API-18 | Race on the last two images | NG-042 | Upload the last two images concurrently, 100 iterations | Every session ends `COMPLETE` exactly once | B | P1 |
| TC-API-19 | Image hash mismatch not stored | NG-042 | Send wrong bytes, then the correct bytes with the same key | 422 `HASH_MISMATCH`, then 201 | B | P1 |
| TC-API-20 | JPEG metadata allow-list | T15 | JPEGs with GPS, `DateTimeOriginal`, XMP, MPF, and an embedded thumbnail; a clean JPEG | Each rejected `IMAGE_METADATA_NOT_ALLOWED`; clean one accepted | B | P1 |
| TC-API-21 | Decompression bomb | T14 | JPEG header declaring 60,000 by 60,000 pixels | `IMAGE_INVALID`; server memory stays flat | B | P1 |
| TC-API-22 | Size limit | NG-042 | 20,000,001-byte image | 413 | B | P2 |
| TC-API-23 | Semantic validation | NG-042 | `captured_at` in a `DAY_PLUS_OFFSETS` study; estimate outside its interval; consent after capture; `VERIFIED` with no batch | 400 or 422 with the codes in doc 7 Section 7 | B | P1 |
| TC-API-24 | TSB links once | FR-28 | Link a TSB value; then change it again | 200 with `linked` true; then 409 `CONFLICT_IMMUTABLE` | B | P1 |
| TC-API-25 | Rate limit | T13 | 21 back-to-back requests from one device; separately, 1 request per second for 2 minutes | 21st gets 429 with `Retry-After`; the steady stream gets none | B | P2 |
| TC-API-26 | Dates only in data tables | T15 | Inspect `session`, `capture`, `tsb_measurement`, `participant` after uploads | Arrival and completion stored as dates; no time of day | B | P1 |
| TC-API-27 | Audit log content | T11, T15 | Inspect `audit_log` after a full study day | No per-session events; no participant codes | B | P1 |
| TC-API-28 | Admin commands audited | T11 | Run every `neogold-admin` command | One `ADMIN` audit row each; `session reject` removes images | B | P2 |
| TC-API-29 | Server export | T15 | `neogold-admin export` on a study with a `--test` site and a withdrawn participant | De-identified; date shift per participant; SHA-256 manifest verifies; nothing from the test site or the withdrawn participant | B | P1 |
| TC-API-30 | Fuzzing | NG-042 | Schemathesis against staging | No 5XX; every response matches the spec | C | P1 |
| TC-API-31 | Error bodies leak nothing | T15 | Trigger every error code | No stack traces, request body content, or participant codes in problem details | B | P1 |
| TC-API-32 | Participant withdrawal | T15 | `participant withdraw SKP-0142`; then a phone uploads another session for that participant | Sessions rejected, images and TSB values deleted, `withdrawn_on` set; the new upload gets `PARTICIPANT_WITHDRAWN` and the phone deletes its local copy | B | P1 |
| TC-API-33 | Client IP through Caddy | T8, T13 | On staging, 11 failed enrollments from client A, then one from client B, all through Caddy | A is limited at the 11th; B is not; logs show the real client addresses, not Caddy's | E2E | P1 |

## 14. Security (TC-SEC)

| ID | Title | Verifies | Steps | Expected | Level | Pri |
|---|---|---|---|---|---|---|
| TC-SEC-01 | Manifest allow-lists | Gate S5 | CI check on the merged release manifest | Only allow-listed exported components and permissions; `allowBackup` false; both backup rule files present | OPS | P1 |
| TC-SEC-02 | Network configuration | Gate S6, T7 | `check_network_config.py` in CI; then a MITM proxy with a user-installed CA and an `http://` URL on a device | Static check passes; proxy cannot read traffic; cleartext refused | S | P1 |
| TC-SEC-03 | Release build hygiene | T17 | Inspect release APK; capture logcat during a journey | Not debuggable; nothing below `WARN` from NeoGold | S | P1 |
| TC-SEC-04 | 16 KB alignment | Gate S4 | `zipalign -c -P 16` and `llvm-objdump` on every `.so`; run on D7 | All aligned; app runs on a 16 KB page size image | OPS | P1 |
| TC-SEC-05 | Vulnerabilities and SBOM | Gate S2, Gate S3, T12 | OSV-Scanner, Trivy, CycloneDX on the release | No open critical or high; SBOMs attached | OPS | P1 |
| TC-SEC-06 | Secrets scan | Gate S9 | gitleaks on the release commit | Clean | OPS | P1 |
| TC-SEC-07 | Keystore loss | T1 | Delete the app's Keystore entries on a test phone; open app | Clear message; encrypted stores reset; app keeps working | R | P1 |
| TC-SEC-08 | Database unreadable off the phone | T1, NFR-08 | Pull the database from a rooted test phone | Cannot be opened without the key | S | P1 |
| TC-SEC-09 | Tampered assets | H7, H9, T3 | Re-sign the APK with a changed config asset | Startup integrity check disables screening; signing fingerprint differs from the published one | S | P1 |
| TC-SEC-10 | Static scan | Gate S1 | MobSF on the release APK | No unresolved high findings | S | P1 |
| TC-SEC-11 | Server exposure | T10 | nmap from outside; SSH with a password; inspect containers | Only 80, 443, and WireGuard open; password login refused; containers non-root and read-only | S | P1 |
| TC-SEC-12 | TLS settings | T7 | testssl.sh against staging | TLS 1.0 and 1.1 refused; HSTS present | S | P1 |
| TC-SEC-13 | Backups encrypted and restorable | Gate S8 | Read the restic repository without the password; run a restore | Unreadable; restore succeeds and passes checks | OPS | P1 |
| TC-SEC-14 | MASVS checklist | Gate S1 | Review doc 8 Section 1 checklist for the release | Every item passed or has a recorded exception | M | P1 |

## 15. Performance (TC-PERF)

| ID | Title | Verifies | Steps | Expected | Level | Pri |
|---|---|---|---|---|---|---|
| TC-PERF-01 | Capture to result | NFR-02 | 30 runs on D1 and D2 (doc 9 Section 11) | p50 under 4 s; p95 under 10 s | P | P1 |
| TC-PERF-02 | 2 GB floor | NFR-03, NFR-04b | 30 runs on D4 | No out-of-memory kills; times reported | P | P1 |
| TC-PERF-03 | Peak memory | NFR-04b | Perfetto during analysis on D1, D4 | Under 120 MB | P | P1 |
| TC-PERF-04 | App size | NFR-04 | bundletool size report | Download under 25 MB per device configuration; install under 40 MB | P | P1 |
| TC-PERF-05 | Full journey | NFR-02 | Trained user, 10 journeys on D1 | Under 60 s each | P | P2 |
| TC-PERF-06 | Battery | NFR-05 | 20 screenings from full charge on D1 | Under 1% per screening | P | P2 |
| TC-PERF-07 | Low storage | NFR-09 | Fill storage to 150 MB free; screen and save research data | Warning shown; no crash; no partial record | R | P2 |

## 16. Reliability and Clock (TC-REL)

| ID | Title | Verifies | Steps | Expected | Level | Pri |
|---|---|---|---|---|---|---|
| TC-REL-01 | Process death at each step | NFR-09, H1 | Kill the app at every screen from S10 to S20 | Flow restarts safely; never shows a result for an unfinished analysis | R | P1 |
| TC-REL-02 | Airplane mode | NFR-01 | Full journey in airplane mode on D1 to D4 | Every screening function works | E2E | P1 |
| TC-REL-03 | Clock before build time | H2 | Set the phone date before the app's build date | Screening blocked with `clock_wrong_block` | U | P1 |
| TC-REL-04 | Automatic time off | H2 | Turn off automatic date and time | `clock_auto_off` warning on S10 | UI | P1 |
| TC-REL-05 | Research clock drift | H2 | Research mode; phone clock 15 minutes from the stored server offset | Screening blocked until corrected | U | P1 |
| TC-REL-06 | Birth time in the future | H2, FR-05 | Enter a birth time after the phone's current time | Refused | U | P1 |
| TC-REL-07 | Battery manager kills the app | NFR-12, H10 | On D1 and D2 with default battery settings, leave the app overnight with a recheck and pending uploads | Due list correct on reopen; Upload now completes the queue | E2E | P1 |
| TC-REL-08 | Upgrade migrations | NFR-09 | Open every earlier schema version with fixture data | Migrates without loss | I | P1 |

## 17. Accessibility and Localization (TC-A11Y)

| ID | Title | Verifies | Steps | Expected | Level | Pri |
|---|---|---|---|---|---|---|
| TC-A11Y-01 | Every string translated | NFR-06 | Lint for hardcoded strings; compare English and Bangla key sets | No hardcoded strings; no missing keys | A | P1 |
| TC-A11Y-02 | Bangla rendering | NFR-06 | All screens on D5 (Android 10) | Conjuncts and vowel signs render correctly in Noto Sans Bengali | A | P2 |
| TC-A11Y-03 | Western digits | NFR-06 | Bangla UI with clinical numbers | Clinical numbers in Western digits | A | P2 |
| TC-A11Y-04 | TalkBack journey | NFR-07, NG-005 | Full screening with TalkBack | Every control labeled; journey possible without sight of the screen except the camera step | A | P2 |
| TC-A11Y-05 | Color vision | NFR-07, H11 | Simulate protanopia and deuteranopia on S20 | Four actions distinguishable | A | P2 |

## 18. Usability and Field (TC-USE)

| ID | Title | Verifies | Steps | Expected | Level | Pri |
|---|---|---|---|---|---|---|
| TC-USE-01 | Unaided screening | H11, NFR-02 | 5 to 8 nurses each complete J2 (doc 9 Section 13) | 90% complete unaided | M | P1 |
| TC-USE-02 | Teach-back on results | H11 | Show each of the four results | Every participant states the correct next step | M | P1 |
| TC-USE-03 | Wording sign-off | FR-09, NG-053 | Nurse panel reviews all Bangla strings | Signed wording record in the repository | M | P1 |
| TC-USE-04 | Ward lighting | H6 | Capture at three partner wards at different times of day | Quality gate decisions reviewed; failure reasons match conditions | E2E | P2 |
| TC-USE-05 | Capture success | PRD Section 12 | Field pilot | 90% or more succeed within two attempts | M | P2 |

## 19. Deployment and Operations (TC-OPS)

| ID | Title | Verifies | Steps | Expected | Level | Pri |
|---|---|---|---|---|---|---|
| TC-OPS-01 | Release pipeline gates | Gate S1, NG-053, NG-001 | Tag a release candidate with a `DRAFT` config; then with a mismatched hash; then correct | First two fail; third builds a signed bundle | OPS | P1 |
| TC-OPS-02 | Upload to internal track | doc 12 Section 4 | Release workflow publishes to the internal track | Bundle accepted; `versionCode` higher than every earlier one | OPS | P1 |
| TC-OPS-03 | Immediate update path | NG-055 | Internal app sharing build with priority 5 over an installed older build | Immediate update flow appears | E2E | P2 |
| TC-OPS-04 | Backend deploy and rollback | doc 12 Section 7 | CI `schema-compat` job runs the previous release's tests on the new schema; on staging, deploy, then deploy an image that fails its health check, then `neogold-deploy --rollback` | CI job green; the failing image is rolled back automatically with smoke passing; `--rollback` returns to the previous digest and `.env` matches | OPS | P1 |
| TC-OPS-05 | Restore drill | Gate S8 | After a device revocation and a participant withdrawal, restore staging from an earlier backup following doc 12 Section 8.2 | Both admin actions re-applied before Caddy restarts; the revoked device is refused; the withdrawn participant's data is gone; phones refill (TC-SYNC-14) | OPS | P1 |
| TC-OPS-06 | Halt drill | doc 12 Section 6 | Walk through the halt and roll-forward procedure on a staged update to a test app | Steps complete within the times in doc 12 | M | P2 |
| TC-OPS-07 | Training pipeline reproducible | NFR-11, NG-032 | Fresh clone; follow the documented steps in the pinned Docker image on the public data | `model.json` matches the released model (coefficients equal within 1e-9) | OPS | P2 |
| TC-OPS-08 | Licenses and repository | NG-037 | CI license check; open the licenses screen | MIT for code and CC BY 4.0 for card and docs present; every third-party license in doc 8 Section 13.4 listed; unknown licenses fail CI | OPS | P2 |
| TC-OPS-09 | Fork drill | NG-038 | Someone other than the founder follows the guide to add a test language and retrain on public data | Both done from the guide alone; gaps in the guide logged as defects | M | P3 |
| TC-OPS-10 | Update priority survives promotion | NG-055 | Upload a test build to internal with priority 5; promote it to closed testing; check `updatePriority()` on a closed-track phone | Priority 5 seen; if not, the runbook switches emergency releases to direct upload (doc 12 Section 6.2) | E2E | P2 |
| TC-OPS-11 | First-time server setup | doc 12 Section 7.2 | Fresh droplet: follow Section 7.2 through the first deploy | Bootstrap creates the database and roles; first deploy skips the backup and passes smoke; Caddy starts with all capabilities dropped | OPS | P1 |

---

## 20. Hazard Verification Map

| Hazard (doc 8 Section 14) | Verified by |
|---|---|
| H1 False reassurance | TC-TRI-07 to TC-TRI-14, TC-TRI-23 to TC-TRI-25, TC-EST-05, TC-REL-01 |
| H2 Wrong age in hours | TC-TRI-14, TC-UI-10, TC-REL-03 to TC-REL-06 |
| H3 Wrong gestational age | TC-TRI-03, TC-TRI-16, TC-TRI-17 |
| H4 Counterfeit or faded card | TC-CARD-02 to TC-CARD-08, TC-CARD-10 |
| H5 Camera processing | TC-DEV-02 to TC-DEV-05, TC-CAP-04 |
| H6 Light | TC-CAP-03, TC-CAP-08, TC-USE-04 |
| H7 Wrong or corrupted config or model | TC-EST-01, TC-EST-06, TC-EST-07, TC-TRI-18, TC-TRI-19, TC-TRI-24, TC-TRI-26, TC-SEC-09 |
| H8 Outdated build | TC-TRI-20 |
| H9 Modified copy | TC-SEC-09 |
| H10 Missed recheck | TC-LOG-08, TC-REL-07 |
| H11 Result misread | TC-UI-01, TC-UI-04, TC-UI-05, TC-UI-08, TC-A11Y-05, TC-USE-01, TC-USE-02 |

---

## 21. Traceability Matrix

Generated from the tables above by `tools/trace.py`; do not edit by hand.

| Requirement | Test cases |
|---|---|
| FR-01 | TC-ONB-01, TC-ONB-04 |
| FR-02 | TC-ONB-02, TC-ONB-03 |
| FR-03 | TC-DEV-01, TC-DEV-09 |
| FR-04 | TC-DEV-02, TC-DEV-03, TC-DEV-04, TC-DEV-05 |
| FR-04b | TC-CARD-01, TC-CARD-02, TC-CARD-03, TC-CARD-05, TC-CARD-09 |
| FR-05 | TC-REL-06 |
| FR-06 | TC-UI-11 |
| FR-07 | TC-UI-12 |
| FR-08 | TC-TRI-01, TC-TRI-02, TC-TRI-03, TC-TRI-04, TC-TRI-05, TC-TRI-06 |
| FR-09 | TC-TRI-17, TC-TRI-18, TC-TRI-21, TC-TRI-26, TC-USE-03 |
| FR-10 | TC-CAP-01 |
| FR-11 | TC-DEV-06, TC-CAP-15 |
| FR-12 | TC-CAP-17 |
| FR-13 | TC-CAP-02, TC-CAP-03, TC-CAP-04, TC-CAP-05, TC-CAP-06, TC-CAP-07, TC-CAP-08, TC-CAP-09, TC-CAP-11, TC-CAP-12 |
| FR-14 | TC-CAP-13, TC-CAP-14, TC-TRI-15 |
| FR-15 | TC-CAP-16 |
| FR-16 | TC-CAP-09, TC-EST-01, TC-EST-02 |
| FR-17 | TC-CAP-10, TC-EST-03 |
| FR-18 | TC-EST-01, TC-EST-04 |
| FR-19 | TC-EST-05, TC-TRI-11 |
| FR-20 | TC-TRI-07, TC-TRI-08, TC-TRI-09, TC-TRI-10, TC-TRI-11, TC-TRI-12, TC-TRI-13, TC-TRI-14, TC-TRI-16 |
| FR-21 | TC-UI-01, TC-UI-14 |
| FR-22 | TC-UI-03 |
| FR-23 | TC-LOG-01, TC-LOG-02, TC-LOG-03, TC-LOG-04 |
| FR-24 | TC-LOG-07 |
| FR-25 | TC-RSH-01, TC-RSH-02, TC-RSH-03, TC-RSH-04, TC-RSH-05, TC-RSH-07, TC-API-07 |
| FR-26 | TC-RSH-08, TC-RSH-09 |
| FR-27 | TC-RSH-10 |
| FR-28 | TC-RSH-11, TC-RSH-12, TC-API-24 |
| FR-29 | TC-RSH-13, TC-SYNC-20 |
| FR-30 | TC-EXP-01, TC-EXP-02, TC-EXP-04 |
| FR-31 | TC-SYNC-01, TC-SYNC-02, TC-SYNC-03, TC-SYNC-04, TC-SYNC-05, TC-SYNC-06, TC-SYNC-07, TC-SYNC-08, TC-SYNC-09, TC-SYNC-12, TC-SYNC-13, TC-SYNC-14, TC-SYNC-15, TC-SYNC-16, TC-SYNC-17, TC-SYNC-18, TC-SYNC-19 |
| FR-32 | TC-ONB-05 |
| FR-33 | TC-LOG-10, TC-LOG-11 |
| FR-34 | TC-PRIV-04, TC-PRIV-05 |
| FR-35 | TC-PRIV-01 |
| FR-36 | TC-LOG-08 |
| FR-37 | TC-UI-13 |
| NFR-01 | TC-EST-04, TC-PRIV-04, TC-REL-02 |
| NFR-02 | TC-PERF-01, TC-PERF-05, TC-USE-01 |
| NFR-03 | TC-EST-07, TC-PERF-02 |
| NFR-04 | TC-PERF-04 |
| NFR-04b | TC-PERF-02, TC-PERF-03 |
| NFR-05 | TC-PERF-06 |
| NFR-06 | TC-ONB-04, TC-A11Y-01, TC-A11Y-02, TC-A11Y-03 |
| NFR-07 | TC-UI-01, TC-UI-05, TC-UI-06, TC-UI-07, TC-UI-08, TC-A11Y-04, TC-A11Y-05 |
| NFR-08 | TC-LOG-06, TC-LOG-07, TC-EXP-02, TC-SEC-08 |
| NFR-09 | TC-RSH-14, TC-RSH-15, TC-SYNC-13, TC-SYNC-14, TC-PERF-07, TC-REL-01, TC-REL-08 |
| NFR-10 | TC-DEV-08, TC-CAP-15, TC-EST-07, TC-TRI-22, TC-LOG-12 |
| NFR-11 | TC-OPS-07 |
| NFR-12 | TC-DEV-09, TC-LOG-08, TC-SYNC-10, TC-SYNC-11, TC-SYNC-12, TC-REL-07 |
| NFR-13 | TC-LOG-09, TC-PRIV-03 |
| NG-001 | TC-OPS-01 |
| NG-002 | TC-ONB-01 |
| NG-003 | TC-ONB-02 |
| NG-004 | TC-DEV-01 |
| NG-005 | TC-A11Y-04 |
| NG-006 | TC-CARD-10 |
| NG-007 | TC-DEV-02 |
| NG-008 | TC-CAP-01 |
| NG-009 | TC-CAP-15 |
| NG-010 | TC-CAP-17 |
| NG-011 | TC-CAP-02 |
| NG-012 | TC-CAP-13 |
| NG-013 | TC-EST-02 |
| NG-014 | TC-CAP-10 |
| NG-015 | TC-EST-04 |
| NG-016 | TC-EST-05 |
| NG-017 | TC-UI-11 |
| NG-018 | TC-UI-12 |
| NG-019 | TC-TRI-01, TC-TRI-12 |
| NG-020 | TC-TRI-17 |
| NG-021 | TC-UI-01 |
| NG-022 | TC-UI-02 |
| NG-023 | TC-LOG-01 |
| NG-024 | TC-LOG-07 |
| NG-025 | TC-TRI-22 |
| NG-026 | TC-LOG-10 |
| NG-027 | TC-ONB-05 |
| NG-028 | TC-RSH-01 |
| NG-029 | TC-RSH-08 |
| NG-030 | TC-RSH-10 |
| NG-031 | TC-RSH-11 |
| NG-032 | TC-OPS-07 |
| NG-033 | TC-ONB-05 |
| NG-034 | TC-RSH-13 |
| NG-035 | TC-RSH-14 |
| NG-036 | TC-EXP-01 |
| NG-037 | TC-OPS-08 |
| NG-038 | TC-OPS-09 |
| NG-039 | TC-CARD-01 |
| NG-040 | TC-CAP-16 |
| NG-041 | TC-RSH-16 |
| NG-042 | TC-SYNC-01, TC-API-01, TC-API-02, TC-API-03, TC-API-12, TC-API-13, TC-API-14, TC-API-16, TC-API-17, TC-API-18, TC-API-19, TC-API-22, TC-API-23, TC-API-30 |
| NG-043 | TC-CARD-08 |
| NG-044 | TC-DEV-07 |
| NG-045 | TC-CARD-10 |
| NG-046 | TC-DEV-10 |
| NG-047 | TC-UI-09 |
| NG-048 | TC-LOG-09 |
| NG-049 | TC-PRIV-05 |
| NG-051 | TC-CAP-08 |
| NG-053 | TC-TRI-21, TC-USE-03, TC-OPS-01 |
| NG-054 | TC-PRIV-01 |
| NG-055 | TC-ONB-06, TC-OPS-03, TC-OPS-10 |
| NG-056 | TC-UI-13 |
| NG-057 | TC-PRIV-02, TC-PRIV-03 |
| H1 | TC-EST-05, TC-TRI-07, TC-TRI-08, TC-TRI-09, TC-TRI-10, TC-TRI-11, TC-TRI-23, TC-TRI-25, TC-TRI-24, TC-REL-01 |
| H2 | TC-TRI-14, TC-UI-10, TC-REL-03, TC-REL-04, TC-REL-05, TC-REL-06 |
| H3 | TC-TRI-03, TC-TRI-16, TC-TRI-17, TC-UI-11 |
| H4 | TC-CARD-02, TC-CARD-03, TC-CARD-05, TC-CARD-06, TC-CARD-07, TC-CARD-08, TC-CARD-10 |
| H5 | TC-DEV-02, TC-DEV-03, TC-DEV-04, TC-DEV-05, TC-CAP-04 |
| H6 | TC-CAP-03, TC-CAP-08, TC-USE-04 |
| H7 | TC-EST-01, TC-EST-06, TC-EST-07, TC-TRI-18, TC-TRI-19, TC-TRI-26, TC-TRI-24, TC-SEC-09 |
| H8 | TC-TRI-20 |
| H9 | TC-SEC-09 |
| H10 | TC-LOG-08, TC-REL-07 |
| H11 | TC-UI-01, TC-UI-04, TC-UI-05, TC-UI-08, TC-UI-14, TC-A11Y-05, TC-USE-01, TC-USE-02 |
| T1 | TC-RSH-05, TC-RSH-06, TC-SYNC-16, TC-SYNC-20, TC-SEC-07, TC-SEC-08 |
| T2 | TC-PRIV-01, TC-PRIV-02 |
| T3 | TC-SEC-09 |
| T4 | TC-CARD-02, TC-CARD-07 |
| T5 | TC-CARD-04 |
| T6 | TC-CARD-04, TC-RSH-02 |
| T7 | TC-SEC-02, TC-SEC-12 |
| T8 | TC-API-02, TC-API-04, TC-API-05, TC-API-06, TC-API-33 |
| T9 | TC-API-08, TC-API-10, TC-API-11 |
| T10 | TC-SEC-11 |
| T11 | TC-API-27, TC-API-28 |
| T12 | TC-SEC-05 |
| T13 | TC-API-09, TC-API-25, TC-API-33 |
| T14 | TC-API-21 |
| T15 | TC-LOG-03, TC-RSH-09, TC-EXP-02, TC-EXP-03, TC-API-15, TC-API-20, TC-API-26, TC-API-27, TC-API-29, TC-API-31, TC-API-32 |
| T16 | TC-LOG-12 |
| T17 | TC-PRIV-03, TC-SEC-03 |
| Gate S1 | TC-TRI-18, TC-SEC-10, TC-SEC-14, TC-OPS-01 |
| Gate S2 | TC-SEC-05 |
| Gate S3 | TC-SEC-05 |
| Gate S4 | TC-SEC-04 |
| Gate S5 | TC-SEC-01 |
| Gate S6 | TC-SEC-02 |
| Gate S7 | TC-API-08 |
| Gate S8 | TC-SEC-13, TC-OPS-05 |
| Gate S9 | TC-SEC-06 |
