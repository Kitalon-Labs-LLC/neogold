---
description: "P11: Local Data Layer (NeoGold work package; plan first, then wait for approval)"
---
# P11: Local data layer (Room on SQLCipher, Tink files, retention)

Phase 4, Data and security. Depends on: P01. Backlog: NG-023 (storage), NG-034, NG-035, NG-057 (storage). Tests: TC-LOG-04, TC-LOG-05, TC-LOG-07, TC-LOG-11, TC-SEC-07, TC-SEC-08, TC-REL-08.

## Read first
- docs/5_Database_Schema.md Section 2 (all) and Section 5
- docs/8_Security_Compliance_Plan.md Sections 4 (K7, K8), 5.1, 6.1 (backup rows)
- docs/2_PRD.md Section 11, D7, D8, D12

## Goal
Every on-device table in doc 5 Section 2, encrypted at rest, with retention and deletion that cannot be skipped, and a database that survives crashes and Keystore loss without corrupting or leaking.

## Build
1. `:core:security`: Keystore AES-256-GCM master keys (StrongBox if present, TEE otherwise, no user-auth requirement), raw 256-bit SQLCipher key wrapped by Keystore and passed in the `x'<hex>'` form, Tink `StreamingAead` keyset wrapped by a separate Keystore key, Keystore-loss detection with a typed error.
2. `:core:data`: Room on SQLCipher with every table, column, index, and constraint in doc 5 Section 2 (including `guardian_consent_confirmed`, outbox `revision` and `dependency_requeues`, card batch `qr_payload` and `server_status`, research study `time_zone` and `data_generation`); `exportSchema = true`; DataStore keys from doc 5 Section 2.4.
3. Encrypted file store for research images: write to temp, `fsync`, rename; never a plaintext file.
4. Retention jobs (doc 5 Section 2.5): register number erasure at 60 days, screening purge at 180 days (30 to 365), abandoned sessions, research local retention with the server confirmation hook left as an interface for P20; run daily via WorkManager and on every app start.
5. Delete all data (FR-33): database, files, keys; recreate empty.
6. Keystore loss path: clear message, reset stores, keep working (doc 8 Section 5.1).

## Tests
- The listed TC IDs; plus migration test harness ready for future versions (`MigrationTestHelper`), and a crash-during-write test (kill the process 50 times during research saves on an emulator, zero partial rows).

## Done when
- Schema v1 exported and committed.
- privacy-security-reviewer subagent has reviewed the diff.

## Stop and ask me
- **At the end:** schema v1 is a review gate, because it can only migrate forward once study phones install it.
