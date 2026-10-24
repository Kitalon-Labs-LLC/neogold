from __future__ import annotations

import base64
import concurrent.futures
import datetime
import hashlib
import io
import json
import random
import struct
from uuid import UUID, uuid4

from fastapi.testclient import TestClient
from PIL import Image
from sqlalchemy import select

from app.jobs import complete_backstop_sessions
from app.middleware.rate_limit import device_limiter
from app.models.db import IdempotencyRecord
from app.models.db import Session as DbSession
from tests.conftest import mint_device_token


def make_clean_jpeg(width: int = 100, height: int = 100, color=(255, 0, 0)) -> bytes:
    img = Image.new("RGB", (width, height), color=color)
    buf = io.BytesIO()
    img.save(buf, format="JPEG")
    return buf.getvalue()


def make_content_digest(img_bytes: bytes) -> tuple[str, str, str]:
    """Returns (sha256_hex, content_digest_header, bsh_base64url)."""
    raw_sha = hashlib.sha256(img_bytes).digest()
    sha_hex = raw_sha.hex()
    b64 = base64.b64encode(raw_sha).decode("ascii")
    header = f"sha-256=:{b64}:"
    bsh = base64.urlsafe_b64encode(raw_sha).decode("ascii").rstrip("=")
    return sha_hex, header, bsh


def make_exif_seg(entries: list[tuple[int, int, int, int]], next_ifd: int = 0) -> bytes:
    num_entries = len(entries)
    tiff = bytearray(b"II\x2a\x00\x08\x00\x00\x00")
    tiff += struct.pack("<H", num_entries)
    for tag, typ, count, val in entries:
        tiff += struct.pack("<HHI", tag, typ, count)
        tiff += struct.pack("<I", val)
    tiff += struct.pack("<I", next_ifd)
    payload = b"Exif\x00\x00" + bytes(tiff)
    return b"\xff\xe1" + struct.pack(">H", len(payload) + 2) + payload


def upload_participant(client: TestClient, privkey, device_id: UUID, participant_id: UUID, code: str = "SKP-101") -> None:
    today_str = datetime.datetime.now(datetime.UTC).date().isoformat()
    part_path = f"/participants/{participant_id}"
    unique_code = f"SKP-{random.randint(100000, 999999)}"
    part_payload = {
        "participant_code": unique_code,
        "consent_form_id": f"CF-{unique_code}",
        "consent_date": today_str,
    }
    part_bytes = json.dumps(part_payload, separators=(",", ":")).encode("utf-8")
    part_tok = mint_device_token(privkey, device_id, "PUT", part_path, body_bytes=part_bytes)
    r = client.put(
        part_path,
        content=part_bytes,
        headers={
            "Authorization": f"Bearer {part_tok}",
            "Idempotency-Key": str(uuid4()),
            "X-NeoGold-App-Version": "1.0.0",
            "Content-Type": "application/json",
        },
    )
    assert r.status_code == 201


def test_tc_api_17_manifest_first_and_lost_images(client: TestClient, db, enrolled_device, test_study_and_site):
    """TC-API-17:
    - Image for an unlisted capture -> 404 CAPTURE_NOT_IN_MANIFEST.
    - Manifest with has_image=false for one capture and has_image=true for another.
    - Session completes without the lost image once the listed image is uploaded.
    """
    device_id, privkey, _jwk = enrolled_device
    today_str = datetime.datetime.now(datetime.UTC).date().isoformat()

    part_id = uuid4()
    upload_participant(client, privkey, device_id, part_id, "SKP-1701")

    session_id = uuid4()
    cap1_id = uuid4()  # has_image = True
    cap2_id = uuid4()  # has_image = False (lost image)
    unlisted_cap_id = uuid4()

    img1_bytes = make_clean_jpeg(50, 50, (10, 20, 30))
    img1_sha_hex, img1_hdr, img1_bsh = make_content_digest(img1_bytes)

    manifest_path = f"/sessions/{session_id}"
    manifest_payload = {
        "participant_id": str(part_id),
        "card_status": "UNVERIFIED",
        "card_batch_id": None,
        "capture_date": today_str,
        "captured_at": None,
        "age_hours": 24.0,
        "gestational_age_weeks": 38,
        "flag_jaundice_under_24h": False,
        "flag_danger_signs": False,
        "flag_prior_phototherapy": False,
        "estimate_mg_dl": 10.5,
        "estimate_low_mg_dl": 8.0,
        "estimate_high_mg_dl": 13.0,
        "app_version": "1.0.0",
        "model_version": "1.0.0",
        "config_version": "1.0.0",
        "captures": [
            {
                "capture_id": str(cap1_id),
                "attempt_number": 1,
                "site": "STERNUM",
                "quality_status": "PASSED",
                "has_image": True,
                "image_sha256": img1_sha_hex,
                "size_bytes": len(img1_bytes),
                "capture_metadata": {
                    "ae_locked": True,
                    "awb_locked": True,
                    "camera_hardware_level": "LIMITED",
                },
            },
            {
                "capture_id": str(cap2_id),
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
            },
        ],
    }
    manifest_bytes = json.dumps(manifest_payload, separators=(",", ":")).encode("utf-8")
    m_tok = mint_device_token(privkey, device_id, "PUT", manifest_path, body_bytes=manifest_bytes)
    r_m = client.put(
        manifest_path,
        content=manifest_bytes,
        headers={
            "Authorization": f"Bearer {m_tok}",
            "Idempotency-Key": str(uuid4()),
            "X-NeoGold-App-Version": "1.0.0",
            "Content-Type": "application/json",
        },
    )
    assert r_m.status_code == 201
    assert r_m.json()["status"] == "RECEIVING"
    assert r_m.json()["missing_images"] == [str(cap1_id)]

    # 1. Attempt image upload for an unlisted capture ID -> 404 CAPTURE_NOT_IN_MANIFEST
    unlisted_path = f"/sessions/{session_id}/captures/{unlisted_cap_id}/image"
    u_tok = mint_device_token(privkey, device_id, "PUT", unlisted_path, body_bytes=img1_bytes, bsh=img1_bsh)
    r_u = client.put(
        unlisted_path,
        content=img1_bytes,
        headers={
            "Authorization": f"Bearer {u_tok}",
            "Content-Digest": img1_hdr,
            "Content-Type": "image/jpeg",
            "Idempotency-Key": str(uuid4()),
            "X-NeoGold-App-Version": "1.0.0",
        },
    )
    assert r_u.status_code == 404
    assert r_u.json()["code"] == "CAPTURE_NOT_IN_MANIFEST"

    # 2. Attempt image upload for cap2 (which declared has_image=False) -> 404 CAPTURE_NOT_IN_MANIFEST
    cap2_path = f"/sessions/{session_id}/captures/{cap2_id}/image"
    c2_tok = mint_device_token(privkey, device_id, "PUT", cap2_path, body_bytes=img1_bytes, bsh=img1_bsh)
    r_c2 = client.put(
        cap2_path,
        content=img1_bytes,
        headers={
            "Authorization": f"Bearer {c2_tok}",
            "Content-Digest": img1_hdr,
            "Content-Type": "image/jpeg",
            "Idempotency-Key": str(uuid4()),
            "X-NeoGold-App-Version": "1.0.0",
        },
    )
    assert r_c2.status_code == 404
    assert r_c2.json()["code"] == "CAPTURE_NOT_IN_MANIFEST"

    # 3. Upload image for cap1 -> Session completes without the lost image!
    cap1_path = f"/sessions/{session_id}/captures/{cap1_id}/image"
    c1_tok = mint_device_token(privkey, device_id, "PUT", cap1_path, body_bytes=img1_bytes, bsh=img1_bsh)
    r_c1 = client.put(
        cap1_path,
        content=img1_bytes,
        headers={
            "Authorization": f"Bearer {c1_tok}",
            "Content-Digest": img1_hdr,
            "Content-Type": "image/jpeg",
            "Idempotency-Key": str(uuid4()),
            "X-NeoGold-App-Version": "1.0.0",
        },
    )
    assert r_c1.status_code == 201
    assert r_c1.json()["verified"] is True

    # 4. GET /sessions/{id} reports COMPLETE
    r_get = client.get(
        manifest_path,
        headers={
            "Authorization": f"Bearer {mint_device_token(privkey, device_id, 'GET', manifest_path, body_bytes=b'')}",
            "X-NeoGold-App-Version": "1.0.0",
        },
    )
    assert r_get.status_code == 200
    assert r_get.json()["status"] == "COMPLETE"
    assert r_get.json()["missing_images"] == []
    assert r_get.json()["completed_on"] == today_str


def test_tc_api_18_race_on_last_two_images(client: TestClient, db, enrolled_device, test_study_and_site):
    """TC-API-18:
    Upload the last two images concurrently, 100 iterations.
    Every session ends COMPLETE exactly once.
    """
    device_id, privkey, _jwk = enrolled_device
    today_str = datetime.datetime.now(datetime.UTC).date().isoformat()

    part_id = uuid4()
    upload_participant(client, privkey, device_id, part_id, "SKP-1801")

    img1_bytes = make_clean_jpeg(32, 32, (10, 20, 30))
    img1_sha, img1_hdr, img1_bsh = make_content_digest(img1_bytes)
    img2_bytes = make_clean_jpeg(32, 32, (40, 50, 60))
    img2_sha, img2_hdr, img2_bsh = make_content_digest(img2_bytes)

    iterations = 100

    def run_single_race(idx: int):
        device_limiter.reset()
        sess_id = uuid4()
        c1_id = uuid4()
        c2_id = uuid4()

        m_path = f"/sessions/{sess_id}"
        m_payload = {
            "participant_id": str(part_id),
            "card_status": "UNVERIFIED",
            "card_batch_id": None,
            "capture_date": today_str,
            "captured_at": None,
            "age_hours": 30.0,
            "gestational_age_weeks": 39,
            "flag_jaundice_under_24h": False,
            "flag_danger_signs": False,
            "flag_prior_phototherapy": False,
            "estimate_mg_dl": 11.0,
            "estimate_low_mg_dl": 9.0,
            "estimate_high_mg_dl": 13.0,
            "app_version": "1.0.0",
            "model_version": "1.0.0",
            "config_version": "1.0.0",
            "captures": [
                {
                    "capture_id": str(c1_id),
                    "attempt_number": 1,
                    "site": "STERNUM",
                    "quality_status": "PASSED",
                    "has_image": True,
                    "image_sha256": img1_sha,
                    "size_bytes": len(img1_bytes),
                    "capture_metadata": {
                        "ae_locked": True,
                        "awb_locked": True,
                        "camera_hardware_level": "LIMITED",
                    },
                },
                {
                    "capture_id": str(c2_id),
                    "attempt_number": 1,
                    "site": "FOREHEAD",
                    "quality_status": "PASSED",
                    "has_image": True,
                    "image_sha256": img2_sha,
                    "size_bytes": len(img2_bytes),
                    "capture_metadata": {
                        "ae_locked": True,
                        "awb_locked": True,
                        "camera_hardware_level": "LIMITED",
                    },
                },
            ],
        }
        m_bytes = json.dumps(m_payload, separators=(",", ":")).encode("utf-8")
        m_tok = mint_device_token(privkey, device_id, "PUT", m_path, body_bytes=m_bytes)
        r_m = client.put(
            m_path,
            content=m_bytes,
            headers={
                "Authorization": f"Bearer {m_tok}",
                "Idempotency-Key": str(uuid4()),
                "X-NeoGold-App-Version": "1.0.0",
                "Content-Type": "application/json",
            },
        )
        assert r_m.status_code == 201, f"Manifest creation failed: {r_m.text}"

        c1_path = f"/sessions/{sess_id}/captures/{c1_id}/image"
        c2_path = f"/sessions/{sess_id}/captures/{c2_id}/image"

        tok1 = mint_device_token(privkey, device_id, "PUT", c1_path, body_bytes=img1_bytes, bsh=img1_bsh)
        tok2 = mint_device_token(privkey, device_id, "PUT", c2_path, body_bytes=img2_bytes, bsh=img2_bsh)

        def upload_img1():
            return client.put(
                c1_path,
                content=img1_bytes,
                headers={
                    "Authorization": f"Bearer {tok1}",
                    "Content-Digest": img1_hdr,
                    "Content-Type": "image/jpeg",
                    "Idempotency-Key": str(uuid4()),
                    "X-NeoGold-App-Version": "1.0.0",
                },
            )

        def upload_img2():
            return client.put(
                c2_path,
                content=img2_bytes,
                headers={
                    "Authorization": f"Bearer {tok2}",
                    "Content-Digest": img2_hdr,
                    "Content-Type": "image/jpeg",
                    "Idempotency-Key": str(uuid4()),
                    "X-NeoGold-App-Version": "1.0.0",
                },
            )

        with concurrent.futures.ThreadPoolExecutor(max_workers=2) as pool:
            f1 = pool.submit(upload_img1)
            f2 = pool.submit(upload_img2)
            r1 = f1.result()
            r2 = f2.result()

        assert r1.status_code in (200, 201), f"Image 1 upload failed: {r1.text}"
        assert r2.status_code in (200, 201), f"Image 2 upload failed: {r2.text}"

        # GET session status must report COMPLETE and missing_images empty
        g_tok = mint_device_token(privkey, device_id, "GET", m_path, body_bytes=b"")
        r_get = client.get(
            m_path,
            headers={
                "Authorization": f"Bearer {g_tok}",
                "X-NeoGold-App-Version": "1.0.0",
            },
        )
        assert r_get.status_code == 200
        assert r_get.json()["status"] == "COMPLETE"
        assert r_get.json()["missing_images"] == []
        assert r_get.json()["completed_on"] == today_str

    for i in range(iterations):
        run_single_race(i)


def test_tc_api_19_image_hash_mismatch_not_stored(client: TestClient, db, enrolled_device, test_study_and_site):
    """TC-API-19:
    Send wrong bytes, then the correct bytes with the same key.
    422 HASH_MISMATCH, then 201.
    """
    device_id, privkey, _jwk = enrolled_device
    today_str = datetime.datetime.now(datetime.UTC).date().isoformat()

    part_id = uuid4()
    upload_participant(client, privkey, device_id, part_id, "SKP-1901")

    session_id = uuid4()
    cap_id = uuid4()

    correct_bytes = make_clean_jpeg(40, 40, (120, 130, 140))
    correct_sha, correct_hdr, correct_bsh = make_content_digest(correct_bytes)

    manifest_path = f"/sessions/{session_id}"
    manifest_payload = {
        "participant_id": str(part_id),
        "card_status": "UNVERIFIED",
        "card_batch_id": None,
        "capture_date": today_str,
        "captured_at": None,
        "age_hours": 36.0,
        "gestational_age_weeks": 40,
        "flag_jaundice_under_24h": False,
        "flag_danger_signs": False,
        "flag_prior_phototherapy": False,
        "estimate_mg_dl": 9.5,
        "estimate_low_mg_dl": 7.5,
        "estimate_high_mg_dl": 11.5,
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
                "image_sha256": correct_sha,
                "size_bytes": len(correct_bytes),
                "capture_metadata": {
                    "ae_locked": True,
                    "awb_locked": True,
                    "camera_hardware_level": "LIMITED",
                },
            }
        ],
    }
    m_bytes = json.dumps(manifest_payload, separators=(",", ":")).encode("utf-8")
    m_tok = mint_device_token(privkey, device_id, "PUT", manifest_path, body_bytes=m_bytes)
    r_m = client.put(
        manifest_path,
        content=m_bytes,
        headers={
            "Authorization": f"Bearer {m_tok}",
            "Idempotency-Key": str(uuid4()),
            "X-NeoGold-App-Version": "1.0.0",
            "Content-Type": "application/json",
        },
    )
    assert r_m.status_code == 201

    cap_img_path = f"/sessions/{session_id}/captures/{cap_id}/image"
    same_idem_key = str(uuid4())

    # 1. Send wrong bytes (body hash != declared digest correct_sha)
    wrong_bytes = make_clean_jpeg(40, 40, (200, 210, 220))
    tok1 = mint_device_token(privkey, device_id, "PUT", cap_img_path, body_bytes=wrong_bytes, bsh=correct_bsh)
    r1 = client.put(
        cap_img_path,
        content=wrong_bytes,
        headers={
            "Authorization": f"Bearer {tok1}",
            "Content-Digest": correct_hdr,
            "Content-Type": "image/jpeg",
            "Idempotency-Key": same_idem_key,
            "X-NeoGold-App-Version": "1.0.0",
        },
    )
    assert r1.status_code == 422
    assert r1.json()["code"] == "HASH_MISMATCH"

    # Verify idempotency row was deleted (not saved as permanent error)
    idem_rec = db.execute(
        select(IdempotencyRecord).where(
            IdempotencyRecord.device_id == device_id,
            IdempotencyRecord.idempotency_key == UUID(same_idem_key),
        )
    ).scalar_one_or_none()
    assert idem_rec is None

    # 2. Retry with exact same Idempotency-Key and correct bytes -> 201 Created!
    tok2 = mint_device_token(privkey, device_id, "PUT", cap_img_path, body_bytes=correct_bytes, bsh=correct_bsh)
    r2 = client.put(
        cap_img_path,
        content=correct_bytes,
        headers={
            "Authorization": f"Bearer {tok2}",
            "Content-Digest": correct_hdr,
            "Content-Type": "image/jpeg",
            "Idempotency-Key": same_idem_key,
            "X-NeoGold-App-Version": "1.0.0",
        },
    )
    assert r2.status_code == 201
    assert r2.json()["verified"] is True


def test_tc_api_20_jpeg_metadata_allow_list(client: TestClient, db, enrolled_device, test_study_and_site):
    """TC-API-20:
    JPEGs with GPS, DateTimeOriginal, XMP, MPF, and an embedded thumbnail; a clean JPEG.
    Each rejected IMAGE_METADATA_NOT_ALLOWED; clean one accepted.
    """
    device_id, privkey, _jwk = enrolled_device
    today_str = datetime.datetime.now(datetime.UTC).date().isoformat()

    part_id = uuid4()
    upload_participant(client, privkey, device_id, part_id, "SKP-2001")

    clean_base = make_clean_jpeg(50, 50, (30, 40, 50))

    # Helper to test image against an upload route
    def upload_candidate(img_bytes: bytes) -> tuple[int, dict]:
        sess_id = uuid4()
        c_id = uuid4()
        sha, hdr, bsh = make_content_digest(img_bytes)

        m_path = f"/sessions/{sess_id}"
        m_payload = {
            "participant_id": str(part_id),
            "card_status": "UNVERIFIED",
            "card_batch_id": None,
            "capture_date": today_str,
            "captured_at": None,
            "age_hours": 20.0,
            "gestational_age_weeks": 39,
            "flag_jaundice_under_24h": False,
            "flag_danger_signs": False,
            "flag_prior_phototherapy": False,
            "app_version": "1.0.0",
            "model_version": "1.0.0",
            "config_version": "1.0.0",
            "captures": [
                {
                    "capture_id": str(c_id),
                    "attempt_number": 1,
                    "site": "STERNUM",
                    "quality_status": "PASSED",
                    "has_image": True,
                    "image_sha256": sha,
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

        c_path = f"/sessions/{sess_id}/captures/{c_id}/image"
        tok_c = mint_device_token(privkey, device_id, "PUT", c_path, body_bytes=img_bytes, bsh=bsh)
        r = client.put(
            c_path,
            content=img_bytes,
            headers={
                "Authorization": f"Bearer {tok_c}",
                "Content-Digest": hdr,
                "Content-Type": "image/jpeg",
                "Idempotency-Key": str(uuid4()),
                "X-NeoGold-App-Version": "1.0.0",
            },
        )
        return r.status_code, r.json()

    # 1. Clean JPEG -> Accepted
    st, resp = upload_candidate(clean_base)
    assert st == 201
    assert resp["verified"] is True

    # 2. Orientation-only EXIF -> Accepted
    orient_seg = make_exif_seg([(0x0112, 3, 1, 1)])
    orient_img = clean_base[:2] + orient_seg + clean_base[2:]
    st, resp = upload_candidate(orient_img)
    assert st == 201
    assert resp["verified"] is True

    # 3. GPS tag (0x8825) -> Rejected IMAGE_METADATA_NOT_ALLOWED
    gps_seg = make_exif_seg([(0x8825, 4, 1, 100)])
    gps_img = clean_base[:2] + gps_seg + clean_base[2:]
    st, resp = upload_candidate(gps_img)
    assert st == 422
    assert resp["code"] == "IMAGE_METADATA_NOT_ALLOWED"

    # 4. DateTime tag (0x0132) -> Rejected IMAGE_METADATA_NOT_ALLOWED
    dt_seg = make_exif_seg([(0x0132, 2, 20, 100)])
    dt_img = clean_base[:2] + dt_seg + clean_base[2:]
    st, resp = upload_candidate(dt_img)
    assert st == 422
    assert resp["code"] == "IMAGE_METADATA_NOT_ALLOWED"

    # 5. Embedded EXIF thumbnail (IFD1 offset != 0) -> Rejected IMAGE_METADATA_NOT_ALLOWED
    thumb_seg = make_exif_seg([(0x0112, 3, 1, 1)], next_ifd=100)
    thumb_img = clean_base[:2] + thumb_seg + clean_base[2:]
    st, resp = upload_candidate(thumb_img)
    assert st == 422
    assert resp["code"] == "IMAGE_METADATA_NOT_ALLOWED"

    # 6. XMP metadata -> Rejected IMAGE_METADATA_NOT_ALLOWED
    xmp_payload = b"http://ns.adobe.com/xap/1.0/\x00<x:xmpmeta/>"
    xmp_seg = b"\xff\xe1" + struct.pack(">H", len(xmp_payload) + 2) + xmp_payload
    xmp_img = clean_base[:2] + xmp_seg + clean_base[2:]
    st, resp = upload_candidate(xmp_img)
    assert st == 422
    assert resp["code"] == "IMAGE_METADATA_NOT_ALLOWED"

    # 7. MPF secondary images (APP2) -> Rejected IMAGE_METADATA_NOT_ALLOWED
    mpf_payload = b"MPF\x00" + b"\x00" * 20
    mpf_seg = b"\xff\xe2" + struct.pack(">H", len(mpf_payload) + 2) + mpf_payload
    mpf_img = clean_base[:2] + mpf_seg + clean_base[2:]
    st, resp = upload_candidate(mpf_img)
    assert st == 422
    assert resp["code"] == "IMAGE_METADATA_NOT_ALLOWED"

    # 8. JFIF with embedded thumbnail -> Rejected IMAGE_METADATA_NOT_ALLOWED
    jfif_thumb = bytearray(clean_base)
    idx = jfif_thumb.find(b"\xff\xe0")
    assert idx != -1
    jfif_thumb[idx + 4 + 12] = 16  # Xthumbnail > 0
    st, resp = upload_candidate(bytes(jfif_thumb))
    assert st == 422
    assert resp["code"] == "IMAGE_METADATA_NOT_ALLOWED"


def test_tc_api_21_decompression_bomb(client: TestClient, db, enrolled_device, test_study_and_site):
    """TC-API-21:
    JPEG header declaring 60,000 by 60,000 pixels.
    IMAGE_INVALID; server memory stays flat.
    """
    device_id, privkey, _jwk = enrolled_device
    today_str = datetime.datetime.now(datetime.UTC).date().isoformat()

    part_id = uuid4()
    upload_participant(client, privkey, device_id, part_id, "SKP-2101")

    clean_base = make_clean_jpeg(50, 50, (10, 20, 30))
    bomb = bytearray(clean_base)
    idx = bomb.find(b"\xff\xc0")  # SOF0 marker
    assert idx != -1
    # Patch height and width to 60000x60000
    bomb[idx + 5 : idx + 7] = (60000).to_bytes(2, "big")
    bomb[idx + 7 : idx + 9] = (60000).to_bytes(2, "big")
    bomb_bytes = bytes(bomb)
    bomb_sha, bomb_hdr, bomb_bsh = make_content_digest(bomb_bytes)

    sess_id = uuid4()
    c_id = uuid4()

    m_path = f"/sessions/{sess_id}"
    m_payload = {
        "participant_id": str(part_id),
        "card_status": "UNVERIFIED",
        "card_batch_id": None,
        "capture_date": today_str,
        "captured_at": None,
        "age_hours": 12.0,
        "gestational_age_weeks": 38,
        "flag_jaundice_under_24h": False,
        "flag_danger_signs": False,
        "flag_prior_phototherapy": False,
        "app_version": "1.0.0",
        "model_version": "1.0.0",
        "config_version": "1.0.0",
        "captures": [
            {
                "capture_id": str(c_id),
                "attempt_number": 1,
                "site": "STERNUM",
                "quality_status": "PASSED",
                "has_image": True,
                "image_sha256": bomb_sha,
                "size_bytes": len(bomb_bytes),
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

    c_path = f"/sessions/{sess_id}/captures/{c_id}/image"
    tok_c = mint_device_token(privkey, device_id, "PUT", c_path, body_bytes=bomb_bytes, bsh=bomb_bsh)
    r = client.put(
        c_path,
        content=bomb_bytes,
        headers={
            "Authorization": f"Bearer {tok_c}",
            "Content-Digest": bomb_hdr,
            "Content-Type": "image/jpeg",
            "Idempotency-Key": str(uuid4()),
            "X-NeoGold-App-Version": "1.0.0",
        },
    )
    assert r.status_code == 422
    assert r.json()["code"] == "IMAGE_INVALID"


def test_tc_api_22_size_limit(client: TestClient, db, enrolled_device, test_study_and_site):
    """TC-API-22:
    20,000,001-byte image -> 413 PAYLOAD_TOO_LARGE.
    """
    device_id, privkey, _jwk = enrolled_device

    part_id = uuid4()
    upload_participant(client, privkey, device_id, part_id, "SKP-2201")

    sess_id = uuid4()
    c_id = uuid4()

    fake_sha, fake_hdr, fake_bsh = make_content_digest(b"fake payload")

    # If Content-Length in header exceeds 20MB, server refuses it early in Phase A
    c_path = f"/sessions/{sess_id}/captures/{c_id}/image"
    tok = mint_device_token(privkey, device_id, "PUT", c_path, body_bytes=b"fake payload", bsh=fake_bsh)
    r = client.put(
        c_path,
        headers={
            "Authorization": f"Bearer {tok}",
            "Content-Digest": fake_hdr,
            "Content-Type": "image/jpeg",
            "Content-Length": "20000001",
            "Idempotency-Key": str(uuid4()),
            "X-NeoGold-App-Version": "1.0.0",
        },
    )
    assert r.status_code == 413
    assert r.json()["code"] == "PAYLOAD_TOO_LARGE"


def test_tc_api_23_semantic_validation(client: TestClient, db, enrolled_device, test_study_and_site):
    """TC-API-23:
    - captured_at in a DAY_PLUS_OFFSETS study
    - estimate outside its interval
    - consent after capture
    - VERIFIED with no batch
    - unknown card_batch_id
    400 or 422 with the codes in doc 7 Section 7.
    """
    device_id, privkey, _jwk = enrolled_device
    study, site = test_study_and_site
    today = datetime.datetime.now(datetime.UTC).date()
    today_str = today.isoformat()

    part_id = uuid4()
    upload_participant(client, privkey, device_id, part_id, "SKP-2301")

    def make_manifest(overrides: dict) -> tuple[int, dict]:
        sess_id = uuid4()
        c_id = uuid4()
        base = {
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
            "estimate_mg_dl": 10.0,
            "estimate_low_mg_dl": 8.0,
            "estimate_high_mg_dl": 12.0,
            "app_version": "1.0.0",
            "model_version": "1.0.0",
            "config_version": "1.0.0",
            "captures": [
                {
                    "capture_id": str(c_id),
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
        base.update(overrides)
        m_path = f"/sessions/{sess_id}"
        m_bytes = json.dumps(base, separators=(",", ":")).encode("utf-8")
        tok = mint_device_token(privkey, device_id, "PUT", m_path, body_bytes=m_bytes)
        r = client.put(
            m_path,
            content=m_bytes,
            headers={
                "Authorization": f"Bearer {tok}",
                "Idempotency-Key": str(uuid4()),
                "X-NeoGold-App-Version": "1.0.0",
                "Content-Type": "application/json",
            },
        )
        return r.status_code, r.json()

    # 1. captured_at in DAY_PLUS_OFFSETS study -> 400 VALIDATION_FAILED
    st, resp = make_manifest({"captured_at": "2026-10-20T10:00:00Z"})
    assert st == 400
    assert resp["code"] == "VALIDATION_FAILED"

    # 2. estimate_mg_dl outside [estimate_low_mg_dl, estimate_high_mg_dl] -> 400 VALIDATION_FAILED
    st, resp = make_manifest({"estimate_mg_dl": 15.0, "estimate_low_mg_dl": 8.0, "estimate_high_mg_dl": 12.0})
    assert st == 400
    assert resp["code"] == "VALIDATION_FAILED"

    # 3. consent_date after capture_date -> 400 VALIDATION_FAILED
    st, resp = make_manifest({"capture_date": (today - datetime.timedelta(days=2)).isoformat()})
    assert st == 400
    assert resp["code"] == "VALIDATION_FAILED"

    # 4. VERIFIED with no card_batch_id -> 400 VALIDATION_FAILED
    st, resp = make_manifest({"card_status": "VERIFIED", "card_batch_id": None})
    assert st == 400
    assert resp["code"] == "VALIDATION_FAILED"

    # 5. VERIFIED with unknown card_batch_id -> 422 CARD_BATCH_UNKNOWN
    st, resp = make_manifest({"card_status": "VERIFIED", "card_batch_id": "BATCH-NONEXISTENT"})
    assert st == 422
    assert resp["code"] == "CARD_BATCH_UNKNOWN"


def test_nightly_backstop_job(client: TestClient, db, enrolled_device, test_study_and_site):
    """Nightly backstop job completes RECEIVING sessions that have no missing images."""
    device_id, privkey, _jwk = enrolled_device
    today_str = datetime.datetime.now(datetime.UTC).date().isoformat()

    part_id = uuid4()
    upload_participant(client, privkey, device_id, part_id, "SKP-9001")

    sess_id = uuid4()
    m_path = f"/sessions/{sess_id}"
    m_payload = {
        "participant_id": str(part_id),
        "card_status": "UNVERIFIED",
        "card_batch_id": None,
        "capture_date": today_str,
        "captured_at": None,
        "age_hours": 15.0,
        "gestational_age_weeks": 38,
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
    tok = mint_device_token(privkey, device_id, "PUT", m_path, body_bytes=m_bytes)
    r = client.put(
        m_path,
        content=m_bytes,
        headers={
            "Authorization": f"Bearer {tok}",
            "Idempotency-Key": str(uuid4()),
            "X-NeoGold-App-Version": "1.0.0",
            "Content-Type": "application/json",
        },
    )
    assert r.status_code == 201
    assert r.json()["status"] == "RECEIVING"

    # Run the backstop job
    completed_count = complete_backstop_sessions(db)
    assert completed_count >= 1

    # Now verify the session is marked COMPLETE
    s_row = db.execute(select(DbSession).where(DbSession.id == sess_id)).scalar_one()
    assert s_row.status == "COMPLETE"
    assert s_row.completed_on is not None
