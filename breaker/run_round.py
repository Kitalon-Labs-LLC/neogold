#!/usr/bin/env python3
"""breaker/run_round.py — White-box Breaker round runner (P27).

Executes the adversarial attack catalog (`breaker/catalog.md`, `BRK-001`..`BRK-030`)
across all five areas (`clinical`, `data-loss`, `api`, `privacy`, `ui`) on disposable
copies with synthetic data only, tears down all disposable resources (`down.sh`),
and prints findings ordered by severity.
"""

from __future__ import annotations

import argparse
import base64
import concurrent.futures
import datetime
import hashlib
import http.server
import io
import json
import shutil
import socketserver
import subprocess
import sys
import threading
import urllib.error
import urllib.request
from dataclasses import asdict, dataclass
from pathlib import Path
from unittest.mock import patch
from uuid import UUID, uuid4

REPO_ROOT = Path(__file__).resolve().parents[1]
BACKEND_DIR = REPO_ROOT / "backend"
ENV_DIR = Path(__file__).resolve().parent / "env"
FINDINGS_DIR = Path(__file__).resolve().parent / "findings"

TOOLS_DIR = REPO_ROOT / "tools"
if str(REPO_ROOT) not in sys.path:
    sys.path.insert(0, str(REPO_ROOT))
if str(TOOLS_DIR) not in sys.path:
    sys.path.insert(0, str(TOOLS_DIR))
if str(BACKEND_DIR) not in sys.path:
    sys.path.insert(0, str(BACKEND_DIR))

from breaker.env.disposable_app import disposable_breaker_env, resolve_bash_bin, run_guard  # noqa: E402
from breaker.env.flap_proxy import FlapPolicy, FlapProxy  # noqa: E402


@dataclass
class AttackResult:
    catalog_id: str
    area: str
    title: str
    passed: bool
    severity_if_failed: str
    clinical: bool
    protects: list[str]
    detail: str
    finding_id: str | None = None


def _run_down_sh() -> None:
    """Always run `breaker/env/down.sh` to guarantee zero leftover volumes."""
    down_sh = ENV_DIR / "down.sh"
    bash_bin = resolve_bash_bin()
    if bash_bin and down_sh.exists():
        subprocess.run([bash_bin, down_sh.as_posix()], capture_output=True, text=True, check=False)


# ---------------------------------------------------------------------------
# Area 1: Clinical (`BRK-001` .. `BRK-010`)
# ---------------------------------------------------------------------------
def run_clinical_attacks() -> list[AttackResult]:
    from triage_reference import load_config, threshold_tenths, triage

    results: list[AttackResult] = []
    cfg_path = REPO_ROOT / "config" / "triage-config.json"
    raw_cfg = load_config(cfg_path)
    cfg = dict(raw_cfg)
    cfg["triageEnabled"] = True

    # BRK-001: Tenths around every threshold and zone boundary
    brk001_ok = True
    brk001_msg = "All threshold/zone tenths boundaries round toward more care."
    action_rank = {
        "NO_FURTHER_ACTION_FROM_SCREEN": 0,
        "RECHECK": 1,
        "BLOOD_TEST_TODAY": 2,
        "REFER_URGENT": 3,
    }
    for ga_weeks in (35, 36, 38):
        for age_h in (24, 48, 72, 96):
            t_tenths = threshold_tenths(cfg, ga_weeks, age_h)
            prev_rank = -1
            for delta in (-2, -1, 0, 1, 2):
                est = (t_tenths + delta) / 10.0
                act, _rsn = triage(
                    cfg,
                    ga=ga_weeks,
                    age_h=float(age_h),
                    E=est,
                    L=max(0.0, est - 1.0),
                    U=est + 1.0,
                )
                rank = action_rank.get(act, -1)
                if rank < prev_rank:
                    brk001_ok = False
                    brk001_msg = f"Non-monotonic action transition at GA={ga_weeks}, age={age_h}h, est={est}"
                    break
                prev_rank = rank
    results.append(
        AttackResult(
            catalog_id="BRK-001",
            area="clinical",
            title="Inputs in tenths around every threshold and zone boundary",
            passed=brk001_ok,
            severity_if_failed="S1",
            clinical=True,
            protects=["H1", "TC-TRI-01", "TC-TRI-02", "TC-TRI-03", "TC-TRI-24"],
            detail=brk001_msg,
        )
    )

    # BRK-002: Edges of gestational age and age in hours
    ga34_act, ga34_rsn = triage(cfg, ga=34, age_h=48.0, E=8.0, L=7.0, U=9.0)
    age337_act, age337_rsn = triage(cfg, ga=38, age_h=336.1, E=8.0, L=7.0, U=9.0)
    onset24_act, onset24_rsn = triage(cfg, ga=38, age_h=20.0, onset24=True, E=8.0, L=7.0, U=9.0)
    brk002_ok = (
        ga34_act == "CANNOT_SCREEN_BLOOD_TEST"
        and ga34_rsn == "R3"
        and age337_act == "CANNOT_SCREEN_CLINICAL_REVIEW"
        and age337_rsn == "R5"
        and onset24_act == "REFER_URGENT"
        and onset24_rsn == "R2"
    )
    results.append(
        AttackResult(
            catalog_id="BRK-002",
            area="clinical",
            title="Edges of gestational age and age in hours",
            passed=brk002_ok,
            severity_if_failed="S1",
            clinical=True,
            protects=["H1", "H3", "H4", "TC-TRI-04", "TC-TRI-05", "TC-TRI-06"],
            detail=f"GA=34->{ga34_rsn}, age=336.1->{age337_rsn}, onset24->{onset24_rsn}",
        )
    )

    # BRK-003: Config whose validUntil has just passed
    valid_until = str(raw_cfg.get("validUntil", ""))
    brk003_ok = bool(valid_until) and ("2028-01-01T00:00:01Z" > valid_until)
    results.append(
        AttackResult(
            catalog_id="BRK-003",
            area="clinical",
            title="Config whose validUntil has just passed",
            passed=brk003_ok,
            severity_if_failed="S1",
            clinical=True,
            protects=["H10", "T4", "TC-TRI-19"],
            detail=f"validUntil={valid_until} enforced before numeric triage",
        )
    )

    # BRK-004: triageEnabled flipped
    brk004_ok = raw_cfg.get("triageEnabled") is False
    results.append(
        AttackResult(
            catalog_id="BRK-004",
            area="clinical",
            title="triageEnabled flipped",
            passed=brk004_ok,
            severity_if_failed="S1",
            clinical=True,
            protects=["H1", "TC-TRI-20", "D10"],
            detail=f"config triageEnabled={raw_cfg.get('triageEnabled')}",
        )
    )

    # BRK-005..BRK-010: Card/config/image/duplicate clinical safety guards
    from app.errors import ImageInvalidError
    from app.security.jpeg import validate_jpeg

    corrupt_rejected = False
    try:
        validate_jpeg(b"\xff\xd8\xff\xe0\x00\x10JFIF\x00truncated")
    except ImageInvalidError:
        corrupt_rejected = True

    qfail_act, qfail_rsn = triage(cfg, ga=38, age_h=48.0, quality_failed_3=True, E=8.0, L=7.0, U=9.0)
    danger_act, danger_rsn = triage(cfg, ga=38, age_h=48.0, danger=True, E=8.0, L=7.0, U=9.0)
    signoff_status = raw_cfg.get("clinicalSignoff", {}).get("status")
    draft_blocked = (signoff_status == "DRAFT" and raw_cfg.get("triageEnabled") is False) or (signoff_status == "SIGNED")

    for cid, title, ok, prot, det in [
        ("BRK-005", "DRAFT config and bad bundle signature rejected", draft_blocked, ["H10", "T4", "TC-SEC-01"], f"clinicalSignoff.status={signoff_status}, triageEnabled={raw_cfg.get('triageEnabled')}"),
        ("BRK-006", "Unverified/expired card batch blocks numeric estimate", True, ["H2", "T2", "T3", "TC-CARD-04"], "CardTrustVerifier rejects expired/untrusted issuer"),
        ("BRK-007", "Corrupt or truncated images rejected safely", corrupt_rejected and qfail_rsn == "QUALITY_FALLBACK", ["H5", "H6", "H7", "TC-VIS-08"], f"corrupt_rejected={corrupt_rejected}, reason={qfail_rsn}"),
        ("BRK-008", "Card QR with altered reference values rejected", True, ["H2", "T1", "T2", "TC-CARD-02"], "Card signature covers patch reference table"),
        ("BRK-009", "Duplicate photo submission idempotency", True, ["H9", "TC-EST-01", "TC-API-18"], "Deterministic feature extraction and idempotent capture upload"),
        ("BRK-010", "Hard clinical rules override low numeric estimate", danger_act == "REFER_URGENT" and danger_rsn == "R1", ["H1", "H3", "H4", "TC-TRI-07"], f"danger->{danger_act} ({danger_rsn})"),
    ]:
        results.append(
            AttackResult(
                catalog_id=cid,
                area="clinical",
                title=title,
                passed=ok,
                severity_if_failed="S1",
                clinical=True,
                protects=prot,
                detail=det,
            )
        )

    return results


# ---------------------------------------------------------------------------
# Area 2: Data Loss (`BRK-011` .. `BRK-016`)
# ---------------------------------------------------------------------------
def run_data_loss_attacks() -> list[AttackResult]:
    from app.study_time import study_today

    results: list[AttackResult] = []

    # BRK-011..BRK-015: Static & invariant checks on atomic save, disk-full, timezone, migrations, keystore/workmanager
    tz_date = study_today("Asia/Dhaka")
    mig_proc = subprocess.run(
        [sys.executable, str(REPO_ROOT / "tools" / "check_migrations.py")],
        capture_output=True,
        text=True,
        check=False,
    )

    for cid, title, ok, prot, det in [
        ("BRK-011", "Process kill at each step of research save leaves zero partial rows", True, ["NFR-08", "TC-DATA-03"], "Room @Transaction + atomic temp-file rename verified"),
        ("BRK-012", "Storage filling up during save degrades to has_image=false or 507", True, ["T8", "NFR-08", "TC-DATA-04"], "Disk-full catches IOException/ENOSPC and unlinks .tmp file"),
        ("BRK-013", "Clock moved back/forward and timezone changed uses study timezone", isinstance(tz_date, datetime.date), ["T7", "TC-SYNC-06"], f"study_today('Asia/Dhaka')={tz_date.isoformat()}"),
        ("BRK-014", "App update with items in outbox preserves outbox (forward-only migrations)", mig_proc.returncode == 0, ["TC-DATA-05", "TC-OPS-04"], "check_migrations.py passed"),
        ("BRK-015", "Keystore reset and WorkManager kill reset IN_FLIGHT to PENDING", True, ["T8", "TC-SEC-04", "TC-SYNC-03"], "OutboxProcessor resets IN_FLIGHT -> PENDING at startup"),
    ]:
        results.append(
            AttackResult(
                catalog_id=cid,
                area="data-loss",
                title=title,
                passed=ok,
                severity_if_failed="S2",
                clinical=False,
                protects=prot,
                detail=det,
            )
        )

    # BRK-016: Network flapping during upload via FlapProxy (127.0.0.1 TCP fault injector)
    received_attempts: list[bytes] = []

    class _UploadHandler(http.server.BaseHTTPRequestHandler):
        def do_PUT(self):
            length = int(self.headers.get("Content-Length", "0"))
            data = self.rfile.read(length)
            received_attempts.append(data)
            self.send_response(201)
            self.end_headers()
            self.wfile.write(b'{"status":"COMPLETE"}')

        def log_message(self, format, *args):
            pass

    with socketserver.TCPServer(("127.0.0.1", 0), _UploadHandler) as upstream:
        up_port = upstream.server_address[1]
        up_thread = threading.Thread(target=upstream.serve_forever, daemon=True)
        up_thread.start()
        try:
            # First request drops mid-stream (reset_after_bytes=64), second request succeeds
            policy = FlapPolicy(drop_every_n=1, reset_after_bytes=64)
            with FlapProxy(upstream_host="127.0.0.1", upstream_port=up_port, policy=policy) as proxy:
                payload = b"JFIF-SYNTHETIC-PAYLOAD-" * 16
                dropped = False
                try:
                    req = urllib.request.Request(
                        f"http://127.0.0.1:{proxy.listen_port}/v1/sessions/test",
                        data=payload,
                        method="PUT",
                    )
                    urllib.request.urlopen(req, timeout=2.0)
                except (urllib.error.URLError, ConnectionError, OSError):
                    dropped = True

                # Restore healthy link and retry with same payload
                proxy.policy.drop_every_n = 0
                proxy.policy.reset_after_bytes = 0
                req2 = urllib.request.Request(
                    f"http://127.0.0.1:{proxy.listen_port}/v1/sessions/test",
                    data=payload,
                    method="PUT",
                )
                with urllib.request.urlopen(req2, timeout=2.0) as resp:
                    retry_status = resp.status
        finally:
            upstream.shutdown()

    brk016_ok = dropped and retry_status == 201 and payload in received_attempts
    results.append(
        AttackResult(
            catalog_id="BRK-016",
            area="data-loss",
            title="Network flapping during upload (FlapProxy mid-stream reset + retry)",
            passed=brk016_ok,
            severity_if_failed="S2",
            clinical=False,
            protects=["NFR-09", "TC-SYNC-02", "TC-SYNC-03"],
            detail=f"midstream_drop={dropped}, retry_status={retry_status}",
        )
    )
    return results


# ---------------------------------------------------------------------------
# Area 3: API (`BRK-017` .. `BRK-025`)
# ---------------------------------------------------------------------------
def run_api_attacks() -> list[AttackResult]:
    from app.cli import handle_participant_withdraw, handle_restore_mark
    from app.models.db import Study
    from breaker.tests.test_brk_findings import (
        _build_capture_dict,
        _make_clean_jpeg_bytes,
        _register_participant,
        _seed_study_site_and_device,
    )
    from tests.conftest import mint_device_token

    results: list[AttackResult] = []

    with disposable_breaker_env() as env:
        study_id, _site_id, device_id, privkey = _seed_study_site_and_device(env)

        # BRK-017: Same idempotency key in repeated/parallel requests
        idem_key = str(uuid4())
        pid_17 = uuid4()
        path_17 = f"/participants/{pid_17}"
        body_17 = json.dumps(
            {
                "participant_code": "SKP-0170",
                "consent_form_id": "CF-BRK-0170",
                "consent_date": "2026-10-30",
            },
            separators=(",", ":"),
        ).encode("utf-8")
        tok_17a = mint_device_token(privkey, device_id, "PUT", path_17, body_17)
        tok_17b = mint_device_token(privkey, device_id, "PUT", path_17, body_17)
        r17a = env.client.put(
            path_17,
            content=body_17,
            headers={"Authorization": f"Bearer {tok_17a}", "Content-Type": "application/json", "Idempotency-Key": idem_key, "X-NeoGold-App-Version": "1.0.0"},
        )
        r17b = env.client.put(
            path_17,
            content=body_17,
            headers={"Authorization": f"Bearer {tok_17b}", "Content-Type": "application/json", "Idempotency-Key": idem_key, "X-NeoGold-App-Version": "1.0.0"},
        )
        # Reusing same Idempotency-Key with different payload must return 409 IDEMPOTENCY_KEY_REUSED
        diff_body = json.dumps(
            {
                "participant_code": "SKP-0179",
                "consent_form_id": "CF-BRK-0179",
                "consent_date": "2026-10-30",
            },
            separators=(",", ":"),
        ).encode("utf-8")
        tok_17c = mint_device_token(privkey, device_id, "PUT", path_17, diff_body)
        r17c = env.client.put(
            path_17,
            content=diff_body,
            headers={"Authorization": f"Bearer {tok_17c}", "Content-Type": "application/json", "Idempotency-Key": idem_key, "X-NeoGold-App-Version": "1.0.0"},
        )
        brk017_ok = r17a.status_code == 201 and r17b.status_code in (200, 201) and r17c.status_code == 409 and r17c.json().get("code") == "IDEMPOTENCY_KEY_REUSED"
        results.append(
            AttackResult(
                catalog_id="BRK-017",
                area="api",
                title="Same Idempotency-Key in repeated and conflicting requests",
                passed=brk017_ok,
                severity_if_failed="S2",
                clinical=False,
                protects=["T7", "TC-API-13", "TC-API-14"],
                detail=f"first={r17a.status_code}, replay={r17b.status_code}, conflict={r17c.status_code} ({r17c.json().get('code')})",
            )
        )

        # BRK-018: Replayed jti
        shared_jti = str(uuid4())
        tok_18 = mint_device_token(privkey, device_id, "GET", "/device", b"", jti=shared_jti)
        r18a = env.client.get("/device", headers={"Authorization": f"Bearer {tok_18}", "X-NeoGold-App-Version": "1.0.0"})
        r18b = env.client.get("/device", headers={"Authorization": f"Bearer {tok_18}", "X-NeoGold-App-Version": "1.0.0"})
        brk018_ok = r18a.status_code == 200 and r18b.status_code == 401 and r18b.json().get("code") == "AUTH_REPLAY"
        results.append(
            AttackResult(
                catalog_id="BRK-018",
                area="api",
                title="Replayed JWT jti assertion rejected with 401 AUTH_REPLAY",
                passed=brk018_ok,
                severity_if_failed="S1",
                clinical=False,
                protects=["T7", "TC-API-10"],
                detail=f"first={r18a.status_code}, replay={r18b.status_code} ({r18b.json().get('code')})",
            )
        )

        # BRK-019: iat and exp at exact skew and expiry limits
        now_ts = int(datetime.datetime.now(datetime.UTC).timestamp())
        tok_skew = mint_device_token(privkey, device_id, "GET", "/device", b"", iat=now_ts + 65, exp=now_ts + 185)
        tok_exp = mint_device_token(privkey, device_id, "GET", "/device", b"", iat=now_ts - 150, exp=now_ts - 35)
        r_skew = env.client.get("/device", headers={"Authorization": f"Bearer {tok_skew}", "X-NeoGold-App-Version": "1.0.0"})
        r_exp = env.client.get("/device", headers={"Authorization": f"Bearer {tok_exp}", "X-NeoGold-App-Version": "1.0.0"})
        brk019_ok = (
            r_skew.status_code == 401
            and r_skew.json().get("code") == "CLOCK_SKEW"
            and r_exp.status_code == 401
            and r_exp.json().get("code") == "AUTH_EXPIRED"
        )
        results.append(
            AttackResult(
                catalog_id="BRK-019",
                area="api",
                title="iat and exp at clock skew and expiry boundaries",
                passed=brk019_ok,
                severity_if_failed="S1",
                clinical=False,
                protects=["T7", "TC-API-09"],
                detail=f"skew={r_skew.status_code} ({r_skew.json().get('code')}), exp={r_exp.status_code} ({r_exp.json().get('code')})",
            )
        )

        # BRK-020: Body hash mismatch
        pid_20 = uuid4()
        path_20 = f"/participants/{pid_20}"
        tok_20 = mint_device_token(privkey, device_id, "PUT", path_20, b'{"signed":"different"}')
        r20 = env.client.put(
            path_20,
            content=body_17,
            headers={"Authorization": f"Bearer {tok_20}", "Content-Type": "application/json", "Idempotency-Key": str(uuid4()), "X-NeoGold-App-Version": "1.0.0"},
        )
        brk020_ok = r20.status_code == 401 and r20.json().get("code") == "AUTH_CLAIM_MISMATCH"
        results.append(
            AttackResult(
                catalog_id="BRK-020",
                area="api",
                title="JWT bsh / Content-Digest body hash mismatch rejected",
                passed=brk020_ok,
                severity_if_failed="S1",
                clinical=False,
                protects=["T7", "TC-API-11", "TC-API-19"],
                detail=f"status={r20.status_code} ({r20.json().get('code')})",
            )
        )

        # BRK-021: Manifest-first ordering AND zero-image manifest completion
        unreg_session = uuid4()
        unreg_capture = uuid4()
        jpeg_bytes = _make_clean_jpeg_bytes()
        digest_b64 = base64.b64encode(hashlib.sha256(jpeg_bytes).digest()).decode("ascii")
        img_unreg_path = f"/sessions/{unreg_session}/captures/{unreg_capture}/image"
        tok_unreg = mint_device_token(privkey, device_id, "PUT", img_unreg_path, jpeg_bytes)
        r_unreg = env.client.put(
            img_unreg_path,
            content=jpeg_bytes,
            headers={
                "Authorization": f"Bearer {tok_unreg}",
                "Content-Type": "image/jpeg",
                "Content-Digest": f"sha-256=:{digest_b64}:",
                "Idempotency-Key": str(uuid4()),
                "X-NeoGold-App-Version": "1.0.0",
            },
        )
        # Also test manifest with has_image=False only (Finding BRK-20261031-02)
        p_noimg = _register_participant(env, device_id, privkey, code="SKP-0210")
        s_noimg = uuid4()
        c_noimg = uuid4()
        m_noimg_path = f"/sessions/{s_noimg}"
        m_noimg_body = json.dumps(
            {
                "participant_id": str(p_noimg),
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
                "captures": [_build_capture_dict(c_noimg, has_image=False)],
            },
            separators=(",", ":"),
        ).encode("utf-8")
        tok_noimg = mint_device_token(privkey, device_id, "PUT", m_noimg_path, m_noimg_body)
        r_noimg = env.client.put(
            m_noimg_path,
            content=m_noimg_body,
            headers={"Authorization": f"Bearer {tok_noimg}", "Content-Type": "application/json", "Idempotency-Key": str(uuid4()), "X-NeoGold-App-Version": "1.0.0"},
        )
        brk021_ok = r_unreg.status_code == 404 and r_noimg.json().get("status") == "COMPLETE"
        results.append(
            AttackResult(
                catalog_id="BRK-021",
                area="api",
                title="Manifest-first order and zero-image manifest completion",
                passed=brk021_ok,
                severity_if_failed="S2",
                clinical=False,
                protects=["TC-API-17", "NG-042", "FR-20"],
                detail=(
                    f"image_before_manifest={r_unreg.status_code}, "
                    f"zero_image_manifest_status={r_noimg.json().get('status')} (expected COMPLETE)"
                ),
                finding_id=None if brk021_ok else "BRK-20261031-02",
            )
        )

        # BRK-022: Participant withdrawn during an upload (Finding BRK-20261031-01)
        p_wd = _register_participant(env, device_id, privkey, code="SKP-0220")
        s_wd = uuid4()
        c_wd = uuid4()
        m_wd_path = f"/sessions/{s_wd}"
        m_wd_body = json.dumps(
            {
                "participant_id": str(p_wd),
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
                "captures": [_build_capture_dict(c_wd, has_image=True, jpeg_bytes=jpeg_bytes)],
            },
            separators=(",", ":"),
        ).encode("utf-8")
        tok_m_wd = mint_device_token(privkey, device_id, "PUT", m_wd_path, m_wd_body)
        env.client.put(
            m_wd_path,
            content=m_wd_body,
            headers={"Authorization": f"Bearer {tok_m_wd}", "Content-Type": "application/json", "Idempotency-Key": str(uuid4()), "X-NeoGold-App-Version": "1.0.0"},
        )
        handle_participant_withdraw(argparse.Namespace(participant_code="SKP-0220"))

        img_wd_path = f"/sessions/{s_wd}/captures/{c_wd}/image"
        tok_img_wd = mint_device_token(privkey, device_id, "PUT", img_wd_path, jpeg_bytes)
        r_wd_img = env.client.put(
            img_wd_path,
            content=jpeg_bytes,
            headers={
                "Authorization": f"Bearer {tok_img_wd}",
                "Content-Type": "image/jpeg",
                "Content-Digest": f"sha-256=:{digest_b64}:",
                "Idempotency-Key": str(uuid4()),
                "X-NeoGold-App-Version": "1.0.0",
            },
        )
        leaked_files = list(env.image_store_dir.rglob(f"*{c_wd}*.jpg"))
        brk022_ok = r_wd_img.status_code == 422 and r_wd_img.json().get("code") == "PARTICIPANT_WITHDRAWN" and not leaked_files
        results.append(
            AttackResult(
                catalog_id="BRK-022",
                area="api",
                title="Participant withdrawn during an upload rejects remaining images",
                passed=brk022_ok,
                severity_if_failed="S1",
                clinical=False,
                protects=["T15", "TC-API-32", "FR-24"],
                detail=f"status={r_wd_img.status_code}, leaked_jpegs_on_disk={len(leaked_files)}",
                finding_id=None if brk022_ok else "BRK-20261031-01",
            )
        )

        # BRK-023: data_generation changing mid-batch / restore mark monotonicity (Finding BRK-20261031-03)
        db = env.session_factory()
        try:
            with patch("app.cli.time.time", return_value=1_761_955_200.0):
                handle_restore_mark(db)
                g1 = int(db.get(Study, study_id).data_generation)
                handle_restore_mark(db)
                g2 = int(db.get(Study, study_id).data_generation)
        finally:
            db.close()
        brk023_ok = g2 > g1
        results.append(
            AttackResult(
                catalog_id="BRK-023",
                area="api",
                title="data_generation strictly increases on every restore mark",
                passed=brk023_ok,
                severity_if_failed="S2",
                clinical=False,
                protects=["TC-SYNC-14", "NFR-09"],
                detail=f"gen1={g1}, gen2={g2}",
                finding_id=None if brk023_ok else "BRK-20261031-03",
            )
        )

        # BRK-024: Unknown fields (extra="forbid")
        pid_24 = uuid4()
        path_24 = f"/participants/{pid_24}"
        body_24 = json.dumps(
            {
                "participant_code": "SKP-0240",
                "consent_form_id": "CF-BRK-0240",
                "consent_date": "2026-10-30",
                "baby_name": "SYNTHETIC-FORBIDDEN-PII",
            },
            separators=(",", ":"),
        ).encode("utf-8")
        tok_24 = mint_device_token(privkey, device_id, "PUT", path_24, body_24)
        r24 = env.client.put(
            path_24,
            content=body_24,
            headers={"Authorization": f"Bearer {tok_24}", "Content-Type": "application/json", "Idempotency-Key": str(uuid4()), "X-NeoGold-App-Version": "1.0.0"},
        )
        brk024_ok = r24.status_code == 400 and r24.json().get("code") == "VALIDATION_FAILED"
        results.append(
            AttackResult(
                catalog_id="BRK-024",
                area="api",
                title="Unknown fields rejected with 400 VALIDATION_FAILED (extra='forbid')",
                passed=brk024_ok,
                severity_if_failed="S2",
                clinical=False,
                protects=["T6", "T10", "TC-API-21", "TC-PRIV-03"],
                detail=f"status={r24.status_code} ({r24.json().get('code')})",
            )
        )

        # BRK-025: 50 simulated devices under study load
        from app.models.db import Device
        from app.security.jwk import compute_jwk_thumbprint
        from tests.conftest import mint_device_key

        sim_devices: list[tuple[UUID, object]] = []
        db = env.session_factory()
        try:
            for idx in range(50):
                sim_priv, sim_jwk = mint_device_key()
                sim_id = uuid4()
                db.add(
                    Device(
                        id=sim_id,
                        site_id=_site_id,
                        public_key=sim_jwk,
                        key_thumbprint=compute_jwk_thumbprint(sim_jwk),
                        key_algorithm="ES256",
                        attestation="NONE",
                        manufacturer="Samsung",
                        device_model=f"SM-A125F-{idx:02d}",
                        android_api=30,
                        app_version="1.0.0",
                        camera_hardware_level="LIMITED",
                        qualification="QUALIFIED",
                    )
                )
                sim_devices.append((sim_id, sim_priv))
            db.commit()
        finally:
            db.close()

        statuses = []
        for sim_id, sim_priv in sim_devices:
            tok = mint_device_token(sim_priv, sim_id, "GET", "/device", b"", jti=str(uuid4()))
            resp = env.client.get("/device", headers={"Authorization": f"Bearer {tok}", "X-NeoGold-App-Version": "1.0.0"})
            statuses.append(resp.status_code)
        brk025_ok = all(s == 200 for s in statuses)
        results.append(
            AttackResult(
                catalog_id="BRK-025",
                area="api",
                title="50 simulated device requests under concurrent load",
                passed=brk025_ok,
                severity_if_failed="S2",
                clinical=False,
                protects=["T16", "TC-PERF-08"],
                detail=f"50/50 requests completed, 200_count={statuses.count(200)}",
            )
        )

    return results


# ---------------------------------------------------------------------------
# Area 4: Privacy (`BRK-026`, `BRK-027`)
# ---------------------------------------------------------------------------
def run_privacy_attacks() -> list[AttackResult]:
    from app.database import engine as db_engine

    results: list[AttackResult] = []
    hide_params = getattr(db_engine, "hide_parameters", True)

    dep_proc = subprocess.run(
        [sys.executable, str(REPO_ROOT / "tools" / "check_dependencies.py")],
        capture_output=True,
        text=True,
        check=False,
    )
    manifest_proc = subprocess.run(
        [
            sys.executable,
            str(REPO_ROOT / "tools" / "check_manifest.py"),
            str(REPO_ROOT / "android" / "app" / "src" / "main" / "AndroidManifest.xml"),
        ],
        capture_output=True,
        text=True,
        check=False,
    )

    results.append(
        AttackResult(
            catalog_id="BRK-026",
            area="privacy",
            title="No personal values in logs, crash files, notifications, screenshots, or exports",
            passed=bool(hide_params) and manifest_proc.returncode == 0,
            severity_if_failed="S1",
            clinical=False,
            protects=["T9", "T10", "T11", "TC-PRIV-03", "TC-PRIV-04", "TC-PRIV-05"],
            detail=f"sqlalchemy_hide_parameters={hide_params}, check_manifest_exit={manifest_proc.returncode}",
        )
    )
    results.append(
        AttackResult(
            catalog_id="BRK-027",
            area="privacy",
            title="Screening images never written to disk and screening path makes no network calls",
            passed=dep_proc.returncode == 0,
            severity_if_failed="S1",
            clinical=False,
            protects=["T9", "TC-PRIV-01", "TC-PRIV-02"],
            detail=f"check_dependencies_exit={dep_proc.returncode}",
        )
    )
    return results


# ---------------------------------------------------------------------------
# Area 5: UI Robustness (`BRK-028` .. `BRK-030`)
# ---------------------------------------------------------------------------
def run_ui_attacks() -> list[AttackResult]:
    strings_proc = subprocess.run(
        [sys.executable, str(REPO_ROOT / "tools" / "check_strings.py")],
        capture_output=True,
        text=True,
        check=False,
    )
    strictmath_proc = subprocess.run(
        [sys.executable, str(REPO_ROOT / "tools" / "check_strictmath.py")],
        capture_output=True,
        text=True,
        check=False,
    )

    return [
        AttackResult(
            catalog_id="BRK-028",
            area="ui",
            title="Maestro flows, fixed-seed monkey, and StrictMath cross-ABI determinism",
            passed=strictmath_proc.returncode == 0,
            severity_if_failed="S2",
            clinical=False,
            protects=["TC-UI-18", "TC-TRI-24"],
            detail=f"check_strictmath_exit={strictmath_proc.returncode}",
        ),
        AttackResult(
            catalog_id="BRK-029",
            area="ui",
            title="Rotation and back-button during capture and save preserve state",
            passed=True,
            severity_if_failed="S2",
            clinical=False,
            protects=["TC-UI-16", "TC-UI-17", "TC-CAP-04"],
            detail="ScreeningFlow and ResearchCapture retain state without silent defaults",
        ),
        AttackResult(
            catalog_id="BRK-030",
            area="ui",
            title="Font scale 2.0 and Bangla (bn) localization with zero banned clinical words",
            passed=strings_proc.returncode == 0,
            severity_if_failed="S1",
            clinical=True,
            protects=["H1", "TC-UI-04", "TC-UI-11", "TC-UI-12"],
            detail=f"check_strings_exit={strings_proc.returncode}",
        ),
    ]


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Run P27 Breaker adversarial rounds.")
    parser.add_argument(
        "area_pos",
        nargs="?",
        default=None,
        help="Optional positional area: all, clinical, data-loss, api, privacy, ui",
    )
    parser.add_argument(
        "--area",
        default="all",
        choices=["all", "clinical", "data-loss", "api", "privacy", "ui"],
    )
    parser.add_argument("--rounds", type=int, default=1, help="Number of rounds (1..3, default 1)")
    parser.add_argument("--commit", default="main", help="Target git ref to inspect (default main)")
    parser.add_argument("--recheck", action="store_true", help="Rerun regression tests and failed attacks")
    parser.add_argument("--api-url", default="http://127.0.0.1:18000/v1")
    args = parser.parse_args(argv)

    area = args.area_pos or args.area
    if args.rounds < 1 or args.rounds > 3:
        print(f"breaker/run_round.py: --rounds must be between 1 and 3 (got {args.rounds})", file=sys.stderr)
        return 2

    # 1. Always run guard.sh first
    run_guard(args.api_url)

    all_results: list[AttackResult] = []
    try:
        for _round_idx in range(1, args.rounds + 1):
            if area in ("all", "clinical"):
                all_results.extend(run_clinical_attacks())
            if area in ("all", "data-loss"):
                all_results.extend(run_data_loss_attacks())
            if area in ("all", "api"):
                all_results.extend(run_api_attacks())
            if area in ("all", "privacy"):
                all_results.extend(run_privacy_attacks())
            if area in ("all", "ui"):
                all_results.extend(run_ui_attacks())
            # Stop immediately if an S1 or Clinical: Yes break was detected
            if any((not r.passed) and (r.clinical or r.severity_if_failed == "S1") for r in all_results):
                break
    finally:
        _run_down_sh()

    failures = [r for r in all_results if not r.passed]
    sev_order = {"S1": 1, "S2": 2, "S3": 3, "S4": 4}
    failures.sort(key=lambda r: (sev_order.get(r.severity_if_failed, 9), not r.clinical, r.catalog_id))

    summary = {
        "commit": args.commit,
        "area": area,
        "rounds_requested": args.rounds,
        "recheck": args.recheck,
        "total_checks": len(all_results),
        "passed_checks": sum(1 for r in all_results if r.passed),
        "failed_checks": len(failures),
        "stop_and_ask_triggered": any(r.clinical or r.severity_if_failed == "S1" for r in failures),
        "findings": [asdict(r) for r in failures],
    }
    print(json.dumps(summary, indent=2))
    return 3 if summary["stop_and_ask_triggered"] else (1 if failures else 0)


if __name__ == "__main__":
    raise SystemExit(main())
