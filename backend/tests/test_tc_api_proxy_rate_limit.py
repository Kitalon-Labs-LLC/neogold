from __future__ import annotations

import json
from uuid import uuid4

from fastapi.testclient import TestClient

from app.middleware.rate_limit import enrollment_limiter
from tests.conftest import mint_device_key


def test_tc_api_33_client_ip_through_caddy(client: TestClient, db, test_study_and_site):
    """TC-API-33:
    Client IP rate limiting through trusted proxy (Caddy / FORWARDED_ALLOW_IPS):
    - 10 failed enrollments from client A, 11th gets 429 RATE_LIMITED
    - Client B is not limited
    """
    enrollment_limiter.reset()

    study, site = test_study_and_site
    _privkey, jwk = mint_device_key()

    def try_enroll(client_ip: str, code: str = "ABCD-EFGH-JKMN") -> int:
        payload = {
            "enrollment_code": code,
            "device_public_key": jwk,
            "device": {
                "manufacturer": "TECNO",
                "model": "TECNO KL8",
                "android_api": 34,
                "camera_hardware_level": "LIMITED",
                "qualification": "QUALIFIED",
                "app_version": "1.0.0",
            },
        }
        body_bytes = json.dumps(payload, separators=(",", ":")).encode("utf-8")
        r = client.post(
            "/enrollments",
            content=body_bytes,
            headers={
                "X-Forwarded-For": client_ip,
                "Idempotency-Key": str(uuid4()),
                "X-NeoGold-App-Version": "1.0.0",
                "Content-Type": "application/json",
            },
        )
        return r.status_code

    client_a = "192.168.1.101"
    client_b = "192.168.1.102"

    # 10 failed attempts from Client A (invalid code -> 403 ENROLLMENT_CODE_INVALID)
    for _ in range(10):
        st = try_enroll(client_a)
        assert st == 403

    # 11th attempt from Client A must get 429 RATE_LIMITED
    st11 = try_enroll(client_a)
    assert st11 == 429

    # Attempt from Client B must NOT be rate limited
    st_b = try_enroll(client_b)
    assert st_b == 403  # Gets 403, not 429!
