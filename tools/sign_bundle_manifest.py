#!/usr/bin/env python3
"""Build-time tool that hashes model, triage config, and trust lists,
and signs the bundle manifest with key K3 (Ed25519).

Usage:
    python tools/sign_bundle_manifest.py [--config-dir config] [--assets-dir android/app/src/main/assets] [--manifest-key path/or/hex]
"""

import argparse
import base64
import hashlib
import json
import os
import shutil
import sys
from pathlib import Path

try:
    from cryptography.hazmat.primitives.asymmetric import ed25519
except ImportError:
    print("ERROR: cryptography package required. Run: pip install cryptography", file=sys.stderr)
    sys.exit(1)

# Default test K3 keypair (for local/debug builds when no key is passed)
DEFAULT_TEST_K3_PRIVATE_HEX = "86b074c2d51b34c46b927d10f9c94bcc087bc317de256d849594045f4bf08893"
DEFAULT_TEST_K3_PUBLIC_HEX = "7ea85ce6309d111e3446befeb992a4335907473c37534ca42df7e99e191c4772"

# Core clinical bundle assets required for app integrity
REQUIRED_BUNDLE_FILES = [
    "model.json",
    "triage-config.json",
    "trust-list.json",
]
OPTIONAL_BUNDLE_FILES = [
    "model-card.json",
    "card-trust-list.json",
    "study-trust-list.json",
]


def load_private_key(key_input: str | None) -> tuple[ed25519.Ed25519PrivateKey, str]:
    if not key_input:
        priv_bytes = bytes.fromhex(DEFAULT_TEST_K3_PRIVATE_HEX)
        key = ed25519.Ed25519PrivateKey.from_private_bytes(priv_bytes)
        pub_hex = key.public_key().public_bytes_raw().hex()
        return key, pub_hex

    # Check if key_input is a file
    key_path = Path(key_input)
    if key_path.is_file():
        content = key_path.read_bytes().strip()
    else:
        content = key_input.strip().encode("utf-8")

    # Try raw 32 bytes
    if len(content) == 32:
        key = ed25519.Ed25519PrivateKey.from_private_bytes(content)
        return key, key.public_key().public_bytes_raw().hex()

    # Try 64-char hex
    text = content.decode("utf-8", errors="ignore").strip()
    if len(text) == 64:
        try:
            raw = bytes.fromhex(text)
            key = ed25519.Ed25519PrivateKey.from_private_bytes(raw)
            return key, key.public_key().public_bytes_raw().hex()
        except ValueError:
            pass

    # Try base64
    try:
        raw = base64.b64decode(text)
        if len(raw) == 32:
            key = ed25519.Ed25519PrivateKey.from_private_bytes(raw)
            return key, key.public_key().public_bytes_raw().hex()
    except Exception:
        pass

    raise ValueError(f"Unable to parse Ed25519 private key from input: {key_input}")


def compute_file_sha256(path: Path) -> str:
    h = hashlib.sha256()
    with open(path, "rb") as f:
        while chunk := f.read(65536):
            h.update(chunk)
    return h.hexdigest()


def canonical_sign_payload(file_hashes: dict[str, str]) -> bytes:
    """Canonical UTF-8 text representation of sorted file hashes."""
    lines = [f"{name}:{file_hashes[name]}" for name in sorted(file_hashes.keys())]
    return ("\n".join(lines) + "\n").encode("utf-8")


def generate_bundle_manifest(
    config_dir: Path,
    assets_dir: Path,
    manifest_key_input: str | None = None,
) -> dict:
    assets_dir.mkdir(parents=True, exist_ok=True)
    priv_key, pub_hex = load_private_key(manifest_key_input)

    file_hashes: dict[str, str] = {}

    # Copy and hash required files
    for filename in REQUIRED_BUNDLE_FILES:
        src = config_dir / filename
        if not src.exists():
            raise FileNotFoundError(f"Required bundle asset not found in config: {src}")
        dest = assets_dir / filename
        shutil.copy2(src, dest)
        file_hashes[filename] = compute_file_sha256(dest)

    # Copy and hash optional files if present
    for filename in OPTIONAL_BUNDLE_FILES:
        src = config_dir / filename
        if src.exists():
            dest = assets_dir / filename
            shutil.copy2(src, dest)
            file_hashes[filename] = compute_file_sha256(dest)

    canonical_bytes = canonical_sign_payload(file_hashes)
    signature_bytes = priv_key.sign(canonical_bytes)
    signature_b64 = base64.b64encode(signature_bytes).decode("ascii")

    manifest = {
        "formatVersion": 1,
        "manifestVersion": "1.0.0",
        "keyId": "K3",
        "signerPublicKeyHex": pub_hex,
        "files": file_hashes,
        "signature": signature_b64,
    }

    manifest_dest = assets_dir / "bundle-manifest.json"
    with open(manifest_dest, "w", encoding="utf-8") as f:
        json.dump(manifest, f, indent=2, sort_keys=True)
        f.write("\n")

    return manifest


def main():
    parser = argparse.ArgumentParser(description="Sign clinical bundle assets with K3.")
    parser.add_argument(
        "--config-dir",
        type=Path,
        default=Path("config"),
        help="Path to repository config directory containing model.json, triage-config.json, trust-list.json",
    )
    parser.add_argument(
        "--assets-dir",
        type=Path,
        default=Path("android/app/src/main/assets"),
        help="Target Android assets directory",
    )
    parser.add_argument(
        "--manifest-key",
        type=str,
        default=None,
        help="Path to K3 private key or hex/base64 string. Defaults to local test key.",
    )

    args = parser.parse_args()

    # Resolve relative paths from current working dir or repo root
    config_dir = args.config_dir
    if not config_dir.is_dir() and Path(__file__).resolve().parent.parent.joinpath("config").is_dir():
        config_dir = Path(__file__).resolve().parent.parent.joinpath("config")

    assets_dir = args.assets_dir
    if not assets_dir.is_absolute() and not assets_dir.exists():
        candidate = Path(__file__).resolve().parent.parent / args.assets_dir
        if candidate.parent.exists():
            assets_dir = candidate

    manifest = generate_bundle_manifest(config_dir, assets_dir, args.manifest_key)
    print(f"Bundle manifest generated and signed with K3 ({manifest['keyId']}):")
    print(f"  Target: {assets_dir / 'bundle-manifest.json'}")
    print(f"  Public Key: {manifest['signerPublicKeyHex']}")
    for name, sha in manifest["files"].items():
        print(f"  - {name}: {sha}")
    print("SUCCESS")
    return 0


if __name__ == "__main__":
    sys.exit(main())
