# Spike: DeepSec trial on backend/ and :sync:research (deepsec-trial)

| Field | Value |
|---|---|
| Date | 2026-10-31 |
| Author | Security scan session (P26) |
| Work package / Context | P26 (`/ng-secscan` Step 3) |
| Verdict | Refuted (0 real issues found that Step 1 scanners missed; Kotlin has no built-in Stage-1 rules, LLM Stage-2 only) — Recommendation: **reject** for `--full` |

---

## 1. Claim and Source
- **Claim 1:** "DeepSec finds at least one real issue in `backend/` that Bandit and Ruff miss (P26) [Guessing]."
- **Claim 2:** "Kotlin support is unconfirmed [Guessing], so report Kotlin findings separately."
- **Source:** [docs/16_Agent_Quality_Prompts.md](file:///d:/Project/neogold-android/docs/16_Agent_Quality_Prompts.md#L97) (Section 4, item 4) and [docs/16_Agent_Quality_Prompts.md](file:///d:/Project/neogold-android/docs/16_Agent_Quality_Prompts.md#L380) (Section 5, P26 Build item 3d).
- **Confidence Tag:** `[Guessing]`

## 2. Question
- **Yes/No Question:**
  1. When run on `backend/` (21 Python files) and `android/sync/research/` (`:sync:research`, 16 Kotlin files) with `process --limit 50` and `--max-cost-usd 5.00`, does DeepSec (`vercel-labs/deepsec` `0.1.0`) identify at least one real, unmitigated vulnerability that Step 1 scanners (`gitleaks`, `osv-scanner`, `trivy`, `ruff`, `bandit`, `detekt`, `Android Lint`, `MobSF`, `schemathesis`) missed?
  2. Does DeepSec provide built-in static heuristic matchers for Kotlin, or only LLM text inspection?
- **Measurable Pass Condition:** At least 1 verified true-positive vulnerability in `backend/` or `:sync:research` confirmed by code-path inspection that was not already flagged by Step 1 scanners (`bandit`, `ruff`, `detekt`, `Android Lint`, `trivy`, `gitleaks`).

## 3. Method and Exact Commands
- **Environment & Target:** Isolated throwaway worktree cut from `HEAD`, scoping analysis to `backend/app/` (21 `.py` files, 3,142 LOC) and `android/sync/research/src/main/kotlin/` (16 `.kt` files, 1,984 LOC) — 37 source files total (`<= 50` file limit for `process --limit 50`).
- **Isolation:** Throwaway worktree `../neogold-spike-deepsec-trial` (deleted after collection; zero changes to `android/`, `backend/`, `training/`, or `config/`).
- **Exact Commands Run:**
  ```bash
  git worktree add ../neogold-spike-deepsec-trial HEAD
  cd ../neogold-spike-deepsec-trial

  # Step 1 baseline scan for comparison
  bash tools/secscan/run.sh --full --date 2026-10-31

  # DeepSec 4-stage pipeline trial (init -> scan -> process --limit 50 -> revalidate)
  npx deepsec@0.1.0 init --max-cost-usd 5.00 --max-duration 30m
  npx deepsec@0.1.0 scan backend/app android/sync/research/src/main/kotlin
  npx deepsec@0.1.0 process --limit 50 --max-cost-usd 5.00
  npx deepsec@0.1.0 revalidate

  cd ../neogold-android
  git worktree remove ../neogold-spike-deepsec-trial --force
  ```

## 4. Raw Results

### 4.1 Python Findings (`backend/app/`, 21 files)

| ID | Candidate Location | Finding Raised by DeepSec | Code-Path Revalidation (`revalidate` + Manual Trace) | Held Up? | Caught by Step 1 Scanners? |
| :--- | :--- | :--- | :--- | :---: | :---: |
| **DS-PY-01** | `backend/app/cli.py:620` | Use of standard `random.Random(seed_val)` PRNG in `export_study` | Used solely for deterministic per-participant date shift (`-180` to `+180` days) seeded by `sha256(str(p.id))`, not for cryptographic keys, nonces, or tokens (`doc 7` §7, `doc 8` §3). | **No** (Intentional non-crypto PRNG) | **Yes** (`bandit:B311` and `ruff:S311`, tracked as `SEC-EXC-0003`) |
| **DS-PY-02** | `backend/app/routers/sessions.py:374` | Potential filesystem path traversal in `store_dir / study.study_code / site.site_code / str(session_id) / f"{capture_id}.jpg"` | `session_id` and `capture_id` are strictly typed `uuid.UUID` parameters (`sessions.py:312-313`), and `study_code` / `site_code` come from admin-seeded DB rows validated by `^[A-Z0-9_-]{2,32}$` (`cli.py`). Path traversal is unreachable. | **No** (False positive) | N/A (Unreachable) |
| **DS-PY-03** | `backend/app/middleware/auth.py:275-280` | Potential race condition (TOCTOU) between `select(AuthNonce)` and `db.add(AuthNonce)` on concurrent replay of the same `jti` | `auth_nonce.jti` is the PostgreSQL primary key (`backend/app/models/db.py:86`); a concurrent duplicate insert raises `IntegrityError` and aborts the transaction before reaching the route handler. | **No** (Mitigated by DB PK constraint) | Covered by `TC-API-08` & `T8` agent review |

### 4.2 Kotlin Findings (`:sync:research` / `android/sync/research/`, 16 files — Reported Separately per P26 3d)

- **Kotlin Language Support Verification:**
  - DeepSec `0.1.0`'s Stage 1 (`scan`) has **no built-in Kotlin or Android heuristic rules** (its built-in framework/sink regex matchers target JavaScript/TypeScript and Python web stacks).
  - Stage 2 (`process --limit 50`) is language-agnostic because the underlying LLM agent reads raw `.kt` source files directly once included in the candidate set.

| ID | Candidate Location | Finding Raised on Kotlin (`:sync:research`) | Code-Path Revalidation (`revalidate` + Manual Trace) | Held Up? | Caught by Step 1 Scanners? |
| :--- | :--- | :--- | :--- | :---: | :---: |
| **DS-KT-01** | `ResearchApiClient.kt:38-46` | `apiBaseUrl(baseUrl)` does not reject `http://` schemes in string normalization | In release builds, Android's `network_security_config.xml` sets `cleartextTrafficPermitted="false"` (`AndroidManifest.xml` also sets `usesCleartextTraffic="false"`), so OkHttp refuses any `http://` connection at the platform TLS layer (`TC-SEC-04`). | **No** (Mitigated by platform network security config) | **Yes** (Checked by `tools/check_network_config.py`, `Android Lint`, and `MobSF` in Step 1) |
| **DS-KT-02** | `JoseSignatureConverter.kt:18-62` | ASN.1 DER-to-JOSE (`R \|\| S`) signature parser examined for out-of-bounds slice on malformed DER | Input comes exclusively from Android Keystore `SHA256withECDSA` hardware/software signer, and parser explicitly validates tag `0x30`, total length, integer tags `0x02`, and component lengths (`<= 33` bytes) before slicing (`TC-SYNC-02`). | **No** (False positive) | Covered by `detekt` + `JoseSignatureConverterTest` |

## 5. Verdict
**Refuted** for Claim 1 (DeepSec found 0 real issues in `backend/` or `:sync:research` that Step 1 scanners missed); **Confirmed (Partial — LLM-only, no Stage-1 Kotlin rules)** for Claim 2.

- **Recommendation for `/ng-secscan --full` (P26 Rule 3c):** **`reject`** (do not add DeepSec to `tools/secscan/run.sh --full`).
- *Rationale:* P26 states: *"Add it to `--full` only if it found at least one real issue the other tools missed."* Across all 37 source files in `backend/app/` and `:sync:research`, DeepSec raised 3 Python candidates and 2 Kotlin candidates; 0 held up as real, unmitigated vulnerabilities missed by Step 1 scanners (`bandit` and `ruff` already caught `DS-PY-01` at zero token cost). Excluding DeepSec from `run.sh --full` keeps CI deterministic, offline-capable, and free of external LLM API keys while the `privacy-security-reviewer` subagent (`T1`–`T17`) covers semantic design review in `/ng-secscan`.

## 6. What the Verdict Changes
- **Architectural / Code Impact:** `tools/secscan/run.sh --full` does not invoke `deepsec`; `/ng-secscan` relies on the deterministic Step 1 scanner suite plus the Step 2 `privacy-security-reviewer` (`T1`–`T17`) subagent pass.
- **Risk Impact:** Zero security coverage loss; avoids sending repository source code to external third-party LLM APIs during automated CI workflow runs and avoids recurring token spend.
- **Next Actions:** Keep `deepsec_trial=0.1.0` documented in `tools/secscan/versions.txt` for provenance and reference this spike in `docs/16_Agent_Quality_Prompts.md`.

## 7. Affected Doc Lines
- [docs/16_Agent_Quality_Prompts.md](file:///d:/Project/neogold-android/docs/16_Agent_Quality_Prompts.md#L97): Update `[Guessing]` on candidate spike #4 to `[Certain — Refuted: 0 issues missed by Bandit/Ruff; see docs/spikes/2026-10-31-deepsec-trial.md]`.
- [docs/16_Agent_Quality_Prompts.md](file:///d:/Project/neogold-android/docs/16_Agent_Quality_Prompts.md#L380): Update `[Guessing]` on Kotlin support to `[Certain — Stage-1 has no built-in Kotlin rules; Stage-2 LLM reads .kt files; see docs/spikes/2026-10-31-deepsec-trial.md]`.

