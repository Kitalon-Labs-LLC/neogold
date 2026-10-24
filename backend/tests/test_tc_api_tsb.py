from __future__ import annotations

import datetime
import json
from uuid import uuid4

from fastapi.testclient import TestClient

from tests.conftest import mint_device_token


def test_tc_api_24_tsb_links_once(client: TestClient, db, enrolled_device, test_study_and_site):
    """TC-API-24:
    - Upload TSB with session_id=null -> 201 Created, linked=False.
    - Link to a session -> 200 OK, linked=True, linked_on set.
    - Attempt to change it again (e.g. to a different session or different values) -> 409 CONFLICT_IMMUTABLE.
    - Re-sending identical linked value -> 200 OK (idempotent).
    """
    device_id, privkey, _jwk = enrolled_device
    today_str = datetime.datetime.now(datetime.UTC).date().isoformat()

    # 1. Register participant
    part_id = uuid4()
    part_path = f"/participants/{part_id}"
    part_payload = {
        "participant_code": "SKP-2401",
        "consent_form_id": "CF-2401",
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

    # 2. Register session 1
    sess1_id = uuid4()
    s1_path = f"/sessions/{sess1_id}"
    s1_payload = {
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
    s1_bytes = json.dumps(s1_payload, separators=(",", ":")).encode("utf-8")
    tok_s1 = mint_device_token(privkey, device_id, "PUT", s1_path, body_bytes=s1_bytes)
    r_s1 = client.put(
        s1_path,
        content=s1_bytes,
        headers={
            "Authorization": f"Bearer {tok_s1}",
            "Idempotency-Key": str(uuid4()),
            "X-NeoGold-App-Version": "1.0.0",
            "Content-Type": "application/json",
        },
    )
    assert r_s1.status_code == 201

    # 3. Register session 2
    sess2_id = uuid4()
    s2_path = f"/sessions/{sess2_id}"
    s2_payload = dict(s1_payload)
    s2_payload["captures"] = [
        {
            "capture_id": str(uuid4()),
            "attempt_number": 1,
            "site": "FOREHEAD",
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
    ]
    s2_bytes = json.dumps(s2_payload, separators=(",", ":")).encode("utf-8")
    tok_s2 = mint_device_token(privkey, device_id, "PUT", s2_path, body_bytes=s2_bytes)
    r_s2 = client.put(
        s2_path,
        content=s2_bytes,
        headers={
            "Authorization": f"Bearer {tok_s2}",
            "Idempotency-Key": str(uuid4()),
            "X-NeoGold-App-Version": "1.0.0",
            "Content-Type": "application/json",
        },
    )
    assert r_s2.status_code == 201

    # 4. Upload TSB measurement initially with session_id=null
    tsb_id = uuid4()
    tsb_path = f"/tsb-measurements/{tsb_id}"
    tsb_payload_unlinked = {
        "participant_id": str(part_id),
        "session_id": None,
        "tsb_mg_dl": 14.5,
        "minutes_from_capture": None,
        "draw_time": None,
        "method": "LAB",
    }
    t_unlinked_bytes = json.dumps(tsb_payload_unlinked, separators=(",", ":")).encode("utf-8")
    tok_t1 = mint_device_token(privkey, device_id, "PUT", tsb_path, body_bytes=t_unlinked_bytes)
    r_t1 = client.put(
        tsb_path,
        content=t_unlinked_bytes,
        headers={
            "Authorization": f"Bearer {tok_t1}",
            "Idempotency-Key": str(uuid4()),
            "X-NeoGold-App-Version": "1.0.0",
            "Content-Type": "application/json",
        },
    )
    assert r_t1.status_code == 201
    assert r_t1.json()["linked"] is False

    # 5. Link TSB measurement to session 1 -> 200 OK, linked=True
    tsb_payload_linked1 = {
        "participant_id": str(part_id),
        "session_id": str(sess1_id),
        "tsb_mg_dl": 14.5,
        "minutes_from_capture": 15,
        "draw_time": None,
        "method": "LAB",
    }
    t_linked1_bytes = json.dumps(tsb_payload_linked1, separators=(",", ":")).encode("utf-8")
    tok_t2 = mint_device_token(privkey, device_id, "PUT", tsb_path, body_bytes=t_linked1_bytes)
    r_t2 = client.put(
        tsb_path,
        content=t_linked1_bytes,
        headers={
            "Authorization": f"Bearer {tok_t2}",
            "Idempotency-Key": str(uuid4()),
            "X-NeoGold-App-Version": "1.0.0",
            "Content-Type": "application/json",
        },
    )
    assert r_t2.status_code == 200
    assert r_t2.json()["linked"] is True

    # 6. Re-sending identical linked body -> 200 OK (idempotent replay)
    tok_t2_replay = mint_device_token(privkey, device_id, "PUT", tsb_path, body_bytes=t_linked1_bytes)
    r_t2_replay = client.put(
        tsb_path,
        content=t_linked1_bytes,
        headers={
            "Authorization": f"Bearer {tok_t2_replay}",
            "Idempotency-Key": str(uuid4()),
            "X-NeoGold-App-Version": "1.0.0",
            "Content-Type": "application/json",
        },
    )
    assert r_t2_replay.status_code == 200
    assert r_t2_replay.json()["linked"] is True

    # 7. Attempting to change to session 2 -> 409 CONFLICT_IMMUTABLE
    tsb_payload_linked2 = {
        "participant_id": str(part_id),
        "session_id": str(sess2_id),
        "tsb_mg_dl": 14.5,
        "minutes_from_capture": 25,
        "draw_time": None,
        "method": "LAB",
    }
    t_linked2_bytes = json.dumps(tsb_payload_linked2, separators=(",", ":")).encode("utf-8")
    tok_t3 = mint_device_token(privkey, device_id, "PUT", tsb_path, body_bytes=t_linked2_bytes)
    r_t3 = client.put(
        tsb_path,
        content=t_linked2_bytes,
        headers={
            "Authorization": f"Bearer {tok_t3}",
            "Idempotency-Key": str(uuid4()),
            "X-NeoGold-App-Version": "1.0.0",
            "Content-Type": "application/json",
        },
    )
    assert r_t3.status_code == 409
    assert r_t3.json()["code"] == "CONFLICT_IMMUTABLE"
