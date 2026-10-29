"""
tools/tests/test_sign_bundle_manifest.py

Proves tools/sign_bundle_manifest.py signs the bundle with the key it was given and with no other (BUG-2026-008): the
release key must match the pinned public key (and must have one), a release build cannot fall back to a development
key, a development key is made once and reused, and no private key is committed in the repository.
"""

import base64
import json
import os
import re
import subprocess
import sys
from pathlib import Path

import pytest
from cryptography.exceptions import InvalidSignature
from cryptography.hazmat.primitives.asymmetric import ed25519

from tools import sign_bundle_manifest as sbm

REPO_ROOT = Path(__file__).resolve().parent.parent.parent

# The public half of the test key whose private half used to be committed in this tool and in a Kotlin test
# (BUG-2026-008). Only the public half is kept here: the test finds the private key by deriving from candidates, so no
# secret is needed to look for one. The name avoids the words a secrets scanner reads as a credential.
RETIRED_SIGNER_ID = "7ea85ce6309d111e3446befeb992a4335907473c37534ca42df7e99e191c4772"


@pytest.fixture
def config_dir(tmp_path: Path) -> Path:
    folder = tmp_path / "config"
    folder.mkdir()
    (folder / "model.json").write_text('{"model": 1}', encoding="utf-8")
    (folder / "triage-config.json").write_text('{"triage": 1}', encoding="utf-8")
    (folder / "trust-list.json").write_text('{"trust": 1}', encoding="utf-8")
    return folder


def new_key() -> ed25519.Ed25519PrivateKey:
    return ed25519.Ed25519PrivateKey.generate()


def raw_hex(key: ed25519.Ed25519PrivateKey) -> str:
    return key.private_bytes_raw().hex()


def public_hex(key: ed25519.Ed25519PrivateKey) -> str:
    return key.public_key().public_bytes_raw().hex()


def pin_for(key: ed25519.Ed25519PrivateKey, folder: Path) -> str:
    """Writes the pinned public key file for [key], the way the repository pins K3, and returns its path."""
    pinned = folder / "k3-public-key.hex"
    pinned.write_text("# K3, pinned\n" + public_hex(key) + "\n", encoding="ascii")
    return str(pinned)


def signed_by(manifest_path: Path, key_hex: str) -> bool:
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    public_key = ed25519.Ed25519PublicKey.from_public_bytes(bytes.fromhex(key_hex))
    try:
        public_key.verify(
            base64.b64decode(manifest["signature"]), sbm.canonical_sign_payload(manifest["files"])
        )
        return True
    except InvalidSignature:
        return False


def run(config_dir: Path, out_dir: Path, *extra: str) -> subprocess.CompletedProcess:
    return subprocess.run(
        [sys.executable, str(REPO_ROOT / "tools" / "sign_bundle_manifest.py"),
         "--config-dir", str(config_dir), "--out-dir", str(out_dir), *extra],
        capture_output=True, text=True, check=False,
    )


def test_release_key_signs_the_bundle_and_says_so(config_dir: Path, tmp_path: Path):
    key = new_key()
    out = tmp_path / "out"

    done = run(config_dir, out, "--manifest-key", raw_hex(key), "--pinned-public-key", pin_for(key, tmp_path))

    assert done.returncode == 0, done.stderr
    info = json.loads((out / "signing-info.json").read_text(encoding="utf-8"))
    assert info == {"keyId": "K3", "keyKind": "release", "publicKeyHex": public_hex(key)}
    assert signed_by(out / "bundle-manifest.json", public_hex(key))
    for name in ("model.json", "triage-config.json", "trust-list.json"):
        assert (out / name).read_bytes() == (config_dir / name).read_bytes()


def test_manifest_hashes_match_the_copied_files(config_dir: Path, tmp_path: Path):
    out = tmp_path / "out"
    assert run(config_dir, out, "--dev-key-file", str(tmp_path / "dev.key")).returncode == 0

    manifest = json.loads((out / "bundle-manifest.json").read_text(encoding="utf-8"))

    assert set(manifest["files"]) == {"model.json", "triage-config.json", "trust-list.json"}
    for name, digest in manifest["files"].items():
        assert digest == sbm.compute_file_sha256(out / name)


def test_release_key_must_match_the_pinned_public_key(config_dir: Path, tmp_path: Path):
    pinned = tmp_path / "k3-public-key.hex"
    pinned.write_text("# K3, pinned\n" + public_hex(new_key()) + "\n", encoding="ascii")

    done = run(config_dir, tmp_path / "out", "--manifest-key", raw_hex(new_key()), "--pinned-public-key", str(pinned))

    assert done.returncode == 2
    assert "does not match" in done.stderr
    assert not (tmp_path / "out" / "bundle-manifest.json").exists()


def test_release_key_that_matches_the_pin_is_accepted(config_dir: Path, tmp_path: Path):
    key = new_key()
    pinned = tmp_path / "k3-public-key.hex"
    pinned.write_text(public_hex(key) + "\n", encoding="ascii")

    done = run(config_dir, tmp_path / "out", "--manifest-key", raw_hex(key), "--pinned-public-key", str(pinned),
               "--require-release-key")

    assert done.returncode == 0, done.stderr


def test_release_build_without_a_pinned_key_is_refused(config_dir: Path, tmp_path: Path):
    done = run(config_dir, tmp_path / "out", "--manifest-key", raw_hex(new_key()),
               "--pinned-public-key", str(tmp_path / "missing.hex"), "--require-release-key")

    assert done.returncode == 2
    assert "pinned" in done.stderr


def test_a_release_key_without_a_pin_is_refused_even_without_the_flag(config_dir: Path, tmp_path: Path):
    """Any key that is passed in is a release key; trusting it with nothing pinned would trust whatever was given."""
    done = run(config_dir, tmp_path / "out", "--manifest-key", raw_hex(new_key()),
               "--pinned-public-key", str(tmp_path / "missing.hex"))

    assert done.returncode == 2
    assert "pinned" in done.stderr
    assert not (tmp_path / "out" / "bundle-manifest.json").exists()


def test_release_build_cannot_fall_back_to_a_development_key(config_dir: Path, tmp_path: Path):
    done = run(config_dir, tmp_path / "out", "--dev-key-file", str(tmp_path / "dev.key"), "--require-release-key")

    assert done.returncode == 2
    assert "real K3" in done.stderr
    assert not (tmp_path / "dev.key").exists()
    assert not (tmp_path / "out" / "bundle-manifest.json").exists()


def test_no_key_at_all_is_an_error(config_dir: Path, tmp_path: Path):
    done = run(config_dir, tmp_path / "out")

    assert done.returncode == 2
    assert not (tmp_path / "out" / "bundle-manifest.json").exists()


def test_development_key_is_made_once_and_reused(config_dir: Path, tmp_path: Path):
    dev_key = tmp_path / "build" / "dev-k3" / "k3-dev.key"

    assert run(config_dir, tmp_path / "out1", "--dev-key-file", str(dev_key)).returncode == 0
    assert run(config_dir, tmp_path / "out2", "--dev-key-file", str(dev_key)).returncode == 0

    first = json.loads((tmp_path / "out1" / "signing-info.json").read_text(encoding="utf-8"))
    second = json.loads((tmp_path / "out2" / "signing-info.json").read_text(encoding="utf-8"))
    assert first["keyKind"] == "development"
    assert first == second
    assert signed_by(tmp_path / "out2" / "bundle-manifest.json", second["publicKeyHex"])


def test_the_private_key_never_reaches_the_output(config_dir: Path, tmp_path: Path):
    key = new_key()
    out = tmp_path / "out"

    done = run(config_dir, out, "--manifest-key", raw_hex(key), "--pinned-public-key", pin_for(key, tmp_path))

    assert done.returncode == 0
    secret = raw_hex(key)
    assert secret not in done.stdout and secret not in done.stderr
    for produced in out.iterdir():
        assert secret not in produced.read_text(encoding="utf-8")


def test_key_can_be_given_as_a_file_or_as_base64(config_dir: Path, tmp_path: Path):
    key = new_key()
    key_file = tmp_path / "manifest.key"
    key_file.write_text(raw_hex(key), encoding="ascii")
    pin = pin_for(key, tmp_path)

    from_file = run(config_dir, tmp_path / "a", "--manifest-key", str(key_file), "--pinned-public-key", pin)
    from_base64 = run(config_dir, tmp_path / "b", "--manifest-key",
                      base64.b64encode(key.private_bytes_raw()).decode("ascii"), "--pinned-public-key", pin)

    assert from_file.returncode == 0 and from_base64.returncode == 0
    for folder in ("a", "b"):
        assert signed_by(tmp_path / folder / "bundle-manifest.json", public_hex(key))


def test_key_can_come_from_an_environment_variable_and_stay_off_the_command_line(config_dir: Path, tmp_path: Path):
    key = new_key()

    done = subprocess.run(
        [sys.executable, str(REPO_ROOT / "tools" / "sign_bundle_manifest.py"),
         "--config-dir", str(config_dir), "--out-dir", str(tmp_path / "out"),
         "--manifest-key-env", "NEOGOLD_TEST_K3", "--pinned-public-key", pin_for(key, tmp_path)],
        capture_output=True, text=True, check=False, env={**os.environ, "NEOGOLD_TEST_K3": raw_hex(key)},
    )

    assert done.returncode == 0, done.stderr
    assert signed_by(tmp_path / "out" / "bundle-manifest.json", public_hex(key))
    assert json.loads((tmp_path / "out" / "signing-info.json").read_text(encoding="utf-8"))["keyKind"] == "release"


@pytest.mark.parametrize("first,last", [(0x20, 0x41), (0x41, 0x0A), (0x09, 0x0D)])
def test_a_raw_key_file_is_read_as_it_is_even_when_its_edge_bytes_look_like_whitespace(
    config_dir: Path, tmp_path: Path, first: int, last: int
):
    key = ed25519.Ed25519PrivateKey.from_private_bytes(bytes([first]) + os.urandom(30) + bytes([last]))
    key_file = tmp_path / "manifest.key"
    key_file.write_bytes(key.private_bytes_raw())

    done = run(config_dir, tmp_path / "out", "--manifest-key", str(key_file), "--pinned-public-key",
               pin_for(key, tmp_path))

    assert done.returncode == 0, done.stderr
    assert signed_by(tmp_path / "out" / "bundle-manifest.json", public_hex(key))


def test_a_malformed_key_is_refused(config_dir: Path, tmp_path: Path):
    done = run(config_dir, tmp_path / "out", "--manifest-key", "not-a-key", "--pinned-public-key",
               str(tmp_path / "none.hex"))

    assert done.returncode == 2
    assert not (tmp_path / "out" / "bundle-manifest.json").exists()


def test_a_missing_required_asset_stops_the_build(config_dir: Path, tmp_path: Path):
    (config_dir / "trust-list.json").unlink()

    done = run(config_dir, tmp_path / "out", "--dev-key-file", str(tmp_path / "dev.key"))

    assert done.returncode == 2
    assert "trust-list.json" in done.stderr


def test_the_committed_vector_verifies_in_the_tools_own_canonical_form():
    """testdata/bundle-manifest is read by the Kotlin verifier's tests too, so the two languages share one format."""
    vector = REPO_ROOT / "testdata" / "bundle-manifest"
    manifest = json.loads((vector / "bundle-manifest.json").read_text(encoding="utf-8"))
    pinned = (vector / "public-key.hex").read_text(encoding="ascii").strip()

    assert set(manifest["files"]) == {"model.json", "triage-config.json", "trust-list.json"}
    for name, digest in manifest["files"].items():
        assert sbm.compute_file_sha256(vector / name) == digest
    assert signed_by(vector / "bundle-manifest.json", pinned)


HEX_64 = re.compile(r"(?<![0-9a-fA-F])[0-9a-fA-F]{64}(?![0-9a-fA-F])")
BASE64_32_BYTES = re.compile(r"(?<![A-Za-z0-9+/])[A-Za-z0-9+/]{43}=(?![A-Za-z0-9+/=])")


def _is_the_retired_private_key(candidate: bytes) -> bool:
    try:
        derived = ed25519.Ed25519PrivateKey.from_private_bytes(candidate).public_key().public_bytes_raw()
    except ValueError:
        return False
    return derived.hex() == RETIRED_SIGNER_ID


def test_the_retired_test_key_is_not_in_the_repository():
    """BUG-2026-008: a private key in the repository lets anyone sign a manifest the app accepts.

    Every 64-character hex string and every base64 string of 32 bytes in a tracked text file is tried as an Ed25519
    private key, so the key is found in any of its usual spellings without this file holding it.
    """
    tracked = subprocess.run(
        ["git", "ls-files", "-z"], cwd=REPO_ROOT, capture_output=True, check=True
    ).stdout.decode("utf-8").split("\0")
    offenders = []
    for name in filter(None, tracked):
        path = REPO_ROOT / name
        if not path.is_file() or path.suffix in {".png", ".jpg", ".jar", ".so", ".webp", ".zip", ".keystore"}:
            continue
        try:
            text = path.read_text(encoding="utf-8")
        except (UnicodeDecodeError, OSError):
            continue
        candidates = [bytes.fromhex(m.group()) for m in HEX_64.finditer(text)]
        for m in BASE64_32_BYTES.finditer(text):
            try:
                candidates.append(base64.b64decode(m.group(), validate=True))
            except ValueError:
                continue
        if any(len(c) == 32 and _is_the_retired_private_key(c) for c in candidates):
            offenders.append(name)
    assert offenders == []
