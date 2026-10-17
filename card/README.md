# NeoGold Color Calibration Card

This directory contains the physical card v1 design, specifications, generator, and test vectors.

## Contents
- **`spec/card-v1.md`**: Card v1 physical specification (120 × 76 mm, 23 color patches, ArUco 4x4 fiducials, 24 × 24 mm punched skin window, print and batch tolerances).
- **`spec/card-v1.json`**: Machine-readable specification (coordinates in mm and canonical 1200 × 760 px space, nominal CIELAB D65 targets, sampling rectangles).
- **`spec/qr-formats.md`**: QR binary payload specifications (card batch and study profile), Ed25519 digital signature scheme, trust list rules, and QR version capacity.
- **`spec/testvectors/`**: Deterministic test keys and test vectors for valid and invalid payloads (tampered, unknown issuer, wrong purpose, expired, revoked).
- **`generator/render_card.py`**: Python script (standard library only) that renders card v1 as SVG and print-ready vector PDF from `card-v1.json`.
- **`generator/qrenc.py`**: Minimal byte-mode QR code encoder (standard library only).
- **`generator/out/`**: Rendered outputs (`card-v1.svg`, `card-v1.pdf`).
- **`tests/`**: Unit tests verifying card geometry, QR encoder, payload test vectors, and trust list schema.
- **`PR_SUMMARY.md`**: One-page technical summary ready to send to print partners.

## Quick Commands
```bash
# Render card SVG and print-ready PDF
python card/generator/render_card.py

# Run card tests (requires pytest, cryptography, jsonschema from requirements-dev.txt)
pytest card/tests
```

## Work Packages
- **P02**: Card v1 layout, physical patch positions, markers, and QR code format specifications (Complete)
- **P05**: Card trust engine, batch generation, and `neogold-sign` tool

