"""Tests for QR payload parsing, signature verification, and trust rules."""

import datetime
import json
import struct
from pathlib import Path
import pytest
from cryptography.hazmat.primitives.asymmetric import ed25519

VECTORS_DIR = Path("card/spec/testvectors")
EPOCH = datetime.date(2020, 1, 1)


def load_json(name: str) -> dict:
    with open(VECTORS_DIR / name, "r", encoding="utf-8") as f:
        return json.load(f)


def parse_and_verify_card_batch(payload: bytes, trust_list: dict, eval_date: datetime.date = datetime.date(2026, 10, 17)):
    """Reference implementation of :engine:cardtrust verification logic for card batches."""
    if len(payload) < 6 + 1 + 1 + 1 + 64:
        return "MALFORMED", None

    p_type, ver, issued_days, expires_days = struct.unpack(">BBHH", payload[0:6])
    if p_type != 0x01 or ver != 0x01:
        return "MALFORMED", None

    offset = 6
    k_len = payload[offset]
    offset += 1
    if len(payload) < offset + k_len:
        return "MALFORMED", None
    issuer_key_id = payload[offset : offset + k_len].decode("ascii", errors="replace")
    offset += k_len

    b_len = payload[offset]
    offset += 1
    if len(payload) < offset + b_len:
        return "MALFORMED", None
    batch_id = payload[offset : offset + b_len].decode("ascii", errors="replace")
    offset += b_len

    n_patches = payload[offset]
    offset += 1
    patches_bytes_len = n_patches * 6
    if len(payload) < offset + patches_bytes_len + 64:
        return "MALFORMED", None

    # Decode patches
    patches = []
    for _ in range(n_patches):
        L, a, b = struct.unpack(">Hhh", payload[offset : offset + 6])
        offset += 6
        patches.append({"L": L / 100.0, "a": a / 100.0, "b": b / 100.0})

    signed_bytes = payload[:offset]
    signature = payload[offset : offset + 64]

    # 1. Lookup key in trust list
    matching_keys = [k for k in trust_list["keys"] if k["key_id"] == issuer_key_id]
    if not matching_keys:
        return "UNKNOWN_ISSUER", None
    key_entry = matching_keys[0]

    # 2. Check purpose
    if key_entry["purpose"] != "CARD_ISSUER":
        return "WRONG_PURPOSE", None

    # 3. Verify Ed25519 signature
    pub_key = ed25519.Ed25519PublicKey.from_public_bytes(bytes.fromhex(key_entry["public_key_hex"]))
    try:
        pub_key.verify(signature, signed_bytes)
    except Exception:
        return "BAD_SIGNATURE", None

    # 4. Revocation check
    if batch_id in trust_list.get("revoked_card_batches", []):
        return "REVOKED", None

    # 5. Expiry check
    exp_date = EPOCH + datetime.timedelta(days=expires_days)
    if eval_date > exp_date:
        return "EXPIRED", None

    return "VERIFIED", {
        "batch_id": batch_id,
        "issuer_key_id": issuer_key_id,
        "issued_at": (EPOCH + datetime.timedelta(days=issued_days)).isoformat(),
        "expires_at": exp_date.isoformat(),
        "patches": patches,
    }


def parse_and_verify_study_profile(payload: bytes, trust_list: dict, eval_date: datetime.date = datetime.date(2026, 10, 17)):
    """Reference implementation of :engine:cardtrust verification logic for study profiles."""
    if len(payload) < 5 + 64:
        return "MALFORMED", None

    p_type, ver, expires_days, tp_code = struct.unpack(">BBHB", payload[0:5])
    if p_type != 0x02 or ver != 0x01:
        return "MALFORMED", None

    offset = 5
    k_len = payload[offset]
    offset += 1
    issuer_key_id = payload[offset : offset + k_len].decode("ascii", errors="replace")
    offset += k_len

    s_len = payload[offset]
    offset += 1
    study_code = payload[offset : offset + s_len].decode("ascii", errors="replace")
    offset += s_len

    d_len = payload[offset]
    offset += 1
    display_name = payload[offset : offset + d_len].decode("utf-8", errors="replace")
    offset += d_len

    p_len = payload[offset]
    offset += 1
    partner = payload[offset : offset + p_len].decode("utf-8", errors="replace")
    offset += p_len

    h_len = payload[offset]
    offset += 1
    host = payload[offset : offset + h_len].decode("ascii", errors="replace") if h_len > 0 else None
    offset += h_len

    export_pub_key = payload[offset : offset + 32]
    offset += 32

    signed_bytes = payload[:offset]
    signature = payload[offset : offset + 64]

    # 1. Lookup key
    matching = [k for k in trust_list["keys"] if k["key_id"] == issuer_key_id]
    if not matching:
        return "UNKNOWN_ISSUER", None
    key_entry = matching[0]

    # 2. Check purpose
    if key_entry["purpose"] != "STUDY_ISSUER":
        return "WRONG_PURPOSE", None

    # 3. Verify signature
    pub_key = ed25519.Ed25519PublicKey.from_public_bytes(bytes.fromhex(key_entry["public_key_hex"]))
    try:
        pub_key.verify(signature, signed_bytes)
    except Exception:
        return "BAD_SIGNATURE", None

    # 4. Revocation check
    if study_code in trust_list.get("revoked_study_codes", []):
        return "REVOKED", None

    # 5. Expiry check
    exp_date = EPOCH + datetime.timedelta(days=expires_days)
    if eval_date > exp_date:
        return "EXPIRED", None

    return "VERIFIED", {
        "study_code": study_code,
        "display_name": display_name,
        "partner": partner,
        "host": host,
        "timestamp_precision": "FULL" if tp_code == 1 else "DAY_PLUS_OFFSETS",
        "expires_at": exp_date.isoformat(),
        "export_public_key_hex": export_pub_key.hex(),
    }


def test_card_batch_test_vectors():
    trust_list = load_json("test_trust_list.json")

    # Valid
    v = load_json("card_batch_valid.json")
    status, res = parse_and_verify_card_batch(bytes.fromhex(v["payload_hex"]), trust_list)
    assert status == v["expected_result"] == "VERIFIED"
    assert res["batch_id"] == "NG-2026-001"
    assert len(res["patches"]) == 23

    # Tampered byte
    t = load_json("card_batch_invalid_tampered.json")
    status, _ = parse_and_verify_card_batch(bytes.fromhex(t["payload_hex"]), trust_list)
    assert status == t["expected_result"] == "BAD_SIGNATURE"

    # Unknown issuer
    u = load_json("card_batch_invalid_unknown_issuer.json")
    status, _ = parse_and_verify_card_batch(bytes.fromhex(u["payload_hex"]), trust_list)
    assert status == u["expected_result"] == "UNKNOWN_ISSUER"

    # Wrong purpose
    w = load_json("card_batch_invalid_wrong_purpose.json")
    status, _ = parse_and_verify_card_batch(bytes.fromhex(w["payload_hex"]), trust_list)
    assert status == w["expected_result"] == "WRONG_PURPOSE"

    # Expired
    e = load_json("card_batch_invalid_expired.json")
    status, _ = parse_and_verify_card_batch(bytes.fromhex(e["payload_hex"]), trust_list)
    assert status == e["expected_result"] == "EXPIRED"

    # Revoked
    r = load_json("card_batch_invalid_revoked.json")
    status, _ = parse_and_verify_card_batch(bytes.fromhex(r["payload_hex"]), trust_list)
    assert status == r["expected_result"] == "REVOKED"


def test_study_profile_test_vectors():
    trust_list = load_json("test_trust_list.json")

    # Valid
    v = load_json("study_profile_valid.json")
    status, res = parse_and_verify_study_profile(bytes.fromhex(v["payload_hex"]), trust_list)
    assert status == v["expected_result"] == "VERIFIED"
    assert res["study_code"] == "SKP01"
    assert res["host"] == "intake.neogold.example.bd"

    # Tampered
    t = load_json("study_profile_invalid_tampered.json")
    status, _ = parse_and_verify_study_profile(bytes.fromhex(t["payload_hex"]), trust_list)
    assert status == t["expected_result"] == "BAD_SIGNATURE"

    # Unknown issuer
    u = load_json("study_profile_invalid_unknown_issuer.json")
    status, _ = parse_and_verify_study_profile(bytes.fromhex(u["payload_hex"]), trust_list)
    assert status == u["expected_result"] == "UNKNOWN_ISSUER"

    # Wrong purpose
    w = load_json("study_profile_invalid_wrong_purpose.json")
    status, _ = parse_and_verify_study_profile(bytes.fromhex(w["payload_hex"]), trust_list)
    assert status == w["expected_result"] == "WRONG_PURPOSE"

    # Expired
    e = load_json("study_profile_invalid_expired.json")
    status, _ = parse_and_verify_study_profile(bytes.fromhex(e["payload_hex"]), trust_list)
    assert status == e["expected_result"] == "EXPIRED"

    # Revoked
    r = load_json("study_profile_invalid_revoked.json")
    status, _ = parse_and_verify_study_profile(bytes.fromhex(r["payload_hex"]), trust_list)
    assert status == r["expected_result"] == "REVOKED"
