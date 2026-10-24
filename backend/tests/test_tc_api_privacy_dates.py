from __future__ import annotations

import datetime
import json
from uuid import uuid4

from fastapi.testclient import TestClient
from sqlalchemy import select

from app.models.db import AuditLog, Participant, TsbMeasurement
from app.models.db import Session as DbSession
from tests.conftest import mint_device_token
from tests.test_tc_api_images import make_clean_jpeg, make_content_digest


def test_tc_api_26_dates_only_in_data_tables(client: TestClient, db, enrolled_device, test_study_and_site):
    """TC-API-26:
    Inspect session, capture, tsb_measurement, participant after uploads:
    Arrival and completion stored as dates; no time of day.
    """
    device_id, privkey, _jwk = enrolled_device
    today = datetime.datetime.now(datetime.UTC).date()
    today_str = today.isoformat()

    part_id = uuid4()
    part_code = "SKP-2601"
    part_path = f"/participants/{part_id}"
    part_payload = {
        "participant_code": part_code,
        "consent_form_id": "CF-2601",
        "consent_date": today_str,
    }
    part_bytes = json.dumps(part_payload, separators=(",", ":")).encode("utf-8")
    tok_p = mint_device_token(privkey, device_id, "PUT", part_path, body_bytes=part_bytes)
    r_p = client.put(
        part_path,
        content=part_bytes,
        headers={
            "Authorization": f"Bearer {tok_p}",
            "Idempotency-Key": str(uuid4()),
            "X-NeoGold-App-Version": "1.0.0",
            "Content-Type": "application/json",
        },
    )
    assert r_p.status_code == 201

    # Upload session with 1 capture
    sess_id = uuid4()
    cap_id = uuid4()
    img_bytes = make_clean_jpeg(20, 20, (1, 2, 3))
    img_sha, img_hdr, img_bsh = make_content_digest(img_bytes)

    m_path = f"/sessions/{sess_id}"
    m_payload = {
        "participant_id": str(part_id),
        "card_status": "UNVERIFIED",
        "card_batch_id": None,
        "capture_date": today_str,
        "captured_at": None,
        "age_hours": 18.0,
        "gestational_age_weeks": 39,
        "flag_jaundice_under_24h": False,
        "flag_danger_signs": False,
        "flag_prior_phototherapy": False,
        "estimate_mg_dl": 10.0,
        "estimate_low_mg_dl": 8.0,
        "estimate_high_mg_dl": 12.0,
        "app_version": "1.0.0",
        "model_version": "1.0.0",
        "config_version": "1.0.0",
        "captures": [
            {
                "capture_id": str(cap_id),
                "attempt_number": 1,
                "site": "STERNUM",
                "quality_status": "PASSED",
                "has_image": True,
                "image_sha256": img_sha,
                "size_bytes": len(img_bytes),
                "capture_metadata": {
                    "ae_locked": True,
                    "awb_locked": True,
                    "camera_hardware_level": "LIMITED",
                },
            }
        ],
    }
    m_bytes = json.dumps(m_payload, separators=(",", ":")).encode("utf-8")
    tok_m = mint_device_token(privkey, device_id, "PUT", m_path, body_bytes=m_bytes)
    r_m = client.put(
        m_path,
        content=m_bytes,
        headers={
            "Authorization": f"Bearer {tok_m}",
            "Idempotency-Key": str(uuid4()),
            "X-NeoGold-App-Version": "1.0.0",
            "Content-Type": "application/json",
        },
    )
    assert r_m.status_code == 201

    # Upload capture image to complete session
    c_path = f"/sessions/{sess_id}/captures/{cap_id}/image"
    tok_c = mint_device_token(privkey, device_id, "PUT", c_path, body_bytes=img_bytes, bsh=img_bsh)
    r_c = client.put(
        c_path,
        content=img_bytes,
        headers={
            "Authorization": f"Bearer {tok_c}",
            "Content-Digest": img_hdr,
            "Content-Type": "image/jpeg",
            "Idempotency-Key": str(uuid4()),
            "X-NeoGold-App-Version": "1.0.0",
        },
    )
    assert r_c.status_code == 201

    # Upload and link TSB
    tsb_id = uuid4()
    tsb_path = f"/tsb-measurements/{tsb_id}"
    tsb_payload = {
        "participant_id": str(part_id),
        "session_id": str(sess_id),
        "tsb_mg_dl": 11.2,
        "minutes_from_capture": 10,
        "draw_time": None,
        "method": "LAB",
    }
    t_bytes = json.dumps(tsb_payload, separators=(",", ":")).encode("utf-8")
    tok_t = mint_device_token(privkey, device_id, "PUT", tsb_path, body_bytes=t_bytes)
    r_t = client.put(
        tsb_path,
        content=t_bytes,
        headers={
            "Authorization": f"Bearer {tok_t}",
            "Idempotency-Key": str(uuid4()),
            "X-NeoGold-App-Version": "1.0.0",
            "Content-Type": "application/json",
        },
    )
    assert r_t.status_code == 201

    # Now verify all date fields in the database:
    p_row = db.execute(select(Participant).where(Participant.id == part_id)).scalar_one()
    assert isinstance(p_row.consent_date, datetime.date)
    assert not isinstance(p_row.consent_date, datetime.datetime)
    assert isinstance(p_row.created_on, datetime.date)
    assert not isinstance(p_row.created_on, datetime.datetime)

    s_row = db.execute(select(DbSession).where(DbSession.id == sess_id)).scalar_one()
    assert isinstance(s_row.received_on, datetime.date)
    assert not isinstance(s_row.received_on, datetime.datetime)
    assert isinstance(s_row.completed_on, datetime.date)
    assert not isinstance(s_row.completed_on, datetime.datetime)
    assert isinstance(s_row.capture_date, datetime.date)
    assert not isinstance(s_row.capture_date, datetime.datetime)
    assert s_row.captured_at is None

    t_row = db.execute(select(TsbMeasurement).where(TsbMeasurement.id == tsb_id)).scalar_one()
    assert isinstance(t_row.received_on, datetime.date)
    assert not isinstance(t_row.received_on, datetime.datetime)
    assert t_row.draw_time is None


def test_tc_api_27_audit_log_content(client: TestClient, db, enrolled_device, test_study_and_site):
    """TC-API-27:
    Inspect audit_log after a full study day:
    No per-session events; no participant codes.
    """
    device_id, privkey, _jwk = enrolled_device
    today_str = datetime.datetime.now(datetime.UTC).date().isoformat()

    part_id = uuid4()
    part_code = "SKP-2799"
    part_path = f"/participants/{part_id}"
    part_payload = {
        "participant_code": part_code,
        "consent_form_id": "CF-2799",
        "consent_date": today_str,
    }
    part_bytes = json.dumps(part_payload, separators=(",", ":")).encode("utf-8")
    tok_p = mint_device_token(privkey, device_id, "PUT", part_path, body_bytes=part_bytes)
    client.put(
        part_path,
        content=part_bytes,
        headers={
            "Authorization": f"Bearer {tok_p}",
            "Idempotency-Key": str(uuid4()),
            "X-NeoGold-App-Version": "1.0.0",
            "Content-Type": "application/json",
        },
    )

    # Perform session manifest upload
    sess_id = uuid4()
    m_path = f"/sessions/{sess_id}"
    m_payload = {
        "participant_id": str(part_id),
        "card_status": "UNVERIFIED",
        "card_batch_id": None,
        "capture_date": today_str,
        "captured_at": None,
        "age_hours": 24.0,
        "gestational_age_weeks": 39,
        "flag_jaundice_under_24h": False,
        "flag_danger_signs": False,
        "flag_prior_phototherapy": False,
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
    m_bytes = json.dumps(m_payload, separators=(",", ":")).encode("utf-8")
    tok_m = mint_device_token(privkey, device_id, "PUT", m_path, body_bytes=m_bytes)
    client.put(
        m_path,
        content=m_bytes,
        headers={
            "Authorization": f"Bearer {tok_m}",
            "Idempotency-Key": str(uuid4()),
            "X-NeoGold-App-Version": "1.0.0",
            "Content-Type": "application/json",
        },
    )

    # Query all audit_log entries
    logs = db.execute(select(AuditLog)).scalars().all()
    for entry in logs:
        # 1. No per-session action events (e.g., SESSION_UPLOADED, IMAGE_UPLOADED, etc.)
        assert "SESSION_" not in entry.action or entry.action == "SESSION_REJECTED"
        assert "IMAGE_" not in entry.action
        assert "TSB_" not in entry.action

        # 2. No participant codes anywhere in details or target_id
        if entry.details:
            details_str = json.dumps(entry.details)
            assert part_code not in details_str, f"Found participant code in audit details: {details_str}"
        if entry.target_id:
            assert part_code not in entry.target_id, f"Found participant code in target_id: {entry.target_id}"
