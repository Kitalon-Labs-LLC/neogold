from __future__ import annotations

import base64
import hashlib
import json

from cryptography.exceptions import InvalidSignature
from cryptography.hazmat.primitives import hashes
from cryptography.hazmat.primitives.asymmetric import ec, utils


def base64url_encode(data: bytes) -> str:
    return base64.urlsafe_b64encode(data).decode("ascii").rstrip("=")


def base64url_decode(data: str) -> bytes:
    padding = "=" * ((4 - len(data) % 4) % 4)
    return base64.urlsafe_b64decode(data + padding)


def compute_jwk_thumbprint(jwk: dict) -> str:
    """RFC 7638 JWK Thumbprint for EC P-256 keys."""
    canonical = {
        "crv": jwk["crv"],
        "kty": jwk["kty"],
        "x": jwk["x"],
        "y": jwk["y"],
    }
    canonical_json = json.dumps(canonical, separators=(",", ":"), sort_keys=True).encode("utf-8")
    digest = hashlib.sha256(canonical_json).digest()
    return base64url_encode(digest)


def compute_bsh(body: bytes) -> str:
    """Computes base64url SHA-256 of the body bytes."""
    return base64url_encode(hashlib.sha256(body).digest())


def load_p256_public_key(jwk: dict) -> ec.EllipticCurvePublicKey:
    x_bytes = base64url_decode(jwk["x"])
    y_bytes = base64url_decode(jwk["y"])
    if len(x_bytes) != 32 or len(y_bytes) != 32:
        raise ValueError("Invalid P-256 coordinates length")
    raw_point = b"\x04" + x_bytes + y_bytes
    return ec.EllipticCurvePublicKey.from_encoded_point(ec.SECP256R1(), raw_point)


def verify_es256_signature(
    public_key_jwk: dict,
    signing_input: bytes,
    signature_bytes: bytes,
) -> bool:
    """Verifies ES256 (P-256 + SHA256) signature in JOSE 64-byte (r || s) format."""
    if len(signature_bytes) != 64:
        return False
    try:
        pubkey = load_p256_public_key(public_key_jwk)
        r = int.from_bytes(signature_bytes[:32], "big")
        s = int.from_bytes(signature_bytes[32:], "big")
        der_signature = utils.encode_dss_signature(r, s)
        pubkey.verify(der_signature, signing_input, ec.ECDSA(hashes.SHA256()))
        return True
    except (InvalidSignature, ValueError, Exception):
        return False
