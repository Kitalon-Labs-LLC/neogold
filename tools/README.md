# NeoGold Tools & Release Gates

This directory contains standalone gate-checking scripts, security scanners, and traceability utilities used during local builds and CI verification.

## Scripts
- `trace.py`: Verifies bidirectional traceability between design documents, requirements (FR/NFR), hazards (H), threats (T), release gates, and test cases (TC). `--tests` also counts the test-code sites citing each TC and lists TCs with none (report only).
- `drift_inventory.py`: P24 direction-check inventory. Compares modules, libraries (doc 4 Section 4.3a), manifest, Room and Alembic schemas, FastAPI OpenAPI, screens, strings, config keys, screening network reach, `triageEnabled` sites, TC citations, and NG references against `docs/`. `--check` fails on unplanned items not in `reports/direction/baseline.json`.
- `verify_clinical_signoff.py`: Release Gate G1 validator. Checks that `CLINICAL_SIGNOFF.md` correctly matches the canonical hash of `config/triage-config.json`.
- `check_manifest.py`: Release Gate S5 validator. Ensures the merged `AndroidManifest.xml` contains only allowed permissions, components, and export settings.
- `check_network_config.py`: Release Gate S6 validator. Ensures network security configuration denies cleartext traffic and trusts only system CAs.
- `check_16kb.sh`: Release Gate S4 validator. Checks that all 64-bit ELF shared libraries inside the release APK are 16 KB page-size aligned.
- `triage_reference.py`: Python reference implementation of the clinical triage decision algorithm for differential testing against the Kotlin engine.
- `check_licenses.py`: CI license compliance checker (TC-OPS-08).
- `check_workflows.py`: Fails when a GitHub Actions workflow is invalid YAML, has a step with neither `run` nor `uses`, or uses an action that is not pinned to a full commit SHA (BUG-2026-014, TC-OPS-01). Runs in `repo-ci`.
