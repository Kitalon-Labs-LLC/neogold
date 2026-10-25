from __future__ import annotations

import datetime
import json
from uuid import UUID, uuid4

import pytest
from fastapi.testclient import TestClient
from sqlalchemy import select

from app.models.db import IdempotencyRecord
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


def test_tc_api_13_only_final_outcomes_stored(client: TestClient, db, enrolled_device, test_study_and_site):
    """TC-API-13: Manifest gets PARTICIPANT_UNKNOWN; upload participant; retry manifest with the same key -> Retry succeeds."""
    device_id, privkey, _jwk = enrolled_device
    _study, _site = test_study_and_site
    today_str = datetime.datetime.now(datetime.UTC).date().isoformat()

    participant_id = uuid4()
    session_id = uuid4()
    manifest_path = f"/sessions/{session_id}"
    idem_key = str(uuid4())

    manifest_payload = {
        "participant_id": str(participant_id),
        "card_status": "UNVERIFIED",
        "card_batch_id": None,
        "capture_date": today_str,
        "captured_at": None,
        "age_hours": 48.0,
        "gestational_age_weeks": 39,
        "flag_jaundice_under_24h": False,
        "flag_danger_signs": False,
        "flag_prior_phototherapy": False,
        "estimate_mg_dl": 12.5,
        "estimate_low_mg_dl": 10.0,
        "estimate_high_mg_dl": 15.0,
        "app_version": "1.0.0",
        "model_version": "1.0.0",
        "config_version": "1.0.0",
        "captures": [
            {
                "capture_id": str(uuid4()),
                "attempt_number": 1,
                "site": "STERNUM",
                "quality_status": "PASSED",
                "has_image": False,
                "image_sha256": None,
                "size_bytes": None,
                "capture_metadata": {
                    "ae_locked": True,
                    "awb_locked": True,
                    "camera_hardware_level": "LIMITED",
                },
            }
        ],
    }
    manifest_bytes = json.dumps(manifest_payload, separators=(",", ":")).encode("utf-8")

    # 1. Attempt manifest upload before participant is registered
    tok1 = mint_device_token(privkey, device_id, "PUT", manifest_path, body_bytes=manifest_bytes)
    r1 = client.put(
        manifest_path,
        content=manifest_bytes,
        headers={
            "Authorization": f"Bearer {tok1}",
            "Idempotency-Key": idem_key,
            "X-NeoGold-App-Version": "1.0.0",
            "Content-Type": "application/json",
        },
    )
    assert r1.status_code == 422
    assert r1.json()["code"] == "PARTICIPANT_UNKNOWN"

    # Verify that the IN_PROGRESS idempotency row was deleted!
    idem_rec = db.execute(
        select(IdempotencyRecord).where(
            IdempotencyRecord.device_id == device_id,
            IdempotencyRecord.idempotency_key == UUID(idem_key),
        )
    ).scalar_one_or_none()
    assert idem_rec is None

    # 2. Upload the participant
    part_path = f"/participants/{participant_id}"
    part_payload = {
        "participant_code": "SKP-2001",
        "consent_form_id": "CF-2026-2001",
        "consent_date": today_str,
    }
    part_bytes = json.dumps(part_payload, separators=(",", ":")).encode("utf-8")
    part_tok = mint_device_token(privkey, device_id, "PUT", part_path, body_bytes=part_bytes)
    r_part = client.put(
        part_path,
        content=part_bytes,
        headers={
            "Authorization": f"Bearer {part_tok}",
            "Idempotency-Key": str(uuid4()),
            "X-NeoGold-App-Version": "1.0.0",
            "Content-Type": "application/json",
        },
    )
    assert r_part.status_code == 201

    # 3. Retry manifest upload with the EXACT SAME Idempotency-Key!
    tok2 = mint_device_token(privkey, device_id, "PUT", manifest_path, body_bytes=manifest_bytes)
    r2 = client.put(
        manifest_path,
        content=manifest_bytes,
        headers={
            "Authorization": f"Bearer {tok2}",
            "Idempotency-Key": idem_key,
            "X-NeoGold-App-Version": "1.0.0",
            "Content-Type": "application/json",
        },
    )
    assert r2.status_code == 201
    assert r2.json()["session_id"] == str(session_id)
    assert r2.json()["status"] == "RECEIVING"

    # Verify final outcome is stored as DONE
    idem_rec_done = db.execute(
        select(IdempotencyRecord).where(
            IdempotencyRecord.device_id == device_id,
            IdempotencyRecord.idempotency_key == UUID(idem_key),
        )
    ).scalar_one_or_none()
    assert idem_rec_done is not None
    assert idem_rec_done.state == "DONE"
    assert idem_rec_done.response_status == 201


def test_tc_api_14_key_reused_with_new_body(client: TestClient, db, enrolled_device):
    """TC-API-14: Same Idempotency-Key with a different body -> 409 IDEMPOTENCY_KEY_REUSED."""
    device_id, privkey, _jwk = enrolled_device
    today_str = datetime.datetime.now(datetime.UTC).date().isoformat()

    part_id1 = uuid4()
    part_path1 = f"/participants/{part_id1}"
    idem_key = str(uuid4())

    body1 = json.dumps(
        {
            "participant_code": "SKP-3001",
            "consent_form_id": "CF-3001",
            "consent_date": today_str,
        },
        separators=(",", ":"),
    ).encode("utf-8")

    tok1 = mint_device_token(privkey, device_id, "PUT", part_path1, body_bytes=body1)
    r1 = client.put(
        part_path1,
        content=body1,
        headers={
            "Authorization": f"Bearer {tok1}",
            "Idempotency-Key": idem_key,
            "X-NeoGold-App-Version": "1.0.0",
            "Content-Type": "application/json",
        },
    )
    assert r1.status_code == 201

    # Second request reusing the SAME idempotency key, but with body2
    part_id2 = uuid4()
    part_path2 = f"/participants/{part_id2}"
    body2 = json.dumps(
        {
            "participant_code": "SKP-3002",
            "consent_form_id": "CF-3002",
            "consent_date": today_str,
        },
        separators=(",", ":"),
    ).encode("utf-8")

    tok2 = mint_device_token(privkey, device_id, "PUT", part_path2, body_bytes=body2)
    r2 = client.put(
        part_path2,
        content=body2,
        headers={
            "Authorization": f"Bearer {tok2}",
            "Idempotency-Key": idem_key,
            "X-NeoGold-App-Version": "1.0.0",
            "Content-Type": "application/json",
        },
    )
    assert r2.status_code == 409
    assert r2.json()["code"] == "IDEMPOTENCY_KEY_REUSED"
