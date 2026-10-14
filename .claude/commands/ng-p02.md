---
description: "P02: Card v1 and QR Format Specs (NeoGold work package; plan first, then wait for approval)"
---
# P02: Card v1 design and QR format specifications

Phase 1, Specs and engines. Depends on: P00. Backlog: NG-006, NG-045. This package writes specifications and a generator; it ends at a review gate.

## Read first
- docs/2_PRD.md Section 9a (card strategy and design rules)
- docs/4_System_Architecture.md Sections 5.2, 5.3, 5.4, 7
- docs/7_API_Documentation.md Section 4.1 (signed study profile)
- docs/8_Security_Compliance_Plan.md Sections 4 (keys K4, K5, K10) and 5.2

## Goal
The two specs the document set does not yet contain, precise enough that the vision engine, the card trust engine, the signing tool, and a print shop can all work from them.

## Build
1. `card/spec/card-v1.md`:
   - Physical size (propose a size that fits a newborn's chest and a phone frame at 15 to 25 cm), material, matte lamination, punched skin window size and position.
   - Four corner markers: family (ArUco 4x4 or AprilTag 36h11; list both options with size impact), IDs, size, quiet zone.
   - Patch set of 16 to 24 patches: a neutral gray ramp (at least 6 steps), skin and yellow tones covering light to dark skin with low to high yellowness, and a few saturated references. Duplicate gray and skin patches in opposite corners (doc 4 Section 5.2). Give each patch an ID, nominal CIELAB (D65) target, and position in card millimetres.
   - QR position and size, print tolerances, and the batch acceptance tolerance (in Delta E 2000) that TC-CARD-10 will test.
   - The canonical warped image size (doc 4 suggests 1200 by 760 px) and each patch's and the window's sampling rectangle in that image.
2. `card/spec/qr-formats.md`:
   - **Card batch payload:** format version, batch ID (pattern from `docs/api/openapi.yaml`), issuer key ID, issue and expiry dates, per-patch measured CIELAB in a compact fixed-point encoding, Ed25519 signature over all preceding bytes. Specify the exact byte layout, endianness, and the QR mode and error-correction level, and show that it fits the chosen QR version.
   - **Study profile payload:** the fields in doc 7 Section 4.1 (`study_code`, `display_name`, `partner`, `host` or none, `timestamp_precision`, `export_public_key`, `expires_at`), key purpose `STUDY_ISSUER`, Ed25519 signature.
   - **Trust list** (`config/trust-list.json`): key IDs, public keys, purposes (`CARD_ISSUER`, `STUDY_ISSUER`), validity dates, revoked batch IDs and study codes, and its own JSON Schema.
   - Test vectors: one valid payload of each kind with a test key (clearly marked test-only), and three invalid ones each.
3. `card/generator/`: a small script (Python, no network) that renders card v1 as SVG and print-ready PDF from `card-v1.md` data in a machine-readable form (`card/spec/card-v1.json`), with a placeholder QR. Commit one generated SVG for review.

## Constraints
- No Android code. No real keys; test keys are generated in tests and named `TEST_ONLY_*`.
- Use only Ed25519 for signatures (Tink-compatible), as doc 4 Section 4.3 says.

## Done when
- Both specs, the JSON schema, the test vectors, and the generated SVG are in the PR.
- The PR description includes a one-page summary I can send to the print partner.

## Stop and ask me
- **Always, at the end:** this package ends at a review gate. Do not start P05 or P08 until I approve the specs.
- Before choosing between ArUco and AprilTag if the size difference matters for the card layout.
