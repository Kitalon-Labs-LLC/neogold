"""Tests for JSON schema validation of config/trust-list.json."""

import json
from pathlib import Path
import pytest
import jsonschema


def load_schema():
    schema_path = Path("config/schema/trust-list.schema.json")
    with open(schema_path, "r", encoding="utf-8") as f:
        return json.load(f)


def load_instance():
    inst_path = Path("card/spec/testvectors/test_trust_list.json")
    with open(inst_path, "r", encoding="utf-8") as f:
        return json.load(f)


def test_schema_itself_is_valid():
    schema = load_schema()
    validator_cls = jsonschema.validators.validator_for(schema)
    validator_cls.check_schema(schema)


def test_valid_trust_list_passes():
    schema = load_schema()
    instance = load_instance()
    jsonschema.validate(instance=instance, schema=schema)


def test_extra_property_rejected():
    schema = load_schema()
    instance = load_instance()
    instance["unexpected_field"] = "malicious"
    with pytest.raises(jsonschema.ValidationError):
        jsonschema.validate(instance=instance, schema=schema)


def test_invalid_purpose_rejected():
    schema = load_schema()
    instance = load_instance()
    instance["keys"][0]["purpose"] = "SUPERUSER"
    with pytest.raises(jsonschema.ValidationError):
        jsonschema.validate(instance=instance, schema=schema)


def test_invalid_key_hex_rejected():
    schema = load_schema()
    instance = load_instance()
    # 63 characters instead of 64
    instance["keys"][0]["public_key_hex"] = "a" * 63
    with pytest.raises(jsonschema.ValidationError):
        jsonschema.validate(instance=instance, schema=schema)

    # Upper case rejected (schema specifies ^[a-f0-9]{64}$)
    instance["keys"][0]["public_key_hex"] = "A" * 64
    with pytest.raises(jsonschema.ValidationError):
        jsonschema.validate(instance=instance, schema=schema)


def test_invalid_date_format_rejected():
    schema = load_schema()
    instance = load_instance()
    instance["valid_from"] = "10/01/2026"
    with pytest.raises(jsonschema.ValidationError):
        jsonschema.validate(instance=instance, schema=schema)


def test_invalid_batch_id_pattern_rejected():
    schema = load_schema()
    instance = load_instance()
    # Lowercase batch id
    instance["revoked_card_batches"] = ["ng-2026-bad"]
    with pytest.raises(jsonschema.ValidationError):
        jsonschema.validate(instance=instance, schema=schema)
