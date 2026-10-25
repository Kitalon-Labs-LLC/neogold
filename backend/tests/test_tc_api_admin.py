from __future__ import annotations

import csv
import datetime
import hashlib
import json
from pathlib import Path
from uuid import uuid4

from fastapi.testclient import TestClient
from sqlalchemy import select

from app.cli import main
from app.config import settings
from app.models.db import AuditLog, Capture, Participant, Site, Study, TsbMeasurement
from app.models.db import Session as DbSession
from tests.conftest import mint_device_token
from tests.test_tc_api_images import make_clean_jpeg, make_content_digest


def test_tc_api_28_admin_commands_audited(client: TestClient, db, test_study_and_site, tmp_path):
    """TC-API-28:
    Run every neogold-admin command:
    One ADMIN audit row each; session reject removes images.
    """
    study, site = test_study_and_site

    # 1. study create
    code_s2 = f"ST{uuid4().hex[:4].upper()}"
    ret = main([
        "study", "create",
        "--code", code_s2,
        "--name", "Study Two",
        "--partner", "Partner Institute",
        "--ethics-ref", "ETH-999",
        "--retention-until", "2030-01-01",
    ])
    assert ret == 0

    # 2. study pause
    ret = main(["study", "pause", "--code", code_s2])
    assert ret == 0

    # 3. study resume
    ret = main(["study", "resume", "--code", code_s2])
    assert ret == 0

    # 4. study set-min-app-version
    ret = main(["study", "set-min-app-version", "1.0.1", "--code", code_s2])
    assert ret == 0

    # 5. site add (and site add --test)
    site_code = f"SITE{uuid4().hex[:3].upper()}"
    ret = main(["site", "add", "--study", code_s2, "--code", site_code, "--name", "Clinic A"])
    assert ret == 0

    test_site_code = f"TEST{uuid4().hex[:3].upper()}"
    ret = main(["site", "add", "--study", code_s2, "--code", test_site_code, "--name", "Smoke Test Site", "--test"])
    assert ret == 0

    # 6. code issue
    ret = main(["code", "issue", "--site", site_code, "--count", "2"])
    assert ret == 0

    # 7. device list
    ret = main(["device", "list", "--site", site_code])
    assert ret == 0

    # 8. status
    ret = main(["status"])
    assert ret == 0

    # 9. restore mark
    ret = main(["restore", "mark"])
    assert ret == 0

    # 10. selftest
    ret = main(["selftest"])
    assert ret == 0

    # 11. audit since
    ret = main(["audit", "since", "2026-01-01T00:00:00Z"])
    assert ret == 0

    # 12. decrypt-export (offline tool stub)
    dummy_file = tmp_path / "dummy.zip"
    dummy_file.write_bytes(b"dummy")
    ret = main(["decrypt-export", "--key", "dummykey", str(dummy_file)])
    assert ret == 0

    # 13. retention check
    ret = main(["retention", "check", "--study", code_s2])
    assert ret == 0

    # 14. retention purge (no-op since retention_until is 2030)
    ret = main(["retention", "purge", "--study", code_s2, "--confirm"])
    assert ret == 0

    # 15. session reject removes images
    # Create a participant, session, and image on disk
    part = Participant(
        id=uuid4(),
        site_id=site.id,
        participant_code=f"SKP-{uuid4().hex[:4].upper()}",
        consent_form_id="CF-99",
        consent_date=datetime.date(2026, 10, 20),
        created_on=datetime.date(2026, 10, 20),
    )
    db.add(part)
    from app.models.db import Device
    dev = Device(
        id=uuid4(),
        site_id=site.id,
        public_key={"kty": "EC"},
        key_thumbprint=uuid4().hex,
        key_algorithm="ES256",
        manufacturer="TECNO",
        device_model="KL8",
        android_api=34,
        camera_hardware_level="LIMITED",
        qualification="QUALIFIED",
        app_version="1.0.0",
    )
    db.add(dev)
    db.flush()

    sess_id = uuid4()
    sess = DbSession(
        id=sess_id,
        participant_id=part.id,
        device_id=dev.id,
        card_status="UNVERIFIED",
        capture_date=datetime.date(2026, 10, 20),
        age_hours=24.0,
        gestational_age_weeks=39,
        flag_jaundice_under_24h=False,
        flag_danger_signs=False,
        flag_prior_phototherapy=False,
        app_version="1.0.0",
        config_version="1.0.0",
        manifest_sha256="test",
        status="COMPLETE",
    )
    db.add(sess)
    db.flush()

    cap_id = uuid4()
    obj_key = f"SKP/SKP01/{sess_id}/{cap_id}.jpg"
    full_img_path = Path(settings.IMAGE_STORE_DIR) / obj_key
    full_img_path.parent.mkdir(parents=True, exist_ok=True)
    full_img_path.write_bytes(b"image bytes")

    cap = Capture(
        id=cap_id,
        session_id=sess_id,
        attempt_number=1,
        site="STERNUM",
        capture_metadata={},
        quality_status="PASSED",
        has_image=True,
        object_key=obj_key,
        sha256="fake",
        size_bytes=11,
        verified=True,
    )
    db.add(cap)
    db.commit()

    assert full_img_path.exists()

    ret = main(["session", "reject", str(sess_id), "--reason", "DATA_ERROR"])
    assert ret == 0
    assert not full_img_path.exists(), "session reject did not remove images from disk!"

    # Verify audit rows: every command recorded an ADMIN audit row
    actions = db.execute(select(AuditLog.action).where(AuditLog.actor_type == "ADMIN")).scalars().all()
    expected_actions = {
        "STUDY_CREATED",
        "STUDY_PAUSED",
        "STUDY_RESUMED",
        "MIN_APP_VERSION_SET",
        "SITE_ADDED",
        "ENROLLMENT_CODES_ISSUED",
        "DEVICE_LIST_VIEWED",
        "STATUS_VIEWED",
        "RESTORE_MARKED",
        "SELFTEST_RUN",
        "AUDIT_VIEWED",
        "DECRYPT_EXPORT",
        "RETENTION_CHECKED",
        "RETENTION_PURGED",
        "SESSION_REJECTED",
    }
    for exp in expected_actions:
        assert exp in actions, f"Missing audit action {exp} from admin commands"


def test_tc_api_29_server_export(client: TestClient, db, test_study_and_site, tmp_path):
    """TC-API-29:
    neogold-admin export on a study with a --test site and a withdrawn participant.
    De-identified; date shift per participant; SHA-256 manifest verifies;
    nothing from the test site or the withdrawn participant.
    """
    study, normal_site = test_study_and_site
    export_study_code = f"EXP{uuid4().hex[:3].upper()}"

    # Create study
    exp_study = Study(
        id=uuid4(),
        study_code=export_study_code,
        name="Export Test Study",
        partner_institution="Partner",
        ethics_reference="ETH-EXP",
        timestamp_precision="DAY_PLUS_OFFSETS",
        retention_until=datetime.date(2030, 1, 1),
        time_zone="Asia/Dhaka",
        min_app_version="1.0.0",
        wifi_only=True,
        status="ACTIVE",
    )
    db.add(exp_study)
    db.flush()

    # Site 1: Normal site
    site1 = Site(
        id=uuid4(),
        study_id=exp_study.id,
        site_code="EXP01",
        name="Normal Export Site",
        is_test=False,
    )
    db.add(site1)

    # Site 2: Test site (--test)
    site_test = Site(
        id=uuid4(),
        study_id=exp_study.id,
        site_code="ZZTEST",
        name="Smoke Test Site",
        is_test=True,
    )
    db.add(site_test)
    db.flush()

    from app.models.db import Device
    dev1 = Device(
        id=uuid4(),
        site_id=site1.id,
        public_key={"kty": "EC"},
        key_thumbprint=uuid4().hex,
        key_algorithm="ES256",
        manufacturer="TECNO",
        device_model="KL8",
        android_api=34,
        camera_hardware_level="LIMITED",
        qualification="QUALIFIED",
        app_version="1.0.0",
    )
    db.add(dev1)

    dev_test = Device(
        id=uuid4(),
        site_id=site_test.id,
        public_key={"kty": "EC"},
        key_thumbprint=uuid4().hex,
        key_algorithm="ES256",
        manufacturer="TECNO",
        device_model="KL8",
        android_api=34,
        camera_hardware_level="LIMITED",
        qualification="QUALIFIED",
        app_version="1.0.0",
    )
    db.add(dev_test)
    db.flush()

    # Participant 1: Normal participant at Site 1
    p_normal = Participant(
        id=uuid4(),
        site_id=site1.id,
        participant_code="EXP-001",
        consent_form_id="CF-001",
        consent_date=datetime.date(2026, 10, 10),
        created_on=datetime.date(2026, 10, 10),
    )
    db.add(p_normal)

    # Participant 2: Withdrawn participant at Site 1
    p_withdrawn = Participant(
        id=uuid4(),
        site_id=site1.id,
        participant_code="EXP-002",
        consent_form_id="CF-002",
        consent_date=datetime.date(2026, 10, 11),
        created_on=datetime.date(2026, 10, 11),
        withdrawn_on=datetime.date(2026, 10, 12),
    )
    db.add(p_withdrawn)

    # Participant 3: Test site participant
    p_test = Participant(
        id=uuid4(),
        site_id=site_test.id,
        participant_code="EXP-TEST99",
        consent_form_id="CF-TEST99",
        consent_date=datetime.date(2026, 10, 10),
        created_on=datetime.date(2026, 10, 10),
    )
    db.add(p_test)
    db.flush()

    # Sessions and Images for normal participant
    s_norm = DbSession(
        id=uuid4(),
        participant_id=p_normal.id,
        device_id=dev1.id,
        card_status="UNVERIFIED",
        capture_date=datetime.date(2026, 10, 10),
        age_hours=20.0,
        gestational_age_weeks=39,
        flag_jaundice_under_24h=False,
        flag_danger_signs=False,
        flag_prior_phototherapy=False,
        estimate_mg_dl=10.0,
        estimate_low_mg_dl=8.0,
        estimate_high_mg_dl=12.0,
        app_version="1.0.0",
        config_version="1.0.0",
        manifest_sha256="manifest_hash",
        status="COMPLETE",
        received_on=datetime.date(2026, 10, 10),
        completed_on=datetime.date(2026, 10, 10),
    )
    db.add(s_norm)
    db.flush()

    img_clean = make_clean_jpeg(30, 30, (50, 60, 70))
    cap_norm_id = uuid4()
    obj_key = f"{export_study_code}/EXP01/{s_norm.id}/{cap_norm_id}.jpg"
    full_path = Path(settings.IMAGE_STORE_DIR) / obj_key
    full_path.parent.mkdir(parents=True, exist_ok=True)
    full_path.write_bytes(img_clean)

    cap_norm = Capture(
        id=cap_norm_id,
        session_id=s_norm.id,
        attempt_number=1,
        site="STERNUM",
        capture_metadata={"test": 1},
        quality_status="PASSED",
        has_image=True,
        object_key=obj_key,
        sha256=hashlib.sha256(img_clean).hexdigest(),
        size_bytes=len(img_clean),
        verified=True,
    )
    db.add(cap_norm)

    # TSB measurement for normal participant
    tsb_norm = TsbMeasurement(
        id=uuid4(),
        participant_id=p_normal.id,
        session_id=s_norm.id,
        tsb_mg_dl=11.5,
        minutes_from_capture=15,
        method="LAB",
        received_on=datetime.date(2026, 10, 10),
        linked_on=datetime.date(2026, 10, 10),
    )
    db.add(tsb_norm)

    db.commit()

    # Run export command
    out_dir = tmp_path / "export_output"
    ret = main(["export", "--study", export_study_code, "--out", str(out_dir)])
    assert ret == 0

    # 1. Verify manifest.sha256 exists and every entry verifies
    manifest_file = out_dir / "manifest.sha256"
    assert manifest_file.exists()
    lines = manifest_file.read_text(encoding="utf-8").strip().splitlines()
    assert len(lines) > 0
    for line in lines:
        expected_hash, rel_path = line.split("  ", 1)
        target_f = out_dir / rel_path
        assert target_f.exists(), f"File in manifest does not exist: {rel_path}"
        actual_hash = hashlib.sha256(target_f.read_bytes()).hexdigest()
        assert actual_hash == expected_hash, f"Hash mismatch for {rel_path}"

    # 2. Verify participants.csv excludes withdrawn participant and test site
    part_csv = out_dir / "participants.csv"
    with open(part_csv, encoding="utf-8") as f:
        reader = list(csv.DictReader(f))
        assert len(reader) == 1
        row = reader[0]
        assert row["site_code"] == "EXP01"
        assert row["site_code"] != "ZZTEST"
        # Date must be shifted from 2026-10-10
        assert row["consent_date"] != "2026-10-10", "Date shift was not applied to consent_date"

    # 3. Verify sessions.csv date shifting
    sess_csv = out_dir / "sessions.csv"
    with open(sess_csv, encoding="utf-8") as f:
        reader = list(csv.DictReader(f))
        assert len(reader) == 1
        srow = reader[0]
        assert srow["capture_date"] != "2026-10-10", "Date shift was not applied to capture_date"

    # 4. Verify images/ contains the exported image
    img_exported = out_dir / "images" / f"{cap_norm_id}.jpg"
    assert img_exported.exists()
    assert img_exported.read_bytes() == img_clean


def test_tc_api_32_participant_withdrawal(client: TestClient, db, enrolled_device, test_study_and_site):
    """TC-API-32:
    neogold-admin participant withdraw <code_or_id>
    - sessions rejected, images and TSB values deleted, withdrawn_on set
    - subsequent uploads for that participant return 422 PARTICIPANT_WITHDRAWN
    - admin audit row written without participant code.
    """
    device_id, privkey, _jwk = enrolled_device
    study, site = test_study_and_site
    today_str = datetime.datetime.now(datetime.UTC).date().isoformat()

    part_id = uuid4()
    p_code = "SKP-3201"
    part_path = f"/participants/{part_id}"
    part_payload = {
        "participant_code": p_code,
        "consent_form_id": "CF-3201",
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
    img_bytes = make_clean_jpeg(20, 20, (11, 22, 33))
    img_sha, img_hdr, img_bsh = make_content_digest(img_bytes)

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

    # Add TSB measurement
    tsb_id = uuid4()
    tsb_path = f"/tsb-measurements/{tsb_id}"
    tsb_payload = {
        "participant_id": str(part_id),
        "session_id": str(sess_id),
        "tsb_mg_dl": 12.0,
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

    # Confirm capture image exists on disk
    cap_row = db.execute(select(Capture).where(Capture.id == cap_id)).scalar_one()
    img_disk_path = Path(settings.IMAGE_STORE_DIR) / cap_row.object_key
    assert img_disk_path.exists()

    # Now withdraw participant using neogold-admin CLI!
    ret = main(["participant", "withdraw", p_code])
    assert ret == 0

    # 1. Sessions marked REJECTED with CONSENT_WITHDRAWN
    s_row = db.execute(select(DbSession).where(DbSession.id == sess_id)).scalar_one()
    assert s_row.status == "REJECTED"
    assert s_row.rejected_reason == "CONSENT_WITHDRAWN"

    # 2. Images on disk deleted
    assert not img_disk_path.exists(), "Image was not deleted after participant withdrawal"

    # 3. TSB measurements deleted
    tsb_cnt = db.execute(select(TsbMeasurement).where(TsbMeasurement.participant_id == part_id)).scalars().all()
    assert len(tsb_cnt) == 0

    # 4. Withdrawn_on set on participant
    p_row = db.execute(select(Participant).where(Participant.id == part_id)).scalar_one()
    assert p_row.withdrawn_on is not None

    # 5. Subsequent upload attempt for that participant returns 422 PARTICIPANT_WITHDRAWN
    sess2_id = uuid4()
    m2_path = f"/sessions/{sess2_id}"
    m2_payload = dict(m_payload)
    m2_bytes = json.dumps(m2_payload, separators=(",", ":")).encode("utf-8")
    tok_m2 = mint_device_token(privkey, device_id, "PUT", m2_path, body_bytes=m2_bytes)
    r_m2 = client.put(
        m2_path,
        content=m2_bytes,
        headers={
            "Authorization": f"Bearer {tok_m2}",
            "Idempotency-Key": str(uuid4()),
            "X-NeoGold-App-Version": "1.0.0",
            "Content-Type": "application/json",
        },
    )
    assert r_m2.status_code == 422
    assert r_m2.json()["code"] == "PARTICIPANT_WITHDRAWN"

    # 6. Admin audit row written without participant code
    withdraw_audit = db.execute(
        select(AuditLog).where(
            AuditLog.actor_type == "ADMIN",
            AuditLog.action == "PARTICIPANT_WITHDRAWN",
            AuditLog.target_id == str(part_id),
        )
    ).scalar_one()
    assert withdraw_audit is not None
    assert p_code not in json.dumps(withdraw_audit.details)

