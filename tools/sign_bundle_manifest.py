#!/usr/bin/env python3
"""Build-time tool that hashes the model, the triage config and the trust list, and signs the bundle manifest with
key K3 (Ed25519). The app trusts the public half of whichever key signed its own bundle (BuildConfig), so a bundle
and the build that carries it always belong together.

There are two kinds of signer, and the file `signing-info.json` in the output says which one was used:

* release: the real K3, given with --manifest-key (a path, 64 hex characters, or base64). It comes from a CI secret and
  never enters this repository. The public half must be pinned in config/k3-public-key.hex and the key must match it,
  so a wrong secret, or a key with nothing pinned, fails the build instead of producing an app that trusts whatever
  key it was given.
* development: a random key made on first use and kept in --dev-key-file, a file under build/. It is for local and
  pull-request builds. The app refuses to run screening or research from a build that is not debuggable and carries a
  development key, so such a build cannot reach a clinic by mistake.

--require-release-key makes a missing release key an error (no fall back to the development key); the release
workflow passes it.

Usage:
    python tools/sign_bundle_manifest.py --out-dir DIR [--config-dir config] [--manifest-key PATH_OR_KEY]
        [--dev-key-file FILE] [--pinned-public-key config/k3-public-key.hex] [--require-release-key]
"""

import argparse
import base64
import binascii
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

KEY_KIND_RELEASE = "release"
KEY_KIND_DEVELOPMENT = "development"
PUBLIC_KEY_HEX_LENGTH = 64
PRIVATE_KEY_BYTES = 32


class SigningError(Exception):
    """The build must stop: the signing request cannot be honoured safely."""


def parse_private_key(key_input: str) -> ed25519.Ed25519PrivateKey:
    """Reads an Ed25519 private key given as a file path, 64 hex characters, or base64 of the 32 raw bytes."""
    key_path = Path(key_input)
    if key_path.is_file():
        content = key_path.read_bytes()
        # The 32 raw bytes are checked as they are: a key that starts or ends with a byte that looks like whitespace
        # would lose it to strip() and then no longer be 32 bytes long.
        if len(content) == PRIVATE_KEY_BYTES:
            return ed25519.Ed25519PrivateKey.from_private_bytes(content)
    else:
        content = key_input.encode("utf-8")

    text = content.decode("utf-8", errors="ignore").strip()
    if len(text) == PUBLIC_KEY_HEX_LENGTH:
        try:
            return ed25519.Ed25519PrivateKey.from_private_bytes(bytes.fromhex(text))
        except ValueError:
            pass

    try:
        raw = base64.b64decode(text, validate=True)
    except (binascii.Error, ValueError):
        raw = b""
    if len(raw) == PRIVATE_KEY_BYTES:
        return ed25519.Ed25519PrivateKey.from_private_bytes(raw)

    raise SigningError("The manifest key is not an Ed25519 private key (a file, 64 hex characters, or base64).")


def load_or_create_dev_key(dev_key_file: Path) -> ed25519.Ed25519PrivateKey:
    """The development key: made once, then reused, so repeated local builds sign alike."""
    if dev_key_file.is_file():
        return ed25519.Ed25519PrivateKey.from_private_bytes(bytes.fromhex(dev_key_file.read_text("ascii").strip()))
    dev_key_file.parent.mkdir(parents=True, exist_ok=True)
    raw = os.urandom(PRIVATE_KEY_BYTES)
    descriptor = os.open(dev_key_file, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(descriptor, "w", encoding="ascii") as handle:
        handle.write(raw.hex() + "\n")
    return ed25519.Ed25519PrivateKey.from_private_bytes(raw)


def read_pinned_public_key(path: Path | None) -> str | None:
    """The first non-comment line of the pinned file, or None when there is no pinned key."""
    if path is None or not path.is_file():
        return None
    for line in path.read_text(encoding="ascii").splitlines():
        line = line.strip()
        if line and not line.startswith("#"):
            if len(line) != PUBLIC_KEY_HEX_LENGTH or any(c not in "0123456789abcdefABCDEF" for c in line):
                raise SigningError(f"{path} must hold the K3 public key as {PUBLIC_KEY_HEX_LENGTH} hex characters.")
            return line.lower()
    return None


def resolve_signer(
    manifest_key: str | None,
    dev_key_file: Path | None,
    pinned_public_key_file: Path | None,
    require_release_key: bool,
) -> tuple[ed25519.Ed25519PrivateKey, str]:
    """Chooses the signing key and says which kind it is. Raises SigningError rather than sign with the wrong one."""
    if manifest_key:
        key = parse_private_key(manifest_key)
        public_hex = key.public_key().public_bytes_raw().hex()
        pinned = read_pinned_public_key(pinned_public_key_file)
        # A key that is given is a release key, and a release key is only worth trusting when the public half was
        # committed ahead of time: without a pin, whichever key happened to be passed in would be trusted.
        if pinned is None:
            raise SigningError(
                "No K3 public key is pinned. Put the public half of the release key in config/k3-public-key.hex."
            )
        if pinned != public_hex:
            raise SigningError("The manifest key does not match the K3 public key pinned in config/k3-public-key.hex.")
        return key, KEY_KIND_RELEASE

    if require_release_key:
        raise SigningError("A release build needs the real K3: pass --manifest-key.")
    if dev_key_file is None:
        raise SigningError("Pass --manifest-key, or --dev-key-file for a development build.")
    return load_or_create_dev_key(dev_key_file), KEY_KIND_DEVELOPMENT


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
    out_dir: Path,
    signer: ed25519.Ed25519PrivateKey,
    key_kind: str,
) -> dict:
    """Copies the bundle files into out_dir and writes bundle-manifest.json and signing-info.json beside them."""
    out_dir.mkdir(parents=True, exist_ok=True)
    public_hex = signer.public_key().public_bytes_raw().hex()

    file_hashes: dict[str, str] = {}
    for filename in REQUIRED_BUNDLE_FILES + OPTIONAL_BUNDLE_FILES:
        src = config_dir / filename
        if not src.exists():
            if filename in REQUIRED_BUNDLE_FILES:
                raise FileNotFoundError(f"Required bundle asset not found in config: {src}")
            continue
        dest = out_dir / filename
        shutil.copy2(src, dest)
        file_hashes[filename] = compute_file_sha256(dest)

    signature_b64 = base64.b64encode(signer.sign(canonical_sign_payload(file_hashes))).decode("ascii")
    manifest = {
        "formatVersion": 1,
        "manifestVersion": "1.0.0",
        "keyId": "K3",
        "signerPublicKeyHex": public_hex,
        "files": file_hashes,
        "signature": signature_b64,
    }
    # LF on every platform, so the same inputs give the same bytes on a developer's Windows machine and on CI.
    with open(out_dir / "bundle-manifest.json", "w", encoding="utf-8", newline="\n") as f:
        json.dump(manifest, f, indent=2, sort_keys=True)
        f.write("\n")

    signing_info = {"keyId": "K3", "keyKind": key_kind, "publicKeyHex": public_hex}
    with open(out_dir / "signing-info.json", "w", encoding="utf-8", newline="\n") as f:
        json.dump(signing_info, f, indent=2, sort_keys=True)
        f.write("\n")

    return manifest


def main() -> int:
    parser = argparse.ArgumentParser(description="Sign the clinical bundle assets with K3.")
    parser.add_argument("--config-dir", type=Path, default=Path("config"), help="Folder with the bundle's source files")
    parser.add_argument("--out-dir", type=Path, required=True, help="Folder that receives the files and the manifest")
    parser.add_argument("--manifest-key", default=None, help="The release K3 private key: a path, hex or base64")
    parser.add_argument(
        "--manifest-key-env",
        default=None,
        help="Name of an environment variable that holds the release key, so it is not on the command line",
    )
    parser.add_argument("--dev-key-file", type=Path, default=None, help="Where the development key is kept")
    parser.add_argument(
        "--pinned-public-key",
        type=Path,
        default=Path("config/k3-public-key.hex"),
        help="The release K3 public key the app is built to trust",
    )
    parser.add_argument("--require-release-key", action="store_true", help="Refuse to sign with a development key")
    args = parser.parse_args()

    manifest_key = args.manifest_key
    if args.manifest_key_env:
        manifest_key = os.environ.get(args.manifest_key_env) or manifest_key

    try:
        signer, key_kind = resolve_signer(
            manifest_key, args.dev_key_file, args.pinned_public_key, args.require_release_key
        )
        manifest = generate_bundle_manifest(args.config_dir, args.out_dir, signer, key_kind)
    except (SigningError, FileNotFoundError) as error:
        print(f"ERROR: {error}", file=sys.stderr)
        return 2

    print(f"Bundle manifest signed with a {key_kind} K3 key.")
    print(f"  Target: {args.out_dir / 'bundle-manifest.json'}")
    print(f"  Public key: {manifest['signerPublicKeyHex']}")
    for name, sha in manifest["files"].items():
        print(f"  - {name}: {sha}")
    print("SUCCESS")
    return 0


if __name__ == "__main__":
    sys.exit(main())
