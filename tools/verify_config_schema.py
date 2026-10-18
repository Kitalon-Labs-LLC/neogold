#!/usr/bin/env python3
"""Validates all JSON configuration files in the repository against their JSON schemas.

Usage: python3 tools/verify_config_schema.py
Exits 0 if all configurations pass validation, 1 if any fail.
"""
import json
import sys
from pathlib import Path

try:
    import jsonschema
except ImportError:
    print("ERROR: jsonschema package is required (pip install jsonschema)")
    sys.exit(1)


CONFIG_SCHEMA_MAPPINGS = [
    # (config_path, schema_path)
    ("config/triage-config.json", "config/schema/triage-config.schema.json"),
    ("testdata/triage/config.synthetic.json", "config/schema/triage-config.schema.json"),
    ("card/spec/testvectors/test_trust_list.json", "config/schema/trust-list.schema.json"),
    ("testdata/models/model.synthetic.json", "config/schema/model.schema.json"),
]


def validate_file(cfg_path: Path, schema_path: Path) -> bool:
    if not cfg_path.exists():
        print(f"FAIL: Configuration file not found: {cfg_path}")
        return False
    if not schema_path.exists():
        print(f"FAIL: Schema file not found: {schema_path}")
        return False

    try:
        with open(schema_path, "r", encoding="utf-8") as sf:
            schema = json.load(sf)
        validator_cls = jsonschema.validators.validator_for(schema)
        validator_cls.check_schema(schema)
    except Exception as e:
        print(f"FAIL: Schema {schema_path} is invalid: {e}")
        return False

    try:
        with open(cfg_path, "r", encoding="utf-8") as cf:
            instance = json.load(cf)
        validator = validator_cls(schema)
        validator.validate(instance=instance)
        print(f"PASS: {cfg_path} complies with {schema_path}")
        return True
    except jsonschema.ValidationError as e:
        print(f"FAIL: {cfg_path} failed validation against {schema_path}:")
        print(f"  Message: {e.message}")
        print(f"  Path: {list(e.path)}")
        return False
    except Exception as e:
        print(f"FAIL: Error reading or validating {cfg_path}: {e}")
        return False


def main():
    root = Path(__file__).resolve().parent.parent
    all_passed = True
    print("Verifying repository JSON configurations against schemas...")
    for cfg_rel, schema_rel in CONFIG_SCHEMA_MAPPINGS:
        cfg_file = root / cfg_rel
        schema_file = root / schema_rel
        if not validate_file(cfg_file, schema_file):
            all_passed = False

    # Also check if config/trust-list.json exists
    optional_trust_list = root / "config" / "trust-list.json"
    if optional_trust_list.exists():
        if not validate_file(optional_trust_list, root / "config" / "schema" / "trust-list.schema.json"):
            all_passed = False

    # Also check if config/model.json exists
    optional_model = root / "config" / "model.json"
    if optional_model.exists():
        if not validate_file(optional_model, root / "config" / "schema" / "model.schema.json"):
            all_passed = False

    if all_passed:
        print("SUCCESS: All configuration schemas verified successfully.")
        return 0
    else:
        print("FAILURE: Configuration schema verification failed.")
        return 1


if __name__ == "__main__":
    sys.exit(main())
