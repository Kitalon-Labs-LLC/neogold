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
