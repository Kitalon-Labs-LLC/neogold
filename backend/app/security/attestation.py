from __future__ import annotations

import base64
import hashlib
from typing import Any

from cryptography import x509
from cryptography.hazmat.primitives.asymmetric import ec

from app.security.jwk import base64url_encode


def parse_der_tlv(der_bytes: bytes, offset: int = 0) -> tuple[int, bytes, int]:
    """Parse tag, value, and next_offset from DER bytes."""
    tag = der_bytes[offset]
    offset += 1
    length_byte = der_bytes[offset]
    offset += 1
    if length_byte < 0x80:
        length = length_byte
    else:
        num_len_bytes = length_byte & 0x7F
        length = int.from_bytes(der_bytes[offset : offset + num_len_bytes], "big")
        offset += num_len_bytes
    value = der_bytes[offset : offset + length]
    next_offset = offset + length
    return tag, value, next_offset


def extract_attestation_challenge(ext_bytes: bytes) -> bytes | None:
    """Extract attestationChallenge (5th field in KeyDescription sequence)."""
    try:
        tag, seq_bytes, _ = parse_der_tlv(ext_bytes, 0)
        if tag != 0x30:
            return None
        offset = 0
        elements = []
        while offset < len(seq_bytes):
            elem_tag, elem_val, offset = parse_der_tlv(seq_bytes, offset)
            elements.append((elem_tag, elem_val))
            if len(elements) == 5:
                break
        if len(elements) >= 5 and elements[4][0] == 0x04:
            return elements[4][1]
    except Exception:
        return None
    return None


def verify_attestation(
    key_attestation_chain: list[str] | None,
    enrollment_code: str,
    device_public_key: dict[str, Any],
) -> str:
    """Verifies Android Key Attestation chain.
    Returns: 'NONE', 'VERIFIED', or 'FAILED'.
    """
    if not key_attestation_chain:
        return "NONE"

    try:
        certs: list[x509.Certificate] = []
        for cert_b64 in key_attestation_chain:
            der = base64.b64decode(cert_b64)
            certs.append(x509.load_der_x509_certificate(der))

        if not certs:
            return "NONE"

        leaf_cert = certs[0]

        # 1. Verify certificate chain signatures
        for i in range(len(certs) - 1):
            cert = certs[i]
            issuer_cert = certs[i + 1]
            issuer_key = issuer_cert.public_key()
            issuer_key.verify(
                cert.signature,
                cert.tbs_certificate_bytes,
                cert.signature_hash_algorithm,
            )

        # 2. Verify leaf certificate public key matches device_public_key
        leaf_pubkey = leaf_cert.public_key()
        if not isinstance(leaf_pubkey, ec.EllipticCurvePublicKey):
            return "FAILED"

        pub_numbers = leaf_pubkey.public_numbers()
        leaf_x = base64url_encode(pub_numbers.x.to_bytes(32, "big"))
        leaf_y = base64url_encode(pub_numbers.y.to_bytes(32, "big"))

        if leaf_x != device_public_key.get("x") or leaf_y != device_public_key.get("y"):
            return "FAILED"

        # 3. Verify Key Attestation extension and challenge
        attestation_oid = x509.ObjectIdentifier("1.3.6.1.4.1.11129.2.1.17")
        try:
            ext = leaf_cert.extensions.get_extension_for_oid(attestation_oid)
        except x509.ExtensionNotFound:
            return "FAILED"

        expected_challenge = hashlib.sha256(enrollment_code.encode("utf-8")).digest()
        actual_challenge = extract_attestation_challenge(ext.value.value)
        if actual_challenge != expected_challenge:
            return "FAILED"

        return "VERIFIED"
    except Exception:
        return "FAILED"
