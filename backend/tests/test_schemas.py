from __future__ import annotations

import datetime
import json
from uuid import UUID, uuid4

import pytest
from fastapi.testclient import TestClient

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


def test_tc_api_15_unknown_fields_rejected_enrollment(client: TestClient, db, test_study_and_site):
    """TC-API-15: Extra field in EnrollmentRequest -> 400 VALIDATION_FAILED."""
    study, site = test_study_and_site
    _code_row, code_str = create_enrollment_code(db, study.id, site.id, code_str=f"K7QM-3XRA-{uuid4().hex[:4].upper()}")
    _privkey, jwk = mint_device_key()

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
        "extra_field_disallowed": "injected_value",
    }
    resp = client.post(
        "/enrollments",
        json=payload,
        headers={"Idempotency-Key": str(uuid4()), "X-NeoGold-App-Version": "1.0.0"},
    )
    assert resp.status_code == 400
    prob = resp.json()
    assert prob["code"] == "VALIDATION_FAILED"


def test_tc_api_15_unknown_fields_rejected_card_batch(client: TestClient, enrolled_device):
    """TC-API-15: Extra field in CardBatchUpload -> 400 VALIDATION_FAILED."""
    device_id, privkey, _jwk = enrolled_device
    batch_path = "/card-batches/NG-2026-999"
    payload = {
        "qr_payload": "AQEAAAAAAAAAAAA=",
        "extra_field_disallowed": "value",
    }
    body_bytes = json.dumps(payload, separators=(",", ":")).encode("utf-8")
    tok = mint_device_token(privkey, device_id, "PUT", batch_path, body_bytes=body_bytes)

    resp = client.put(
        batch_path,
        content=body_bytes,
        headers={
            "Authorization": f"Bearer {tok}",
            "Idempotency-Key": str(uuid4()),
            "X-NeoGold-App-Version": "1.0.0",
            "Content-Type": "application/json",
        },
    )
    assert resp.status_code == 400
    assert resp.json()["code"] == "VALIDATION_FAILED"


def test_tc_api_15_unknown_fields_rejected_participant(client: TestClient, enrolled_device):
    """TC-API-15: Extra field in ParticipantUpload -> 400 VALIDATION_FAILED."""
    device_id, privkey, _jwk = enrolled_device
    part_id = uuid4()
    part_path = f"/participants/{part_id}"
    today_str = datetime.datetime.now(datetime.UTC).date().isoformat()
    payload = {
        "participant_code": "SKP-999",
        "consent_form_id": "CF-999",
        "consent_date": today_str,
        "unexpected_phi_field": "John Doe",
    }
    body_bytes = json.dumps(payload, separators=(",", ":")).encode("utf-8")
    tok = mint_device_token(privkey, device_id, "PUT", part_path, body_bytes=body_bytes)

    resp = client.put(
        part_path,
        content=body_bytes,
        headers={
            "Authorization": f"Bearer {tok}",
            "Idempotency-Key": str(uuid4()),
            "X-NeoGold-App-Version": "1.0.0",
            "Content-Type": "application/json",
        },
    )
    assert resp.status_code == 400
    assert resp.json()["code"] == "VALIDATION_FAILED"


def test_tc_api_15_unknown_fields_rejected_session(client: TestClient, enrolled_device):
    """TC-API-15: Extra field in SessionManifest -> 400 VALIDATION_FAILED."""
    device_id, privkey, _jwk = enrolled_device
    sess_id = uuid4()
    sess_path = f"/sessions/{sess_id}"
    today_str = datetime.datetime.now(datetime.UTC).date().isoformat()
    payload = {
        "participant_id": str(uuid4()),
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
        "rogue_field": "not_allowed",
    }
    body_bytes = json.dumps(payload, separators=(",", ":")).encode("utf-8")
    tok = mint_device_token(privkey, device_id, "PUT", sess_path, body_bytes=body_bytes)

    resp = client.put(
        sess_path,
        content=body_bytes,
        headers={
            "Authorization": f"Bearer {tok}",
            "Idempotency-Key": str(uuid4()),
            "X-NeoGold-App-Version": "1.0.0",
            "Content-Type": "application/json",
        },
    )
    assert resp.status_code == 400
    assert resp.json()["code"] == "VALIDATION_FAILED"


def test_tc_api_15_unknown_fields_rejected_tsb(client: TestClient, enrolled_device):
    """TC-API-15: Extra field in TsbUpload -> 400 VALIDATION_FAILED."""
    device_id, privkey, _jwk = enrolled_device
    tsb_id = uuid4()
    tsb_path = f"/tsb-measurements/{tsb_id}"
    payload = {
        "participant_id": str(uuid4()),
        "session_id": None,
        "tsb_mg_dl": 14.2,
        "minutes_from_capture": None,
        "draw_time": None,
        "method": "LAB",
        "extraneous_notes": "draw was easy",
    }
    body_bytes = json.dumps(payload, separators=(",", ":")).encode("utf-8")
    tok = mint_device_token(privkey, device_id, "PUT", tsb_path, body_bytes=body_bytes)

    resp = client.put(
        tsb_path,
        content=body_bytes,
        headers={
            "Authorization": f"Bearer {tok}",
            "Idempotency-Key": str(uuid4()),
            "X-NeoGold-App-Version": "1.0.0",
            "Content-Type": "application/json",
        },
    )
    assert resp.status_code == 400
    assert resp.json()["code"] == "VALIDATION_FAILED"
