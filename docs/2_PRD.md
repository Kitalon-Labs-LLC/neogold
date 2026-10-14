# NeoGold: Product Requirements Document

| Field | Value |
|---|---|
| Document | 2 of 13 |
| Version | 0.4 (low-end device targets, retention, register number, hosting decided; 0.3.1 corrects the data protection law citations; 0.4 records D10 to D12: closed research release, decision-support intended use, guardian consent) |
| Date | 10 October 2026 |
| Owner | Sowad Al-Mughni, Kitalon Labs |
| Platform | Android, native Kotlin with Jetpack Compose |
| License | MIT (code), CC BY 4.0 (card design and documentation) |
| Status | Approved for Phase 2. See Section 16 for the decision log |
| Depends on | `1_Market_Competitor_Analysis.md` |

---

## 1. Overview

**One line:** NeoGold is a free, open-source Android app that estimates neonatal jaundice risk from a calibrated phone photo, fully offline, so health workers without a bilirubin meter know which newborns need a blood test.

**Intended use (decided 11 October 2026):** NeoGold is decision support for trained health workers. It suggests a next step; the health worker decides, using their own clinical judgment. It does not diagnose and is not intended to be a medical device. Every screen, store text, and document uses this framing (D11).

**Vision:** No newborn in a low-resource setting suffers brain damage from jaundice because nobody could measure it.

**What success looks like in one sentence:** a nurse in an Upazila Health Complex with no transcutaneous bilirubinometer uses NeoGold, gets a "refer for blood test" result in under a minute, and the baby gets phototherapy in time.

---

## 2. Problem Statements

**P1. The measurement gap at first contact.**
Health workers in district and community settings decide whether a jaundiced newborn needs a blood test by looking at the skin. Visual assessment is unreliable, and worse on darker skin. The device that measures it properly costs thousands of dollars and is rarely available outside larger hospitals.

**P2. Late arrival.**
In Bangladeshi tertiary hospital data, about a third of newborns needing exchange transfusion already showed signs of kernicterus on arrival. Severe cases are being detected too late, after the window where phototherapy alone would have worked.

**P3. Existing smartphone tools do not reach this population.**
Validated apps exist, but they are commercial, cloud-dependent, restricted by country, or validated mostly on lighter skin. None is open, offline, and built for South Asian newborns.

---

## 3. Goals and Non-Goals

### Goals (v1.0)
- G1. Estimate jaundice risk from a calibrated photo and return a clear triage action in under 60 seconds end to end.
- G2. Work with zero network connectivity for every screening function.
- G3. Run on low-cost Android phones common among Bangladeshi health workers.
- G4. Provide a research mode that captures clean, consented, paired photo and blood bilirubin data for partner validation studies.
- G5. Publish code, model weights, card design, and methodology openly.

### Non-goals (v1.0)
- NG1. Diagnosis or treatment decisions. NeoGold supports a referral decision only.
- NG2. Parent or caregiver home use.
- NG3. iOS.
- NG4. Monitoring babies who have already started phototherapy (accuracy drops after phototherapy in published studies).
- NG4b. Screening babies born before 35 weeks gestation (major validation studies such as BiliCam enrolled 35 weeks and above; these babies go straight to a blood test).
- NG5. Electronic health record integration, patient identity management, or cloud storage of screening results.
- NG6. Monetization of any kind.

---

## 4. Product Principles

1. **Screening, never diagnosis.** The app never says a baby is fine. The lowest result is "no further action from this screen; continue routine care and watch for danger signs."
2. **When in doubt, refer.** Every uncertainty (bad image, borderline value, unsupported device, missing input) resolves toward a blood test, not away from one.
3. **Offline by default.** If a feature needs the internet, it is not a screening feature.
4. **Rules beat model.** Clinical red flags (jaundice in the first 24 hours, danger signs) trigger urgent referral before the model runs.
5. **Privacy by design.** Screening mode does not keep images unless the user is in a consented research session.
6. **Honest about evidence.** The app shows which model version and validation population it was built on.

---

## 5. Personas

### Persona 1 (primary): Rokeya, Senior Staff Nurse
- Upazila Health Complex, rural Rangpur division
- 15 years in maternal and newborn care; sees 10 to 20 newborns a day during discharge checks
- Phone: entry-level Android, 3 GB RAM, shared ward charger
- Connectivity: patchy mobile data, no Wi-Fi in the ward
- Language: Bangla first; reads simple English medical terms
- **Need:** a fast, defensible reason to send a baby for a blood test or to the district hospital
- **Frustration:** "The doctors ask me why I referred. I can only say the baby looked yellow."
- **Success for her:** a result she can write in the register and show the doctor

### Persona 2: Shirin, Community Health Worker
- Postnatal home visits in villages; community skilled birth attendant training [Guessing on exact cadre title; confirm with partner]
- Phone: older budget Android (2 to 3 GB RAM), often shared with family, often low battery, no data balance
- **Need:** decide on the spot whether a family must travel to the health complex today
- **Frustration:** families delay travel because of cost; she needs confidence to insist
- **Success for her:** a clear "go today" or "recheck tomorrow" she can explain to the mother in simple Bangla

### Persona 3: Dr. Farhan, Neonatology Registrar and Study Lead
- Tertiary hospital in Dhaka; running the validation study with ethics approval
- **Need:** collect photos paired with same-hour TSB values, with consent, without slowing the ward
- **Frustration:** research apps that lose data, crash, or need Wi-Fi
- **Success for him:** a clean, de-identified CSV plus image export that drops straight into analysis

### Persona 4: Daniel, NGO Digital Health Lead
- Maternal and child health NGO in Kenya
- **Need:** fork NeoGold, translate it to Swahili, run a local validation, deploy
- **Success for him:** clear docs, a permissive license, a localization file, and a retraining guide

### Anti-persona: the anxious parent at home
NeoGold v1 is not built for parents. Unsupervised home use raises the risk of false reassurance. Picterus and BiliSG already target this use under regulatory oversight.

---

## 6. Key User Journeys

### J1. First launch and setup (Rokeya, one time)
1. Opens app; chooses Bangla or English.
2. Reads a three-screen safety notice: what NeoGold does, what it does not do, always confirm with a blood test. Must tap "I understand."
3. App runs a device check: memory, camera controls, and brand-specific battery settings guidance (TECNO, Infinix, itel, Xiaomi). Camera qualification itself happens in step 4.
4. Card and phone calibration: scans the QR code on the NeoGold card (app verifies the batch signature offline and loads that batch's measured colors), then photographs the card three times on a plain surface under ward lighting. The app checks that repeated photos agree, that identical patches in different corners of the card come out the same (which catches HDR and beauty processing), and that the corrected colors match the batch. A phone that passes is qualified for screening and a device color profile is stored; a phone that fails is limited to research mode with a plain explanation. A card without a valid batch QR is rejected for screening.
5. Lands on home screen with one primary button: "New screening."

### J2. Screening a newborn (Rokeya, core flow, target under 60 seconds)
1. Taps "New screening."
1a. Confirms that the baby's parent or guardian has agreed to the screening (D12). If not, the screen ends with no photo taken.
2. Enters date and time of birth (app computes age in hours), gestational age in weeks, and answers three yes/no safety questions:
   - Did yellow color appear in the first 24 hours of life?
   - Any danger signs? (poor feeding, unusual sleepiness, high-pitched cry, arching of the back)
   - Has the baby already had phototherapy?
3. **Rule check before camera:** jaundice under 24 hours or any danger sign shows "Refer urgently now" immediately. Prior phototherapy or gestational age under 35 weeks shows "NeoGold cannot screen this baby; use a blood test." Age over 14 days shows "Jaundice after two weeks needs a doctor's review."
4. Places card on the baby's chest (sternum). Camera opens with an on-screen guide.
5. App locks exposure and white balance, captures image, runs a quality gate (card found, all patches visible, no glare, no blur, enough light).
6. If quality fails, the app explains why in plain words ("Too much glare. Move away from the window.") and asks for a retake. After three failures: "Refer for blood test."
7. Optional second capture (forehead) for multi-site estimate.
8. On-device analysis returns in under 10 seconds.
9. **Result screen:** a suggested next step for the health worker to act on, one of four actions, color plus icon plus text (never color alone):
   - **Refer urgently now**
   - **Blood test today**
   - **Screen again in [N] hours**
   - **No further action from this screen. Continue routine care and watch for danger signs.**
   Plus estimated bilirubin range (not a single number), model version, and "Always confirm with a blood test."
10. Taps "Save to log" (no name required; optional register number).

### J3. Home visit, offline (Shirin)
Same as J2 with no connectivity at any step. Result screen adds a "What to tell the mother" card with simple Bangla script and an audio playback option (Could-have).

### J4. Research capture (Dr. Farhan)
1. Unlocks research mode with a study code and PIN.
2. Records consent confirmation (paper consent form ID; app does not replace written consent).
3. Captures images following the study protocol (sternum, forehead, optional sclera with flash and no-flash pair).
4. **Estimate is hidden (blinded)** so the app does not influence clinical care during the study.
5. Enters TSB value and time of blood draw (same session or later via participant code).
6. Data stored encrypted on device; exported as de-identified CSV plus images to a USB drive or, if configured, uploaded to the study server when Wi-Fi is available.

### J5. Fork and deploy (Daniel)
1. Finds NeoGold on GitHub; reads README, model card, and retraining guide.
2. Adds a translation file; produces cards through the published Card Production Kit (print spec, batch measurement protocol, QR signing tool) and adds his organization's signing key to the build's trust list.
3. Runs the research mode in his own study; retrains with the published pipeline.
4. Publishes a localized build.

---

## 7. Functional Requirements

### 7.1 Onboarding and safety
- FR-01 Language selection (Bangla, English) at first launch and in settings.
- FR-02 Mandatory safety notice with explicit acknowledgment; re-shown after each major model update.
- FR-03 Device check covering memory, available camera controls (used when present, never required), and brand-specific battery setting guidance. Screening eligibility is decided by the empirical camera qualification in FR-04, not by a spec sheet.
- FR-04 Per-device camera qualification and color calibration using the NeoGold card: repeatability across three photos, uniformity of duplicate patches across the card, and corrected-color accuracy. Pass qualifies the phone for screening; fail limits it to research mode. Re-run every 30 days or after an OS or camera update.
- FR-04b Card batch verification: read the card's QR code, verify its batch signature offline against a trust list, load the batch reference colors, and check the batch expiry date. Unverified or expired cards are blocked from screening and allowed only in research mode with a warning.

### 7.2 Screening inputs and rules
- FR-05 Capture date and time of birth; compute age in hours.
- FR-06 Capture gestational age in completed weeks.
- FR-07 Safety questions: jaundice onset under 24 hours, danger signs, prior phototherapy.
- FR-37 Parent or guardian consent: before the camera opens, the health worker confirms that a parent or guardian agreed to the screening. The confirmation is stored on the session; no name is recorded. Without consent the screen ends with no photo and advises clinical judgment or a blood test.
- FR-08 Rule engine runs before the camera and can short-circuit to urgent referral (jaundice under 24 hours, danger signs) or "cannot screen" (prior phototherapy, gestational age under 35 weeks, age over 14 days, since jaundice beyond two weeks needs a doctor's review for other causes). Rule values are drafts until the clinical lead signs them.
- FR-09 Rule thresholds and triage bands stored in a versioned, reviewable configuration file, signed off by a clinical advisor, not hardcoded.

### 7.3 Capture and quality
- FR-10 Guided capture with on-screen card placement overlay.
- FR-11 Lock auto-exposure and auto-white-balance before capture where the camera supports it (never required; qualification in FR-04 decides eligibility); record actual capture metadata.
- FR-12 Card detection and patch extraction.
- FR-13 Quality gate: card present, patches readable, glare, blur, under or over exposure, skin region size.
- FR-14 Plain-language retake guidance; automatic "blood test" recommendation after three failed attempts.
- FR-15 Optional multi-site capture (sternum plus forehead).

### 7.4 Analysis
- FR-16 Color correction using card reference patches and device profile.
- FR-17 Skin region extraction from the card window, with a check that the window contains skin and not cloth, shadow, or a finger.
- FR-18 Feature extraction and bilirubin estimation on device; no network call.
- FR-19 Output an estimated range with an uncertainty band; widen toward referral when uncertainty is high.
- FR-20 Map estimate plus age in hours plus gestational age to a triage action using the configuration from FR-09.

### 7.5 Results and log
- FR-21 Result screen with four triage actions, each with color, icon, and text, presented as a suggested next step with the line "You decide, using your clinical judgment" (D11).
- FR-22 Display model version, validation population summary, and the blood test reminder on every result.
- FR-23 Local screening log (no name required), optional facility register number, filterable by date. The register number field rejects patterns that look like national identifiers or phone numbers (Section 11).
- FR-24 Screening mode discards images after analysis by default.

### 7.6 Research mode
- FR-25 PIN-protected research mode tied to a signed study profile, scanned from the study's enrollment QR (doc 7 Section 4.1).
- FR-26 Consent confirmation field (paper consent ID) required before capture.
- FR-27 Blinded mode: estimate hidden from user during study capture.
- FR-28 Paired TSB entry with draw time; allow later entry by participant code.
- FR-29 Encrypted on-device storage of images and data.
- FR-30 De-identified export (CSV plus images) to local storage or USB, encrypted to the study's export public key so a lost USB drive exposes nothing (doc 8 Section 5.2).
- FR-31 Optional upload to a study server when Wi-Fi is available (see Section 10).

### 7.7 Settings and transparency
- FR-32 About screen: model card summary, data sources, limitations, license, version.
- FR-33 Export and delete all local data.
- FR-34 No analytics or crash reporting unless the user opts in.
- FR-35 Optional app lock using the phone's screen lock (PIN, pattern, or fingerprint) for shared phones.
- FR-36 Rechecks due are listed on the Home screen; notifications are secondary because budget Android skins often block them.

---

## 8. Non-Functional Requirements

| ID | Category | Requirement |
|---|---|---|
| NFR-01 | Offline | 100% of screening functions work in airplane mode |
| NFR-02 | Performance | Capture to result under 10 seconds on the reference class (entry MediaTek Helio G81/G85 class, 3 to 4 GB RAM); full journey J2 under 60 seconds |
| NFR-03 | Device support | Android 8.0 (API 26) and above, including Android Go phones with 2 GB RAM; both 64-bit and 32-bit ARM builds; tested on the device matrix in doc 1, Section 13.5 |
| NFR-04 | App size | Download under 25 MB, install under 40 MB including model |
| NFR-04b | Memory | Peak memory under 120 MB during analysis so 2 GB phones do not kill the app |
| NFR-05 | Battery | One screening uses under 1% battery on a typical device |
| NFR-06 | Localization | All user-facing strings in Bangla and English; translation file structure documented for forks |
| NFR-07 | Accessibility | Result never conveyed by color alone; minimum 4.5:1 contrast; large touch targets for use with gloves |
| NFR-08 | Privacy | No personal identifiers required; images not retained in screening mode; research data encrypted at rest |
| NFR-09 | Reliability | Research data never lost on crash or battery death; writes are atomic |
| NFR-10 | Reproducibility | Every result traceable to app version, model version, config version, and device model |
| NFR-11 | Openness | Training pipeline reproducible from the public repository with documented steps |
| NFR-12 | Background limits | Every time-sensitive function works even if the phone's battery manager kills background work (in-app due list, manual upload) |
| NFR-13 | Shared phones | No NeoGold data in Android cloud backup; optional app lock; register numbers never on lock-screen notifications |

---

## 9. ML Approach (v1)

Grounded in what has worked in published studies:

1. **Color calibration first.** Use the card's reference patches to correct each image to a standard color space. This matters more than model choice.
2. **Engineered color features plus a simple model.** Extract skin chromaticity features (yellow-blue and red-green channels in a perceptual color space) from the corrected skin region. Fit a regression model. BiliCam reached 0.91 correlation with this style of approach, and it needs far less data than a deep network.
3. **No neural network needed in v1.** The card's punched window defines the skin region, and corner markers locate the card, so classic image processing replaces a segmentation model. MediaPipe face and eye landmarks are reserved for the sclera mode in v2.
4. **Uncertainty is a first-class output.** The model returns a range. Wide ranges push toward referral.
5. **Phase 2 experiment: sclera.** neoSCB found sclera more accurate than skin and less affected by skin tone. Research mode captures sclera images from day one so this can be tested on Bangladeshi data without a second study.

Model performance claims are made only for the population and devices the model was validated on, and shown in the app.

---

## 9a. Card Strategy (decided 10 October 2026)

**Decision:** cards are centrally produced, batch-verified, and carry a signed QR code. Home printing is not supported.

**Why not home printing.** [Likely] Health workers in rural Bangladesh, India, and Nigeria rarely have access to a color printer. Consumer printers and local print shops do not reproduce color consistently from run to run, plain paper reflects light unevenly and causes glare [Certain, per Nixon et al. 2020], and unprotected prints fade. A card that is silently off by a few color units produces a wrong bilirubin estimate with no visible warning.

**Why "professionally printed" alone is not enough.** Even good print houses drift between runs. Picterus, the only CE-marked competitor, printed on a large-format photo printer with matte paper and still measured every card with a spectrophotometer after printing [Certain]. Measurement is the step that makes a card trustworthy, not the printer.

**The NeoGold process:**

| Step | What happens | Where |
|---|---|---|
| 1. Print | Color-managed pigment inkjet or calibrated digital press, matte stock, matte lamination (wipeable, no glare), skin window punched out | One print partner in Dhaka |
| 2. Measure | Sample cards from each batch measured with a reflectance spectrophotometer; batch rejected if samples disagree beyond tolerance | Textile testing lab, university color lab, or print house that owns a spectrophotometer [Likely available in Dhaka given the garment industry's color labs] |
| 2b. Interim measurement (grant phase) | Cards photographed beside a certified commercial color chart under a controlled light box; values transferred from the chart | Founder, using the reference chart already in the grant budget |
| 3. Encode | Batch ID, measured reference colors, expiry date, and a digital signature written into a QR code printed on each card | Card Production Kit signing tool |
| 4. Distribute | Through study partners and NGOs, in a sunlight-protective sleeve; replaced on expiry or when the in-app integrity check fails | Partner networks |

**Card design rules:**
- Patches concentrated in the skin and yellow range plus neutral grays. A targeted yellows card improved smartphone color accuracy by about 40% over a general chart in UCL's work [Certain].
- A punched window so the skin is measured right next to the patches, as Picterus and BiliCam do [Certain for Picterus].
- Four corner markers for detection and perspective correction.
- Matte finish only. Glossy lamination creates glare that fails the quality gate.

**Cost:** [Guessing] under US$1 per card at batches of a few hundred in Dhaka, excluding measurement. Get two print quotes before committing. Owning a spectrophotometer costs around CAD 1,937 for a Nix Spectro 2 [Certain], which is beyond the grant budget, so measurement is outsourced until a partner covers it.

**For other countries:** the Card Production Kit (open design files, print spec, measurement protocol, signing tool) lets an NGO in India or Nigeria run the same process locally and sign batches with its own key.

---

## 9b. Clinical Governance (decided 10 October 2026)

**Threshold basis.** The triage configuration uses the AAP 2022 hyperbilirubinemia guideline as its reference:
- AAP 2022 covers newborns at 35 weeks gestation and above [Certain], which matches NeoGold's scope rule.
- AAP 2022 recommends a blood test when a transcutaneous reading is within 3 mg/dL of the age-specific phototherapy threshold, or above 15 mg/dL [Certain]. NeoGold treats its estimate like a transcutaneous screen: if the upper end of the estimated range falls inside that zone, the action is "Blood test today."
- The Bangladeshi community screening trial in Sakhipur used AAP 2004 thresholds adjusted for gestational age and blood group incompatibility [Certain]. Which edition NeoGold follows is the clinical advisor's call, and it is written into the signed configuration.

**Who signs off:**

| Role | Signs off | Profile | First people to approach |
|---|---|---|---|
| Clinical lead | Triage thresholds, rule engine, referral wording, hazard log | Consultant neonatologist at a Bangladeshi tertiary neonatology department | Neonatology investigators on the Sakhipur trial protocol: Bangladesh Shishu Hospital & Institute and Bangladesh Medical University (formerly BSMMU) [Certain on 2024 published affiliations; verify current roles] |
| Frontline wording panel | Bangla wording, comprehension, workflow fit | 3 to 5 senior staff nurses or midwives from an Upazila Health Complex or the partner hospital | Through the clinical lead's network |
| Research and ethics partner | Study protocol, ethics approval, data governance, study server hosting | Institution with an ethics review committee and neonatal research record | icddr,b (ran the Sakhipur protocol with Stanford) [Certain] |
| Public health alignment (later) | Fit with national newborn programs | National Newborn Health Program and IMCI, DGHS | Only after Phase B validation |

**Why this team first.** [Likely] The Sakhipur investigators already run community jaundice screening in rural Bangladesh with an agreed threshold protocol and a CHW workflow, which is exactly the setting NeoGold targets. A phone-based screen that needs no transcutaneous meter is a natural extension of their work, not a competing idea.

**Sign-off is a release gate.** No build that shows a triage result to users ships until the clinical lead has signed the triage configuration and wording, and that sign-off is recorded in the repository (`CLINICAL_SIGNOFF.md`) alongside the hazard log. Without sign-off, v1.0 ships code and research mode with the triage result disabled.

---

## 10. Backend Scope (decided 10 October 2026)

**Decision:** a small containerized backend used only to receive consented research uploads, run per study under the partner's data governance. Confirmed by the founder.

- **Screening needs no backend.** No account, no login, no server call.
- **A backend is justified only for research data intake:** receiving encrypted, consented, de-identified study uploads from partner sites, and optionally hosting signed model updates.
- **Default deployment of the backend is per study,** run by or with the partner institution, so clinical data stays under the partner's ethics approval and data governance.

Accounts, dashboards, and cloud sync of screening results remain out of scope.

**Hosting (decided 10 October 2026):** the founder's DigitalOcean account hosts development and staging on a new, dedicated droplet with synthetic data only. Real study data, which includes newborn health data and images, is hosted inside Bangladesh by the partner or a Bangladeshi data center. Bangladesh's Personal Data Protection Ordinance 2025 (Ordinance No. 61 of 2025) treats health-related data as sensitive personal data (section 2(23)) and requires any personal data stored in a cloud, domestic or foreign, to keep at least one synchronized real-time copy inside Bangladesh (section 29(7)(b)) [Certain, ordinance text in unofficial English translation]. The government also sorts personal data into public, internal, confidential, and limited classes through a schedule (section 29(1)) [Certain]; which class study data falls into is a question for the lawyer (Section 16, open item 5). *Correction (v0.3.1):* an earlier version said the ordinance requires confidential data to be stored only in Bangladesh, citing law firm summaries; the ordinance text does not say that directly. The decision stands, because hosting abroad would still need an in-country copy plus cross-border transfer conditions. Most of the ordinance applies already; only section 23 and the offence and penalty sections 31 to 46 wait for a government notification after 18 months (section 1(3)) [Certain], so around May 2027 at the earliest [Likely]. DigitalOcean has no Bangladesh region [Certain]. The service runs the same Docker Compose stack in both places, so staging on DigitalOcean is a dress rehearsal for the partner deployment. Details in doc 4, Section 10.

---

## 11. Data, Privacy, and Consent

- Screening requires the parent's or guardian's agreement, confirmed by the health worker before the camera opens (D12, FR-37). Screening mode stores: timestamp, guardian consent confirmation, age in hours, gestational age, safety answers, estimate range, triage action, versions, device model, and an optional facility register number. No images, no names, no birth dates.
- **Retention (decided 10 October 2026):** screening records are kept **180 days** by default (facility can set 30 to 365). The register number is erased after **60 days**, leaving a de-identified record for supervision and incident review. Reasoning: rechecks happen within days; the neonatal period ends at 28 days; facility registers feed monthly reports and supervision runs quarterly, so 180 days covers two quarterly reviews and the time a missed case could surface; Bangladesh's Personal Data Protection Ordinance 2025 bars keeping personal data longer than the period its regulations set, with longer retention allowed for research (sections 18(1), 18(2)) [Certain]; until those regulations exist, keeping only what the purpose needs is the defensible reading [Likely]. Keeping register numbers for 6 months has no purpose that 60 days does not already serve.
- **Register number (decided 10 October 2026):** kept, optional, labeled "Facility register no." Its only purposes are linking a screen to the paper register for a recheck, a doctor's question, supervision, or an incident review. The field accepts letters, digits, "/" and "-", up to 20 characters, and rejects anything that looks like a 17-digit birth registration number, a 10, 13, or 17-digit national ID number, or an 11-digit mobile number starting with 01 [Likely formats; confirm with the partner]. Names are never accepted.
- Research mode stores: the above plus images, TSB value, draw time, participant code, consent form ID. Encrypted at rest. Mapping of participant code to identity stays on paper at the hospital, never in the app.
- No clinical image collection happens outside an ethics-approved study with a partner institution. Ethics approval is in hand before the first real newborn is photographed.
- Users can export or delete all local data at any time.

---

## 12. Success Metrics

### Phase A: Grant period (target end of January 2027)
| Metric | Target |
|---|---|
| End-to-end on-device pipeline (capture, calibrate, estimate, triage) | Working on at least 3 Android models |
| Accuracy on public data (mean absolute error, correlation vs TSB) | Measured and published honestly, whatever the number |
| Capture to result time | Under 10 seconds on a 3 GB RAM device |
| Quality gate | Rejects 100% of a deliberately bad test image set (glare, blur, missing card) |
| Open release | Code, model, card design, model card, retraining guide on GitHub under MIT |
| Partner | At least one hospital or research partner in written discussion |

### Phase B: Validation study (with partner)
| Metric | Target |
|---|---|
| Sensitivity for TSB above treatment threshold (or above 250 µmol/L) | 95% or higher (published competitors range 84.6% to 100%) |
| Specificity | 65% or higher |
| Subgroup performance, Fitzpatrick V and VI | Sensitivity within 5 percentage points of overall |
| Capture success within two attempts | 90% or higher |
| Data loss in research mode | Zero records lost |

### Phase C: Adoption (after validation)
| Metric | Target |
|---|---|
| Health facilities piloting under partner supervision | Set with partner; no target before validation |
| Independent forks or localized deployments | Tracked, not targeted |
| Reported missed severe cases | Zero tolerance; every report triggers review |

---

## 13. Release Plan

| Release | Audience | Scope |
|---|---|---|
| v0.1 Prototype | Founder only | Camera with AE and AWB locked where supported, camera qualification test, card detection, color correction, feature extraction; parity with a desktop notebook |
| v0.5 Research build | Partner study team | Research mode (FR-25 to FR-31), blinded capture, export; screening triage hidden |
| v1.0 Closed research release | Partner study sites, Play closed testing only; source public on GitHub | Research mode, blinded capture, paired TSB, export and study upload, Bangla and English, model v1; screening triage hidden (`triageEnabled` false); screening flow including guardian consent built and tested but not shown |
| v1.x Public release (later) | Public, Bangladesh first | Screening triage shown; only after Phase B results, clinical sign-off, and the legal opinion (doc 8 gate G3) |
| v2.0 Validated release | Partner-led deployment | Model retrained on Bangladeshi study data, published validation results, sclera option |

**Decision (11 October 2026, D10, replaces D2):** v1.0 is a closed research build for the partner's validation study, distributed only through Play closed testing. No public build shows a triage result until the study shows the estimate works and the legal opinion (doc 8 gate G3) is in hand. Legal questions are handled as they arise; the study itself runs under the partner's ethics approval, which is required before the first real newborn is photographed (Section 11).

---

## 14. Assumptions and Dependencies

| # | Assumption or dependency | Owner | Status |
|---|---|---|---|
| A1 | Target users have access to an Android phone with a usable camera (own, shared, or program-issued) | Partner device survey: brand, model, RAM, Android version, ownership | Market data gathered (doc 1, Section 13); site survey pending |
| A2 | A hospital partner will run an ethics-approved validation study | Founder | Not started |
| A3 | A clinical lead will sign off triage thresholds and wording | Founder | Plan defined (Section 9b); outreach not started |
| A4 | A Dhaka print partner and a measurement lab can produce verified card batches | Founder | Process defined (Section 9a); quotes not yet obtained |
| A5 | Public datasets are licensed for this use | Founder | Unchecked |
| A6 | Research-use open-source release does not require DGDA registration | Regulatory contact | Unverified |

---

## 15. Risks

See `1_Market_Competitor_Analysis.md` Section 11 for the full register. Product-specific additions:

| Risk | Mitigation |
|---|---|
| Users treat "no further action" as "baby is safe" | Wording reviewed with nurses; danger signs reminder on every result; never use "safe" or "normal" |
| Ward lighting (fluorescent, sunlight through windows) breaks color correction | Calibration under real ward lighting; quality gate checks light uniformity |
| Card gets dirty, wet, or faded in use | Card integrity check against stored profile; replacement prompt |
| Founder capacity (solo, multiple roles) | Scope v0.1 to the calibration pipeline only; research mode next; everything else waits |

---

## 16. Decision Log

| # | Date | Decision | Section |
|---|---|---|---|
| D1 | 10 Oct 2026 | Backend limited to per-study research data intake under partner governance | 10 |
| D2 | 10 Oct 2026 | ~~v1.0 public before Bangladeshi validation, triage behind an "experimental" label, gated on clinical sign-off~~ Replaced by D10 | 13 |
| D3 | 10 Oct 2026 | Cards centrally printed, batch-measured, QR-signed; home printing unsupported | 9a |
| D4 | 10 Oct 2026 | Clinical lead is a Bangladeshi consultant neonatologist; AAP 2022 as reference unless the lead chooses otherwise; nurse panel signs wording | 9b |
| D5 | 10 Oct 2026 | Sclera images captured in research mode from day one | 9 |
| D6 | 10 Oct 2026 | Target low-end Android: Android 8.0+, 2 GB RAM floor, 3 to 4 GB reference class, Transsion and Xiaomi entry phones first in testing; phones qualified by an empirical camera test, not by camera spec flags | 8, FR-03, FR-04 |
| D7 | 10 Oct 2026 | Screening records kept 180 days by default (30 to 365 configurable); register number erased at 60 days | 11 |
| D8 | 10 Oct 2026 | Keep optional facility register number with identifier blocking | 11 |
| D9 | 10 Oct 2026 | DigitalOcean for development and staging with synthetic data; real study data hosted in Bangladesh | 10 |
| D10 | 11 Oct 2026 | v1.0 is a closed research build on Play closed testing; public release with triage deferred until validation and the legal opinion. **Replaces D2** | 13 |
| D11 | 11 Oct 2026 | Intended use: decision support for trained health workers; the health worker decides; NeoGold does not diagnose and is not intended to be a medical device | 1, FR-21 |
| D12 | 11 Oct 2026 | Parent or guardian consent confirmed by the health worker at every screening | FR-37, 11 |

### Still open
1. Which named neonatologist agrees to be clinical lead (first approach: the Sakhipur trial neonatology investigators).
2. Print partner and measurement lab quotes in Dhaka.
3. AAP 2022 or AAP 2004 thresholds, decided by the clinical lead.
4. Partner device survey at the first site (ownership, brand, model, RAM, Android version).
5. A Bangladeshi lawyer's written opinion, **before any public release** (gate G3, doc 8 Section 13.3; deferred by D10), covering: the Personal Data Protection Ordinance 2025 as it applies to screening logs and study data; whether an automated triage of a specific baby counts as "profiling" of a child, which section 9(3) prohibits; and whether a free, open-source triage app is a medical device under the Drug and Cosmetics Act 2023, which covers software (doc 8 Section 13).
