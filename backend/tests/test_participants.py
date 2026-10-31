from __future__ import annotations

import datetime
import json
from uuid import UUID, uuid4

import pytest
from fastapi.testclient import TestClient
from sqlalchemy import select

from app.models.db import IdempotencyRecord, ParticipantAlias
from tests.conftest import (
    create_enrollment_code,
    mint_device_key,
    mint_device_token,
)


@pytest.fixture
def two_enrolled_devices(client: TestClient, db, test_study_and_site):
    study, site = test_study_and_site

    # Enroll Device 1
    _code_row1, code_str1 = create_enrollment_code(db, study.id, site.id, code_str=f"K7QM-3XRA-{uuid4().hex[:4].upper()}")
    privkey1, jwk1 = mint_device_key()
    resp1 = client.post(
        "/enrollments",
        json={
            "enrollment_code": code_str1,
            "device_public_key": jwk1,
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
    assert resp1.status_code == 201
    dev1_id = UUID(resp1.json()["device_id"])

    # Enroll Device 2
    _code_row2, code_str2 = create_enrollment_code(db, study.id, site.id, code_str=f"K7QM-3XRA-{uuid4().hex[:4].upper()}")
    privkey2, jwk2 = mint_device_key()
    resp2 = client.post(
        "/enrollments",
        json={
            "enrollment_code": code_str2,
            "device_public_key": jwk2,
            "device": {
                "manufacturer": "Xiaomi",
                "model": "Redmi 9",
                "android_api": 29,
                "app_version": "1.0.0",
                "camera_hardware_level": "FULL",
                "qualification": "QUALIFIED",
            },
        },
        headers={"Idempotency-Key": str(uuid4()), "X-NeoGold-App-Version": "1.0.0"},
    )
    assert resp2.status_code == 201
    dev2_id = UUID(resp2.json()["device_id"])

    return (dev1_id, privkey1), (dev2_id, privkey2)


def test_tc_api_16_participant_alias_and_conflict(client: TestClient, db, two_enrolled_devices, test_study_and_site):
    """TC-API-16: Same code and consent ID from a second device; then a different consent ID -> 200 alias; then 409 PARTICIPANT_CODE_TAKEN."""
    (dev1_id, privkey1), (dev2_id, privkey2) = two_enrolled_devices
    _study, site = test_study_and_site
    today_str = datetime.datetime.now(datetime.UTC).date().isoformat()

    p1_id = uuid4()
    p1_path = f"/participants/{p1_id}"
    code_str = "SKP-0142"
    consent_id_a = "CF-2026-001"
    consent_id_b = "CF-2026-002"

    payload1 = {
        "participant_code": code_str,
        "consent_form_id": consent_id_a,
        "consent_date": today_str,
    }
    bytes1 = json.dumps(payload1, separators=(",", ":")).encode("utf-8")

    # 1. Device 1 creates participant P1 -> 201 Created
    tok1 = mint_device_token(privkey1, dev1_id, "PUT", p1_path, body_bytes=bytes1)
    r1 = client.put(
        p1_path,
        content=bytes1,
        headers={
            "Authorization": f"Bearer {tok1}",
            "Idempotency-Key": str(uuid4()),
            "X-NeoGold-App-Version": "1.0.0",
            "Content-Type": "application/json",
        },
    )
    assert r1.status_code == 201
    receipt1 = r1.json()
    assert receipt1["participant_id"] == str(p1_id)
    assert receipt1["canonical_participant_id"] == str(p1_id)
    assert receipt1["site_code"] == site.site_code

    # 2. Device 2 submits the SAME code and SAME consent form ID under P2_id -> 200 OK (alias registered!)
    p2_id = uuid4()
    p2_path = f"/participants/{p2_id}"
    payload2 = {
        "participant_code": code_str,
        "consent_form_id": consent_id_a,
        "consent_date": today_str,
    }
    bytes2 = json.dumps(payload2, separators=(",", ":")).encode("utf-8")
    tok2 = mint_device_token(privkey2, dev2_id, "PUT", p2_path, body_bytes=bytes2)
    r2 = client.put(
        p2_path,
        content=bytes2,
        headers={
            "Authorization": f"Bearer {tok2}",
            "Idempotency-Key": str(uuid4()),
            "X-NeoGold-App-Version": "1.0.0",
            "Content-Type": "application/json",
        },
    )
    assert r2.status_code == 200
    receipt2 = r2.json()
    assert receipt2["participant_id"] == str(p2_id)
    assert receipt2["canonical_participant_id"] == str(p1_id)  # Canonical points to P1!

    # Verify alias row in database
    alias_row = db.execute(select(ParticipantAlias).where(ParticipantAlias.alias_id == p2_id)).scalar_one_or_none()
    assert alias_row is not None
    assert alias_row.participant_id == p1_id
    assert alias_row.device_id == dev2_id

    # 3. Device 2 submits the SAME code with a DIFFERENT consent form ID under P3_id -> 409 PARTICIPANT_CODE_TAKEN
    p3_id = uuid4()
    p3_path = f"/participants/{p3_id}"
    payload3 = {
        "participant_code": code_str,
        "consent_form_id": consent_id_b,  # Different consent ID!
        "consent_date": today_str,
    }
    bytes3 = json.dumps(payload3, separators=(",", ":")).encode("utf-8")
    tok3 = mint_device_token(privkey2, dev2_id, "PUT", p3_path, body_bytes=bytes3)
    r3 = client.put(
        p3_path,
        content=bytes3,
        headers={
            "Authorization": f"Bearer {tok3}",
            "Idempotency-Key": str(uuid4()),
            "X-NeoGold-App-Version": "1.0.0",
            "Content-Type": "application/json",
        },
    )
    assert r3.status_code == 409
    prob3 = r3.json()
    assert prob3["code"] == "PARTICIPANT_CODE_TAKEN"
    # doc 7 Section 6.1: a problem detail never carries a participant code. The response is also stored for
    # idempotent replay, so a code here would sit in idempotency_record too (audit A10).
    assert code_str not in r3.text
    assert code_str not in prob3.get("detail", "")
    # ... and what was stored for replay is the same body, so check the table and not only the response.
    stored = db.execute(
        select(IdempotencyRecord.response_body).where(IdempotencyRecord.response_status == 409)
    ).scalars().all()
    assert stored, "the 409 should have been stored for idempotent replay"
    assert all(code_str not in json.dumps(body) for body in stored)

    # 4. Same participant ID submitted with different content -> 409 CONFLICT_IMMUTABLE
    payload_conflict = {
        "participant_code": "SKP-9999",
        "consent_form_id": consent_id_a,
        "consent_date": today_str,
    }
    bytes_conflict = json.dumps(payload_conflict, separators=(",", ":")).encode("utf-8")
    tok_conflict = mint_device_token(privkey1, dev1_id, "PUT", p1_path, body_bytes=bytes_conflict)
    r_conflict = client.put(
        p1_path,
        content=bytes_conflict,
        headers={
            "Authorization": f"Bearer {tok_conflict}",
            "Idempotency-Key": str(uuid4()),
            "X-NeoGold-App-Version": "1.0.0",
            "Content-Type": "application/json",
        },
    )
    assert r_conflict.status_code == 409
    assert r_conflict.json()["code"] == "CONFLICT_IMMUTABLE"
