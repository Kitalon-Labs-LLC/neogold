---
description: "P18: Intake Images, Admin CLI, Export (NeoGold work package; plan first, then wait for approval)"
---
# P18: Intake images, session completion, admin CLI, and export

Phase 8, Backend. Depends on: P17. Backlog: NG-042. Tests: TC-API-17 to TC-API-24, TC-API-26 to TC-API-30, TC-API-32, TC-API-33.

## Read first
- docs/7_API_Documentation.md Sections 3, 5.3, 5.4, 7, 8, 10
- docs/5_Database_Schema.md Sections 3.4 to 3.6
- docs/8_Security_Compliance_Plan.md Sections 8.3, 11

## Goal
Manifest-first sessions, verified images, safe completion under concurrency, and every admin command the runbook relies on.

## Build
1. `PUT /sessions/{id}` manifest (card status rules, `has_image`, local-date fields, semantic validation in doc 7 Section 7) and `GET /sessions/{id}`.
2. `PUT .../image`: header-phase checks before the body (session exists, capture listed, declared digest equals manifest hash, size), streaming hash, JPEG segment allow-list, Pillow pixel limit, write to `/data/images/.tmp` then `fsync` and rename, session row locked `FOR UPDATE` before counting missing images, a nightly backstop job.
3. `PUT /tsb-measurements/{id}` with the one allowed null-to-session link.
4. Day-precision server dates only; no per-session audit events.
5. `neogold-admin` (inside the container): every command in doc 7 Section 10, including `site add --test`, `participant withdraw`, `restore mark` (Unix time), `audit since`, `selftest` (with `NEOGOLD_ALLOW_NEWER_SCHEMA`), `export` (excludes test sites and withdrawn participants, per-participant date shift, SHA-256 manifest), `retention check` and `purge` using the `neogold_purge` role. Each writes an `ADMIN` audit row.

## Tests
- The listed TC IDs, including TC-API-18 (100 concurrent last-image races) and TC-API-21 (decompression bomb).

## Done when
- All backend tests pass; Schemathesis run against a local stack shows no 5XX.
- privacy-security-reviewer subagent has reviewed the diff.
