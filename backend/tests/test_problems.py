from __future__ import annotations

from uuid import UUID, uuid4

import pytest
from fastapi.testclient import TestClient

from app.models.api import Problem
from tests.conftest import (
    create_enrollment_code,
    mint_device_key,
    mint_device_token,
)


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


def assert_clean_problem_response(response, expected_code: str, expected_status: int):
    assert response.status_code == expected_status
    assert "application/problem+json" in response.headers.get("content-type", "")

    data = response.json()
    assert "type" in data
    assert "title" in data
    assert data["status"] == expected_status
    assert data["code"] == expected_code
    assert "retryable" in data
    assert isinstance(data["retryable"], bool)
    assert "request_id" in data
    assert data["request_id"] != ""

    # Ensure no internal leaks or stack traces
    raw_text = response.text
    assert "Traceback (most recent call last)" not in raw_text
    assert "site-packages" not in raw_text
    assert "sqlalchemy" not in raw_text.lower()
    assert "psycopg" not in raw_text.lower()
    assert "File \"" not in raw_text

    # Validate against Pydantic Problem schema
    Problem.model_validate(data)


def test_tc_api_31_error_bodies_leak_nothing(client: TestClient, enrolled_device):
    """TC-API-31: Errors conform to RFC 9457 Problem Details and leak zero stack traces or internal implementation data."""
    device_id, privkey, jwk = enrolled_device

    # 1. AUTH_MISSING (401)
    r1 = client.get("/device")
    assert_clean_problem_response(r1, "AUTH_MISSING", 401)

    # 2. NOT_FOUND (404)
    tok2 = mint_device_token(privkey, device_id, "GET", "/nonexistent-route")
    r2 = client.get(
        "/nonexistent-route",
        headers={"Authorization": f"Bearer {tok2}", "X-NeoGold-App-Version": "1.0.0"},
    )
    assert_clean_problem_response(r2, "NOT_FOUND", 404)

    # 3. ENROLLMENT_CODE_INVALID (403)
    r3 = client.post(
        "/enrollments",
        json={
            "enrollment_code": "0000-0000-0000",
            "device_public_key": jwk,
            "device": {
                "manufacturer": "Samsung",
                "model": "SM-A125F",
                "android_api": 30,
                "app_version": "1.0.0",
                "camera_hardware_level": "LIMITED",
                "qualification": "QUALIFIED",
            },
        },
        headers={"Idempotency-Key": str(uuid4()), "X-NeoGold-App-Version": "1.0.0"},
    )
    assert_clean_problem_response(r3, "ENROLLMENT_CODE_INVALID", 403)

    # 4. VALIDATION_FAILED (400)
    import json
    part_id = uuid4()
    part_path = f"/participants/{part_id}"
    bad_bytes = json.dumps({"bad": "json"}, separators=(",", ":")).encode("utf-8")
    tok4 = mint_device_token(privkey, device_id, "PUT", part_path, body_bytes=bad_bytes)
    r4 = client.put(
        part_path,
        content=bad_bytes,
        headers={
            "Authorization": f"Bearer {tok4}",
            "Idempotency-Key": str(uuid4()),
            "X-NeoGold-App-Version": "1.0.0",
            "Content-Type": "application/json",
        },
    )
    assert_clean_problem_response(r4, "VALIDATION_FAILED", 400)
    assert "errors" in r4.json()
    assert isinstance(r4.json()["errors"], list)
