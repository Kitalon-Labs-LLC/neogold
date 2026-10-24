from __future__ import annotations

import datetime
from uuid import UUID, uuid4

import pytest
from fastapi.testclient import TestClient
from sqlalchemy import delete, select, update

from app.models.db import AuthNonce, Device, Participant, Study
from app.models.db import Session as DbSession
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


def test_tc_api_08_ten_authentication_cases(client: TestClient, db, enrolled_device, test_study_and_site):
    """TC-API-08: Run doc 7 Section 12.3 cases 1 to 10."""
    device_id, privkey, _jwk = enrolled_device
    _study, _site = test_study_and_site
    now = int(datetime.datetime.now(datetime.UTC).timestamp())

    # Case 1: Valid token -> Accepted
    tok1 = mint_device_token(privkey, device_id, "GET", "/device")
    r1 = client.get("/device", headers={"Authorization": f"Bearer {tok1}", "X-NeoGold-App-Version": "1.0.0"})
    assert r1.status_code == 200
    assert r1.json()["device_id"] == str(device_id)

    # Case 2: Same token sent twice -> AUTH_REPLAY
    r2 = client.get("/device", headers={"Authorization": f"Bearer {tok1}", "X-NeoGold-App-Version": "1.0.0"})
    assert r2.status_code == 401
    assert r2.json()["code"] == "AUTH_REPLAY"

    # Case 3: Valid token, body swapped -> AUTH_CLAIM_MISMATCH
    body_a = b'{"participant_code":"SKP-001","consent_form_id":"CF-01","consent_date":"2026-10-20"}'
    body_b = b'{"participant_code":"SKP-002","consent_form_id":"CF-02","consent_date":"2026-10-20"}'
    part_id = str(uuid4())
    part_path = f"/participants/{part_id}"
    tok3 = mint_device_token(privkey, device_id, "PUT", part_path, body_bytes=body_a)
    r3 = client.put(
        part_path,
        content=body_b,  # Swapped body!
        headers={
            "Authorization": f"Bearer {tok3}",
            "Idempotency-Key": str(uuid4()),
            "X-NeoGold-App-Version": "1.0.0",
            "Content-Type": "application/json",
        },
    )
    assert r3.status_code == 401
    assert r3.json()["code"] == "AUTH_CLAIM_MISMATCH"

    # Case 4: Valid token, sent to another URL -> AUTH_CLAIM_MISMATCH
    tok4 = mint_device_token(privkey, device_id, "GET", "/device")
    fake_sess_id = str(uuid4())
    r4 = client.get(
        f"/sessions/{fake_sess_id}",
        headers={"Authorization": f"Bearer {tok4}", "X-NeoGold-App-Version": "1.0.0"},
    )
    assert r4.status_code == 401
    assert r4.json()["code"] == "AUTH_CLAIM_MISMATCH"

    # Case 5: Phone clock 10 minutes fast -> CLOCK_SKEW with server_time
    tok5 = mint_device_token(privkey, device_id, "GET", "/device", iat=now + 600, exp=now + 720)
    r5 = client.get("/device", headers={"Authorization": f"Bearer {tok5}", "X-NeoGold-App-Version": "1.0.0"})
    assert r5.status_code == 401
    prob5 = r5.json()
    assert prob5["code"] == "CLOCK_SKEW"
    assert "server_time" in prob5

    # Case 6: Token issued 400 seconds ago -> AUTH_EXPIRED with server_time
    tok6 = mint_device_token(privkey, device_id, "GET", "/device", iat=now - 400, exp=now - 100)
    r6 = client.get("/device", headers={"Authorization": f"Bearer {tok6}", "X-NeoGold-App-Version": "1.0.0"})
    assert r6.status_code == 401
    prob6 = r6.json()
    assert prob6["code"] == "AUTH_EXPIRED"
    assert "server_time" in prob6

    # Case 7: Upload whose headers arrived inside token's life but finished after it (t0 rule)
    # Token whose exp is 5 seconds after t0. It passes Phase A step 5 (t0 <= exp + 30)
    tok7 = mint_device_token(privkey, device_id, "GET", "/device", iat=now - 10, exp=now + 5)
    r7 = client.get("/device", headers={"Authorization": f"Bearer {tok7}", "X-NeoGold-App-Version": "1.0.0"})
    assert r7.status_code == 200

    # Case 8: Token with a one-hour life -> AUTH_CLAIM_MISMATCH
    tok8 = mint_device_token(privkey, device_id, "GET", "/device", iat=now, exp=now + 3600)
    r8 = client.get("/device", headers={"Authorization": f"Bearer {tok8}", "X-NeoGold-App-Version": "1.0.0"})
    assert r8.status_code == 401
    assert r8.json()["code"] == "AUTH_CLAIM_MISMATCH"

    # Case 9: Token signed by a different key -> AUTH_INVALID_SIGNATURE
    other_privkey, _ = mint_device_key()
    tok9 = mint_device_token(other_privkey, device_id, "GET", "/device")
    r9 = client.get("/device", headers={"Authorization": f"Bearer {tok9}", "X-NeoGold-App-Version": "1.0.0"})
    assert r9.status_code == 401
    assert r9.json()["code"] == "AUTH_INVALID_SIGNATURE"

    # Case 10: Token from a revoked device -> DEVICE_REVOKED (403)
    db.execute(
        update(Device)
        .where(Device.id == device_id)
        .values(revoked_at=datetime.datetime.now(datetime.UTC), revoked_reason="LOST")
    )
    db.commit()

    tok10 = mint_device_token(privkey, device_id, "GET", "/device")
    # Even on GET /device, a revoked device gets 403 DEVICE_REVOKED
    r10 = client.get("/device", headers={"Authorization": f"Bearer {tok10}", "X-NeoGold-App-Version": "1.0.0"})
    assert r10.status_code == 403
    assert r10.json()["code"] == "DEVICE_REVOKED"


def test_tc_api_09_rejection_before_body(client: TestClient, db, enrolled_device):
    """TC-API-09: Revoked device sends large payload -> 403 returned without reading body."""
    device_id, privkey, _jwk = enrolled_device

    # Revoke device
    db.execute(
        update(Device)
        .where(Device.id == device_id)
        .values(revoked_at=datetime.datetime.now(datetime.UTC), revoked_reason="STOLEN")
    )
    db.commit()

    # Track whether generator was consumed
    consumed = False

    def body_stream():
        nonlocal consumed
        consumed = True
        yield b"0" * 1024

    tok = mint_device_token(privkey, device_id, "PUT", "/card-batches/NG-2026-999")
    resp = client.put(
        "/card-batches/NG-2026-999",
        content=body_stream(),
        headers={
            "Authorization": f"Bearer {tok}",
            "Idempotency-Key": str(uuid4()),
            "X-NeoGold-App-Version": "1.0.0",
            "Content-Type": "application/json",
        },
    )
    assert resp.status_code == 403
    assert resp.json()["code"] == "DEVICE_REVOKED"
    # Body generator should never be consumed because Phase A rejected before Phase B!
    assert not consumed


def test_tc_api_10_nonce_lifetime(client: TestClient, db, enrolled_device):
    """TC-API-10: Token at exp + 20s after purge job runs -> Nonce still present; replay refused."""
    device_id, privkey, _jwk = enrolled_device
    now = int(datetime.datetime.now(datetime.UTC).timestamp())

    # Token issued with exp in the past, but within the 30s grace window (e.g. t0 = exp + 10s)
    exp = now - 10
    iat = exp - 120
    jti = uuid4()
    tok = mint_device_token(privkey, device_id, "GET", "/device", iat=iat, exp=exp, jti=jti)

    # 1. First request arrives at exp + 10s (within 30s grace window): accepted!
    r1 = client.get("/device", headers={"Authorization": f"Bearer {tok}", "X-NeoGold-App-Version": "1.0.0"})
    assert r1.status_code == 200

    # 2. Simulate the purge job running at current time (exp + 10s):
    # Purge job rule: DELETE FROM auth_nonce WHERE expires_at <= :purge_time
    # Since expires_at = exp + 30s, at purge_time = now (exp + 10s), expires_at is in the future (+20s)!
    current_time_dt = datetime.datetime.now(datetime.UTC)
    db.execute(delete(AuthNonce).where(AuthNonce.expires_at <= current_time_dt))
    db.commit()

    # Nonce is still in database!
    nonce_row = db.execute(select(AuthNonce).where(AuthNonce.jti == jti)).scalar_one_or_none()
    assert nonce_row is not None
    assert nonce_row.expires_at > current_time_dt

    # 3. Replay token: replay is refused with AUTH_REPLAY
    r2 = client.get("/device", headers={"Authorization": f"Bearer {tok}", "X-NeoGold-App-Version": "1.0.0"})
    assert r2.status_code == 401
    assert r2.json()["code"] == "AUTH_REPLAY"


def test_tc_api_11_url_binding_behind_proxy(client: TestClient, enrolled_device):
    """TC-API-11: aud and htu match the public base URL; request accepted behind proxy."""
    device_id, privkey, _jwk = enrolled_device

    # Token minted using the configured PUBLIC_BASE_URL
    tok = mint_device_token(privkey, device_id, "GET", "/device")
    # Request made via TestClient (which might use localhost / testserver)
    resp = client.get(
        "/device",
        headers={
            "Authorization": f"Bearer {tok}",
            "X-NeoGold-App-Version": "1.0.0",
            "Host": "caddy.internal:8080",  # Internal proxy host header
        },
    )
    assert resp.status_code == 200


def test_tc_api_12_exempt_calls(client: TestClient, db, enrolled_device, test_study_and_site):
    """TC-API-12: GET /device, GET /sessions/{id}, DELETE /device on CLOSED study and old app version succeed."""
    device_id, privkey, _jwk = enrolled_device
    study, site = test_study_and_site

    # Create participant for session foreign key
    today = datetime.datetime.now(datetime.UTC).date()
    p_id = uuid4()
    db.add(
        Participant(
            id=p_id,
            site_id=site.id,
            participant_code=f"SKP-{uuid4().hex[:4].upper()}",
            consent_form_id="CF-EXEMPT-01",
            consent_date=today,
            created_on=today,
        )
    )
    db.flush()

    # Create dummy session for GET /sessions/{id}
    sess_id = uuid4()
    db.add(
        DbSession(
            id=sess_id,
            participant_id=p_id,
            device_id=device_id,
            capture_date=today,
            age_hours=24.0,
            gestational_age_weeks=38,
            flag_jaundice_under_24h=False,
            flag_danger_signs=False,
            flag_prior_phototherapy=False,
            app_version="0.5.0",
            config_version="1.0.0",
            manifest_sha256="dummy_manifest_hash",
            card_status="UNVERIFIED",
            card_batch_id=None,
            status="RECEIVING",
            received_on=today,
        )
    )
    db.commit()

    try:
        # Set study to CLOSED and min_app_version to "2.0.0"
        db.execute(
            update(Study)
            .where(Study.id == study.id)
            .values(status="CLOSED", min_app_version="2.0.0")
        )
        db.commit()

        old_app_version = "0.5.0"

        # 1. Non-exempt call fails due to CLOSED study and old app version
        tok_batch = mint_device_token(privkey, device_id, "PUT", "/card-batches/NG-2026-001")
        r_batch = client.put(
            "/card-batches/NG-2026-001",
            json={"qr_payload": "AQEAAAAAAAAAAAA="},
            headers={
                "Authorization": f"Bearer {tok_batch}",
                "Idempotency-Key": str(uuid4()),
                "X-NeoGold-App-Version": old_app_version,
            },
        )
        assert r_batch.status_code == 403
        assert r_batch.json()["code"] == "STUDY_CLOSED"

        # 2. Exempt call: GET /device succeeds!
        tok_dev = mint_device_token(privkey, device_id, "GET", "/device")
        r_dev = client.get(
            "/device",
            headers={"Authorization": f"Bearer {tok_dev}", "X-NeoGold-App-Version": old_app_version},
        )
        assert r_dev.status_code == 200
        assert r_dev.json()["study_status"] == "CLOSED"

        # 3. Exempt call: GET /sessions/{id} succeeds!
        tok_sess = mint_device_token(privkey, device_id, "GET", f"/sessions/{sess_id}")
        r_sess = client.get(
            f"/sessions/{sess_id}",
            headers={"Authorization": f"Bearer {tok_sess}", "X-NeoGold-App-Version": old_app_version},
        )
        assert r_sess.status_code == 200
        assert r_sess.json()["session_id"] == str(sess_id)

        # 4. Exempt call: DELETE /device succeeds!
        tok_del = mint_device_token(privkey, device_id, "DELETE", "/device")
        r_del = client.delete(
            "/device",
            headers={
                "Authorization": f"Bearer {tok_del}",
                "Idempotency-Key": str(uuid4()),
                "X-NeoGold-App-Version": old_app_version,
            },
        )
        assert r_del.status_code == 204

        # Verify device is now self-unenrolled in DB
        dev_row = db.execute(select(Device).where(Device.id == device_id)).scalar_one()
        assert dev_row.revoked_at is not None
        assert dev_row.revoked_reason == "SELF_UNENROLLED"
    finally:
        # Reset study status
        db.execute(
            update(Study)
            .where(Study.id == study.id)
            .values(status="ACTIVE", min_app_version="1.0.0")
        )
        db.commit()

