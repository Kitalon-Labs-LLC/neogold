# NeoGold: Review Gate Approval Records (draft)

| Field | Value |
|---|---|
| Document | Approval records for the review gates of doc 14 Section 4 (action A18 of the 26 October 2026 direction check) |
| Status | **DRAFT. Nothing below is approved.** The `Decision`, `Approver` and `Date` cells are empty on purpose: only the person named under Approver fills them, in a commit of their own |
| Prepared | 30 October 2026, in package D1, from the repository as it is at that date |

Each record names what is being approved, where it is, and the evidence in the repository. The evidence is what the code and tests show; it is not the approval. A rejection is as valid as an approval and goes in the same cell with the reason.

---

## G-P02: Card layout and QR formats

| Field | Value |
|---|---|
| What | Card v1 layout (patch positions, marker IDs, reference values) and the three QR payload formats (card batch, study profile, signature framing) |
| Where | `card/spec/card-v1.md`, `card/spec/card-v1.json`, `card/spec/qr-formats.md`, `card/spec/testvectors/` |
| Evidence | `TC-CARD-01` to `TC-CARD-09` pass against the test vectors (`:engine:cardtrust`, `card/tests`); the app reads both QR kinds from camera frames (C1) and the vectors round-trip |
| Not shown | A printed card read by the app (`TC-CARD-10`, `TC-CAP-17` on printed samples) needs the owner's bench work |
| Decision | |
| Approver | |
| Date | |

## G-P03: Config schema

| Field | Value |
|---|---|
| What | The structure of `config/triage-config.json` that the clinical lead signs (gestational bands, thresholds, quality limits, versions) |
| Where | `config/schema/triage-config.schema.json`, `config/triage-config.json`, `config/README.md` |
| Evidence | `verifyConfigSchema` passes in CI; `TC-TRI-*` pass against the configured values; the file is protected by `tools/verify_clinical_signoff.py` (Gate G1) |
| Not shown | The clinical values themselves are not approved here. They need the signed `CLINICAL_SIGNOFF.md` (PRD 9b), which this repository does not hold |
| Decision | |
| Approver | |
| Date | |

## G-P06: Model format

| Field | Value |
|---|---|
| What | The shape of `model.json` (ridge coefficients, standardization, split-conformal quantile, multi-site weights) that the training pipeline writes and the app reads |
| Where | `config/schema/model.schema.json`, `config/model.json` (synthetic until the real model is trained), `testdata/models/model.synthetic.json` |
| Evidence | `TC-EST-01` to `TC-EST-07` pass; since D1 the golden vectors are compared with the committed files and a changed value fails (BUG-2026-045); `make reproduce` covers the pipeline (`TC-OPS-07`) |
| Not shown | A model trained on real paired data does not exist yet; the bundled model is synthetic |
| Decision | |
| Approver | |
| Date | |

## G-P08: Marker library

| Field | Value |
|---|---|
| What | OpenCV ArUco 4x4 as the marker detector, behind the `MarkerDetector` interface, with the pure Kotlin detector as the JVM reference |
| Where | `docs/4_System_Architecture.md` Section 4.3 and ADR-018 notes, `:engine:vision` |
| Evidence | Measured on a release bundle on 27 October 2026 (download 13.96 MiB arm64-v8a, 11.82 armeabi-v7a, against 25; install 34.28 against 40; 64-bit libraries 16 KB aligned); 10 on-device tests pass on an emulator; both reviewers ran, no HIGH. The decision itself is recorded in doc 14 Section 4 as "OpenCV (ArUco 4x4), decided by the measured size on both ABIs (ADR-018, 27 October 2026)" |
| Not shown | Any ARM phone, speed on a phone, printed cards, the minified release build running OpenCV (BUG-2026-054 to 057 are open) |
| Decision | |
| Approver | |
| Date | |

## G-P11: Database schema v1

| Field | Value |
|---|---|
| What | The on-device schema (Room version 2) that can only migrate forward once real study phones install it |
| Where | `docs/5_Database_Schema.md`, `android/core/data/schemas/.../1.json`, `2.json` |
| Evidence | `tools/check_migrations.py` passes (schemas immutable); migration tests; SQLCipher with a Keystore-held key opens and closes on an emulator (B1 instrumented suite, 7 of 7); no field holds a name, birth date, phone number or free text (`TC-PRIV-03`) |
| Not shown | An upgrade from a build that real phones hold: there is none yet. Approve before the first closed-testing build installs |
| Decision | |
| Approver | |
| Date | |

## G-P16 / G-P22: Release

| Field | Value |
|---|---|
| What | Go or no-go for the closed research build with the second approver |
| Evidence | Not ready. Open items are listed in `reports/direction/` and doc 11; the human-gated list is in the build plan, package G (real issuer and study keys, upload keystore, Play account, clinical approvals, device matrix runs, nurse panel) |
| Decision | |
| Approver | |
| Date | |
