---
description: "P19: Deployment Stack and Backend CI (NeoGold work package; plan first, then wait for approval)"
---
# P19: Deployment stack and backend CI

Phase 8, Backend. Depends on: P18. Backlog: NG-042. Tests: TC-OPS-04, TC-OPS-05, TC-OPS-11, TC-SEC-11 to TC-SEC-13.

## Read first
- docs/12_Deployment_Runbook.md Sections 7 and 8 and Appendices C and D
- docs/8_Security_Compliance_Plan.md Sections 5.4, 8
- backend/deploy/ (validated templates from the starter kit)

## Goal
The backend runs on the staging droplet exactly as the runbook says, deploys and rolls back by digest, and restores from backup without undoing admin actions.

## Build
1. `backend/Dockerfile`: non-root user 10001, read-only friendly, `neogold-admin` on the path, healthcheck endpoint.
2. Use `backend/deploy/` as given (compose, Caddyfile, Caddy Dockerfile, `neogold-deploy`, `smoke.sh`, `neogold-backup`, `neogold-bootstrap`, `neogold-admin` wrapper, systemd units). Change them only to fix a real defect, and re-run ShellCheck, `docker compose config`, and `caddy validate` after any change.
3. `backend-ci.yml` with the `schema-compat` job, image build with SBOM and provenance, and the secret-free scan job.
4. `docs/test-reports/staging-setup.md`: the checklist I follow on the droplet (doc 12 Section 7.2), with commands to copy.

## Tests
- TC-OPS-04 automated in CI (schema-compat) and as a staging procedure; TC-OPS-05 restore drill steps; TC-OPS-11 first-time setup; TC-SEC-11 to 13 as a staging checklist with expected outputs.

## Done when
- CI builds and scans the image; I complete the staging checklist with your instructions and the smoke test passes.

## Stop and ask me
- Before anything touches the droplet. You prepare; I run the commands.
