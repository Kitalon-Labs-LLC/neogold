# NeoGold Clinical & Operational Configuration

This directory contains versioned configuration files, JSON schemas, and trust anchors for the app.

## Contents
- `triage-config.json`: Canonical triage thresholds, gestational age bands, and decision rules (signed by the clinical lead)
- `trust-list.json`: Embedded public keys of trusted card issuers and study instances
- JSON Schemas: Validation schemas for triage config, trust lists, and model definitions

## Work Packages
- **P03**: Triage engine, signed configuration verification, and schema validation
- **P05**: Trust list integration and card trust rules
