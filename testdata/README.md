# NeoGold Test Data

This directory contains synthetic, simulated, and bench test fixtures.

> **CRITICAL PRIVACY & SAFETY RULE:**
> Real newborn patient data NEVER enters this repository, test suites, logs, screenshots, or issues. Only synthetic or laboratory bench data is stored here.

## Directories
- `golden/`: Golden image vectors and expected pipeline stage outputs (color correction, feature values, estimation)
- `bad/`: Edge cases, corrupted, blurred, poorly illuminated, and out-of-bounds test images for input qualification
- `triage/`: Synthetic clinical scenario fixtures for differential triage testing
- `api/`: Fixture payloads for Study Intake API testing
- `bench/`: Laboratory spectrophotometer measurements and phantom calibration data

## Work Packages
- **P03**: Triage synthetic test fixtures
- **P04**: Color science bench fixtures
- **P06**: Estimation golden vectors
- **P08**: Vision edge case fixtures
- **P17**: Backend API fixtures
