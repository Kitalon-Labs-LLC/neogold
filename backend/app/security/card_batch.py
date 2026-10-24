from __future__ import annotations

import datetime
import json
import os
import struct
from pathlib import Path

from cryptography.exceptions import InvalidSignature
from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PublicKey

from app.errors import CardBatchInvalidError

EPOCH = datetime.date(2020, 1, 1)


def days_to_date(days: int) -> datetime.date:
    return EPOCH + datetime.timedelta(days=days)


def verify_card_batch_payload(
    qr_payload_bytes: bytes,
    trust_list_path: str = "config/trust-list.json",
) -> dict:
    if len(qr_payload_bytes) < 71:  # min header + 64 bytes sig
        raise CardBatchInvalidError("QR payload too short")

    signed_bytes = qr_payload_bytes[:-64]
    signature = qr_payload_bytes[-64:]

    try:
        offset = 0
        payload_type, format_version, issued_at_days, expires_at_days = struct.unpack_from(
            ">BBHH", signed_bytes, offset
        )
        offset += 6

        if payload_type != 0x01 or format_version != 0x01:
            raise CardBatchInvalidError("Invalid payload type or format version")

        issuer_key_id_len = signed_bytes[offset]
        offset += 1
        issuer_key_id = signed_bytes[offset : offset + issuer_key_id_len].decode("ascii")
        offset += issuer_key_id_len

        batch_id_len = signed_bytes[offset]
        offset += 1
        batch_id = signed_bytes[offset : offset + batch_id_len].decode("ascii")
        offset += batch_id_len

        patch_count = signed_bytes[offset]
        offset += 1

        patches = []
        for _ in range(patch_count):
            L, a, b = struct.unpack_from(">Hhh", signed_bytes, offset)
            offset += 6
            patches.append({
                "L": round(L / 100.0, 2),
                "a": round(a / 100.0, 2),
                "b": round(b / 100.0, 2),
            })
    except Exception as e:
        if isinstance(e, CardBatchInvalidError):
            raise
        raise CardBatchInvalidError(f"Malformed card batch payload: {e}") from e

    # Load trust list
    if not os.path.exists(trust_list_path):
        # Check root relative path if needed
        alt_path = Path(__file__).resolve().parents[3] / trust_list_path
        if alt_path.exists():
            trust_list_path = str(alt_path)

    try:
        with open(trust_list_path, encoding="utf-8") as f:
            trust_data = json.load(f)
    except Exception as e:
        raise CardBatchInvalidError(f"Cannot load trust list: {e}") from e

    # Check revocation
    revoked_batches = trust_data.get("revoked_card_batches", [])
    if batch_id in revoked_batches:
        raise CardBatchInvalidError(f"Card batch {batch_id} is revoked")

    # Find issuer key
    issuer_key_entry = None
    for k in trust_data.get("keys", []):
        if k.get("key_id") == issuer_key_id:
            issuer_key_entry = k
            break

    if not issuer_key_entry:
        raise CardBatchInvalidError(f"Issuer key {issuer_key_id} not found in trust list")

    if issuer_key_entry.get("purpose") != "CARD_ISSUER":
        raise CardBatchInvalidError(f"Key {issuer_key_id} purpose is not CARD_ISSUER")

    # Verify Ed25519 signature
    pub_hex = issuer_key_entry.get("public_key_hex")
    if not pub_hex:
        raise CardBatchInvalidError("Missing public key hex in trust list")

    try:
        pubkey = Ed25519PublicKey.from_public_bytes(bytes.fromhex(pub_hex))
        pubkey.verify(signature, signed_bytes)
    except (InvalidSignature, Exception) as e:
        raise CardBatchInvalidError(f"Card batch signature verification failed: {e}") from e

    issued_at = days_to_date(issued_at_days)
    expires_at = days_to_date(expires_at_days)

    return {
        "batch_id": batch_id,
        "issuer_id": issuer_key_id,
        "signature_valid": True,
        "issued_at": issued_at,
        "expires_at": expires_at,
        "reference_values": patches,
    }
