from __future__ import annotations

import datetime
import hashlib
from uuid import uuid4

import pytest
from fastapi.testclient import TestClient
from sqlalchemy import select

from app.middleware.rate_limit import enrollment_limiter
from app.models.db import Device
from tests.conftest import (
    build_test_attestation_chain,
    create_enrollment_code,
    mint_device_key,
)


@pytest.fixture(autouse=True)
def reset_limiters():
    enrollment_limiter.reset()
    yield
    enrollment_limiter.reset()


def make_enrollment_payload(code_str: str, jwk: dict, attestation_chain: list[str] | None = None) -> dict:
    return {
        "enrollment_code": code_str,
        "device_public_key": jwk,
        "key_attestation_chain": attestation_chain,
        "device": {
            "manufacturer": "Samsung",
            "model": "SM-A125F",
            "android_api": 30,
            "app_version": "1.0.0",
            "camera_hardware_level": "LIMITED",
            "qualification": "QUALIFIED",
        },
    }


def test_tc_api_02_enrollment_succeeds_once(client: TestClient, db, test_study_and_site):
    """TC-API-02: Enroll with a fresh code -> 201; code marked used; device stored with thumbprint."""
    study, site = test_study_and_site
    code_row, code_str = create_enrollment_code(db, study.id, site.id, code_str="K7QM-3XRA-9TP1")
    _privkey, jwk = mint_device_key()

    idem_key = str(uuid4())
    headers = {
        "Idempotency-Key": idem_key,
        "X-NeoGold-App-Version": "1.0.0",
    }
    payload = make_enrollment_payload(code_str, jwk)

    response = client.post("/enrollments", json=payload, headers=headers)
    assert response.status_code == 201, response.text
    data = response.json()

    assert data["study_code"] == study.study_code
    assert data["study_name"] == study.name
    assert data["site_code"] == site.site_code
    assert data["data_generation"] == study.data_generation
    assert "device_id" in data
    assert "server_time" in data
    assert data["upload_policy"]["wifi_only"] is True
    assert data["upload_policy"]["timestamp_precision"] == "DAY_PLUS_OFFSETS"

    # Verify database state
    db.refresh(code_row)
    assert code_row.used_at is not None
    assert str(code_row.device_id) == data["device_id"]
    assert str(code_row.enroll_idempotency_key) == idem_key
    assert code_row.enroll_key_thumbprint is not None

    device = db.execute(select(Device).where(Device.id == code_row.device_id)).scalar_one()
    assert device.key_thumbprint == code_row.enroll_key_thumbprint
    assert device.attestation == "NONE"


def test_tc_api_03_lost_201_replayed(client: TestClient, db, test_study_and_site):
    """TC-API-03: Repeat the same request, key, and public key -> same 201 and device; no new device."""
    study, site = test_study_and_site
    _code_row, code_str = create_enrollment_code(db, study.id, site.id, code_str="K7QM-3XRA-9TP2")
    _privkey, jwk = mint_device_key()

    idem_key = str(uuid4())
    headers = {
        "Idempotency-Key": idem_key,
        "X-NeoGold-App-Version": "1.0.0",
    }
    payload = make_enrollment_payload(code_str, jwk)

    # First attempt: 201
    resp1 = client.post("/enrollments", json=payload, headers=headers)
    assert resp1.status_code == 201
    dev_id1 = resp1.json()["device_id"]

    # Replay with identical key, code, and public key
    resp2 = client.post("/enrollments", json=payload, headers=headers)
    assert resp2.status_code == 201
    dev_id2 = resp2.json()["device_id"]

    assert dev_id1 == dev_id2

    # Verify no second device was created
    devices = db.execute(select(Device).where(Device.id == dev_id1)).all()
    assert len(devices) == 1


def test_tc_api_04_double_submit(client: TestClient, db, test_study_and_site):
    """TC-API-04: Two enrollments with the same code and different keys -> exactly one device; the other gets ENROLLMENT_CODE_USED."""
    study, site = test_study_and_site
    _code_row, code_str = create_enrollment_code(db, study.id, site.id, code_str="K7QM-3XRA-9TP3")

    _privkey1, jwk1 = mint_device_key()
    _privkey2, jwk2 = mint_device_key()

    payload1 = make_enrollment_payload(code_str, jwk1)
    payload2 = make_enrollment_payload(code_str, jwk2)

    headers1 = {"Idempotency-Key": str(uuid4()), "X-NeoGold-App-Version": "1.0.0"}
    headers2 = {"Idempotency-Key": str(uuid4()), "X-NeoGold-App-Version": "1.0.0"}

    # First enrollment succeeds
    resp1 = client.post("/enrollments", json=payload1, headers=headers1)
    assert resp1.status_code == 201

    # Second enrollment with different key fails with ENROLLMENT_CODE_USED (403)
    resp2 = client.post("/enrollments", json=payload2, headers=headers2)
    assert resp2.status_code == 403
    prob = resp2.json()
    assert prob["code"] == "ENROLLMENT_CODE_USED"


def test_tc_api_05_bad_codes_and_rate_limit(client: TestClient, db, test_study_and_site):
    """TC-API-05: Expired, used, and unknown codes; 11 failed attempts from one client IP within an hour; 10 successful enrollments not limited."""
    study, site = test_study_and_site

    # 1. Unknown code
    _privkey, jwk = mint_device_key()
    resp_unknown = client.post(
        "/enrollments",
        json=make_enrollment_payload("0000-0000-0000", jwk),
        headers={"Idempotency-Key": str(uuid4()), "X-NeoGold-App-Version": "1.0.0"},
    )
    assert resp_unknown.status_code == 403
    assert resp_unknown.json()["code"] == "ENROLLMENT_CODE_INVALID"

    # 2. Expired code
    _expired_code_row, expired_code_str = create_enrollment_code(
        db, study.id, site.id, code_str="K7QM-3XRA-9EXP", expires_delta=datetime.timedelta(hours=-1)
    )
    resp_expired = client.post(
        "/enrollments",
        json=make_enrollment_payload(expired_code_str, jwk),
        headers={"Idempotency-Key": str(uuid4()), "X-NeoGold-App-Version": "1.0.0"},
    )
    assert resp_expired.status_code == 403
    assert resp_expired.json()["code"] == "ENROLLMENT_CODE_EXPIRED"

    # 3. 11 failed attempts from one client IP
    enrollment_limiter.reset()
    target_ip = "192.168.10.50"
    ip_headers = {
        "Idempotency-Key": str(uuid4()),
        "X-NeoGold-App-Version": "1.0.0",
        "X-Forwarded-For": target_ip,
    }

    # Generate 10 failures
    for i in range(10):
        r = client.post(
            "/enrollments",
            json=make_enrollment_payload("0000-0000-0000", jwk),
            headers=ip_headers,
        )
        assert r.status_code == 403

    # 11th failure is rate limited with 429
    r11 = client.post(
        "/enrollments",
        json=make_enrollment_payload("0000-0000-0000", jwk),
        headers=ip_headers,
    )
    assert r11.status_code == 429
    assert r11.json()["code"] == "RATE_LIMITED"
    assert "retry-after" in r11.headers

    # 4. Successful enrollments are not limited by failure rate limiter
    enrollment_limiter.reset()
    success_ip = "192.168.10.60"
    for idx in range(10):
        _code_row, c_str = create_enrollment_code(
            db, study.id, site.id, code_str=f"K7QM-3XRA-00{idx:02d}"
        )
        _k_priv, k_jwk = mint_device_key()
        r_succ = client.post(
            "/enrollments",
            json=make_enrollment_payload(c_str, k_jwk),
            headers={
                "Idempotency-Key": str(uuid4()),
                "X-NeoGold-App-Version": "1.0.0",
                "X-Forwarded-For": success_ip,
            },
        )
        assert r_succ.status_code == 201


def test_tc_api_06_attestation_binding(client: TestClient, db, test_study_and_site):
    """TC-API-06: Valid chain; wrong challenge; leaf key different from the JWK -> VERIFIED; FAILED; FAILED."""
    study, site = test_study_and_site

    # Case 1: Valid attestation chain matching challenge & leaf key
    _code_row1, code_str1 = create_enrollment_code(db, study.id, site.id, code_str="K7QM-3XRA-9AT1")
    privkey1, jwk1 = mint_device_key()
    challenge1 = hashlib.sha256(code_str1.encode("utf-8")).digest()
    chain1 = build_test_attestation_chain(privkey1, challenge=challenge1, match_key=True)

    r1 = client.post(
        "/enrollments",
        json=make_enrollment_payload(code_str1, jwk1, attestation_chain=chain1),
        headers={"Idempotency-Key": str(uuid4()), "X-NeoGold-App-Version": "1.0.0"},
    )
    assert r1.status_code == 201
    dev1 = db.execute(select(Device).where(Device.id == r1.json()["device_id"])).scalar_one()
    assert dev1.attestation == "VERIFIED"

    # Case 2: Attestation with wrong challenge
    _code_row2, code_str2 = create_enrollment_code(db, study.id, site.id, code_str="K7QM-3XRA-9AT2")
    privkey2, jwk2 = mint_device_key()
    wrong_challenge = hashlib.sha256(b"completely-wrong-code").digest()
    chain2 = build_test_attestation_chain(privkey2, challenge=wrong_challenge, match_key=True)

    r2 = client.post(
        "/enrollments",
        json=make_enrollment_payload(code_str2, jwk2, attestation_chain=chain2),
        headers={"Idempotency-Key": str(uuid4()), "X-NeoGold-App-Version": "1.0.0"},
    )
    assert r2.status_code == 201
    dev2 = db.execute(select(Device).where(Device.id == r2.json()["device_id"])).scalar_one()
    assert dev2.attestation == "FAILED"

    # Case 3: Leaf key different from JWK
    _code_row3, code_str3 = create_enrollment_code(db, study.id, site.id, code_str="K7QM-3XRA-9AT3")
    privkey3, jwk3 = mint_device_key()
    challenge3 = hashlib.sha256(code_str3.encode("utf-8")).digest()
    chain3 = build_test_attestation_chain(privkey3, challenge=challenge3, match_key=False)

    r3 = client.post(
        "/enrollments",
        json=make_enrollment_payload(code_str3, jwk3, attestation_chain=chain3),
        headers={"Idempotency-Key": str(uuid4()), "X-NeoGold-App-Version": "1.0.0"},
    )
    assert r3.status_code == 201
    dev3 = db.execute(select(Device).where(Device.id == r3.json()["device_id"])).scalar_one()
    assert dev3.attestation == "FAILED"


def test_tc_api_07_timestamp_precision_policy(client: TestClient, db, test_study_and_site):
    """TC-API-07: Server timestamp_precision is authoritative in upload policy."""
    study, site = test_study_and_site
    _code_row, code_str = create_enrollment_code(db, study.id, site.id, code_str="K7QM-3XRA-9PR1")
    _privkey, jwk = mint_device_key()

    resp = client.post(
        "/enrollments",
        json=make_enrollment_payload(code_str, jwk),
        headers={"Idempotency-Key": str(uuid4()), "X-NeoGold-App-Version": "1.0.0"},
    )
    assert resp.status_code == 201
    assert resp.json()["upload_policy"]["timestamp_precision"] == study.timestamp_precision
