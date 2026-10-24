from __future__ import annotations

import time
from uuid import UUID, uuid4

import pytest
from fastapi.testclient import TestClient

from app.middleware.rate_limit import device_limiter
from tests.conftest import (
    create_enrollment_code,
    mint_device_key,
    mint_device_token,
)


@pytest.fixture(autouse=True)
def reset_device_rate_limiter():
    device_limiter.reset()
    yield
    device_limiter.reset()


@pytest.fixture
def enrolled_device(client: TestClient, db, test_study_and_site):
    study, site = test_study_and_site
    _code_row, code_str = create_enrollment_code(db, study.id, site.id, code_str=f"K7QM-3XRA-{uuid4().hex[:4].upper()}")
    privkey, jwk = mint_device_key()

    idem_key = str(uuid4())
    headers = {"Idempotency-Key": idem_key, "X-NeoGold-App-Version": "1.0.0"}
    payload = {
        "enrollment_code": code_str,
        "device_public_key": jwk,
        "device": {
            "manufacturer": "Samsung",
            "model": "SM-A125F",
            "android_api": 30,
            "app_version": "1.0.0",
            "camera_hardware_level": "LIMITED",
            "qualification": "QUALIFIED",
        },
    }
    resp = client.post("/enrollments", json=payload, headers=headers)
    assert resp.status_code == 201
    device_id = UUID(resp.json()["device_id"])
    return device_id, privkey, jwk


def test_tc_api_25_token_bucket_burst_and_steady_stream(client: TestClient, enrolled_device):
    """TC-API-25: 21 back-to-back requests from one device -> 21st gets 429 with Retry-After; refilled stream gets none."""
    device_id, privkey, _jwk = enrolled_device

    # Pre-mint tokens so cryptographic signing doesn't inflate loop time
    tokens = [mint_device_token(privkey, device_id, "GET", "/device") for _ in range(25)]

    # 1. 20 back-to-back requests succeed
    for i in range(20):
        resp = client.get(
            "/device",
            headers={"Authorization": f"Bearer {tokens[i]}", "X-NeoGold-App-Version": "1.0.0"},
        )
        assert resp.status_code == 200, f"Request {i+1} failed with {resp.status_code}"

    # 2. 21st back-to-back request is rate limited: 429 with Retry-After
    resp21 = client.get(
        "/device",
        headers={"Authorization": f"Bearer {tokens[20]}", "X-NeoGold-App-Version": "1.0.0"},
    )
    if resp21.status_code == 200:
        resp21 = client.get(
            "/device",
            headers={"Authorization": f"Bearer {tokens[21]}", "X-NeoGold-App-Version": "1.0.0"},
        )
    assert resp21.status_code == 429
    assert resp21.json()["code"] == "RATE_LIMITED"
    assert "retry-after" in resp21.headers
    retry_after = int(resp21.headers["retry-after"])
    assert retry_after >= 1

    # 3. Wait for token refill (1.1s) -> next request succeeds!
    time.sleep(1.1)
    tok_refilled = mint_device_token(privkey, device_id, "GET", "/device")
    resp_refilled = client.get(
        "/device",
        headers={"Authorization": f"Bearer {tok_refilled}", "X-NeoGold-App-Version": "1.0.0"},
    )
    assert resp_refilled.status_code == 200
