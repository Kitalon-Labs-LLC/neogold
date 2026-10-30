# NeoGold: Clinical Safety Hazard Log

| Field | Value |
|---|---|
| Document | Hazard log, structured after ISO 14971 (doc 8 Section 14 is the seed) |
| Version | 0.1.0-draft (31 October 2026) |
| Status | **Seeded by engineering. Severity, likelihood and acceptance are the clinical lead's to rate; none is rated here.** |
| Reviewed | With every clinical sign-off (`CLINICAL_SIGNOFF.md`) and before any build sets `triageEnabled: true` |
| Intended use | Decision support for trained health workers. The app suggests a next step; the health worker decides. It does not diagnose (PRD D11). |

**State of the build.** v1.0 is a closed research build. The shipped config has `triageEnabled: false`, so no screening result is shown to a worker, and nothing in this log has run on a phone. "Built" below means the control is in the code and a test on a computer proves it; "On a phone" means a check that needs a real device and has not been done. The shipped model (`1.0.0-synthetic`) was trained on bench phantoms and has no clinical meaning (`MODEL_CARD.md`).

How to read the status column: **Built and tested** (code and JVM tests), **Built, not tested on a phone**, **Partly built**, **Not built**, **Process** (not software). The "Open" column lists what is known to be missing; each item points to the bug log (doc 11).

---

## H1. False reassurance

| | |
|---|---|
| Cause | The estimate is too low: skin tone, lighting, camera, a card that does not match the stored batch, a second photo that is not the baby's forehead |
| Harm | Treatment missed; risk of brain injury from bilirubin |
| Controls built | Hard rules R1 to R5 decided by the triage engine before the camera opens (danger signs, jaundice in the first 24 hours, under 35 weeks, prior phototherapy, over 14 days). Integer-tenth comparison rounding toward more care; one reason code. Upper-bound zones and the wide-interval rule. Three refused photos, a timeout of 10 seconds, or a failed analysis give a blood test, decided by the engine. Danger signs are decided at once, without the later questions. "Always confirm with a blood test" and the experimental label on every result. `triageEnabled: false` in the shipped config; the signed `CLINICAL_SIGNOFF.md` gate (G1). A forehead photo can only raise the level of care: if the decision from both sites is less careful than the one from the chest photo, the chest result is kept (BUG-2026-090). |
| Status | Built and tested (JVM): `TriageEngine` tests, `triageDifferentialTest`, `ScreeningFlowRunTest`, `ScreeningForeheadTest`. Built, not tested on a phone: the camera path and the whole flow. |
| Open | The card in the photo is not matched to the stored batch (BUG-2026-083 item 15, 36 (a)). No check that a forehead photo is a forehead or the same baby (item 36 (j)). A two-site result is not validated (item 36 (i)). The model is synthetic; no validation study has run. Wording of `mother_card_blood_test` and `mother_card_refer_urgent` (item 36 (b)). Danger signs when the config expires mid-session (item 36 (c)). Display rounds half up while the engine rounds toward more care (item 36 (e)). |
| Verified by | Validation study (not started); triage boundary tests |
| Rating | _clinical lead_ |

## H2. Wrong age in hours

| | |
|---|---|
| Cause | Birth time entered wrong, or the phone clock is wrong (age is computed from the phone clock) |
| Harm | Wrong threshold |
| Controls built | No pre-filled birth time; Next stays off until the worker sets birth time and gestational age. "Age now: N hours" is shown. Screening is blocked when the clock is earlier than the app's build time or the birth time is in the future (`ClockIntegrityValidator`). A baby over 14 days goes to a doctor's review (R5). Age is stored as entered, never clamped. |
| Status | Built and tested (JVM). Not tested on a phone. |
| Open | The clock check against the last server offset in research mode, and the warning when automatic date and time is off, are not in the screening flow (BUG-2026-083 item 6, deferred). The first tap on the gestational-age stepper still starts at 38 (item 5). |
| Verified by | Usability test with nurses (not done); clock-manipulation tests |
| Rating | _clinical lead_ |

## H3. Wrong gestational age

| | |
|---|---|
| Cause | Not known, or entered wrong |
| Harm | Wrong threshold band |
| Controls built | Required field with no default. Under 35 weeks goes to a blood test (R3). |
| Status | Built and tested (JVM). |
| Open | How an unknown gestational age is handled is the clinical lead's decision (doc 8 H3). Stepper first-tap value (item 5). |
| Verified by | Clinical review |
| Rating | _clinical lead_ |

## H4. Counterfeit, faded or dirty card

| | |
|---|---|
| Cause | Copied QR, sun damage, handling |
| Harm | Biased estimate |
| Controls built | Card signature verified against the trust list; expired and revoked batches blocked; the quality gate's patch-residual and uniformity checks. |
| Status | Built and tested (JVM and an emulator test of the ArUco detector). Not tested with printed cards. |
| Open | The card integrity check against a stored profile (NG-043) is not built. The photographed card is not matched to the scanned batch (item 15). Counterfeit and aged-card tests (v0.5 plan) not run. |
| Verified by | Deliberate counterfeit and aged-card tests |
| Rating | _clinical lead_ |

## H5. Camera processing distorts colours

| | |
|---|---|
| Cause | Local tone mapping, HDR, beauty filters |
| Harm | Biased estimate |
| Controls built | Device qualification at onboarding (repeatability, uniformity, colour difference); a phone that is not `QUALIFIED`, has no profile, or is due again for qualification cannot screen (`QualificationService.screeningEligible`, wired to the Home screen); the qualification is repeated after 30 days or an OS build change. |
| Status | Built and tested (JVM). Not tested on the device matrix. |
| Open | Device matrix runs D1 to D7 (owner). |
| Verified by | Device matrix (doc 1 Section 13.5) |
| Rating | _clinical lead_ |

## H6. Light that defeats correction

| | |
|---|---|
| Cause | Mixed window and tube light, coloured light |
| Harm | Biased estimate |
| Controls built | Light-uniformity and residual checks in the quality gate; retake guidance names glare, uneven light, under- and over-exposure. |
| Status | Built and tested on synthetic cards. |
| Open | Ward lighting tests (owner). |
| Verified by | Ward lighting tests |
| Rating | _clinical lead_ |

## H7. Wrong or corrupted config or model

| | |
|---|---|
| Cause | Build error, tampering on the phone |
| Harm | Wrong output for every baby |
| Controls built | Signed bundle manifest verified at start (config, model, trust list read once and parsed from the verified bytes); a changed asset is refused and screening and research stay off; schema check and golden vectors that compare with committed files; the clinical sign-off hash check in CI (G1). |
| Status | Built and tested (JVM and an emulator test of the real verifier). |
| Open | The bundled copies of the config and trust list are not covered by the edit-time hook (BUG-2026-046 note). |
| Verified by | CI; start-up check test |
| Rating | _clinical lead_ |

## H8. Outdated build keeps running

| | |
|---|---|
| Cause | No updates in the field |
| Harm | Thresholds or model known to be wrong stay in use |
| Controls built | `validUntil` (12 months at most) disables triage ("use a blood test"). |
| Status | Built and tested (JVM). |
| Open | In-app update prompt (Play in-app updates, BUG-2026-028, package F2). A config that expires in the middle of a session gives only "use a blood test", even to a baby with a danger sign (item 36 (c)). |
| Verified by | Expiry test |
| Rating | _clinical lead_ |

## H9. Modified copy of the app

| | |
|---|---|
| Cause | Repackaged or forked build passed around |
| Harm | Unknown behaviour under the NeoGold name |
| Controls built | Signed release; a release key is required for a release build; the published fingerprint; no analytics SDK. |
| Status | Process (T3 controls). |
| Open | Periodic search for copies; trademark decision (doc 8 Section 15). |
| Verified by | Periodic search for copies |
| Rating | _clinical lead_ |

## H10. Missed recheck

| | |
|---|---|
| Cause | Notification blocked by the battery manager |
| Harm | Delayed detection |
| Controls built | The Home due list shows rechecks that are due (FR-36); the recheck is counted from the moment the decision was shown, with the date; a battery guidance screen; the notification carries no baby details. |
| Status | Built and tested (JVM). Not tested on the target phones. |
| Open | The log shows a fixed 24 hours for a recheck and lumps a doctor's review with "cannot screen" (item 36 (d)). Field test on target phones (owner). |
| Verified by | Field test on target phones |
| Rating | _clinical lead_ |

## H11. Result misread

| | |
|---|---|
| Cause | Low literacy, colour vision, small screens |
| Harm | Wrong action taken |
| Controls built | Colour, icon, dashed outline and words together on the result card; the doc 6 Section 3 wording for each action; the banned-word lint over `strings.xml` and over every Kotlin UI literal; every result labelled as a suggested next step. |
| Status | Built and tested (JVM). Bangla wording is a draft (`docs/BANGLA_STRINGS_REVIEW.md`). |
| Open | Nurse panel sign-off of the Bangla wording; usability test (TC-USE-01 to 05); the 320 dp and 200% font-scale layout check on a phone; bundling the Noto fonts (BUG-2026-091 notes). |
| Verified by | Usability test |
| Rating | _clinical lead_ |

---

## Sign-off

| Role | Name | Confirmation | Date |
|---|---|---|---|
| Clinical lead | | | |
| Engineering | | | |
