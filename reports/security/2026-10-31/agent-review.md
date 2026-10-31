# Privacy & Security Agent Review (`privacy-security-reviewer`)

- **Scan Date:** `2026-10-31`
- **Scan Scope:** P26 `/ng-secscan --full` baseline scan
- **Reference Standards:** `docs/8_Security_Compliance_Plan.md` (Sections 3, 5, 6, 9 `T1`–`T17`, 10, 12), `docs/7_API_Documentation.md` (Sections 4.3, 12), `.claude/agents/privacy-security-reviewer.md`
- **Overall Result:** **PASS — 0 unaccepted vulnerabilities or weakened controls found (`17 / 17` threats `Covered`)**

---

## 1. Findings Summary

Zero unaccepted vulnerabilities, privacy regressions, or weakened controls were identified across the Android client (`android/app`, `android/core/security`, `android/core/data`, `android/engine/cardtrust`, `android/feature/research`, `android/sync/research`), the Study Intake API (`backend/app`, `backend/Dockerfile`, `backend/deploy`), the offline decryption CLI (`tools/neogold-decrypt`), and the CI/CD workflows (`.github/workflows/`).

---

## 2. Threat Checklist (`docs/8_Security_Compliance_Plan.md` Section 9: `T1` to `T17`)

| Threat ID | Threat & Attack Vector (`docs/8` Section 9) | Status | Repository Evidence (`file:line`) |
| :--- | :--- | :---: | :--- |
| **T1** | Lost or stolen phone exposes screening or research data | **Covered** | `android/core/security/src/main/kotlin/com/kitalonlabs/neogold/core/security/DatabaseKeyProvider.kt:60-68`, `android/core/data/src/main/kotlin/com/kitalonlabs/neogold/core/data/local/NeoGoldDatabase.kt:64-89`, `android/core/security/src/main/kotlin/com/kitalonlabs/neogold/core/security/FileKeysetProvider.kt:70`, `android/core/security/src/main/kotlin/com/kitalonlabs/neogold/core/security/EncryptedFileStore.kt:58-107`, `android/core/security/src/main/kotlin/com/kitalonlabs/neogold/core/security/applock/AppLockManager.kt:24-197`, `android/core/security/src/main/kotlin/com/kitalonlabs/neogold/core/security/pin/Argon2PinHasher.kt:56-75`, `android/core/data/src/main/kotlin/com/kitalonlabs/neogold/core/data/retention/RetentionManager.kt:31-70`, `backend/app/middleware/auth.py:265-266` |
| **T2** | Family member on a shared phone browses NeoGold | **Covered** | `android/core/security/src/main/kotlin/com/kitalonlabs/neogold/core/security/applock/AppLockManager.kt:24-197`, `android/core/security/src/main/kotlin/com/kitalonlabs/neogold/core/security/privacy/SecureScreenHelper.kt:17-68`, `android/app/src/main/kotlin/com/kitalonlabs/neogold/MainActivity.kt:170,187-196,466-478` |
| **T3** | Repackaged APK with altered thresholds or malware, shared over messaging apps | **Covered** | `android/core/security/src/main/kotlin/com/kitalonlabs/neogold/core/security/integrity/BundleManifestVerifier.kt:26-214`, `android/app/src/main/kotlin/com/kitalonlabs/neogold/integrity/VerifiedAssets.kt:23-144`, `docs/8_Security_Compliance_Plan.md:107-115,334` |
| **T4** | Counterfeit card: genuine QR copied onto a home-printed card | **Covered** | `android/engine/cardtrust/src/main/kotlin/com/kitalonlabs/neogold/engine/cardtrust/CardTrustVerifier.kt:29-129`, `android/engine/vision/src/main/kotlin/com/kitalonlabs/neogold/engine/vision/VisionQualityGate.kt:20-180` |
| **T5** | Card issuer key stolen | **Covered** | `android/engine/cardtrust/src/main/kotlin/com/kitalonlabs/neogold/engine/cardtrust/CardTrustVerifier.kt:72-80,108-125`, `android/engine/cardtrust/src/main/kotlin/com/kitalonlabs/neogold/engine/cardtrust/TrustList.kt:1-95`, `docs/8_Security_Compliance_Plan.md:107-115,431-438` |
| **T6** | Forged study QR points phones at a rogue server | **Covered** | `android/engine/cardtrust/src/main/kotlin/com/kitalonlabs/neogold/engine/cardtrust/CardTrustVerifier.kt:142-275`, `android/feature/research/src/main/kotlin/com/kitalonlabs/neogold/feature/research/ui/R08EnrollmentScreen.kt:50-250` |
| **T7** | Malicious Wi-Fi or network attacker | **Covered** | `android/app/src/main/res/xml/network_security_config.xml:1-9`, `android/sync/research/src/main/kotlin/com/kitalonlabs/neogold/sync/research/crypto/DeviceAssertionMinter.kt:23-81`, `backend/app/middleware/auth.py:203-263` |
| **T8** | Stolen enrollment code | **Covered** | `backend/app/cli.py:239-286`, `backend/app/routers/enrollments.py:92-145`, `backend/app/security/attestation.py:50-110`, `backend/app/middleware/rate_limit.py:55-88` |
| **T9** | Forged or replayed uploads | **Covered** | `android/sync/research/src/main/kotlin/com/kitalonlabs/neogold/sync/research/crypto/DeviceAssertionMinter.kt:23-81`, `backend/app/middleware/auth.py:203-280,358-392`, `backend/app/models/db.py:86-98` |
| **T10** | Study server compromise | **Covered** | `backend/deploy/docker-compose.yml:1-100`, `backend/deploy/neogold-bootstrap:1-31`, `backend/deploy/neogold-backup:1-17`, `backend/Dockerfile:16-78`, `docs/8_Security_Compliance_Plan.md:260-324` |
| **T11** | Insider at the partner copies data | **Covered** | `backend/app/cli.py:600-735`, `backend/app/models/db.py:242-254`, `backend/migrations/versions/0001_initial_schema.py:1-190`, `docs/8_Security_Compliance_Plan.md:274-295,342` |
| **T12** | Supply chain: malicious dependency, compromised GitHub account, poisoned CI action | **Covered** | `tools/check_dependencies.py:1-150`, `tools/check_actions_pinned.py:1-77`, `tools/secscan/scanners.py:1-580`, `.github/workflows/security-scan.yml:1-95` |
| **T13** | Denial of service on the study server | **Covered** | `backend/app/middleware/rate_limit.py:1-120`, `backend/app/middleware/auth.py:164-202`, `android/sync/research/src/main/kotlin/com/kitalonlabs/neogold/sync/research/worker/ResearchUploadWorker.kt:1-160` |
| **T14** | Crafted image exploits the server's image parser | **Covered** | `backend/app/middleware/auth.py:164-415`, `backend/app/security/jpeg.py:28-198`, `backend/Dockerfile:16-78`, `backend/deploy/docker-compose.yml:1-100` |
| **T15** | Re-identification from uploaded data | **Covered** | `android/feature/research/src/main/kotlin/com/kitalonlabs/neogold/feature/research/data/ResearchFormats.kt:13-37`, `backend/app/security/jpeg.py:28-198`, `backend/app/cli.py:600-735`, `tools/neogold-decrypt/src/main/kotlin/com/kitalonlabs/neogold/decrypt/DecryptEngine.kt:363-429` |
| **T16** | A health worker disputes what the app showed | **Covered** | `android/core/data/src/main/kotlin/com/kitalonlabs/neogold/core/data/local/entity/AuditEventEntity.kt:8-22`, `android/core/data/src/main/kotlin/com/kitalonlabs/neogold/core/data/local/dao/AuditEventDao.kt:1-25` |
| **T17** | Data pulled off the phone through backup or debugging | **Covered** | `android/app/src/main/AndroidManifest.xml:4-36`, `android/app/src/main/res/xml/backup_rules.xml:1-8`, `android/app/src/main/res/xml/data_extraction_rules.xml:1-17`, `tools/check_manifest.py:1-120` |

