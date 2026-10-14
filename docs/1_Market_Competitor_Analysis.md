# NeoGold: Market and Competitor Analysis

| Field | Value |
|---|---|
| Document | 1 of 13 |
| Version | 0.3 (Bangladesh device and economic landscape added; camera rationale corrected) |
| Date | 10 October 2026 |
| Owner | Sowad Al-Mughni, Kitalon Labs |
| Status | Approved for Phase 2 |

Confidence tags used in this document:
- **[Certain]** backed by a cited source
- **[Likely]** strong inference from cited sources
- **[Guessing]** filling a gap, needs verification

---

## 1. Executive Summary

1. **The science is settled, the access is not.** At least four groups have shown that a smartphone photo can screen for severe neonatal jaundice with sensitivity in the 85% to 100% range. [Certain]
2. **Every serious competitor is closed, cloud-dependent, or validated mostly on lighter skin.** Picterus is CE-marked but its CE certification covers lighter skin types. BiliSG excluded Fitzpatrick V and VI. BiliCam and Picterus both send images to a server. [Certain]
3. **NeoGold's defensible position is narrow and real:** the first open-source, fully offline, Android-native jaundice screener built and validated for South Asian newborns in low-resource clinics. [Likely]
4. **The bottleneck is not code. It is paired clinical data** (photo plus same-hour blood bilirubin) from Bangladeshi newborns, collected under ethics approval. No public dataset fills this gap. [Likely]
5. **Platform decision: native Kotlin with Jetpack Compose.** Flutter offers nothing this product needs and makes the hardest part (camera color control) harder. See Section 10. [Likely]

---

## 2. The Problem in Numbers

### Global
- Neonatal jaundice affects about 60% of term and 80% of preterm newborns. [Certain] (Picterus JMIR 2023; BiliSG JAMA Netw Open 2024)
- Neonatal jaundice causes an estimated 100,000 deaths per year, with low-income countries in Africa and South Asia particularly affected. [Certain] (Outlaw, UCL PhD thesis, 2021)
- Most cases are harmless. The danger is the minority that progress to severe hyperbilirubinemia and kernicterus, which is preventable with timely phototherapy or exchange transfusion. [Certain]

### Bangladesh
- Khulna Medical College Hospital, 2005 to 2008: 426 of 1,981 neonatal ward admissions (22%) had jaundice. 12 died (2.8%) and 9 developed kernicterus (2.1%). [Certain]
- Dr. MR Khan Shishu Hospital, Dhaka, 2017 to 2021: of 61 newborns needing exchange transfusion, 20 (32.8%) arrived with signs of kernicterus, and 45% of those were discharged with neurological deficit. [Certain]
- Interpretation: a large share of severe cases reach tertiary care too late. Earlier detection at district and community level is where a screening tool changes outcomes. [Likely]
- Gap: there is no recent national-level prevalence or kernicterus incidence figure for Bangladesh in the sources reviewed. [Certain that it was not found; Guessing on whether it exists]

---

## 3. How Jaundice Is Screened Today

| Method | How it works | Cost | Accuracy | Availability in Bangladesh district settings |
|---|---|---|---|---|
| Visual assessment | Nurse or doctor looks at skin and eyes | Free | Unreliable, worse on darker skin | Universal |
| Transcutaneous bilirubinometer (TcB) | Handheld optical device on forehead or sternum | About $7,000 to $8,000 per device (US estimate) [Certain] | Good screening tool | Limited to larger hospitals [Likely] |
| Total serum bilirubin (TSB) | Blood test, lab analysis | Lab cost plus heel prick | Gold standard | Requires lab access and turnaround time [Likely] |
| Smartphone image analysis | Photo of skin or eye, color analysis | Phone plus card | Comparable to TcB in published studies [Certain] | Not deployed in Bangladesh [Likely] |

The gap NeoGold fills sits between visual assessment (free but unreliable) and TcB (reliable but unaffordable at the point of first contact).

---

## 4. Competitor Landscape

### 4.1 Comparison matrix

| | Picterus Jaundice Pro | BiliCam | neoSCB | BiliSG | Academic Android apps (Iran) | **NeoGold (target)** |
|---|---|---|---|---|---|---|
| Origin | Picterus AS, Norway (NTNU spin-off) | University of Washington, BiliCam LLC | UCL, with University of Ghana | Singapore General Hospital, SingHealth, Synapxe | Shiraz and Mashhad universities | Kitalon Labs, Bangladesh |
| Body site | Skin with calibration card | Skin (sternum) with calibration card | Sclera (white of eye) | Forehead, sternum, abdomen with stickers | Skin | Skin with card (v1), sclera (later) |
| Processing | App plus server [Certain] | Cloud server [Certain] | On phone [Likely] | App with ML model [Certain] | Varies | **Fully on-device** |
| Largest validation | About 3,000 newborns tested [Certain] | 530 newborns, 7 US sites [Certain] | 336 newborns, Ghana [Certain] | 546 newborns, Singapore [Certain] | 113 newborns [Certain] | Target: Bangladeshi cohort |
| Headline performance | Sensitivity 94%, specificity 71% for TSB above 250 µmol/L [Certain] | Correlation 0.91; sensitivity 84.6% to 100%, specificity 75% to 76% [Certain] | Sensitivity 0.94, specificity 0.73, AUC 0.90 [Certain] | Correlation 0.84; sensitivity 100%, specificity 70% [Certain] | Sensitivity 68%, specificity 92.3% [Certain] | Must match or beat 94% sensitivity |
| Skin tone coverage | CE certification for lighter skin; trials running in Botswana and Chicago to extend [Certain] | Diverse US sample; lowest correlation in Asian Americans (0.88) [Certain] | Ghanaian newborns (sclera less affected by melanin) [Certain] | Excluded Fitzpatrick V and VI [Certain] | Iranian population | **Built for Fitzpatrick IV to VI** |
| Regulatory | CE-marked medical device [Certain] | US validation study with NIH funding [Certain] | Research [Likely] | Research, single phone model tested [Certain] | Research | Research-use tool first |
| Access | Commercial; cards sold only in approved countries [Certain] | Not publicly available [Likely] | Not publicly distributed [Guessing] | Singapore pilot [Certain] | Not distributed [Likely] | **Free, MIT-licensed, printable card** |
| Open source | No [Likely] | No [Likely] | No [Guessing] | No [Likely] | No [Likely] | **Yes** |

### 4.2 What each competitor teaches us

- **Picterus:** Seven years (2014 to 2021) from idea to CE mark. [Certain] Lesson: a regulatory path is long. NeoGold must not market itself as a diagnostic device in v1.
- **BiliCam:** Color features plus regression (not a giant neural network) reached 0.91 correlation. [Certain] Lesson: with small data, engineered color features beat deep learning. Start simple.
- **neoSCB:** Sclera beat skin in their analysis, and needs no card after one-time phone calibration. Performance dropped in babies who already had phototherapy. [Certain] Lesson: sclera may be the better long-term signal for darker-skinned babies. Exclude post-phototherapy babies from v1 claims.
- **BiliSG:** Multi-site capture (forehead, sternum, abdomen) follows how jaundice spreads head to toe and improved accuracy. Tested on one phone only. [Certain] Lesson: multi-site capture is worth it; device diversity is an unsolved problem everyone admits.

---

## 5. Gap Analysis

| Need | Picterus | BiliCam | neoSCB | BiliSG | Unmet? |
|---|---|---|---|---|---|
| Works with zero internet | No | No | Likely | Unknown | **Yes** |
| Free to use | No | N/A | N/A | N/A | **Yes** |
| Code and model weights public | No | No | No | No | **Yes** |
| Validated on South Asian newborns | No | Partial (Asian American) | No | Partial (Chinese, Malay, Indian; no V/VI) | **Yes** |
| Runs on low-cost Android | Device list restricted | iPhone-focused | Galaxy S8 | Single model | **Yes** |
| Bangla interface | No | No | No | No | **Yes** |

[Likely] No existing tool meets all six needs. The combination, not any single feature, is the position.

---

## 6. Positioning Statement

> For nurses, midwives, and community health workers in Bangladesh who must decide which newborns need a blood test, **NeoGold** is a free, offline Android screening tool that estimates jaundice risk from a calibrated photo. Unlike Picterus or BiliCam, NeoGold is open source, runs entirely on the phone, and is built and validated for South Asian skin tones.

What NeoGold is **not**:
- Not a diagnostic device. It says "refer for blood test," never "this baby is fine."
- Not a parent home-monitoring app in v1. Picterus and BiliSG already chase that market, and it carries higher risk.
- Not a business. No paywall, no subscription, no ads.

---

## 7. User Needs Summary

Detailed personas live in `2_PRD.md`. Core needs:

| User | Primary need | Biggest constraint |
|---|---|---|
| District hospital nurse or midwife | Decide fast which newborn needs a blood test | Time, no TcB device, crowded wards, mixed lighting |
| Community health worker on postnatal home visits | Catch jaundice before the family travels to hospital too late | No internet, low-end phone, variable literacy |
| Clinical researcher or pediatrician | Collect paired photo and TSB data for validation | Ethics compliance, data quality, export format |
| NGO or open-source deployer | Adapt and deploy in another country | Documentation, licensing, localization |

---

## 8. Regulatory Landscape

- Bangladesh's Drug and Cosmetics Act 2023 (enacted 18 September 2023) requires medical devices, including software, to be registered with the DGDA before being imported, manufactured, or sold. [Certain, per secondary regulatory guide]
- Standalone software that meets the medical device definition is treated as an active medical device. Devices are classed A to D by risk; classes B, C, and D need registration. [Certain]
- [Likely] A bilirubin-estimating app would fall into a registrable class once it is offered for clinical decision-making.

**Implication for NeoGold:**
1. v1 is released as an open-source **research tool**, labeled "Not a medical device. For research and screening support only. Always confirm with a blood test."
2. Clinical image collection happens only inside an ethics-approved study with a hospital partner.
3. A DGDA registration path is a later decision, likely owned by an institutional partner (hospital, university, or NGO), not by a solo builder.
4. Google Play health app policies will apply to distribution. [Likely] Verify before Phase 4.

[Guessing] The exact DGDA classification for this software and whether a research-labeled open-source app needs registration. Get a written opinion from a regulatory contact before any clinical deployment.

---

## 9. Data Landscape

| Dataset | Content | Fit for NeoGold |
|---|---|---|
| Mendeley "Neonatal Jaundice Assessment" (Mashhad, Iran, 2023) | Forehead and sternum images of 300 infants with TSB and TcB, ethics-approved [Certain] | Useful for prototyping. **Captured with a USB digital microscope, not a phone camera** [Certain], so domain mismatch. Check license before use. |
| UCL sclera dataset | 87 newborn sclera images used in neoSCB thesis [Certain] | Small; availability unknown [Guessing] |
| Published papers (BiliCam, Picterus, BiliSG) | Methods and feature descriptions | Method replication, not raw data |
| **Bangladeshi paired photo and TSB data** | **Does not exist publicly** [Likely] | **The core asset NeoGold must create** |

**Conclusion:** the grant phase can build and test the pipeline on public data, but a model fit for Bangladeshi newborns requires a partner study. This is the single most important dependency in the project.

---

## 10. Platform Decision: Kotlin vs Flutter

**Recommendation: native Kotlin with Jetpack Compose.** [Likely]

| Factor | Kotlin + Compose | Flutter | Winner |
|---|---|---|---|
| Camera control (scene modes, effects, AE and AWB locks, flash control, metadata, RAW) | Full access through CameraX with Camera2 interop | Official camera plugin exposes limited controls; the missing ones need custom platform code written in Kotlin anyway [Likely] | **Kotlin** |
| On-device ML (LiteRT, formerly TFLite; MediaPipe) | First-class Android SDKs | Community wrappers over the same native libraries | **Kotlin** |
| Target platform | Android only (low-cost Androids dominate the target market) [Likely] | Cross-platform advantage is wasted when iOS is out of scope | **Kotlin** |
| App size and speed on low-end phones | No extra runtime | Adds the Flutter engine to every install [Likely] | **Kotlin** |
| Founder's existing code | PixelPro AI (Kotlin, Compose, TFLite, ONNX Runtime, MediaPipe) and ScanLite (13-module Clean Architecture) are directly reusable [Certain] | Nothing reusable | **Kotlin** |
| Future iOS port | Rewrite UI, or share logic through Kotlin Multiplatform | Single codebase | Flutter |

**Why the camera point decides it:** the entire product is color measurement. Because the card sits in the same photo as the skin, global white balance and exposure affect both equally and the card corrects for them. The real risk is processing that treats different parts of the image differently (HDR, local tone mapping, scene modes, beauty and "AI" enhancement), which is common on budget phones and silently breaks the correction. Turning that processing off where the phone allows it, reading capture metadata, locking exposure for the flash and no-flash sclera pairs (the neoSCB method), and optionally capturing RAW all require direct Camera2 access. In Flutter you would end up writing that layer in Kotlin through platform channels, which means maintaining two languages for no gain.

*Correction (v0.3):* v0.1 of this document said white balance had to be locked between card and skin. That was wrong for a single photo containing both. Locks remain useful for multi-frame captures but are no longer a hard device requirement; see Section 13 and `4_System_Architecture.md` Section 5.1.

**When Flutter would win:** only if v1 had to ship on iPhone for parents at home. That is explicitly out of scope.

---

## 11. Top Risks

| # | Risk | Impact | Likelihood | Mitigation |
|---|---|---|---|---|
| R1 | No hospital partner, so no Bangladeshi clinical data | Model never reaches clinical usefulness | High | Start outreach now. First approach: the team behind the Sakhipur community jaundice screening trial (icddr,b, Bangladesh Shishu Hospital & Institute, Bangladesh Medical University, with Stanford), who already screen newborns at home with transcutaneous meters. See PRD Section 9b. |
| R2 | Printed cards vary in color by printer, paper, and run | Calibration silently wrong | High | **Decided:** central printing, per-batch spectrophotometer measurement, signed batch QR read by the app, home printing unsupported. Sclera method kept as a card-free alternative. See PRD Section 9a. |
| R3 | Camera behavior varies across Android phones | Accuracy differs by device | High | Maintain a supported device list; per-device calibration step; log device model with every capture. |
| R4 | Model misses a severe case (false negative) | Harm to a newborn | Medium | Threshold tuned for high sensitivity; output never says "safe"; always recommend blood test when uncertain. |
| R5 | Regulatory exposure if used for clinical decisions | Legal and reputational | Medium | Research-use labeling; partner-led deployment; regulatory opinion before clinical use. |
| R6 | Reviewers or users assume NeoGold claims to be first | Credibility loss | Medium | Always cite Picterus, BiliCam, neoSCB, BiliSG; claim only the open, offline, South Asian position. |

---

## 12. Strategic Recommendations

1. **Lead with the gap, credit the science.** Every external message names the prior work and states the narrow position.
2. **Build the pipeline on public data during the grant, recruit the partner in parallel.** Do not wait for one to start the other.
3. **Ship skin-plus-card first, test sclera second.** Skin with card matches the grant story; sclera may win on darker skin and removes the card-printing risk.
4. **Treat the research mode as a product, not an afterthought.** A clean paired-data capture flow is what makes a hospital willing to partner.
5. **Native Kotlin, offline only for screening.** A backend exists only to receive consented research data. This is argued in detail in Phase 2.

---

## 13. Device and Economic Landscape (Bangladesh)

### 13.1 The economy shapes the phone

| Indicator | Value | Implication |
|---|---|---|
| Inflation | 8.34% year on year, September 2026 [Certain]; ADB forecasts 8.8% average for FY2026-27, the highest in South Asia [Certain] | Phones are replaced slowly. Old, cheap devices stay in use for years |
| Handset mix | 5 million handsets shipped in Q3 2025, 53.5% of them feature phones [Certain, IDC via Daily Sun] | More than half of new phones sold cannot run NeoGold at all |
| Buying pattern | Value-driven; zero-down-payment and cardless installment schemes drive purchases, especially for lower-middle-income and rural buyers [Certain] | Target the phones people actually buy, not what developers own |
| Entry smartphone prices | Popular entry models listed around BDT 16,800 to 18,100 (Redmi A7, Galaxy A06) [Certain, retailer listing] | Roughly the price band the target users can reach |

### 13.2 Who owns smartphones

| Indicator | Value | Source |
|---|---|---|
| People (aged 5+) who use a mobile phone | 89.6% | BBS ICT survey, Q4 FY26 (April to June 2026) [Certain] |
| People who own a personal mobile phone | 65.6% | Same [Certain] |
| Households with at least one smartphone user | 75.1% | Same [Certain] |
| Adult smartphone ownership, men vs women | 40% vs 22% | GSMA Mobile Gender Gap Report 2024 (2023 survey) [Certain] |

**Implication:** nurses, midwives, and community health workers in Bangladesh are mostly women [Likely], and women are far less likely to own a smartphone. Many will use a shared family phone or a program-issued device. NeoGold must protect data on shared phones and must not assume one phone per worker. The partner device survey (PRD assumption A1) must ask who owns the phone.

### 13.3 Which phones

**Smartphone brands by shipments, Q3 2025 (IDC):** TECNO 20.4%, Infinix 18.2%, Xiaomi 14.9% [Certain]. TECNO, Infinix, and itel all belong to Transsion. In 2023, Counterpoint reported TECNO rising from 5.9% to 15.8%, Infinix from 9.3% to 13.3%, and itel from 6.2% to 11.8% [Certain], while Symphony led the overall handset market, driven by feature phones [Certain].

**Web usage by brand, September 2026 (StatCounter):** Xiaomi 25.0%, Samsung 22.4%, Vivo 8.8%, Oppo 7.7%, Apple 6.5%, Realme 6.3% [Certain]. This measures phones browsing the web, so it over-represents better-connected urban users and under-represents the budget phones NeoGold targets [Likely].

**Android versions in use, September 2026 (StatCounter):**

| Android | Share |
|---|---|
| 16 | 21.7% |
| 15 | 17.1% |
| 13 | 14.9% |
| 14 | 13.0% |
| 11 | 11.6% |
| 12 | 11.2% |
| 10 and older | about 10.6% (remainder) |

[Certain for the listed values; the remainder is inferred.] The same web-usage bias applies, so rural phones skew older than this table.

**Typical entry-level specification** [Likely, from retailer listings]: MediaTek Helio G81 or G85 class chipset, 4 GB RAM (often marketed with extra "virtual RAM"), 64 to 128 GB storage, 50 MP main camera, HD+ (720p) screen of about 6.6 to 6.7 inches. Android Go devices with 2 GB RAM remain in use at the very bottom of the market; Android 13 Go requires at least 2 GB RAM [Certain].

### 13.4 What this means for NeoGold

| Finding | Product decision |
|---|---|
| Transsion phones (TECNO, Infinix, itel) lead shipments | They are the primary test devices, not Samsung flagships |
| Their Android skins (HiOS, XOS) restrict background apps and notifications unless users change battery settings [Certain] | Recheck reminders show inside the app first; notifications are a bonus. Research uploads have a manual "Upload now" button. Onboarding shows brand-specific battery steps |
| Budget phones vary in camera processing; per-phone color bias before correction can reach Delta E 12 to 14 even on flagships, while same-phone repeatability is about 0.45 [Certain on the numbers; Springer journal article, 2025] | Every phone is qualified by an empirical camera test at onboarding, not by a spec sheet (doc 4, Section 5.1) |
| Viewing angle raised color error by 64% between 15 and 75 degrees in the same study [Certain] | Capture guide enforces a near-perpendicular angle; the quality gate rejects tilted cards |
| Storage is often full on cheap phones [Likely] | Download under 25 MB; no images kept in screening mode |
| 2 GB Android Go devices still exist | Must run on 2 GB RAM; 3 to 4 GB is the reference class |
| Shared and program-issued phones are common [Likely] | Optional app lock; research mode behind a PIN; no data in Android cloud backup |
| Older Android versions persist in rural areas | Minimum Android 8.0 (API 26) stays; covers effectively all phones in use |

### 13.5 Device test matrix

| Class | Example models (verify local availability) | Why |
|---|---|---|
| Transsion entry | TECNO Spark series, Infinix Smart or Hot series, itel A or P series | Largest shipment share; HiOS/XOS battery behavior |
| Xiaomi entry | Redmi 14C or Redmi A series | High shipments and web share; MIUI/HyperOS background limits |
| Samsung entry | Galaxy A06 | Common in programs and among health staff |
| Android Go floor | Any 2 GB RAM Android Go phone | Proves the minimum works |
| Older device | Any Android 10 or 11 phone with 3 GB RAM | Rural reality |
| Local assembly | A Symphony or Walton smartphone | Locally made, often program-procured [Guessing] |

[Guessing] Exact model names change every few months; the partner device survey sets the final list before v0.5.

---

## Sources

- Picterus development and CE certification, JMIR Pediatrics and Parenting 2023: https://pediatrics.jmir.org/2023/1/e40463
- Picterus parental usability study, JMIR Pediatrics 2026: https://pediatrics.jmir.org/2026/1/e83077
- Picterus CE certification for lighter skin types, CORDIS: https://www.cordis.europa.eu/article/id/442848-smartphone-app-monitors-jaundice-in-newborns/it
- Picterus Botswana trial NCT06399146: https://clinicaltrials.gov/study/NCT06399146
- Picterus Chicago trial NCT05521607: https://clinicaltrials.gov/study/NCT05521607
- Picterus App Store listing: https://apps.apple.com/app/id1667860636
- BiliCam, Pediatrics 2017 (PMC): https://pmc.ncbi.nlm.nih.gov/articles/PMC5574723
- BiliCam summary, Contemporary Pediatrics: https://contemporarypediatrics.com/view/app-assessing-neonatal-jaundice-you-bet
- BiliCam pilot and TcB cost, MDedge: https://www.mdedge.com/content/newborn-jaundice-app-shows-promise
- BiliCam clinical validation study NCT03246503: https://clinicaltrials.gov/study/NCT03246503
- neoSCB Ghana validation, Pediatrics 2022: https://ugspace.ug.edu.gh/items/6751392f-ddce-42d7-81b2-a4b48f08e9a2
- neoSCB PhD thesis, UCL Discovery: https://discovery.ucl.ac.uk/id/eprint/10134694
- neoSCB news, UCLH: https://uclh.nhs.uk/news/app-detecting-neonatal-jaundice-eye-successful-first-major-clinical-trial
- BiliSG, JAMA Network Open 2024 (summary): https://www.contemporarypediatrics.com/view/using-a-smartphone-application-to-screen-for-neonatal-jaundice
- BiliSG skin type exclusion, JournalFeed: https://journalfeed.org/article-a-day/2025/a-smartphone-app-for-neonatal-bilirubin-screening/
- BiliSG, SGH news: https://www.sgh.com.sg/news/innovation/new-app-will-allow-parents-to-test-their-babies-for-jaundice-fro
- Android smartphone jaundice app, Shiraz: https://brieflands.com/articles/ijp-84397.pdf
- Mendeley neonatal jaundice dataset: https://data.mendeley.com/datasets/yfsz6c36vc
- Khulna outcomes study (PMC): https://pmc.ncbi.nlm.nih.gov/articles/pmid/22135536
- Exchange transfusion, Dr. MR Khan Shishu Hospital: https://www.banglajol.info/index.php/DSHJ/article/view/70592
- Bangladesh DGDA device registration guide: https://meddeviceguide.com/blog/bangladesh-dgda-medical-device-registration-guide
- Bangladesh medical device definitions, RegDesk: https://www.regdesk.co/regulations-library/bangladesh/
- Smartphone colorimetry with targeted yellows card, Nixon et al., PLoS One 2020: https://discovery-pp.ucl.ac.uk/10094305/1/NixonPlosOne2020.pdf
- Calibration card patent (opening for skin, spectral printing, post-print spectrometer measurement): https://image-ppubs.uspto.gov/dirsearch-public/print/downloadPdf/10799150
- Calibrite ColorChecker Classic Mini pricing: https://colorconfidence.com/products/calibrite-colorchecker-classic-mini
- Nix Spectro 2 pricing: https://www.nixsensor.com/product/nix-spectro-2-spectrophotometer-5mm-print/
- CHW household jaundice screening trial protocol, Sakhipur, Gates Open Research: https://gatesopenresearch.org/articles/7-58
- CHW home phototherapy acceptability, BMC Pediatrics 2024: https://link.springer.com/10.1186/s12887-024-04584-7
- AAP 2022 guideline summary, ADLM: https://myadlm.org/cln/articles/2023/julaug/hyperbilirubinemia-management-in-newborns
- BSMMU renamed Bangladesh Medical University (2025): https://wiki.riteme.site/wiki/BSMMU
- Bangladesh inflation, Trading Economics: https://tradingeconomics.com/bangladesh/inflation-cpi
- ADB inflation forecast via UNB: https://unb.com.bd/category/Business/bangladesh-to-experience-highest-inflation-in-south-asia-in-2026-27-forecasts-adb/190381
- IDC Q3 2025 market shares via Daily Sun: https://www.daily-sun.com/other/838292/value-for-money-redefines-bangladesh-s-smartphone-market
- Counterpoint 2023 brand shares: https://www.detailsbusinesssolution.com/articles/symphony-leads-xiaomi-slips-study-outlines-market-share-of-smartphones-in-bangladesh
- Counterpoint 2022 market via Daily Star: https://www.thedailystar.net/business/economy/news/smartphone-shipment-shrinks-3253256
- BBS ICT survey Q4 FY26 via Dhaka Tribune: https://www.dhakatribune.com/amp/business/420730/bbs-survey-66%25-of-population-own-a-mobile-phone
- GSMA gender gap via Daily Star: https://www.thedailystar.net/business/news/women-still-lag-mobile-ownership-internet-adoption-3613001
- StatCounter Android versions, Bangladesh: https://gs.statcounter.com/android-version-market-share/mobile-tablet/bangladesh
- StatCounter vendors, Bangladesh: https://gs.statcounter.com/vendor-market-share/mobile/bangladesh
- Entry phone listing, Apple Gadgets BD: https://www.applegadgetsbd.com/blog/buying-guides/official-phone-under-15000/
- Android Go minimum RAM: https://developer.android.com/guide/topics/androidgo
- TECNO HiOS background limits: https://singularity-app.com/wiki/tecno-notifications/
- Smartphone color measurement variability, Anal Bioanal Chem 2025: https://link.springer.com/article/10.1007/s00216-025-06021-9
- Camera2 hardware levels: https://android.googlesource.com/platform/docs/source.android.com/+/2764d6f/src/devices/camera/versioning.jd
