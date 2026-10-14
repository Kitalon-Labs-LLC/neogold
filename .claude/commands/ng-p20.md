---
description: "P20: Research Sync Client and Enrollment (NeoGold work package; plan first, then wait for approval)"
---
# P20: Research sync client and study server enrollment

Phase 9, Sync. Depends on: P14, P17 (a running staging server for the E2E cases). Backlog: NG-042. Tests: TC-SYNC-01 to TC-SYNC-20, TC-API-07.

## Read first
- docs/7_API_Documentation.md Sections 3, 4, 5, 6, 11 (all)
- docs/api/openapi.yaml
- docs/5_Database_Schema.md outbox, research_study, card_batch tables
- docs/6_UI_UX_Guidelines.md R07, R08, R09 and the `upload_*`, `enroll_*` strings

## Goal
Research data reaches the study server without loss, duplication, or wasted bandwidth, on phones whose battery managers kill background work.

## Build
1. `:sync:research` only (the one module allowed an HTTP client): OkHttp, kotlinx.serialization, request bytes serialized once and hashed for `bsh`.
2. Device key in Android Keystore (P-256, StrongBox if present), attestation challenge from the enrollment code, DER to JOSE signature conversion, per-request ES256 JWT, clock offset from server time with one retry on `CLOCK_SKEW` or `AUTH_EXPIRED`.
3. Enrollment R08 (signed study QR, code, settings check against the profile) and Leave study R09 (delete only the device key and `server_device_id`).
4. Outbox processor: order (`GET /device` first, batch, participant, manifest, images, TSB), idempotency keys, the retry table in doc 7 Section 5.2, dependency re-queue cap of 3, card-batch rejection to `UNVERIFIED`, unknown codes by `retryable`, client-bug re-queue after an app update.
5. One unique WorkManager work; Upload now replaces a waiting run with an expedited one; foreground worker with `dataSync` type and `FOREGROUND_SERVICE_DATA_SYNC` on API 34 and up; `IN_FLIGHT` reset on start; Wi-Fi only.
6. Reconciliation before local deletion and on a `data_generation` change: re-send every held item (doc 7 Section 5.4).
7. Contract tests: every request model validated against `openapi.yaml`; every spec example deserializes.

## Tests
- TC-SYNC-01 to TC-SYNC-20 (MockWebServer for most; staging for 12, 14, 15, 17, 19); TC-API-07.

## Done when
- A full study day (5 participants, 20 sessions, 120 images, linked TSB values) uploads from D1 to staging over a throttled link with zero loss and zero duplicates.
- privacy-security-reviewer subagent has reviewed the diff.
