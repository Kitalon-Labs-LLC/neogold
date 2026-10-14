---
description: "P05: Card Trust Engine and Signing Tool (NeoGold work package; plan first, then wait for approval)"
---
# P05: Card trust engine and the neogold-sign tool

Phase 1, Specs and engines. Depends on: P01, approved P02. Backlog: NG-039, NG-045. Tests: TC-CARD-01 to TC-CARD-06, TC-RSH-02, TC-RSH-03.

## Read first
- card/spec/qr-formats.md and its test vectors (from P02)
- docs/4_System_Architecture.md Section 7
- docs/8_Security_Compliance_Plan.md Section 4 (K4, K5, K10, trust list rules, offline signing machine)
- docs/7_API_Documentation.md Section 4.1

## Goal
Offline verification of card batches and study profiles on the phone, and the offline tool that signs them.

## Build
1. `:engine:cardtrust` (pure Kotlin, Tink for Ed25519 verification):
   - Parse card batch and study profile payloads per `qr-formats.md`; reject unknown format versions.
   - Verify signatures against the trust list, honoring key purpose, validity dates, and revocation lists.
   - Return typed results: `Verified`, `Expired`, `Revoked`, `UnknownIssuer`, `WrongPurpose`, `BadSignature`, `Malformed`, with the batch reference values when verified.
2. `card/tools/neogold-sign` (Kotlin JVM CLI or Python; pick one and justify): `keygen`, `card-batch`, `study-profile`, `verify`. It refuses to run if the machine has a network interface up, unless `--i-understand-this-is-online` is passed for tests. Every signature appends a line to a signing log file.
3. `config/trust-list.json` containing only test keys for now, plus a test that fails a release build if any `TEST_ONLY` key is present (wire into the release workflow later in P16).

## Tests
- TC-CARD-01 to TC-CARD-06 and TC-RSH-02, TC-RSH-03, using the P02 test vectors.
- Cross-implementation: payloads signed by `neogold-sign` verify in `:engine:cardtrust`, and the reverse fails for tampered bytes.
- Fuzz the parser with random bytes: no crash, always `Malformed`.

## Done when
- `./gradlew :engine:cardtrust:test` passes and the CLI round trip test passes in CI.
- privacy-security-reviewer subagent has reviewed the diff.

## Stop and ask me if
- The payload does not fit the QR version chosen in P02.
