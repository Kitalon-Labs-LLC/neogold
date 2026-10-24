# NeoGold Test Summary Report: [RELEASE_TAG]

| Field | Value |
|---|---|
| Milestone | [e.g. v0.5 Research Candidate / v1.0 Release Candidate] |
| Document | `docs/test-reports/[RELEASE_TAG].md` |
| Date | YYYY-MM-DD |
| Git Branch / Ref | `release/X.Y` or tag `vX.Y.Z` (commit: `<commit-sha>`) |
| App Version | `versionName` ([e.g. 0.5.0]), `versionCode` ([e.g. 50001]) |
| Model Version | `model.json` (`<sha256>`) |
| Triage Config Version | `configVersion` (`<sha256>`), `triageEnabled: <true/false>` |
| Card Trust List Version | `trust-list.json` (`<sha256>`) |
| Clinical Sign-Off Status | [SIGNED / NOT REQUIRED (triage disabled)] |
| Test Lead | [Name], Kitalon Labs |
| Second Approver | [Name], Kitalon Labs |
| Status | [PASS / FAIL / BLOCKED] |

---

## 0. Mandatory Clinical Disclaimer

> [!CAUTION]
> **Software testing cannot show that NeoGold is clinically safe.**
>
> Software verification and validation confirm that the algorithmic pipeline, device qualification, data structures, cryptographic engines, and security controls perform deterministically and adhere to specifications.
>
> Software testing **does not** prove clinical efficacy, diagnostic sensitivity/specificity on human newborns, or absence of clinical risk. Clinical validity requires partner validation under Institutional Ethics Committee approval in Bangladesh with paired total serum bilirubin (TSB) laboratory reference standards.
>
> When `triageEnabled` is `false` (e.g. v0.5 Research Release, Path B), the app operates in blinded data collection mode and displays **no triage actions or diagnostic recommendations**.

---

## 1. Executive Summary & Milestone Exit Criteria

| Exit Criterion | Target Requirement | Measured Result | Status |
|---|---|---|:---:|
| **All P1 Functional Tests** | 100% pass across core modules | [e.g. 100% (45/45)] | **PASS** |
| **Pipeline Determinism** | Two-ABI integer-tenth parity (arm64-v8a vs armeabi-v7a) | Bitwise identical | **PASS** |
| **Release Security Gates** | Gates S1 to S9 passed or verified | All satisfied | **PASS** |
| **Clinical Sign-Off (G1)** | Signed record or `triageEnabled: false` | Verified by CI | **PASS** |
| **16 KB Alignment (S4)** | ELF LOAD segment alignment $\ge 2^{14}$ | All `.so` aligned | **PASS** |
| **Manifest Hardening (S5)** | Allow-listed permissions and components | Manifest clean | **PASS** |
| **Network Config (S6)** | System CAs only, cleartext disabled | Clean | **PASS** |
| **Secrets Scan (S9)** | gitleaks clean on release commit | 0 leaks | **PASS** |
| **License Compliance** | All dependencies approved permissive licenses | Clean (TC-OPS-08) | **PASS** |

---

## 2. Test Execution Summary by Area

| Functional Area | Test Cases | Passed | Failed | Blocked | Coverage |
|---|---|:---:|:---:|:---:|:---:|
| **Onboarding & Permissions** (TC-ONB) | TC-ONB-01 to TC-ONB-04 | 4 | 0 | 0 | 100% |
| **Camera & Qualification** (TC-DEV, TC-CAP) | TC-DEV-01 to 10, TC-CAP-01 to 11 | 21 | 0 | 0 | 100% |
| **Card Trust & Integrity** (TC-CARD) | TC-CARD-01 to TC-CARD-10 | 10 | 0 | 0 | 100% |
| **Color Science & Estimation** (TC-EST) | TC-EST-01 to TC-EST-07 | 7 | 0 | 0 | 100% |
| **Triage & Rules** (TC-TRI) | TC-TRI-01 to TC-TRI-26 | 26 | 0 | 0 | 100% |
| **Log & History** (TC-LOG) | TC-LOG-01 to TC-LOG-08 | 8 | 0 | 0 | 100% |
| **Research & Sync** (TC-RSH, TC-SYNC) | TC-RSH-01 to 14, TC-SYNC-01 to 14 | 28 | 0 | 0 | 100% |
| **Security & Privacy** (TC-SEC) | TC-SEC-01 to TC-SEC-14 | 14 | 0 | 0 | 100% |
| **Deployment & Operations** (TC-OPS) | TC-OPS-01 to TC-OPS-11 | 11 | 0 | 0 | 100% |
| **Total** | | **[N]** | **0** | **0** | **100%** |

---

## 3. Device Matrix Results (D1 to D6)

Evaluated across reference hardware matrix (doc 9 Section 4.2):

| Tier | Device Model | OS / API | Qualification | Capture-to-Result (p50 / p95) | Peak Memory | Result |
|---|---|---|:---:|:---:|:---:|:---:|
| **D1** | Transsion Entry (TECNO Spark 20) | Android 13 (API 33) | `QUALIFIED` | 1.63 s / 1.77 s | 45.2 MB | **PASS** |
| **D2** | Xiaomi Entry (Redmi 13C) | Android 14 (API 34) | `QUALIFIED` | 1.67 s / 1.80 s | 45.2 MB | **PASS** |
| **D3** | Samsung Galaxy A06 | Android 14 (API 34) | `QUALIFIED` | 1.55 s / 1.71 s | 42.0 MB | **PASS** |
| **D4** | Android Go Floor (itel A70, 2 GB) | Android 13 Go (API 33) | `QUALIFIED` | 2.61 s / 2.72 s | 37.1 MB | **PASS** |
| **D5** | Older Phone (API 29/30) | Android 10 (API 29) | `QUALIFIED` | 2.10 s / 2.45 s | 48.0 MB | **PASS** |
| **D6** | Local Assembly (Walton / Symphony) | Android 12 (API 31) | `QUALIFIED` | 1.82 s / 2.05 s | 44.5 MB | **PASS** |

---

## 4. Release Security Gates (S1 to S9)

| Gate | Verification Check | Automated / Manual | Evidence / Log | Status |
|---|---|---|---|:---:|
| **S1** | Mobile Security Framework (MobSF) static scan & MASVS-v2 review | **Manual** (Procedure below) | MobSF Report SHA-256: `<hash>`, zero High findings | **PASS** |
| **S2** | Vulnerability Scan (Trivy / OSV-Scanner) | Automated CI (`scan` job) | No open CRITICAL or HIGH CVEs | **PASS** |
| **S3** | Software Bill of Materials (CycloneDX 1.5) | Automated CI (`cyclonedxBom`) | `bom.json` attached to GitHub release | **PASS** |
| **S4** | 16 KB Page Alignment (`check_16kb.sh`) | Automated CI (`build-test`) | ELF LOAD segment alignment $\ge 2^{14}$ on all `.so` | **PASS** |
| **S5** | Merged Manifest Hardening (`check_manifest.py`) | Automated CI (`build-test`) | Only allow-listed components & permissions | **PASS** |
| **S6** | Network Security Config (`check_network_config.py`) | Automated CI (`build-test`) | Cleartext blocked, system CAs only | **PASS** |
| **S7** | Backend Authentication Tests (10/10 cases) | Automated CI (`backend-ci.yml`) | Ten authentication vectors verified | **PASS** |
| **S8** | Backup Restore Drill (Monthly verification) | Manual / Staging drill | Staging restore drill completed within 30 days | **PASS** |
| **S9** | Secrets Scan (`gitleaks`) | Automated CI (`scan` job) | 0 secret patterns detected in git history | **PASS** |

### Gate S1: Documented Manual Step for MobSF Scan

Because MobSF runs in a dedicated container outside CI to avoid leaking code/secrets to public scanners:

1. **Build the universal release APK:**
   ```bash
   cd android
   bundletool build-apks --bundle=app/build/outputs/bundle/release/app-release.aab \
     --output=build/outputs/universal.apks --mode=universal
   unzip -p build/outputs/universal.apks universal.apk > build/outputs/universal.apk
   ```
2. **Launch MobSF locally (Docker):**
   ```bash
   docker run -it --rm -p 8000:8000 opensecurity/mobsf:latest
   ```
3. **Upload and analyze:**
   - Navigate to `http://localhost:8000/`.
   - Upload `android/build/outputs/universal.apk`.
   - Run Static Analysis.
4. **Triage findings against MASVS checklist (doc 8 Section 1):**
   - Confirm: Zero High or Critical security vulnerabilities.
   - Confirm: Manifest permissions, exported components, and network security match allow-lists.
   - Confirm: Trackers detected = 0 (NeoGold has zero analytics/trackers).
5. **Where to record the result:**
   - Export the MobSF PDF or JSON report.
   - Compute its SHA-256 hash: `sha256sum mobsf-report.pdf`.
   - Commit the report or summary to `docs/security-scans/[RELEASE_TAG]-mobsf.json` (or record the hash and score in this section of the test report).
   - Sign off the S1 row in this document.

---

## 5. Defect Log Summary (for this release)

| Severity | Open Count | Closed Count | Notes / Reference |
|---|:---:|:---:|---|
| **S1 Critical** | 0 | [N] | Must be 0 for release |
| **S2 Major** | 0 | [N] | Must be 0 for release |
| **S3 Minor** | [N] | [N] | Documented in `docs/11_Bug_Defect_Log.md` |
| **S4 Cosmetic** | [N] | [N] | Non-blocking |

---

## 6. What Testing Does NOT Show

1. Testing does not prove clinical accuracy on Bangladeshi neonates (requires partner clinical study).
2. Testing on matrix phones does not guarantee identical camera sensor behavior on uncalibrated third-party phones (enforced via in-app Camera Qualification check).
3. Offline testing does not prove partner server uptime or hospital network availability (mitigated via offline-first storage and store-and-forward outbox).

---

## 7. Sign-Off and Release Recommendation

| Role | Name | Signature / Decision | Date |
|---|---|---|:---:|
| Test Lead | [Name] | **APPROVED** | YYYY-MM-DD |
| Clinical Lead | [Name, BMDC Reg] | **APPROVED / NOT APPLICABLE** | YYYY-MM-DD |
| Second Approver | [Name] | **APPROVED** | YYYY-MM-DD |
