---
description: "P15: Encrypted Export and Decrypt Tool (NeoGold work package; plan first, then wait for approval)"
---
# P15: Encrypted export and the decrypt tool

Phase 6, Research. Depends on: P14. Backlog: NG-036. Tests: TC-EXP-01 to TC-EXP-04.

## Read first
- docs/8_Security_Compliance_Plan.md Section 5.2 and key K10
- docs/7_API_Documentation.md Sections 7 and 10 (`decrypt-export`)
- docs/2_PRD.md FR-30

## Goal
A study can run with no server: the phone writes an export that only the study team can read, and a tool on the coordinator's own machine decrypts it with per-participant date shifting.

## Build
1. Export format: a ZIP containing per-file Tink streaming-AEAD ciphertext, a header with the data key wrapped by Tink HPKE (X25519, HKDF-SHA256, AES-256-GCM) to the study's export public key from the signed profile, a CSV schema version, and a SHA-256 manifest. Document it in `docs/specs/export-format.md`.
2. Phone side: export through the Storage Access Framework picker; no identifiers beyond participant code; progress and resume for large exports.
3. `tools/neogold-decrypt/` (Python or Kotlin JVM CLI, runs offline on the coordinator's machine): decrypts, verifies hashes, applies one random date offset per participant within plus or minus 180 days by default (`--no-date-shift` requires an explicit flag and prints a warning), writes CSV and images.

## Tests
- TC-EXP-01 to TC-EXP-04; a tampered ciphertext fails with a clear error; a wrong key fails.

## Done when
- Round trip from phone export to decrypted CSV in CI with synthetic data.
- privacy-security-reviewer subagent has reviewed the diff.

## Stop and ask me if
- Tink HPKE is not available in the Tink Android version we use; propose the alternative before writing it.
