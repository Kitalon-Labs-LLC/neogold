# NeoGold Security-Debt & Vulnerability Ledger

- **Last Regenerated:** `2026-10-31`
- **Scan Mode:** `--full`
- **SLA Fix Deadlines (`docs/8_Security_Compliance_Plan.md` Section 10):** **Critical:** 7 days | **High:** 30 days | **Medium:** 90 days
- **Exception Policy:** Max expiry 90 days (`security/exceptions.yaml`); inline suppressions must cite `SEC-EXC-XXXX`.

## 1. Summary by Severity

| Severity | Fix SLA Deadline | Unaccepted (Open) | Accepted (In `exceptions.yaml`) | Gate Behavior |
| :--- | :--- | :---: | :---: | :--- |
| **CRITICAL** | 7 days | 0 | 0 | Fails scan + immediate incident stop (Doc 8 §12) |
| **HIGH** | 30 days | 0 | 30 | Fails scan unless in `security/exceptions.yaml` |
| **MEDIUM** | 90 days | 0 | 1 | Tracked in ledger with 90-day fix deadline |
| **LOW** | 90 days | 0 | 1 | Tracked in ledger |

## 2. Unaccepted Open Findings

_None. Zero unaccepted findings._

## 3. Accepted Security-Debt Ledger (`security/exceptions.yaml`)

| Exception ID | Severity | Tool | Rule | File | Age (days) | Fix Deadline (Doc 8 §10) | Exception Expiry | Approver | Reason |
| :--- | :--- | :--- | :--- | :--- | :---: | :---: | :---: | :--- | :--- |
| `SEC-EXC-0001` | **HIGH** | `trivy` | `CVE-2026-8763` | `android/settings-gradle.lockfile` | 4d | `2026-11-26` (30d) | `2027-01-25` | `@sowad` | Build-time only: Bouncy Castle 1.80.2 is bundled inside Android's own lint tool (androidLintTool configuration of AGP 9.4.1). It is in no APK and no runtime classpath (verified by tools/tests/test_trivy_exceptions.py, BUG-2026-036). Clears with AGP upgrade. |
| `SEC-EXC-0002` | **HIGH** | `trivy` | `CVE-2026-13506` | `android/settings-gradle.lockfile` | 4d | `2026-11-26` (30d) | `2027-01-25` | `@sowad` | Build-time only: Bouncy Castle 1.80.2 is bundled inside Android's own lint tool (androidLintTool configuration of AGP 9.4.1). It is in no APK and no runtime classpath (verified by tools/tests/test_trivy_exceptions.py, BUG-2026-036). Clears with AGP upgrade. |
| `SEC-EXC-0005` | **MEDIUM** | `gitleaks` | `generic-api-key` | `android/core/security/src/test/resources/export/legacy-tink-1.7.0.properties` | 1d | `2027-01-28` (90d) | `2027-01-28` | `@sowad` | Synthetic throwaway X25519 test key in Tink 1.7.0 compatibility fixture (BUG-2026-083 item 27); protects no study, build, or server and is required to test backward-compatible archive decryption. |
| `SEC-EXC-0004` | **MEDIUM** | `bandit` | `B310` | `training/lock_dependencies.py` | 0d | `2027-01-29` (90d) | `2027-01-29` | `@sowad` | Offline lockfile maintenance utility fetches SHA-256 wheel digests from hardcoded https://pypi.org/pypi/<package>/<version>/json URLs over HTTPS only; never invoked at runtime or with untrusted user input. |
| `SEC-EXC-0003` | **LOW** | `bandit` | `B311` | `backend/app/cli.py` | 0d | `2027-01-29` (90d) | `2027-01-29` | `@sowad` | Deterministic per-participant date-shift PRNG (-180 to +180 days) seeded from the SHA-256 digest of the participant UUID during de-identified study CSV export (doc 7 Section 7, doc 8 Section 3). Not used for cryptographic key, nonce, or token generation. |

## 4. Policy & Guardrail Checks

- **Expired Exceptions:** 0
- **Schema / >90-Day Expiry Violations:** 0
- **Uncited Inline Suppressions:** 0
