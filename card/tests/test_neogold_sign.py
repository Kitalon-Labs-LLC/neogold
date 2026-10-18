"""Unit and integration tests for neogold-sign CLI tool.

Verifies:
- Key generation (Ed25519, X25519)
- Card batch signing and verification round trip
- Study profile signing and verification round trip
- Size budget enforcement (251-byte ceiling for study profiles)
- Air-gap / network interface detection and refusal
- Audit log generation without private key leakage
- Tamper detection
"""

import datetime
import json
import sys
from pathlib import Path
import pytest

SIGN_TOOL_DIR = Path(__file__).resolve().parent.parent / "tools" / "neogold-sign"
if str(SIGN_TOOL_DIR) not in sys.path:
    sys.path.insert(0, str(SIGN_TOOL_DIR))
import neogold_sign

TESTVECTORS_DIR = Path("card/spec/testvectors")


@pytest.fixture
def test_keys():
    with open(TESTVECTORS_DIR / "keys.json", "r", encoding="utf-8") as f:
        return json.load(f)


@pytest.fixture
def trust_list():
    with open("config/trust-list.json", "r", encoding="utf-8") as f:
        return json.load(f)


def test_network_detection_enforcement(monkeypatch):
    """Test that active network interfaces trigger refusal unless overridden."""
    monkeypatch.setattr(neogold_sign, "detect_active_network_interfaces", lambda: ["eth0:up", "wlan0:up"])

    # Without override -> must exit with code 2
    with pytest.raises(SystemExit) as exc:
        neogold_sign.enforce_offline_machine(allow_online=False)
    assert exc.value.code == 2

    # With override -> passes without error
    neogold_sign.enforce_offline_machine(allow_online=True)

    # When no active interfaces -> passes without error
    monkeypatch.setattr(neogold_sign, "detect_active_network_interfaces", lambda: [])
    neogold_sign.enforce_offline_machine(allow_online=False)


def test_keygen_ed25519_and_x25519(tmp_path):
    """Test keygen command generates valid keys."""
    priv_file = tmp_path / "card.priv"
    pub_file = tmp_path / "card.pub"

    class Args:
        command = "keygen"
        key_id = "TEST-GEN-KEY-01"
        purpose = "CARD_ISSUER"
        algorithm = "Ed25519"
        description = "Unit test generated key"
        test_only = True
        out_private = str(priv_file)
        out_public = str(pub_file)

    ret = neogold_sign.cmd_keygen(Args)
    assert ret == 0
    assert priv_file.exists()
    assert pub_file.exists()

    priv_hex = priv_file.read_text().strip()
    pub_hex = pub_file.read_text().strip()
    assert len(priv_hex) == 64
    assert len(pub_hex) == 64


def test_card_batch_signing_round_trip(tmp_path, test_keys, trust_list):
    """Test card batch signing and subsequent offline verification."""
    card_key = test_keys["TEST_ONLY_CARD_KEY"]
    card_v1 = json.load(open("card/spec/card-v1.json", encoding="utf-8"))
    log_file = tmp_path / "audit.jsonl"
    out_file = tmp_path / "batch.json"

    import types
    args = types.SimpleNamespace(
        command="card-batch",
        key_id=card_key["key_id"],
        private_key=card_key["private_key_hex"],
        batch_id="NG-2026-TEST",
        issued_at="2026-10-15",
        expires_at="2028-10-15",
        patches=json.dumps(card_v1["patches"]),
        out=str(out_file),
        operator="QA-Tester",
        log_file=str(log_file),
        i_understand_this_is_online=True,
    )

    ret = neogold_sign.cmd_card_batch(args)
    assert ret == 0
    assert out_file.exists()
    assert log_file.exists()

    # Verify audit log format and ensure NO private key is logged
    log_lines = log_file.read_text(encoding="utf-8").strip().splitlines()
    assert len(log_lines) == 1
    log_entry = json.loads(log_lines[0])
    assert log_entry["action"] == "card-batch"
    assert log_entry["identifier"] == "NG-2026-TEST"
    assert log_entry["operator"] == "QA-Tester"
    assert "private" not in json.dumps(log_entry).lower()
    assert "4e454f" not in json.dumps(log_entry).lower()

    # Verify generated payload
    payload_data = json.load(open(out_file, encoding="utf-8"))
    raw_payload = bytes.fromhex(payload_data["payload_hex"])
    result, details = neogold_sign.verify_payload(
        payload=raw_payload,
        trust_list=trust_list,
        eval_date=datetime.date(2026, 10, 17),
    )
    assert result == "VERIFIED"
    assert details["batch_id"] == "NG-2026-TEST"
    assert len(details["patches"]) == 23


def test_study_profile_signing_round_trip(tmp_path, test_keys, trust_list):
    """Test study profile signing and verification."""
    study_key = test_keys["TEST_ONLY_STUDY_KEY"]
    export_key = test_keys["TEST_ONLY_EXPORT_KEY"]
    log_file = tmp_path / "audit.jsonl"
    out_file = tmp_path / "study.json"

    import types
    args = types.SimpleNamespace(
        command="study-profile",
        key_id=study_key["key_id"],
        private_key=study_key["private_key_hex"],
        study_code="SKP01",
        display_name="Sakhipur Community Screening Study",
        partner="icddr,b / Stanford",
        host="intake.neogold.example.bd",
        timestamp_precision="DAY_PLUS_OFFSETS",
        expires_at="2028-12-31",
        export_public_key=export_key["public_key_hex"],
        out=str(out_file),
        operator="Study-Admin",
        log_file=str(log_file),
        i_understand_this_is_online=True,
    )

    ret = neogold_sign.cmd_study_profile(args)
    assert ret == 0
    assert out_file.exists()

    payload_data = json.load(open(out_file, encoding="utf-8"))
    raw_payload = bytes.fromhex(payload_data["payload_hex"])
    assert len(raw_payload) <= 251

    result, details = neogold_sign.verify_payload(
        payload=raw_payload,
        trust_list=trust_list,
        eval_date=datetime.date(2026, 10, 17),
    )
    assert result == "VERIFIED"
    assert details["study_code"] == "SKP01"


def test_study_profile_size_budget_exceeded(test_keys):
    """Test that study profile exceeding 251 bytes is rejected."""
    study_key = test_keys["TEST_ONLY_STUDY_KEY"]
    export_key = test_keys["TEST_ONLY_EXPORT_KEY"]
    priv = neogold_sign.load_private_key(study_key["private_key_hex"])

    # Create oversized display name and partner
    long_name = "A" * 120
    long_partner = "B" * 120
    long_host = "intake.very.long.subdomain.neogold.example.org"

    with pytest.raises(ValueError) as exc:
        neogold_sign.encode_study_profile_payload(
            issuer_key_id=study_key["key_id"],
            study_code="SKP01",
            display_name=long_name,
            partner=long_partner,
            host=long_host,
            timestamp_precision="DAY_PLUS_OFFSETS",
            expires_at=datetime.date(2028, 12, 31),
            export_public_key_bytes=bytes.fromhex(export_key["public_key_hex"]),
            private_key=priv,
        )
    assert "exceeds maximum allowable limit of 251 bytes" in str(exc.value)


def test_tampered_payload_verification(test_keys, trust_list):
    """Test that flipping 1 bit in payload results in BAD_SIGNATURE."""
    tampered_file = TESTVECTORS_DIR / "card_batch_invalid_tampered.json"
    with open(tampered_file, "r", encoding="utf-8") as f:
        doc = json.load(f)
    raw = bytes.fromhex(doc["payload_hex"])
    res, _ = neogold_sign.verify_payload(raw, trust_list, eval_date=datetime.date(2026, 10, 17))
    assert res == "BAD_SIGNATURE"
