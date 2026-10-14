# NeoGold

NeoGold is a free, open-source Android app (Kotlin, Jetpack Compose) that estimates newborn jaundice risk from a phone photo of the baby's chest next to a signed color card, fully offline. A small per-study backend (FastAPI, PostgreSQL 16, Caddy, Docker Compose) receives consented research uploads only.

**Intended use (PRD D11):** decision support for trained health workers. The app suggests a next step; the health worker decides. It does not diagnose.

**Current release target (PRD D10):** v1.0 is a closed research build for the partner's validation study, distributed only on Play closed testing. The screening flow is built and tested but hidden (`triageEnabled: false` in the config).

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

## Repository Layout

```
android/        Gradle project: :app, :feature:*, :engine:*, :core:*, :sync:research
backend/        Study Intake Service (FastAPI), Alembic migrations, admin CLI, deploy/
training/       Python training pipeline, pinned environment
card/           Card v1 design files, print spec, neogold-sign tool
config/         triage-config.json, trust-list.json, schemas
testdata/       golden/, bad/, triage/, api/, bench/ (synthetic and bench data only)
tools/          release gates and checks (doc 12 Appendix B)
docs/           the design documents; docs/api/openapi.yaml; docs/ci-templates/
.claude/        commands/ (one per work package), agents/ (reviewers)
```

---

## Licenses

- Code: [MIT License](LICENSE)
- Color Calibration Card design and specifications: [CC BY 4.0](card/LICENSE)
- Documentation: [CC BY 4.0](docs/LICENSE)
