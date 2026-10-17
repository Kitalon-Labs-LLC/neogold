"""Build test vectors and test trust list for NeoGold P02.

This script generates:
- card/spec/testvectors/keys.json
- card/spec/testvectors/test_trust_list.json
- card/spec/testvectors/card_batch_*.json
- card/spec/testvectors/study_profile_*.json
"""

import base64
import datetime
import json
import os
import struct
from pathlib import Path
from cryptography.hazmat.primitives.asymmetric import ed25519, x25519
from cryptography.hazmat.primitives import serialization

EPOCH = datetime.date(2020, 1, 1)


def date_to_days(d: datetime.date) -> int:
    return (d - EPOCH).days


def days_to_date(days: int) -> str:
    return (EPOCH + datetime.timedelta(days=days)).isoformat()


def make_ed25519_key(seed: bytes):
    priv = ed25519.Ed25519PrivateKey.from_private_bytes(seed)
    pub = priv.public_key()
    priv_bytes = priv.private_bytes(
        encoding=serialization.Encoding.Raw,
        format=serialization.PrivateFormat.Raw,
        encryption_algorithm=serialization.NoEncryption(),
    )
    pub_bytes = pub.public_bytes(
        encoding=serialization.Encoding.Raw,
        format=serialization.PublicFormat.Raw,
    )
    return priv, pub, priv_bytes.hex(), pub_bytes.hex()


def make_x25519_key(seed: bytes):
    priv = x25519.X25519PrivateKey.from_private_bytes(seed)
    pub = priv.public_key()
    priv_bytes = priv.private_bytes(
        encoding=serialization.Encoding.Raw,
        format=serialization.PrivateFormat.Raw,
        encryption_algorithm=serialization.NoEncryption(),
    )
    pub_bytes = pub.public_bytes(
        encoding=serialization.Encoding.Raw,
        format=serialization.PublicFormat.Raw,
    )
    return priv, pub, priv_bytes.hex(), pub_bytes.hex()


def encode_card_batch(
    issuer_key_id: str,
    batch_id: str,
    issued_at: datetime.date,
    expires_at: datetime.date,
    patches: list[dict],
    priv_key: ed25519.Ed25519PrivateKey,
) -> tuple[bytes, bytes]:
    # Header: type (0x01), version (0x01), issued_at_days (uint16), expires_at_days (uint16)
    header = struct.pack(
        ">BBHH",
        0x01,
        0x01,
        date_to_days(issued_at),
        date_to_days(expires_at),
    )
    # Issuer ID
    issuer_bytes = issuer_key_id.encode("ascii")
    issuer_sec = struct.pack(">B", len(issuer_bytes)) + issuer_bytes

    # Batch ID
    batch_bytes = batch_id.encode("ascii")
    batch_sec = struct.pack(">B", len(batch_bytes)) + batch_bytes

    # Patches: count (uint8), followed by N * 6 bytes (L* uint16, a* int16, b* int16, x100)
    patch_sec = struct.pack(">B", len(patches))
    for p in patches:
        L = int(round(p["lab_d65"][0] * 100))
        a = int(round(p["lab_d65"][1] * 100))
        b = int(round(p["lab_d65"][2] * 100))
        patch_sec += struct.pack(">Hhh", L, a, b)

    signed_bytes = header + issuer_sec + batch_sec + patch_sec
    sig = priv_key.sign(signed_bytes)
    full_payload = signed_bytes + sig
    return signed_bytes, full_payload


def encode_study_profile(
    issuer_key_id: str,
    study_code: str,
    display_name: str,
    partner: str,
    host: str | None,
    timestamp_precision: str,
    expires_at: datetime.date,
    export_pub_key_bytes: bytes,
    priv_key: ed25519.Ed25519PrivateKey,
) -> tuple[bytes, bytes]:
    # Header: type (0x02), version (0x01), expires_at_days (uint16), timestamp_precision (uint8: 0=DAY_PLUS_OFFSETS, 1=FULL)
    tp_code = 1 if timestamp_precision == "FULL" else 0
    header = struct.pack(
        ">BBHB",
        0x02,
        0x01,
        date_to_days(expires_at),
        tp_code,
    )
    # Issuer key ID
    issuer_bytes = issuer_key_id.encode("ascii")
    issuer_sec = struct.pack(">B", len(issuer_bytes)) + issuer_bytes

    # Study code
    code_bytes = study_code.encode("ascii")
    code_sec = struct.pack(">B", len(code_bytes)) + code_bytes

    # Display name (UTF-8)
    name_bytes = display_name.encode("utf-8")
    name_sec = struct.pack(">B", len(name_bytes)) + name_bytes

    # Partner (UTF-8)
    partner_bytes = partner.encode("utf-8")
    partner_sec = struct.pack(">B", len(partner_bytes)) + partner_bytes

    # Host (ASCII or empty)
    host_bytes = (host or "").encode("ascii")
    host_sec = struct.pack(">B", len(host_bytes)) + host_bytes

    # Export public key: 32 bytes
    if len(export_pub_key_bytes) != 32:
        raise ValueError("export_pub_key_bytes must be 32 bytes")

    signed_bytes = (
        header
        + issuer_sec
        + code_sec
        + name_sec
        + partner_sec
        + host_sec
        + export_pub_key_bytes
    )
    sig = priv_key.sign(signed_bytes)
    full_payload = signed_bytes + sig
    return signed_bytes, full_payload


def main():
    out_dir = Path("card/spec/testvectors")
    out_dir.mkdir(parents=True, exist_ok=True)

    # 1. Deterministic test keys
    card_priv, card_pub, card_priv_hex, card_pub_hex = make_ed25519_key(
        b"NEOGOLD_TEST_SEED_CARD_KEY_00001"
    )
    study_priv, study_pub, study_priv_hex, study_pub_hex = make_ed25519_key(
        b"NEOGOLD_TEST_SEED_STUDY_KEY_0001"
    )
    unknown_priv, unknown_pub, unknown_priv_hex, unknown_pub_hex = (
        make_ed25519_key(b"NEOGOLD_TEST_SEED_UNKWN_KEY_0001")
    )
    export_priv, export_pub, export_priv_hex, export_pub_hex = make_x25519_key(
        b"NEOGOLD_TEST_SEED_EXPRT_KEY_0001"
    )

    keys_doc = {
        "$comment": "TEST ONLY keys for NeoGold P02 and P05. NEVER use in production.",
        "TEST_ONLY_CARD_KEY": {
            "key_id": "TEST-CARD-KEY-01",
            "purpose": "CARD_ISSUER",
            "algorithm": "Ed25519",
            "public_key_hex": card_pub_hex,
            "private_key_hex": card_priv_hex,
        },
        "TEST_ONLY_STUDY_KEY": {
            "key_id": "TEST-STUDY-KEY-01",
            "purpose": "STUDY_ISSUER",
            "algorithm": "Ed25519",
            "public_key_hex": study_pub_hex,
            "private_key_hex": study_priv_hex,
        },
        "TEST_ONLY_UNKNOWN_KEY": {
            "key_id": "TEST-UNKNOWN-KEY-99",
            "purpose": "CARD_ISSUER",
            "algorithm": "Ed25519",
            "public_key_hex": unknown_pub_hex,
            "private_key_hex": unknown_priv_hex,
            "note": "Not included in trust list, used to test TC-CARD-03 rejection.",
        },
        "TEST_ONLY_EXPORT_KEY": {
            "key_id": "TEST-EXPORT-K10-01",
            "purpose": "STUDY_EXPORT",
            "algorithm": "X25519",
            "public_key_hex": export_pub_hex,
            "private_key_hex": export_priv_hex,
        },
    }
    with open(out_dir / "keys.json", "w", encoding="utf-8") as f:
        json.dump(keys_doc, f, indent=2)

    # 2. Test trust list (validates against config/schema/trust-list.schema.json)
    trust_list = {
        "schema_version": 1,
        "version": 1,
        "valid_from": "2026-10-01",
        "valid_until": "2028-10-01",
        "keys": [
            {
                "key_id": "TEST-CARD-KEY-01",
                "purpose": "CARD_ISSUER",
                "algorithm": "Ed25519",
                "public_key_hex": card_pub_hex,
                "valid_from": "2026-10-01",
                "valid_until": "2028-10-01",
                "description": "Test-only card issuer key for unit and integration tests",
            },
            {
                "key_id": "TEST-STUDY-KEY-01",
                "purpose": "STUDY_ISSUER",
                "algorithm": "Ed25519",
                "public_key_hex": study_pub_hex,
                "valid_from": "2026-10-01",
                "valid_until": "2028-10-01",
                "description": "Test-only study issuer key for enrollment QR tests",
            },
        ],
        "revoked_card_batches": ["NG-2026-REVOKED"],
        "revoked_study_codes": ["STUDY-REVOKED"],
    }
    with open(out_dir / "test_trust_list.json", "w", encoding="utf-8") as f:
        json.dump(trust_list, f, indent=2)

    # Load patches from card-v1.json
    card_v1_json = json.load(open("card/spec/card-v1.json", encoding="utf-8"))
    patches = card_v1_json["patches"]

    # 3. Card Batch Test Vectors
    # Valid
    sb, valid_card_payload = encode_card_batch(
        issuer_key_id="TEST-CARD-KEY-01",
        batch_id="NG-2026-001",
        issued_at=datetime.date(2026, 10, 15),
        expires_at=datetime.date(2028, 10, 15),
        patches=patches,
        priv_key=card_priv,
    )
    with open(out_dir / "card_batch_valid.json", "w", encoding="utf-8") as f:
        json.dump(
            {
                "description": "Valid card batch payload signed with TEST-CARD-KEY-01 (TC-CARD-01)",
                "expected_result": "VERIFIED",
                "batch_id": "NG-2026-001",
                "issuer_key_id": "TEST-CARD-KEY-01",
                "issued_at": "2026-10-15",
                "expires_at": "2028-10-15",
                "patch_count": len(patches),
                "payload_bytes_len": len(valid_card_payload),
                "payload_hex": valid_card_payload.hex(),
                "payload_base64": base64.b64encode(valid_card_payload).decode(
                    "ascii"
                ),
            },
            f,
            indent=2,
        )

    # Invalid 1: Tampered byte (flip 1 bit in patch 0 L*) (TC-CARD-02)
    tampered_bytes = bytearray(valid_card_payload)
    tampered_bytes[35] ^= 0x01  # mutate inside patch area
    with open(
        out_dir / "card_batch_invalid_tampered.json", "w", encoding="utf-8"
    ) as f:
        json.dump(
            {
                "description": "Card batch with one tampered bit in payload; Ed25519 signature fails (TC-CARD-02)",
                "expected_result": "BAD_SIGNATURE",
                "mutated_byte_offset": 35,
                "payload_bytes_len": len(tampered_bytes),
                "payload_hex": tampered_bytes.hex(),
                "payload_base64": base64.b64encode(tampered_bytes).decode(
                    "ascii"
                ),
            },
            f,
            indent=2,
        )

    # Invalid 2: Unknown issuer key (TC-CARD-03)
    _, unknown_card_payload = encode_card_batch(
        issuer_key_id="TEST-UNKNOWN-KEY-99",
        batch_id="NG-2026-001",
        issued_at=datetime.date(2026, 10, 15),
        expires_at=datetime.date(2028, 10, 15),
        patches=patches,
        priv_key=unknown_priv,
    )
    with open(
        out_dir / "card_batch_invalid_unknown_issuer.json", "w", encoding="utf-8"
    ) as f:
        json.dump(
            {
                "description": "Card batch signed with a valid key not in the trust list (TC-CARD-03)",
                "expected_result": "UNKNOWN_ISSUER",
                "issuer_key_id": "TEST-UNKNOWN-KEY-99",
                "payload_bytes_len": len(unknown_card_payload),
                "payload_hex": unknown_card_payload.hex(),
                "payload_base64": base64.b64encode(unknown_card_payload).decode(
                    "ascii"
                ),
            },
            f,
            indent=2,
        )

    # Invalid 3: Wrong purpose (signed with STUDY_ISSUER key) (TC-CARD-04)
    _, wrong_purpose_card_payload = encode_card_batch(
        issuer_key_id="TEST-STUDY-KEY-01",
        batch_id="NG-2026-001",
        issued_at=datetime.date(2026, 10, 15),
        expires_at=datetime.date(2028, 10, 15),
        patches=patches,
        priv_key=study_priv,
    )
    with open(
        out_dir / "card_batch_invalid_wrong_purpose.json", "w", encoding="utf-8"
    ) as f:
        json.dump(
            {
                "description": "Card batch signed with a STUDY_ISSUER key instead of CARD_ISSUER (TC-CARD-04)",
                "expected_result": "WRONG_PURPOSE",
                "issuer_key_id": "TEST-STUDY-KEY-01",
                "payload_bytes_len": len(wrong_purpose_card_payload),
                "payload_hex": wrong_purpose_card_payload.hex(),
                "payload_base64": base64.b64encode(
                    wrong_purpose_card_payload
                ).decode("ascii"),
            },
            f,
            indent=2,
        )

    # Invalid 4: Expired batch (TC-CARD-05)
    _, expired_card_payload = encode_card_batch(
        issuer_key_id="TEST-CARD-KEY-01",
        batch_id="NG-2026-001",
        issued_at=datetime.date(2024, 1, 1),
        expires_at=datetime.date(2025, 1, 1),  # Expired
        patches=patches,
        priv_key=card_priv,
    )
    with open(
        out_dir / "card_batch_invalid_expired.json", "w", encoding="utf-8"
    ) as f:
        json.dump(
            {
                "description": "Card batch signature is valid but batch has expired relative to current date 2026-10-17 (TC-CARD-05)",
                "expected_result": "EXPIRED",
                "expires_at": "2025-01-01",
                "evaluation_date": "2026-10-17",
                "payload_bytes_len": len(expired_card_payload),
                "payload_hex": expired_card_payload.hex(),
                "payload_base64": base64.b64encode(expired_card_payload).decode(
                    "ascii"
                ),
            },
            f,
            indent=2,
        )

    # Invalid 5: Revoked batch (TC-CARD-06)
    _, revoked_card_payload = encode_card_batch(
        issuer_key_id="TEST-CARD-KEY-01",
        batch_id="NG-2026-REVOKED",
        issued_at=datetime.date(2026, 10, 15),
        expires_at=datetime.date(2028, 10, 15),
        patches=patches,
        priv_key=card_priv,
    )
    with open(
        out_dir / "card_batch_invalid_revoked.json", "w", encoding="utf-8"
    ) as f:
        json.dump(
            {
                "description": "Card batch signature is valid but batch ID is listed in revoked_card_batches (TC-CARD-06)",
                "expected_result": "REVOKED",
                "batch_id": "NG-2026-REVOKED",
                "payload_bytes_len": len(revoked_card_payload),
                "payload_hex": revoked_card_payload.hex(),
                "payload_base64": base64.b64encode(revoked_card_payload).decode(
                    "ascii"
                ),
            },
            f,
            indent=2,
        )

    # 4. Study Profile Test Vectors
    # Valid
    export_pub_bytes = bytes.fromhex(export_pub_hex)
    _, valid_study_payload = encode_study_profile(
        issuer_key_id="TEST-STUDY-KEY-01",
        study_code="SKP01",
        display_name="Sakhipur Community Screening Study",
        partner="icddr,b / Stanford",
        host="intake.neogold.example.bd",
        timestamp_precision="DAY_PLUS_OFFSETS",
        expires_at=datetime.date(2028, 12, 31),
        export_pub_key_bytes=export_pub_bytes,
        priv_key=study_priv,
    )
    with open(out_dir / "study_profile_valid.json", "w", encoding="utf-8") as f:
        json.dump(
            {
                "description": "Valid study profile payload signed with TEST-STUDY-KEY-01 (doc 7 Section 4.1)",
                "expected_result": "VERIFIED",
                "study_code": "SKP01",
                "display_name": "Sakhipur Community Screening Study",
                "partner": "icddr,b / Stanford",
                "host": "intake.neogold.example.bd",
                "timestamp_precision": "DAY_PLUS_OFFSETS",
                "expires_at": "2028-12-31",
                "export_public_key_hex": export_pub_hex,
                "payload_bytes_len": len(valid_study_payload),
                "payload_hex": valid_study_payload.hex(),
                "payload_base64": base64.b64encode(valid_study_payload).decode(
                    "ascii"
                ),
            },
            f,
            indent=2,
        )

    # Invalid 1: Tampered byte in display name
    tampered_study = bytearray(valid_study_payload)
    tampered_study[40] ^= 0x01
    with open(
        out_dir / "study_profile_invalid_tampered.json", "w", encoding="utf-8"
    ) as f:
        json.dump(
            {
                "description": "Study profile with one tampered byte in display name; signature fails",
                "expected_result": "BAD_SIGNATURE",
                "mutated_byte_offset": 40,
                "payload_bytes_len": len(tampered_study),
                "payload_hex": tampered_study.hex(),
                "payload_base64": base64.b64encode(tampered_study).decode(
                    "ascii"
                ),
            },
            f,
            indent=2,
        )

    # Invalid 2: Unknown issuer key
    _, unknown_study_payload = encode_study_profile(
        issuer_key_id="TEST-UNKNOWN-KEY-99",
        study_code="SKP01",
        display_name="Sakhipur Community Screening Study",
        partner="icddr,b / Stanford",
        host="intake.neogold.example.bd",
        timestamp_precision="DAY_PLUS_OFFSETS",
        expires_at=datetime.date(2028, 12, 31),
        export_pub_key_bytes=export_pub_bytes,
        priv_key=unknown_priv,
    )
    with open(
        out_dir / "study_profile_invalid_unknown_issuer.json",
        "w",
        encoding="utf-8",
    ) as f:
        json.dump(
            {
                "description": "Study profile signed with key not present in trust list",
                "expected_result": "UNKNOWN_ISSUER",
                "issuer_key_id": "TEST-UNKNOWN-KEY-99",
                "payload_bytes_len": len(unknown_study_payload),
                "payload_hex": unknown_study_payload.hex(),
                "payload_base64": base64.b64encode(
                    unknown_study_payload
                ).decode("ascii"),
            },
            f,
            indent=2,
        )

    # Invalid 3: Wrong purpose (signed with CARD_ISSUER key)
    _, wrong_purpose_study_payload = encode_study_profile(
        issuer_key_id="TEST-CARD-KEY-01",
        study_code="SKP01",
        display_name="Sakhipur Community Screening Study",
        partner="icddr,b / Stanford",
        host="intake.neogold.example.bd",
        timestamp_precision="DAY_PLUS_OFFSETS",
        expires_at=datetime.date(2028, 12, 31),
        export_pub_key_bytes=export_pub_bytes,
        priv_key=card_priv,
    )
    with open(
        out_dir / "study_profile_invalid_wrong_purpose.json",
        "w",
        encoding="utf-8",
    ) as f:
        json.dump(
            {
                "description": "Study profile signed with CARD_ISSUER key instead of STUDY_ISSUER",
                "expected_result": "WRONG_PURPOSE",
                "issuer_key_id": "TEST-CARD-KEY-01",
                "payload_bytes_len": len(wrong_purpose_study_payload),
                "payload_hex": wrong_purpose_study_payload.hex(),
                "payload_base64": base64.b64encode(
                    wrong_purpose_study_payload
                ).decode("ascii"),
            },
            f,
            indent=2,
        )

    # Invalid 4: Expired profile
    _, expired_study_payload = encode_study_profile(
        issuer_key_id="TEST-STUDY-KEY-01",
        study_code="SKP01",
        display_name="Sakhipur Community Screening Study",
        partner="icddr,b / Stanford",
        host="intake.neogold.example.bd",
        timestamp_precision="DAY_PLUS_OFFSETS",
        expires_at=datetime.date(2025, 1, 1),
        export_pub_key_bytes=export_pub_bytes,
        priv_key=study_priv,
    )
    with open(
        out_dir / "study_profile_invalid_expired.json", "w", encoding="utf-8"
    ) as f:
        json.dump(
            {
                "description": "Study profile signature is valid but profile is expired",
                "expected_result": "EXPIRED",
                "expires_at": "2025-01-01",
                "evaluation_date": "2026-10-17",
                "payload_bytes_len": len(expired_study_payload),
                "payload_hex": expired_study_payload.hex(),
                "payload_base64": base64.b64encode(
                    expired_study_payload
                ).decode("ascii"),
            },
            f,
            indent=2,
        )

    # Invalid 5: Revoked study code
    _, revoked_study_payload = encode_study_profile(
        issuer_key_id="TEST-STUDY-KEY-01",
        study_code="STUDY-REVOKED",
        display_name="Revoked Trial",
        partner="Former Partner",
        host="revoked.neogold.example.bd",
        timestamp_precision="DAY_PLUS_OFFSETS",
        expires_at=datetime.date(2028, 12, 31),
        export_pub_key_bytes=export_pub_bytes,
        priv_key=study_priv,
    )
    with open(
        out_dir / "study_profile_invalid_revoked.json", "w", encoding="utf-8"
    ) as f:
        json.dump(
            {
                "description": "Study profile signature is valid but study_code is revoked in trust list",
                "expected_result": "REVOKED",
                "study_code": "STUDY-REVOKED",
                "payload_bytes_len": len(revoked_study_payload),
                "payload_hex": revoked_study_payload.hex(),
                "payload_base64": base64.b64encode(
                    revoked_study_payload
                ).decode("ascii"),
            },
            f,
            indent=2,
        )

    print("Generated all test vectors in", out_dir)


if __name__ == "__main__":
    main()
