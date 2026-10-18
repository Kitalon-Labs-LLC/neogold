#!/usr/bin/env python3
"""neogold-sign: Offline signing and verification tool for NeoGold calibration cards and study profiles.

Features:
- keygen: Generate Ed25519 (or X25519) keypairs with trust-list snippet.
- card-batch: Pack and sign card batch CIELAB measurements (type = 0x01).
- study-profile: Pack and sign study profile configurations (type = 0x02, capped at 251 bytes).
- verify: Offline verification against trust-list.json honoring purpose, validity dates, and revocation.
- Network guard: Refuses to run if any active non-loopback network interface is up unless overridden.
- Audit log: Appends structured signing events to a signing log without logging private keys.
"""

import argparse
import base64
import datetime
import hashlib
import json
import os
import re
import socket
import struct
import subprocess
import sys
from pathlib import Path

try:
    from cryptography.hazmat.primitives.asymmetric import ed25519, x25519
    from cryptography.hazmat.primitives import serialization
except ImportError:
    print("ERROR: cryptography package required. Run: pip install -r card/requirements-dev.txt", file=sys.stderr)
    sys.exit(1)

EPOCH = datetime.date(2020, 1, 1)
BATCH_ID_PATTERN = re.compile(r"^[A-Z0-9][A-Z0-9-]{2,31}$")
STUDY_CODE_PATTERN = re.compile(r"^[A-Za-z0-9_-]{2,32}$")
MAX_STUDY_PROFILE_PAYLOAD_BYTES = 251  # QR Version 11-M capacity ceiling


def date_to_days(d: datetime.date) -> int:
    return (d - EPOCH).days


def days_to_date(days: int) -> datetime.date:
    return EPOCH + datetime.timedelta(days=days)


# ==============================================================================
# Network Guard (Offline Air-Gap Enforcement)
# ==============================================================================

def detect_active_network_interfaces() -> list[str]:
    """Detect active, non-loopback network interfaces using Python standard library."""
    active: list[str] = []

    # 1. Platform-specific detections
    if sys.platform.startswith("win"):
        # On Windows, check ipconfig or socket
        try:
            res = subprocess.run(
                ["ipconfig"],
                capture_output=True,
                text=True,
                check=False,
                timeout=3,
            )
            # Find IPv4 addresses that are not 127.0.0.1 and not empty/disconnected
            for line in res.stdout.splitlines():
                if ("IPv4" in line or "Autoconfiguration IPv4" in line) and ":" in line:
                    parts = line.split(":")
                    ip = parts[-1].strip()
                    if ip and not ip.startswith("127.") and ip != "0.0.0.0":
                        active.append(f"Windows-Adapter({ip})")
        except Exception:
            pass
    elif sys.platform.startswith("linux"):
        # On Linux, inspect /sys/class/net
        net_dir = Path("/sys/class/net")
        if net_dir.exists():
            for iface in net_dir.iterdir():
                if iface.name == "lo":
                    continue
                operstate_file = iface / "operstate"
                if operstate_file.exists():
                    try:
                        state = operstate_file.read_text().strip().lower()
                        if state in ("up", "unknown"):
                            active.append(f"{iface.name}:{state}")
                    except Exception:
                        pass
    elif sys.platform == "darwin":
        # On macOS, check ifconfig
        try:
            res = subprocess.run(
                ["ifconfig"],
                capture_output=True,
                text=True,
                check=False,
                timeout=3,
            )
            # Parse interfaces with status: active
            current_iface = None
            for line in res.stdout.splitlines():
                if line and not line.startswith("\t") and not line.startswith(" "):
                    current_iface = line.split(":")[0]
                if "status: active" in line and current_iface and current_iface != "lo0":
                    active.append(current_iface)
        except Exception:
            pass

    # 2. General socket check (cross-platform fallback)
    try:
        hostname = socket.gethostname()
        for info in socket.getaddrinfo(hostname, None):
            sockaddr = info[4]
            ip = sockaddr[0]
            if ip and not ip.startswith("127.") and ip != "::1" and ip != "0.0.0.0":
                entry = f"host_addr({ip})"
                if entry not in active:
                    active.append(entry)
    except Exception:
        pass

    return active


def enforce_offline_machine(allow_online: bool) -> None:
    """Enforce that neogold-sign is running on an offline machine unless overridden."""
    if allow_online:
        return

    active_interfaces = detect_active_network_interfaces()
    if active_interfaces:
        msg = (
            "CRITICAL SECURITY REFUSAL: Active network interface detected:\n"
            f"  {', '.join(active_interfaces)}\n\n"
            "Per NeoGold Security Plan (doc 8 Section 4), signing keys (K4, K5) must ONLY be\n"
            "used on a dedicated, air-gapped offline machine with all network interfaces disabled.\n\n"
            "To override this check for local testing only, pass '--i-understand-this-is-online'."
        )
        print(msg, file=sys.stderr)
        sys.exit(2)


# ==============================================================================
# Audit Logging
# ==============================================================================

def append_signing_log(
    log_file_path: Path,
    action: str,
    key_id: str,
    identifier: str,
    payload_bytes: bytes,
    operator: str,
    was_online_override: bool,
) -> None:
    """Appends an immutable audit log entry without leaking private key material."""
    payload_sha256 = hashlib.sha256(payload_bytes).hexdigest()
    entry = {
        "timestamp_utc": datetime.datetime.now(datetime.timezone.utc).isoformat(),
        "action": action,
        "key_id": key_id,
        "identifier": identifier,
        "payload_length_bytes": len(payload_bytes),
        "payload_sha256": payload_sha256,
        "operator": operator,
        "online_override": was_online_override,
    }
    log_file_path.parent.mkdir(parents=True, exist_ok=True)
    with open(log_file_path, "a", encoding="utf-8") as f:
        f.write(json.dumps(entry, sort_keys=True) + "\n")


# ==============================================================================
# Binary Payload Encoding
# ==============================================================================

def encode_card_batch_payload(
    issuer_key_id: str,
    batch_id: str,
    issued_at: datetime.date,
    expires_at: datetime.date,
    patches: list[dict],
    private_key: ed25519.Ed25519PrivateKey,
) -> bytes:
    """Pack and sign card batch payload per qr-formats.md Section 2."""
    if not BATCH_ID_PATTERN.match(batch_id):
        raise ValueError(f"Batch ID '{batch_id}' does not match pattern ^[A-Z0-9][A-Z0-9-]{{2,31}}$")

    issuer_bytes = issuer_key_id.encode("ascii")
    if not (3 <= len(issuer_bytes) <= 32):
        raise ValueError(f"Issuer key ID length ({len(issuer_bytes)}) must be between 3 and 32")

    batch_bytes = batch_id.encode("ascii")
    if not (3 <= len(batch_bytes) <= 32):
        raise ValueError(f"Batch ID length ({len(batch_bytes)}) must be between 3 and 32")

    if len(patches) != 23:
        raise ValueError(f"Expected 23 patches, got {len(patches)}")

    issued_days = date_to_days(issued_at)
    expires_days = date_to_days(expires_at)
    if not (0 <= issued_days <= 65535 and 0 <= expires_days <= 65535):
        raise ValueError("Dates out of uint16 range relative to 2020-01-01")

    # Header: type (0x01), version (0x01), issued_at_days (uint16 BE), expires_at_days (uint16 BE)
    header = struct.pack(">BBHH", 0x01, 0x01, issued_days, expires_days)
    issuer_sec = struct.pack(">B", len(issuer_bytes)) + issuer_bytes
    batch_sec = struct.pack(">B", len(batch_bytes)) + batch_bytes

    # Patch records: count (uint8), followed by N * 6 bytes (L* uint16 BE, a* int16 BE, b* int16 BE)
    patch_sec = bytearray()
    patch_sec.append(len(patches))
    for i, p in enumerate(patches):
        # Support dict with 'L', 'a', 'b' or 'lab_d65' or list/tuple [L, a, b]
        if isinstance(p, dict):
            if "lab_d65" in p:
                l_val, a_val, b_val = p["lab_d65"][0], p["lab_d65"][1], p["lab_d65"][2]
            else:
                l_val, a_val, b_val = p["L"], p["a"], p["b"]
        elif isinstance(p, (list, tuple)):
            l_val, a_val, b_val = p[0], p[1], p[2]
        else:
            raise ValueError(f"Patch {i} invalid format: {p}")

        raw_L = int(round(l_val * 100))
        raw_a = int(round(a_val * 100))
        raw_b = int(round(b_val * 100))

        if not (0 <= raw_L <= 10000):
            raise ValueError(f"Patch {i} L* ({l_val}) out of valid range [0, 100]")
        if not (-12800 <= raw_a <= 12700):
            raise ValueError(f"Patch {i} a* ({a_val}) out of valid range [-128, 127]")
        if not (-12800 <= raw_b <= 12700):
            raise ValueError(f"Patch {i} b* ({b_val}) out of valid range [-128, 127]")

        patch_sec.extend(struct.pack(">Hhh", raw_L, raw_a, raw_b))

    signed_bytes = header + issuer_sec + batch_sec + bytes(patch_sec)
    signature = private_key.sign(signed_bytes)
    return signed_bytes + signature


def encode_study_profile_payload(
    issuer_key_id: str,
    study_code: str,
    display_name: str,
    partner: str,
    host: str | None,
    timestamp_precision: str,
    expires_at: datetime.date,
    export_public_key_bytes: bytes,
    private_key: ed25519.Ed25519PrivateKey,
) -> bytes:
    """Pack and sign study profile payload per qr-formats.md Section 3."""
    if not STUDY_CODE_PATTERN.match(study_code):
        raise ValueError(f"Study code '{study_code}' does not match pattern ^[A-Za-z0-9_-]{{2,32}}$")

    issuer_bytes = issuer_key_id.encode("ascii")
    if not (3 <= len(issuer_bytes) <= 32):
        raise ValueError(f"Issuer key ID length ({len(issuer_bytes)}) must be between 3 and 32")

    code_bytes = study_code.encode("ascii")
    if not (2 <= len(code_bytes) <= 32):
        raise ValueError(f"Study code length ({len(code_bytes)}) must be between 2 and 32")

    name_bytes = display_name.encode("utf-8")
    if not (1 <= len(name_bytes) <= 128):
        raise ValueError(f"Display name UTF-8 length ({len(name_bytes)}) must be between 1 and 128")

    partner_bytes = partner.encode("utf-8")
    if not (1 <= len(partner_bytes) <= 128):
        raise ValueError(f"Partner UTF-8 length ({len(partner_bytes)}) must be between 1 and 128")

    host_bytes = (host or "").encode("ascii")
    if len(host_bytes) > 128:
        raise ValueError(f"Host length ({len(host_bytes)}) exceeds 128 bytes")

    if len(export_public_key_bytes) != 32:
        raise ValueError(f"Export public key must be exactly 32 bytes, got {len(export_public_key_bytes)}")

    if timestamp_precision not in ("DAY_PLUS_OFFSETS", "FULL"):
        raise ValueError(f"Invalid timestamp_precision: {timestamp_precision}")
    tp_code = 1 if timestamp_precision == "FULL" else 0

    expires_days = date_to_days(expires_at)
    if not (0 <= expires_days <= 65535):
        raise ValueError("Expiry date out of uint16 range relative to 2020-01-01")

    # Header: type (0x02), version (0x01), expires_at_days (uint16 BE), timestamp_precision (uint8)
    header = struct.pack(">BBHB", 0x02, 0x01, expires_days, tp_code)
    issuer_sec = struct.pack(">B", len(issuer_bytes)) + issuer_bytes
    code_sec = struct.pack(">B", len(code_bytes)) + code_bytes
    name_sec = struct.pack(">B", len(name_bytes)) + name_bytes
    partner_sec = struct.pack(">B", len(partner_bytes)) + partner_bytes
    host_sec = struct.pack(">B", len(host_bytes)) + host_bytes

    signed_bytes = (
        header
        + issuer_sec
        + code_sec
        + name_sec
        + partner_sec
        + host_sec
        + export_public_key_bytes
    )
    total_len = len(signed_bytes) + 64
    if total_len > MAX_STUDY_PROFILE_PAYLOAD_BYTES:
        raise ValueError(
            f"Study profile payload size ({total_len} bytes) exceeds maximum allowable limit "
            f"of {MAX_STUDY_PROFILE_PAYLOAD_BYTES} bytes (QR Version 11-M capacity ceiling)."
        )

    signature = private_key.sign(signed_bytes)
    return signed_bytes + signature


# ==============================================================================
# Verification
# ==============================================================================

def verify_payload(payload: bytes, trust_list: dict, eval_date: datetime.date | None = None) -> tuple[str, dict | None]:
    """Offline verification of payload against trust list."""
    if eval_date is None:
        eval_date = datetime.date.today()

    if len(payload) < 6 + 64:
        return "MALFORMED", None

    payload_type = payload[0]
    format_version = payload[1]

    if format_version != 0x01:
        return "MALFORMED", None

    # Check trust list date validity window
    try:
        tl_from = datetime.date.fromisoformat(trust_list["valid_from"])
        tl_until = datetime.date.fromisoformat(trust_list["valid_until"])
        if not (tl_from <= eval_date <= tl_until):
            return "EXPIRED", None
    except Exception:
        return "MALFORMED", None

    if payload_type == 0x01:
        # Card batch
        if len(payload) < 6 + 1 + 1 + 1 + 64:
            return "MALFORMED", None

        signed_bytes = payload[:-64]
        signature = payload[-64:]

        offset = 6
        k_len = payload[offset]
        offset += 1
        if len(payload) < offset + k_len + 64:
            return "MALFORMED", None
        try:
            issuer_key_id = payload[offset : offset + k_len].decode("ascii")
        except UnicodeDecodeError:
            return "MALFORMED", None
        offset += k_len

        # Step 2: Issuer lookup
        matching = [k for k in trust_list.get("keys", []) if k.get("key_id") == issuer_key_id]
        if not matching:
            return "UNKNOWN_ISSUER", None
        key_entry = matching[0]

        # Step 3: Purpose binding
        if key_entry.get("purpose") != "CARD_ISSUER":
            return "WRONG_PURPOSE", None

        # Step 4: Signature verification
        try:
            pub_bytes = bytes.fromhex(key_entry["public_key_hex"])
            pub_key = ed25519.Ed25519PublicKey.from_public_bytes(pub_bytes)
            pub_key.verify(signature, signed_bytes)
        except Exception:
            return "BAD_SIGNATURE", None

        # Step 5: Revocation check
        b_len = payload[offset]
        offset += 1
        if len(payload) < offset + b_len + 64:
            return "MALFORMED", None
        try:
            batch_id = payload[offset : offset + b_len].decode("ascii")
        except UnicodeDecodeError:
            return "MALFORMED", None
        offset += b_len

        if not BATCH_ID_PATTERN.match(batch_id):
            return "MALFORMED", None

        if batch_id in trust_list.get("revoked_card_batches", []):
            return "REVOKED", None

        # Step 6: Date validity check
        try:
            k_from = datetime.date.fromisoformat(key_entry["valid_from"])
            k_until = datetime.date.fromisoformat(key_entry["valid_until"])
            if not (k_from <= eval_date <= k_until):
                return "EXPIRED", None
        except Exception:
            return "EXPIRED", None

        issued_days, expires_days = struct.unpack(">HH", payload[2:6])
        exp_date = days_to_date(expires_days)
        if eval_date > exp_date:
            return "EXPIRED", None

        # Step 7: Parse patches
        n_patches = payload[offset]
        offset += 1
        if n_patches != 23:
            return "MALFORMED", None

        if len(payload) != offset + (n_patches * 6) + 64:
            return "MALFORMED", None

        patches = []
        for _ in range(n_patches):
            raw_L, raw_a, raw_b = struct.unpack(">Hhh", payload[offset : offset + 6])
            offset += 6
            patches.append({
                "L": raw_L / 100.0,
                "a": raw_a / 100.0,
                "b": raw_b / 100.0,
            })

        return "VERIFIED", {
            "type": "card_batch",
            "batch_id": batch_id,
            "issuer_key_id": issuer_key_id,
            "issued_at": days_to_date(issued_days).isoformat(),
            "expires_at": exp_date.isoformat(),
            "patch_count": n_patches,
            "patches": patches,
        }

    elif payload_type == 0x02:
        # Study profile
        if len(payload) < 5 + 1 + 1 + 1 + 1 + 1 + 32 + 64:
            return "MALFORMED", None

        signed_bytes = payload[:-64]
        signature = payload[-64:]

        expires_days, tp_code = struct.unpack(">HB", payload[2:5])
        if tp_code not in (0, 1):
            return "MALFORMED", None
        timestamp_precision = "FULL" if tp_code == 1 else "DAY_PLUS_OFFSETS"

        offset = 5
        k_len = payload[offset]
        offset += 1
        if len(payload) < offset + k_len + 64:
            return "MALFORMED", None
        try:
            issuer_key_id = payload[offset : offset + k_len].decode("ascii")
        except UnicodeDecodeError:
            return "MALFORMED", None
        offset += k_len

        # Step 2: Lookup issuer key
        matching = [k for k in trust_list.get("keys", []) if k.get("key_id") == issuer_key_id]
        if not matching:
            return "UNKNOWN_ISSUER", None
        key_entry = matching[0]

        # Step 3: Check purpose
        if key_entry.get("purpose") != "STUDY_ISSUER":
            return "WRONG_PURPOSE", None

        # Step 4: Verify signature
        try:
            pub_bytes = bytes.fromhex(key_entry["public_key_hex"])
            pub_key = ed25519.Ed25519PublicKey.from_public_bytes(pub_bytes)
            pub_key.verify(signature, signed_bytes)
        except Exception:
            return "BAD_SIGNATURE", None

        # Step 5: Check revocation
        s_len = payload[offset]
        offset += 1
        if len(payload) < offset + s_len + 64:
            return "MALFORMED", None
        try:
            study_code = payload[offset : offset + s_len].decode("ascii")
        except UnicodeDecodeError:
            return "MALFORMED", None
        offset += s_len

        if not STUDY_CODE_PATTERN.match(study_code):
            return "MALFORMED", None

        if study_code in trust_list.get("revoked_study_codes", []):
            return "REVOKED", None

        # Step 6: Check key validity dates & payload expiry
        try:
            k_from = datetime.date.fromisoformat(key_entry["valid_from"])
            k_until = datetime.date.fromisoformat(key_entry["valid_until"])
            if not (k_from <= eval_date <= k_until):
                return "EXPIRED", None
        except Exception:
            return "EXPIRED", None

        exp_date = days_to_date(expires_days)
        if eval_date > exp_date:
            return "EXPIRED", None

        # Step 7: Parse strings and public key
        d_len = payload[offset]
        offset += 1
        if len(payload) < offset + d_len + 64:
            return "MALFORMED", None
        try:
            display_name = payload[offset : offset + d_len].decode("utf-8")
        except UnicodeDecodeError:
            return "MALFORMED", None
        offset += d_len

        p_len = payload[offset]
        offset += 1
        if len(payload) < offset + p_len + 64:
            return "MALFORMED", None
        try:
            partner = payload[offset : offset + p_len].decode("utf-8")
        except UnicodeDecodeError:
            return "MALFORMED", None
        offset += p_len

        h_len = payload[offset]
        offset += 1
        if len(payload) < offset + h_len + 64:
            return "MALFORMED", None
        try:
            host = payload[offset : offset + h_len].decode("ascii") if h_len > 0 else None
        except UnicodeDecodeError:
            return "MALFORMED", None
        offset += h_len

        if len(payload) != offset + 32 + 64:
            return "MALFORMED", None

        export_pub_key = payload[offset : offset + 32]
        offset += 32

        signed_bytes = payload[:offset]
        signature = payload[offset : offset + 64]

        # 1. Lookup issuer key
        matching = [k for k in trust_list.get("keys", []) if k.get("key_id") == issuer_key_id]
        if not matching:
            return "UNKNOWN_ISSUER", None
        key_entry = matching[0]

        # 2. Check purpose
        if key_entry.get("purpose") != "STUDY_ISSUER":
            return "WRONG_PURPOSE", None

        # 3. Verify signature
        try:
            pub_bytes = bytes.fromhex(key_entry["public_key_hex"])
            pub_key = ed25519.Ed25519PublicKey.from_public_bytes(pub_bytes)
            pub_key.verify(signature, signed_bytes)
        except Exception:
            return "BAD_SIGNATURE", None

        # 4. Check revocation
        if study_code in trust_list.get("revoked_study_codes", []):
            return "REVOKED", None

        # 5. Check key validity dates
        try:
            k_from = datetime.date.fromisoformat(key_entry["valid_from"])
            k_until = datetime.date.fromisoformat(key_entry["valid_until"])
            if not (k_from <= eval_date <= k_until):
                return "EXPIRED", None
        except Exception:
            return "EXPIRED", None

        # 6. Check payload expiry
        exp_date = days_to_date(expires_days)
        if eval_date > exp_date:
            return "EXPIRED", None

        return "VERIFIED", {
            "type": "study_profile",
            "study_code": study_code,
            "display_name": display_name,
            "partner": partner,
            "host": host,
            "timestamp_precision": timestamp_precision,
            "expires_at": exp_date.isoformat(),
            "export_public_key_hex": export_pub_key.hex(),
        }

    else:
        return "MALFORMED", None


# ==============================================================================
# Helper Utilities
# ==============================================================================

def load_private_key(key_input: str) -> ed25519.Ed25519PrivateKey:
    """Load an Ed25519 private key from a 64-char hex string or a file."""
    p = Path(key_input)
    if p.exists() and p.is_file():
        text = p.read_text(encoding="utf-8").strip()
    else:
        text = key_input.strip()

    try:
        raw_bytes = bytes.fromhex(text)
        if len(raw_bytes) != 32:
            raise ValueError(f"Ed25519 seed/private key must be 32 bytes (64 hex characters), got {len(raw_bytes)}")
        return ed25519.Ed25519PrivateKey.from_private_bytes(raw_bytes)
    except Exception as e:
        raise ValueError(f"Failed to parse private key: {e}")


def load_payload_bytes(payload_input: str) -> bytes:
    """Load raw payload bytes from hex string, base64 string, or binary file."""
    p = Path(payload_input)
    if p.exists() and p.is_file():
        data = p.read_bytes()
        # If it looks like text (hex or base64 or json)
        try:
            text = data.decode("utf-8").strip()
            # If JSON
            if text.startswith("{"):
                doc = json.loads(text)
                if "payload_hex" in doc:
                    return bytes.fromhex(doc["payload_hex"])
                if "payload_base64" in doc:
                    return base64.b64decode(doc["payload_base64"])
            # Try hex
            if re.match(r"^[0-9a-fA-F]+$", text):
                return bytes.fromhex(text)
            # Try base64
            return base64.b64decode(text)
        except Exception:
            return data
    else:
        text = payload_input.strip()
        if re.match(r"^[0-9a-fA-F]+$", text):
            return bytes.fromhex(text)
        try:
            return base64.b64decode(text)
        except Exception as e:
            raise ValueError(f"Could not parse payload as hex or base64: {e}")


# ==============================================================================
# CLI Commands
# ==============================================================================

def cmd_keygen(args) -> int:
    algorithm = args.algorithm.upper()
    purpose = args.purpose.upper()

    if algorithm == "ED25519":
        priv = ed25519.Ed25519PrivateKey.generate()
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
    elif algorithm == "X25519":
        priv = x25519.X25519PrivateKey.generate()
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
    else:
        print(f"ERROR: Unsupported algorithm: {algorithm}", file=sys.stderr)
        return 1

    pub_hex = pub_bytes.hex()
    priv_hex = priv_bytes.hex()

    today = datetime.date.today()
    in_two_years = today.replace(year=today.year + 2)

    trust_list_entry = {
        "key_id": args.key_id,
        "purpose": purpose,
        "algorithm": algorithm,
        "public_key_hex": pub_hex,
        "valid_from": today.isoformat(),
        "valid_until": in_two_years.isoformat(),
        "description": args.description or f"Key for {purpose} generated on {today.isoformat()}",
    }

    if args.test_only:
        trust_list_entry["test_only"] = True

    if args.out_private:
        Path(args.out_private).write_text(priv_hex + "\n", encoding="utf-8")
        print(f"Private key written to: {args.out_private}")

    if args.out_public:
        Path(args.out_public).write_text(pub_hex + "\n", encoding="utf-8")
        print(f"Public key hex written to: {args.out_public}")

    print("\nGenerated Keypair:")
    print(f"  Key ID:     {args.key_id}")
    print(f"  Algorithm:  {algorithm}")
    print(f"  Purpose:    {purpose}")
    print(f"  Public Hex: {pub_hex}")
    if not args.out_private:
        print(f"  Private:    {priv_hex} (KEEP SECURE / AIR-GAPPED)")

    print("\nTrust List Entry (copy to config/trust-list.json):")
    print(json.dumps(trust_list_entry, indent=2))
    return 0


def cmd_card_batch(args) -> int:
    enforce_offline_machine(args.i_understand_this_is_online)

    priv_key = load_private_key(args.private_key)
    issued_at = datetime.date.fromisoformat(args.issued_at)
    expires_at = datetime.date.fromisoformat(args.expires_at)

    # Load patches
    patches_path = Path(args.patches)
    if patches_path.exists() and patches_path.is_file():
        patches_data = json.loads(patches_path.read_text(encoding="utf-8"))
    else:
        patches_data = json.loads(args.patches)

    if isinstance(patches_data, dict):
        if "patches" in patches_data:
            patches = patches_data["patches"]
        elif "targets" in patches_data:
            patches = patches_data["targets"]
        else:
            raise ValueError("JSON patch dictionary must have 'patches' or 'targets' key")
    elif isinstance(patches_data, list):
        patches = patches_data
    else:
        raise ValueError("Invalid patches JSON format")

    payload = encode_card_batch_payload(
        issuer_key_id=args.key_id,
        batch_id=args.batch_id,
        issued_at=issued_at,
        expires_at=expires_at,
        patches=patches,
        private_key=priv_key,
    )

    log_path = Path(args.log_file)
    append_signing_log(
        log_file_path=log_path,
        action="card-batch",
        key_id=args.key_id,
        identifier=args.batch_id,
        payload_bytes=payload,
        operator=args.operator,
        was_online_override=args.i_understand_this_is_online,
    )

    payload_hex = payload.hex()
    payload_b64 = base64.b64encode(payload).decode("ascii")

    if args.out:
        out_p = Path(args.out)
        if out_p.suffix == ".bin":
            out_p.write_bytes(payload)
        else:
            out_doc = {
                "batch_id": args.batch_id,
                "issuer_key_id": args.key_id,
                "issued_at": args.issued_at,
                "expires_at": args.expires_at,
                "patch_count": len(patches),
                "payload_bytes_len": len(payload),
                "payload_hex": payload_hex,
                "payload_base64": payload_b64,
            }
            out_p.write_text(json.dumps(out_doc, indent=2) + "\n", encoding="utf-8")
        print(f"Output saved to {args.out}")

    print(f"Successfully signed card batch '{args.batch_id}' ({len(payload)} bytes):")
    print(f"Hex:    {payload_hex}")
    print(f"Base64: {payload_b64}")
    return 0


def cmd_study_profile(args) -> int:
    enforce_offline_machine(args.i_understand_this_is_online)

    priv_key = load_private_key(args.private_key)
    expires_at = datetime.date.fromisoformat(args.expires_at)

    # Export public key
    exp_key_str = args.export_public_key.strip()
    exp_path = Path(exp_key_str)
    if exp_path.exists() and exp_path.is_file():
        exp_key_str = exp_path.read_text(encoding="utf-8").strip()
    export_pub_bytes = bytes.fromhex(exp_key_str)

    payload = encode_study_profile_payload(
        issuer_key_id=args.key_id,
        study_code=args.study_code,
        display_name=args.display_name,
        partner=args.partner,
        host=args.host,
        timestamp_precision=args.timestamp_precision,
        expires_at=expires_at,
        export_public_key_bytes=export_pub_bytes,
        private_key=priv_key,
    )

    log_path = Path(args.log_file)
    append_signing_log(
        log_file_path=log_path,
        action="study-profile",
        key_id=args.key_id,
        identifier=args.study_code,
        payload_bytes=payload,
        operator=args.operator,
        was_online_override=args.i_understand_this_is_online,
    )

    payload_hex = payload.hex()
    payload_b64 = base64.b64encode(payload).decode("ascii")

    if args.out:
        out_p = Path(args.out)
        if out_p.suffix == ".bin":
            out_p.write_bytes(payload)
        else:
            out_doc = {
                "study_code": args.study_code,
                "issuer_key_id": args.key_id,
                "display_name": args.display_name,
                "partner": args.partner,
                "host": args.host,
                "timestamp_precision": args.timestamp_precision,
                "expires_at": args.expires_at,
                "export_public_key_hex": exp_key_str,
                "payload_bytes_len": len(payload),
                "payload_hex": payload_hex,
                "payload_base64": payload_b64,
            }
            out_p.write_text(json.dumps(out_doc, indent=2) + "\n", encoding="utf-8")
        print(f"Output saved to {args.out}")

    print(f"Successfully signed study profile '{args.study_code}' ({len(payload)} bytes):")
    print(f"Hex:    {payload_hex}")
    print(f"Base64: {payload_b64}")
    return 0


def cmd_verify(args) -> int:
    payload = load_payload_bytes(args.payload)

    trust_list_path = Path(args.trust_list)
    if not trust_list_path.exists():
        print(f"ERROR: Trust list file not found: {trust_list_path}", file=sys.stderr)
        return 1

    with open(trust_list_path, "r", encoding="utf-8") as f:
        trust_list = json.load(f)

    eval_date = datetime.date.fromisoformat(args.date) if args.date else datetime.date.today()

    result_code, details = verify_payload(payload, trust_list, eval_date)
    print(f"Verification Result: {result_code}")
    if details:
        print("Decoded Details:")
        print(json.dumps(details, indent=2))

    return 0 if result_code == "VERIFIED" else 1


# ==============================================================================
# Main Parser Definition
# ==============================================================================

def main():
    parser = argparse.ArgumentParser(
        prog="neogold-sign",
        description="NeoGold offline signing and verification tool for calibration cards and study profiles.",
    )
    subparsers = parser.add_subparsers(dest="command", required=True)

    # 1. keygen
    p_key = subparsers.add_parser("keygen", help="Generate an Ed25519 or X25519 keypair.")
    p_key.add_argument("--key-id", required=True, help="Unique identifier for key (e.g., TEST-CARD-KEY-01)")
    p_key.add_argument("--purpose", required=True, choices=["CARD_ISSUER", "STUDY_ISSUER", "STUDY_EXPORT"])
    p_key.add_argument("--algorithm", default="Ed25519", choices=["Ed25519", "X25519"])
    p_key.add_argument("--description", default="", help="Description for trust list entry")
    p_key.add_argument("--test-only", action="store_true", help="Mark key as test-only")
    p_key.add_argument("--out-private", help="File to write private key hex")
    p_key.add_argument("--out-public", help="File to write public key hex")

    # 2. card-batch
    p_batch = subparsers.add_parser("card-batch", help="Pack and sign a card batch payload.")
    p_batch.add_argument("--key-id", required=True, help="Key ID in trust list")
    p_batch.add_argument("--private-key", required=True, help="Private key (hex string or file)")
    p_batch.add_argument("--batch-id", required=True, help="Batch ID (pattern ^[A-Z0-9][A-Z0-9-]{2,31}$)")
    p_batch.add_argument("--issued-at", required=True, help="Issued date (YYYY-MM-DD)")
    p_batch.add_argument("--expires-at", required=True, help="Expiration date (YYYY-MM-DD)")
    p_batch.add_argument("--patches", required=True, help="Path to patches JSON file or JSON string")
    p_batch.add_argument("--out", help="Output JSON or BIN file")
    p_batch.add_argument("--operator", default="unknown", help="Name or ID of signing operator")
    p_batch.add_argument("--log-file", default="signing-log.jsonl", help="Path to signing audit log")
    p_batch.add_argument("--i-understand-this-is-online", action="store_true", help="Bypass offline network check for testing")

    # 3. study-profile
    p_study = subparsers.add_parser("study-profile", help="Pack and sign a study profile payload.")
    p_study.add_argument("--key-id", required=True, help="Key ID in trust list")
    p_study.add_argument("--private-key", required=True, help="Private key (hex string or file)")
    p_study.add_argument("--study-code", required=True, help="Study code (pattern ^[A-Za-z0-9_-]{2,32}$)")
    p_study.add_argument("--display-name", required=True, help="Human-readable study name (max 128 bytes UTF-8)")
    p_study.add_argument("--partner", required=True, help="Partner / institution name (max 128 bytes UTF-8)")
    p_study.add_argument("--host", default=None, help="Intake server hostname (optional)")
    p_study.add_argument("--timestamp-precision", required=True, choices=["DAY_PLUS_OFFSETS", "FULL"])
    p_study.add_argument("--expires-at", required=True, help="Expiration date (YYYY-MM-DD)")
    p_study.add_argument("--export-public-key", required=True, help="32-byte X25519 public key (hex or file)")
    p_study.add_argument("--out", help="Output JSON or BIN file")
    p_study.add_argument("--operator", default="unknown", help="Name or ID of signing operator")
    p_study.add_argument("--log-file", default="signing-log.jsonl", help="Path to signing audit log")
    p_study.add_argument("--i-understand-this-is-online", action="store_true", help="Bypass offline network check for testing")

    # 4. verify
    p_ver = subparsers.add_parser("verify", help="Verify payload against trust list.")
    p_ver.add_argument("--payload", required=True, help="Payload (hex string, base64 string, or file)")
    p_ver.add_argument("--trust-list", default="config/trust-list.json", help="Path to trust list JSON")
    p_ver.add_argument("--date", help="Evaluation date (YYYY-MM-DD), defaults to today")

    args = parser.parse_args()

    if args.command == "keygen":
        return cmd_keygen(args)
    elif args.command == "card-batch":
        return cmd_card_batch(args)
    elif args.command == "study-profile":
        return cmd_study_profile(args)
    elif args.command == "verify":
        return cmd_verify(args)
    else:
        parser.print_help()
        return 1


if __name__ == "__main__":
    sys.exit(main())
