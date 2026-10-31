"""BUG-2026-063: the server has no default enrollment-code secret and refuses to start without a strong one (K12)."""

from __future__ import annotations

import os
import secrets
import subprocess
import sys
from pathlib import Path

import pytest

from app.config import (
    RETIRED_ENROLLMENT_SECRETS,
    ConfigurationError,
    Settings,
)

BACKEND_DIR = Path(__file__).resolve().parent.parent


def settings_with(secret: str | None) -> Settings:
    return Settings(ENROLLMENT_CODE_SECRET=secret)


def test_a_missing_secret_is_refused():
    with pytest.raises(ConfigurationError, match="not set"):
        settings_with(None).enrollment_code_key()


def test_an_empty_secret_is_refused():
    with pytest.raises(ConfigurationError, match="not set"):
        settings_with("").enrollment_code_key()


def test_the_old_published_default_is_refused():
    for retired in RETIRED_ENROLLMENT_SECRETS:
        with pytest.raises(ConfigurationError, match="old published default"):
            settings_with(retired).enrollment_code_key()


def test_a_short_secret_is_refused():
    with pytest.raises(ConfigurationError, match="at least"):
        settings_with(secrets.token_hex(8)).enrollment_code_key()


@pytest.mark.parametrize("weak", ["x" * 64, " " * 64, "ab" * 32, "0123" * 16])
def test_a_long_secret_that_is_not_random_is_refused(weak: str):
    with pytest.raises(ConfigurationError, match="at least"):
        settings_with(weak).enrollment_code_key()


def test_surrounding_whitespace_does_not_count_toward_the_length():
    with pytest.raises(ConfigurationError, match="at least"):
        settings_with("  " + secrets.token_hex(8) + " " * 40).enrollment_code_key()


def test_a_strong_secret_is_the_hmac_key():
    secret = secrets.token_hex(32)

    assert settings_with(secret).enrollment_code_key() == secret.encode("utf-8")


STARTUP = "\n".join(
    [
        "import app.main as m",
        "from fastapi.testclient import TestClient",
        "with TestClient(m.app):",
        "    pass",
    ]
)


def _start_server_import(secret: str | None, code: str = STARTUP) -> subprocess.CompletedProcess:
    env = {k: v for k, v in os.environ.items() if k != "ENROLLMENT_CODE_SECRET"}
    if secret is not None:
        env["ENROLLMENT_CODE_SECRET"] = secret
    return subprocess.run(
        [sys.executable, "-c", code],
        cwd=str(BACKEND_DIR), env=env, capture_output=True, text=True, check=False,
    )


def test_the_server_refuses_to_start_without_the_secret():
    done = _start_server_import(None)

    assert done.returncode != 0
    assert "ENROLLMENT_CODE_SECRET is not set" in done.stderr


def test_the_server_refuses_to_start_on_the_old_default():
    done = _start_server_import("default-study-enrollment-secret-key-32b")

    assert done.returncode != 0
    assert "old published default" in done.stderr


def test_the_server_starts_with_a_strong_secret():
    done = _start_server_import(secrets.token_hex(32))

    assert done.returncode == 0, done.stderr


def test_the_api_description_can_be_read_without_the_secret():
    """backend/tools/diff_spec.py and the drift inventory import the app to read its OpenAPI document in CI."""
    done = _start_server_import(None, code="import app.main as m; m.app.openapi()")

    assert done.returncode == 0, done.stderr
