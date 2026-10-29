"""The claims a phone puts in its device assertion, checked the way the server checks them (doc 7 Section 4.3 steps 4 and 6).

testdata/api/assertion_claims_vector.json is also read by the Android test DeviceAssertionAudienceTest. The phone once put
the study host without /v1 in `aud`, so the server answered 401 AUTH_CLAIM_MISMATCH to every call (package B2).
"""

from __future__ import annotations

import json
from pathlib import Path

from app.middleware.auth import normalize_htu
from app.security.jwk import compute_bsh

VECTOR_PATH = Path(__file__).resolve().parents[2] / "testdata" / "api" / "assertion_claims_vector.json"
VECTOR = json.loads(VECTOR_PATH.read_text(encoding="utf-8"))
CLAIMS = VECTOR["expected_claims"]


def test_aud_equals_the_servers_public_base_url():
    # Step 4: `aud` equals the configured public base URL (trailing slash ignored).
    assert CLAIMS["aud"].rstrip("/") == VECTOR["public_base_url"].rstrip("/")


def test_aud_is_the_study_host_with_v1_which_the_client_derives():
    assert CLAIMS["aud"] == f"https://{VECTOR['study_host']}/v1"


def test_htu_is_the_public_base_url_plus_the_path_the_server_sees():
    # Step 6: the server rebuilds `htu` from its public base URL and the request path, with or without the /v1 prefix.
    assert normalize_htu(VECTOR["path"], VECTOR["public_base_url"]).lower() == CLAIMS["htu"].lower()
    assert normalize_htu("/v1" + VECTOR["path"], VECTOR["public_base_url"]).lower() == CLAIMS["htu"].lower()


def test_bsh_is_the_base64url_sha256_of_the_body():
    assert compute_bsh(VECTOR["body"].encode("utf-8")) == CLAIMS["bsh"]


def test_a_host_only_aud_would_not_match():
    # What the phone used to send. Kept as a negative case so the rule it broke stays visible.
    assert f"https://{VECTOR['study_host']}".rstrip("/") != VECTOR["public_base_url"].rstrip("/")


def test_the_token_life_is_within_the_limit():
    assert CLAIMS["exp"] - CLAIMS["iat"] <= 300
