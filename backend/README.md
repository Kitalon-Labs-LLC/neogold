# NeoGold Study Intake Backend

This directory contains the per-study research backend service:
- FastAPI study intake REST API
- PostgreSQL 16 database migrations (Alembic)
- Study coordinator CLI (`neogold-admin`)
- Deployment infrastructure (`deploy/`: Docker Compose, Caddy, backup timers, bootstrap scripts)

## Work Packages
- **P17**: Study Intake API core, device enrollment, authentication, and database schemas
- **P18**: Research image intake, coordinator CLI, audit logging, and de-identified export
- **P19**: Deployment stack, staging server configuration, and backend CI

## Container & Deployment Stack
- **Image build**:
  ```bash
  docker build --build-context repo-config=config -t ghcr.io/kitalon-labs-llc/neogold-intake:dev backend
  ```
- **Deploy templates validation**:
  ```bash
  backend/tools/validate_deploy.sh
  ```
- **Runtime lock synchronization**:
  ```bash
  python backend/tools/check_runtime_lock.py backend/requirements-runtime.lock backend/requirements.lock
  ```
- **Staging Setup Runbook**:
  See `docs/test-reports/staging-setup.md` for step-by-step droplet provisioning and operational test procedures (TC-OPS-04, TC-OPS-05, TC-OPS-11, TC-SEC-11 to 13).

