"""Failing regression tests produced by the P27 Breaker agent (Round 1).

Each test cites the TC, T, or H ID it protects and corresponds to a filed
finding in `breaker/findings/BRK-20261031-nn.md`.
"""

from __future__ import annotations

import base64
import datetime
import hashlib
import io
import json
import sys
from pathlib import Path
from unittest.mock import patch
from uuid import UUID, uuid4

from PIL import Image

REPO_ROOT = Path(__file__).resolve().parents[2]
BACKEND_DIR = REPO_ROOT / "backend"
if str(REPO_ROOT) not in sys.path:
    sys.path.insert(0, str(REPO_ROOT))
if str(BACKEND_DIR) not in sys.path:
    sys.path.insert(0, str(BACKEND_DIR))

from breaker.env.disposable_app import disposable_breaker_env  # noqa: E402
from tests.conftest import create_enrollment_code, mint_device_key, mint_device_token  # noqa: E402


def _make_clean_jpeg_bytes() -> bytes:
    """Create a synthetic JFIF JPEG with no thumbnail or Exif PII."""
    buf = io.BytesIO()
    img = Image.new("RGB", (32, 32), color=(215, 178, 112))
    img.save(buf, format="JPEG", quality=90)
    return buf.getvalue()


def _seed_study_site_and_device(env) -> tuple[UUID, UUID, UUID, object]:
    """Seed synthetic study SKP-BRK, test site SKP01, and an enrolled device."""
    from app.models.db import Site, Study

    db = env.session_factory()
    try:
        study = Study(
            id=uuid4(),
            study_code="SKP",
            name="NeoGold Breaker Synthetic Study",
            partner_institution="ICDDR,B (Synthetic)",
            ethics_reference="ETH-BRK-001",
            timestamp_precision="DAY_PLUS_OFFSETS",
            retention_until=datetime.date(2029, 12, 31),
            time_zone="Asia/Dhaka",
            min_app_version="1.0.0",
            wifi_only=True,
            status="ACTIVE",
            data_generation=1,
        )
        db.add(study)
        db.flush()

        site = Site(
            id=uuid4(),
            study_id=study.id,
            site_code="SKP01",
            name="Breaker Synthetic Site 01",
            is_test=True,
        )
        db.add(site)
        db.commit()

        _code_row, code_str = create_enrollment_code(
            db, study.id, site.id, code_str=f"K7QM-3XRA-{uuid4().hex[:4].upper()}"
        )
        privkey, jwk = mint_device_key()
        resp = env.client.post(
            "/enrollments",
            json={
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
            },
            headers={
                "Idempotency-Key": str(uuid4()),
                "X-NeoGold-App-Version": "1.0.0",
            },
        )
        assert resp.status_code == 201, resp.text
        device_id = UUID(resp.json()["device_id"])
        return study.id, site.id, device_id, privkey
    finally:
        db.close()


def _register_participant(env, device_id: UUID, privkey, code: str = "SKP-0001") -> UUID:
    participant_id = uuid4()
    path = f"/participants/{participant_id}"
    payload = {
        "participant_code": code,
        "consent_form_id": f"CF-BRK-{code}",
        "consent_date": "2026-10-30",
    }
    body_bytes = json.dumps(payload, separators=(",", ":")).encode("utf-8")
    token = mint_device_token(privkey, device_id, "PUT", path, body_bytes)
    resp = env.client.put(
        path,
        content=body_bytes,
        headers={
            "Authorization": f"Bearer {token}",
            "Content-Type": "application/json",
            "Idempotency-Key": str(uuid4()),
            "X-NeoGold-App-Version": "1.0.0",
        },
    )
    assert resp.status_code in (200, 201), resp.text
    return participant_id


def _build_capture_dict(capture_id: UUID, *, has_image: bool, jpeg_bytes: bytes | None = None) -> dict:
    return {
        "capture_id": str(capture_id),
        "attempt_number": 1,
        "site": "STERNUM",
        "quality_status": "PASSED",
        "quality_fail_reasons": [],
        "correction_residual_de": 1.2,
        "has_image": has_image,
        "image_sha256": hashlib.sha256(jpeg_bytes).hexdigest() if (has_image and jpeg_bytes) else None,
        "size_bytes": len(jpeg_bytes) if (has_image and jpeg_bytes) else None,
        "capture_metadata": {
            "iso": 100,
            "exposure_ns": 10000000,
            "ae_locked": True,
            "awb_locked": True,
            "camera_hardware_level": "LIMITED",
            "color_gains": [1.5, 1.0, 1.0, 1.8],
        },
        "features": {
            "skin_l": 65.2,
            "skin_a": 9.1,
            "skin_b": 24.8,
        },
    }


def test_brk_022_participant_withdrawn_mid_upload_rejects_capture_image():
    """Finding BRK-20261031-01 (BRK-022): Protects T15, TC-API-32, FR-24.

    When a participant withdraws consent after the session manifest is accepted
    (`RECEIVING`) but before the phone uploads the remaining capture image(s),
    `PUT /sessions/{session_id}/captures/{capture_id}/image` must reject the upload
    with `422 PARTICIPANT_WITHDRAWN` and must NOT write the JPEG to disk or crash
    with a 500 IntegrityError.
    """
    from app.cli import handle_participant_withdraw

    with disposable_breaker_env() as env:
        _study_id, _site_id, device_id, privkey = _seed_study_site_and_device(env)
        participant_id = _register_participant(env, device_id, privkey, code="SKP-0001")

        session_id = uuid4()
        capture_id = uuid4()
        jpeg_bytes = _make_clean_jpeg_bytes()

        manifest_path = f"/sessions/{session_id}"
        manifest_payload = {
            "participant_id": str(participant_id),
            "capture_date": "2026-10-30",
            "age_hours": 48.0,
            "gestational_age_weeks": 38,
            "flag_jaundice_under_24h": False,
            "flag_danger_signs": False,
            "flag_prior_phototherapy": False,
            "card_status": "UNVERIFIED",
            "card_batch_id": None,
            "estimate_mg_dl": 11.4,
            "estimate_low_mg_dl": 9.8,
            "estimate_high_mg_dl": 13.0,
            "app_version": "1.0.0",
            "model_version": "1.0.0-v1",
            "config_version": "1.0.0-20260301",
            "captures": [_build_capture_dict(capture_id, has_image=True, jpeg_bytes=jpeg_bytes)],
        }
        manifest_bytes = json.dumps(manifest_payload, separators=(",", ":")).encode("utf-8")
        manifest_token = mint_device_token(privkey, device_id, "PUT", manifest_path, manifest_bytes)
        resp_manifest = env.client.put(
            manifest_path,
            content=manifest_bytes,
            headers={
                "Authorization": f"Bearer {manifest_token}",
                "Content-Type": "application/json",
                "Idempotency-Key": str(uuid4()),
                "X-NeoGold-App-Version": "1.0.0",
            },
        )
        assert resp_manifest.status_code in (201, 202), resp_manifest.text

        # Coordinator withdraws participant mid-upload
        import argparse
        rc = handle_participant_withdraw(argparse.Namespace(participant_code="SKP-0001"))
        assert rc == 0

        # Phone attempts to upload the remaining capture JPEG for the withdrawn participant
        img_path = f"/sessions/{session_id}/captures/{capture_id}/image"
        digest_b64 = base64.b64encode(hashlib.sha256(jpeg_bytes).digest()).decode("ascii")
        img_token = mint_device_token(privkey, device_id, "PUT", img_path, jpeg_bytes)
        resp_img = env.client.put(
            img_path,
            content=jpeg_bytes,
            headers={
                "Authorization": f"Bearer {img_token}",
                "Content-Type": "image/jpeg",
                "Content-Digest": f"sha-256=:{digest_b64}:",
                "Idempotency-Key": str(uuid4()),
                "X-NeoGold-App-Version": "1.0.0",
            },
        )

        stored_jpegs = list(env.image_store_dir.rglob("*.jpg"))
        assert resp_img.status_code == 422, (
            f"Expected 422 PARTICIPANT_WITHDRAWN, got {resp_img.status_code}: {resp_img.text}"
        )
        assert resp_img.json().get("code") == "PARTICIPANT_WITHDRAWN"
        assert stored_jpegs == [], (
            f"Withdrawn participant's JPEG was written to disk: {stored_jpegs}"
        )


def test_brk_021_manifest_with_all_has_image_false_completes_immediately():
    """Finding BRK-20261031-02 (BRK-021): Protects TC-API-17, NG-042, FR-20.

    Per docs/7_API_Documentation.md Section 5.1 ('A capture with has_image false
    needs no upload, so the session can still complete'), a session manifest whose
    captures all have `has_image=False` has zero missing images and must immediately
    transition to `status == 'COMPLETE'` with `completed_on` set.
    """
    with disposable_breaker_env() as env:
        _study_id, _site_id, device_id, privkey = _seed_study_site_and_device(env)
        participant_id = _register_participant(env, device_id, privkey, code="SKP-0002")

        session_id = uuid4()
        capture_id = uuid4()
        manifest_path = f"/sessions/{session_id}"
        manifest_payload = {
            "participant_id": str(participant_id),
            "capture_date": "2026-10-30",
            "age_hours": 36.0,
            "gestational_age_weeks": 39,
            "flag_jaundice_under_24h": False,
            "flag_danger_signs": False,
            "flag_prior_phototherapy": False,
            "card_status": "UNVERIFIED",
            "card_batch_id": None,
            "estimate_mg_dl": 9.2,
            "estimate_low_mg_dl": 7.8,
            "estimate_high_mg_dl": 10.6,
            "app_version": "1.0.0",
            "model_version": "1.0.0-v1",
            "config_version": "1.0.0-20260301",
            "captures": [_build_capture_dict(capture_id, has_image=False)],
        }
        manifest_bytes = json.dumps(manifest_payload, separators=(",", ":")).encode("utf-8")
        manifest_token = mint_device_token(privkey, device_id, "PUT", manifest_path, manifest_bytes)
        resp = env.client.put(
            manifest_path,
            content=manifest_bytes,
            headers={
                "Authorization": f"Bearer {manifest_token}",
                "Content-Type": "application/json",
                "Idempotency-Key": str(uuid4()),
                "X-NeoGold-App-Version": "1.0.0",
            },
        )
        assert resp.status_code in (200, 201, 202), resp.text
        body = resp.json()
        assert body["missing_images"] == []
        assert body["status"] == "COMPLETE", (
            f"Session with 0 missing images stayed in status={body['status']!r}, expected 'COMPLETE'"
        )
        assert body["completed_on"] is not None


def test_brk_023_restore_mark_strictly_increases_data_generation():
    """Finding BRK-20261031-03 (BRK-023): Protects TC-SYNC-14, NFR-09.

    Per docs/7_API_Documentation.md Section 5.4, `neogold-admin restore mark` must
    ensure `data_generation` strictly increases even after a second restore within
    the same second or when `study.data_generation >= int(time.time())`.
    """
    from app.cli import handle_restore_mark
    from app.models.db import Study

    with disposable_breaker_env() as env:
        study_id, _site_id, _device_id, _privkey = _seed_study_site_and_device(env)
        db = env.session_factory()
        try:
            fixed_unix_ts = 1_761_955_200
            with patch("app.cli.time.time", return_value=float(fixed_unix_ts)):
                assert handle_restore_mark(db) == 0
                db.expire_all()
                gen_first = int(db.get(Study, study_id).data_generation)

                # Second restore mark within the same second (or slightly behind clock)
                assert handle_restore_mark(db) == 0
                db.expire_all()
                gen_second = int(db.get(Study, study_id).data_generation)

            assert gen_second > gen_first, (
                f"data_generation did not increase on second restore: first={gen_first}, second={gen_second}"
            )
        finally:
            db.close()
