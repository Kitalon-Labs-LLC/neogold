---
description: "P17: Study Intake API Core (NeoGold work package; plan first, then wait for approval)"
---
# P17: Study Intake API core (backend)

Phase 8, Backend. Depends on: P00. Backlog: NG-042. Tests: TC-API-01 to TC-API-16, TC-API-25, TC-API-31.

## Read first
- docs/7_API_Documentation.md Sections 0 to 9 and 12 (all of them, carefully)
- docs/api/openapi.yaml (source of truth)
- docs/5_Database_Schema.md Section 3
- docs/8_Security_Compliance_Plan.md Sections 7 and 8.3

## Goal
The FastAPI service that enrolls phones and accepts their signed requests exactly as doc 7 specifies, with tests for every authentication and idempotency rule.

## Build
1. `backend/`: Python 3.12, FastAPI, Pydantic v2 models generated or hand-written from `openapi.yaml` with `extra="forbid"`, SQLAlchemy 2, Alembic migrations reproducing doc 5 Section 3 DDL exactly (no `pgcrypto`), locked dependencies with hashes.
2. ASGI middleware recording t0 at header arrival; device assertion verification in the exact order of doc 7 Section 4.3 Phase A, with study-status and app-version checks skipped for `GET /device`, `GET /sessions/{id}`, and `DELETE /device`; `aud` and `htu` from a configured `PUBLIC_BASE_URL`; `auth_nonce` with `expires_at = exp + 30 s`.
3. Idempotency on `(device_id, key)` with an `IN_PROGRESS` row before processing and only final outcomes stored (doc 7 Section 5.1); enrollment idempotency on the `enrollment_code` row; atomic code consumption.
4. Endpoints: `/health`, `/enrollments` (attestation challenge = SHA-256 of the code; leaf key must match), `/device` GET and DELETE, `/card-batches/{id}`, `/participants/{id}` with aliases.
5. RFC 9457 problem responses with the error catalog in doc 7 Section 6.2; no stack traces or body content in responses.
6. Token-bucket rate limits (20 burst, 1 per second per device; 10 failed enrollments per hour per client IP using `X-Forwarded-For` trusted only from `FORWARDED_ALLOW_IPS`).
7. `backend/tools/diff_spec.py`: compares FastAPI's generated schema with `openapi.yaml` and fails on any difference in paths, status codes, or schemas.

## Tests
- pytest with Testcontainers PostgreSQL 16: the listed TC IDs, including the ten authentication cases in doc 7 Section 12.3 (port them from the prototype behavior described there).

## Done when
- `pytest -q` and `diff_spec.py` pass in `backend-ci.yml` (enable it from `docs/ci-templates/`).
- privacy-security-reviewer subagent has reviewed the diff.

## Stop and ask me if
- Any part of `openapi.yaml` is ambiguous or contradicts doc 7 prose. Report it; do not guess.
