# NeoGold

NeoGold is a free, open-source Android app (Kotlin, Jetpack Compose) that estimates newborn jaundice risk from a phone photo of the baby's chest next to a signed color card, fully offline. A small per-study backend (FastAPI, PostgreSQL 16, Caddy, Docker Compose) receives consented research uploads only.

**Intended use (PRD D11):** Decision support for trained health workers. The app suggests a next step; the health worker decides. It does not diagnose.

**Current release target (PRD D10):** v1.0 is a closed research build for the partner's validation study, distributed only on Play closed testing. The screening flow is built and tested but hidden (`triageEnabled: false` in configuration).

> **Important Security Notice:**
> Install only from Google Play; the signing certificate fingerprint is published here at release.

---

## How to Build

### Prerequisites
- JDK 21 (Temurin recommended)
- Android SDK (API 36 build tools, platform-tools, NDK)
- Python 3.11+
- Docker & Docker Compose (for backend testing/deployment)

### Android Build
Run tests and build debug or unsigned release artifacts:
```bash
./gradlew testReleaseUnitTest
./gradlew assembleRelease
```

### Backend Build & Tests
```bash
cd backend
pytest -q
```

### Traceability & Specification Checks
Verify requirement and test case coverage:
```bash
python3 tools/trace.py docs
```

---

## Quality, Guardrails & Verification Suite

NeoGold includes automated validation pipelines and agent quality guardrails (P23–P30):

### 1. Quality Gate Evaluation
Evaluate changed files across tiered quality guardrails:
```bash
python3 tools/quality_gate.py --base origin/main
```

### 2. Security Scanning (/ng-secscan)
Scan for hardcoded credentials, dependency vulnerabilities, and insecure patterns:
```bash
# PR incremental mode (working tree)
bash tools/secscan/run.sh --changed

# Full repository and git history scan
bash tools/secscan/run.sh --full
```

### 3. White-Box Breaker Attacks (/ng-break)
Run penetration fuzzing, network mutation tests, and boundary attacks against a disposable local backend:
```bash
python3 breaker/run_round.py all
```

### 4. End-to-End User Journey Benchmarks (/ng-journey)
Verify error-free session completion and performance budgets across the device matrix (D1–D4):
```bash
python3 tools/check_journeys.py --version 1.0.0
```

### 5. Post-Release Feedback Triage (/ng-feedback)
Ingest, redact PII, and classify user feedback into triage buckets:
```bash
python3 tools/feedback/triage.py
```

### 6. Commit Attribution Guard
Enforce no-AI-attribution compliance on git commit messages:
```bash
python3 tools/check_commit_attribution.py origin/main..HEAD
```

---

## Repository Layout

```
android/        Gradle project: :app, :feature:*, :engine:*, :core:*, :sync:research
backend/        Study Intake Service (FastAPI), Alembic migrations, admin CLI, deploy/
breaker/        White-box penetration test catalog, disposable stack, and attack runner
card/           Card v1 design files, print spec, neogold-sign tool
config/         triage-config.json, trust-list.json, schemas
docs/           Design specifications, PRD, architecture, security plan, runbooks
feedback/       Post-release feedback triage rules, classification buckets, and templates
reports/        UX walkthrough reports, journey benchmarks, security ledgers, assumptions logs
security/       Security debt ledger exceptions manifest (exceptions.yaml)
testdata/       golden/, bad/, triage/, api/, bench/ (synthetic and bench fixtures only)
tools/          Release gates, security scanners, journey runner, and attribution hooks
training/       Python training pipeline, dataset loaders, pinned environment
.claude/        Commands, agents, skills, and guardrail pre-tool hooks
```

---

## Licenses

- Code: [MIT License](LICENSE)
- Color Calibration Card design and specifications: [CC BY 4.0](card/LICENSE)
- Documentation: [CC BY 4.0](docs/LICENSE)
