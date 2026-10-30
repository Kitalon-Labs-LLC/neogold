from __future__ import annotations

import argparse
import csv
import datetime
import hashlib
import hmac
import os
import random
import secrets
import shutil
import sys
import time
from pathlib import Path
from typing import Any
from uuid import UUID, uuid4

from sqlalchemy import create_engine, delete, func, select, text
from sqlalchemy.orm import Session, sessionmaker

from app.config import settings
from app.database import SessionLocal
from app.models.db import (
    AuditLog,
    Device,
    EnrollmentCode,
    ExportJob,
    Participant,
    ParticipantAlias,
    Site,
    Study,
    TsbMeasurement,
)
from app.models.db import (
    Capture as DbCapture,
)
from app.models.db import (
    Session as DbSession,
)
from app.privileges import privilege_problems
from app.study_time import study_today

CROCKFORD_ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"


def get_purge_db() -> Session:
    if settings.PURGE_DATABASE_URL:
        purge_engine = create_engine(settings.PURGE_DATABASE_URL, pool_pre_ping=True, hide_parameters=True)
        PurgeSession = sessionmaker(autocommit=False, autoflush=False, bind=purge_engine)
        return PurgeSession()
    return SessionLocal()


def get_export_db() -> Session:
    if settings.EXPORT_DATABASE_URL:
        export_engine = create_engine(settings.EXPORT_DATABASE_URL, pool_pre_ping=True, hide_parameters=True)
        ExportSession = sessionmaker(autocommit=False, autoflush=False, bind=export_engine)
        return ExportSession()
    return SessionLocal()


def record_admin_audit(
    db: Session,
    action: str,
    target_type: str | None = None,
    target_id: str | None = None,
    details: dict[str, Any] | None = None,
) -> None:
    actor_id = os.getenv("SUDO_USER") or os.getenv("USER") or "coordinator"
    log_entry = AuditLog(
        actor_type="ADMIN",
        actor_id=actor_id,
        action=action,
        target_type=target_type,
        target_id=target_id,
        details=details or {},
    )
    db.add(log_entry)
    db.commit()


def parse_duration_to_timedelta(dur_str: str) -> datetime.timedelta:
    dur_str = dur_str.strip().lower()
    if dur_str.endswith("h"):
        hours = int(dur_str[:-1])
        return datetime.timedelta(hours=hours)
    elif dur_str.endswith("d"):
        days = int(dur_str[:-1])
        return datetime.timedelta(days=days)
    elif dur_str.endswith("m"):
        minutes = int(dur_str[:-1])
        return datetime.timedelta(minutes=minutes)
    else:
        # Default hours
        return datetime.timedelta(hours=int(dur_str))


# ---------------------------------------------------------
# Subcommand Handlers
# ---------------------------------------------------------


def handle_study_create(args: argparse.Namespace) -> int:
    db = SessionLocal()
    try:
        retention_date = datetime.date.fromisoformat(args.retention_until)
        # an unknown zone name would fail every request that assigns a date, so it is refused when the study is created
        try:
            study_today(args.time_zone or "Asia/Dhaka")
        except (ValueError, LookupError) as e:
            print(f"ERROR: unknown time zone {args.time_zone!r}: {e}", file=sys.stderr)
            return 1
        study = Study(
            id=uuid4(),
            study_code=args.code,
            name=args.name,
            partner_institution=args.partner,
            ethics_reference=args.ethics_ref,
            retention_until=retention_date,
            timestamp_precision=args.timestamp_precision or "DAY_PLUS_OFFSETS",
            time_zone=args.time_zone or "Asia/Dhaka",
            min_app_version=args.min_app_version or "1.0.0",
            wifi_only=True,
            status="ACTIVE",
            data_generation=1,
        )
        db.add(study)
        db.commit()

        record_admin_audit(
            db,
            action="STUDY_CREATED",
            target_type="study",
            target_id=str(study.id),
            details={"study_code": study.study_code},
        )
        print(f"Study {study.study_code} created (id: {study.id})")
        return 0
    finally:
        db.close()


def _set_study_status(study_code: str | None, status: str, action_name: str) -> int:
    db = SessionLocal()
    try:
        q = select(Study)
        if study_code:
            q = q.where(Study.study_code == study_code)
        study = db.execute(q).scalar_one_or_none()
        if not study:
            print("Study not found", file=sys.stderr)
            return 1
        study.status = status
        db.commit()

        record_admin_audit(
            db,
            action=action_name,
            target_type="study",
            target_id=str(study.id),
            details={"study_code": study.study_code, "status": status},
        )
        print(f"Study {study.study_code} is now {status}")
        return 0
    finally:
        db.close()


def handle_study_pause(args: argparse.Namespace) -> int:
    return _set_study_status(args.code, "PAUSED", "STUDY_PAUSED")


def handle_study_resume(args: argparse.Namespace) -> int:
    return _set_study_status(args.code, "ACTIVE", "STUDY_RESUMED")


def handle_study_close(args: argparse.Namespace) -> int:
    return _set_study_status(args.code, "CLOSED", "STUDY_CLOSED")


def handle_study_set_min_app_version(args: argparse.Namespace) -> int:
    db = SessionLocal()
    try:
        q = select(Study)
        if args.code:
            q = q.where(Study.study_code == args.code)
        study = db.execute(q).scalar_one_or_none()
        if not study:
            print("Study not found", file=sys.stderr)
            return 1
        study.min_app_version = args.version
        db.commit()

        record_admin_audit(
            db,
            action="MIN_APP_VERSION_SET",
            target_type="study",
            target_id=str(study.id),
            details={"study_code": study.study_code, "min_app_version": args.version},
        )
        print(f"Study {study.study_code} min app version set to {args.version}")
        return 0
    finally:
        db.close()


def handle_site_add(args: argparse.Namespace) -> int:
    db = SessionLocal()
    try:
        study = db.execute(select(Study).where(Study.study_code == args.study)).scalar_one_or_none()
        if not study:
            print(f"Study {args.study} not found", file=sys.stderr)
            return 1

        is_test = bool(args.test)
        site = Site(
            id=uuid4(),
            study_id=study.id,
            site_code=args.code,
            name=args.name,
            is_test=is_test,
        )
        db.add(site)
        db.commit()

        record_admin_audit(
            db,
            action="SITE_ADDED",
            target_type="site",
            target_id=str(site.id),
            details={"study_code": study.study_code, "site_code": site.site_code, "is_test": is_test},
        )
        print(f"Site {site.site_code} added (test={is_test})")
        return 0
    finally:
        db.close()


def handle_code_issue(args: argparse.Namespace) -> int:
    db = SessionLocal()
    try:
        site = db.execute(select(Site).where(Site.site_code == args.site)).scalar_one_or_none()
        if not site:
            print(f"Site {args.site} not found", file=sys.stderr)
            return 1

        duration = parse_duration_to_timedelta(args.expires or "72h")
        expires_at = datetime.datetime.now(datetime.UTC) + duration

        count = args.count or 1
        secret_bytes = settings.enrollment_code_key()

        issued_codes = []
        for _ in range(count):
            g1 = "".join(secrets.choice(CROCKFORD_ALPHABET) for _ in range(4))
            g2 = "".join(secrets.choice(CROCKFORD_ALPHABET) for _ in range(4))
            g3 = "".join(secrets.choice(CROCKFORD_ALPHABET) for _ in range(4))
            raw_code = f"{g1}-{g2}-{g3}"

            code_hash = hmac.new(secret_bytes, raw_code.encode("utf-8"), hashlib.sha256).hexdigest()

            row = EnrollmentCode(
                id=uuid4(),
                study_id=site.study_id,
                site_id=site.id,
                code_hash=code_hash,
                expires_at=expires_at,
            )
            db.add(row)
            issued_codes.append(raw_code)

        db.commit()

        record_admin_audit(
            db,
            action="ENROLLMENT_CODES_ISSUED",
            target_type="site",
            target_id=str(site.id),
            details={"site_code": site.site_code, "count": count},
        )

        for code in issued_codes:
            print(code)
        return 0
    finally:
        db.close()


def handle_device_list(args: argparse.Namespace) -> int:
    db = SessionLocal()
    try:
        q = select(Device, Site.site_code).join(Site, Device.site_id == Site.id)
        if args.site:
            q = q.where(Site.site_code == args.site)
        rows = db.execute(q).all()

        record_admin_audit(
            db,
            action="DEVICE_LIST_VIEWED",
            details={"filter_site": args.site or None, "count": len(rows)},
        )

        print(f"{'DEVICE_ID':<38} {'SITE':<10} {'MODEL':<20} {'ATTESTATION':<12} {'QUALIFICATION':<14} {'LAST_SEEN':<12}")
        print("-" * 110)
        for dev, site_code in rows:
            last_seen = dev.last_seen_on.isoformat() if dev.last_seen_on else "never"
            print(f"{str(dev.id):<38} {site_code:<10} {dev.device_model:<20} {dev.attestation:<12} {dev.qualification:<14} {last_seen:<12}")
        return 0
    finally:
        db.close()


def handle_device_revoke(args: argparse.Namespace) -> int:
    db = SessionLocal()
    try:
        try:
            device_id = UUID(args.device_id)
        except Exception:
            print("Invalid device_id UUID", file=sys.stderr)
            return 1

        dev = db.execute(select(Device).where(Device.id == device_id)).scalar_one_or_none()
        if not dev:
            print(f"Device {device_id} not found", file=sys.stderr)
            return 1

        dev.revoked_at = datetime.datetime.now(datetime.UTC)
        dev.revoked_reason = args.reason
        db.commit()

        record_admin_audit(
            db,
            action="DEVICE_REVOKED",
            target_type="device",
            target_id=str(dev.id),
            details={"reason": args.reason},
        )
        print(f"Device {dev.id} revoked ({args.reason})")
        return 0
    finally:
        db.close()


def handle_status(args: argparse.Namespace) -> int:
    db = SessionLocal()
    try:
        today = datetime.datetime.now(datetime.UTC).date()
        cutoff = None
        if args.older_than:
            dur = parse_duration_to_timedelta(args.older_than)
            cutoff = today - datetime.timedelta(days=dur.days)

        q = select(DbSession).where(DbSession.status == "RECEIVING")
        if cutoff:
            q = q.where(DbSession.received_on <= cutoff)
        receiving_sessions = db.execute(q).scalars().all()

        record_admin_audit(
            db,
            action="STATUS_VIEWED",
            details={"older_than": args.older_than or None, "receiving_count": len(receiving_sessions)},
        )

        print(f"Sessions still RECEIVING: {len(receiving_sessions)}")
        for s in receiving_sessions:
            missing_count = db.execute(
                select(func.count(DbCapture.id)).where(
                    DbCapture.session_id == s.id,
                    DbCapture.has_image.is_(True),
                    DbCapture.verified.is_(False),
                )
            ).scalar_one()
            print(f"  Session {s.id} (device: {s.device_id}, received_on: {s.received_on}, missing: {missing_count})")
        return 0
    finally:
        db.close()


def handle_session_reject(args: argparse.Namespace) -> int:
    db = SessionLocal()
    try:
        try:
            sess_id = UUID(args.session_id)
        except Exception:
            print("Invalid session_id UUID", file=sys.stderr)
            return 1

        sess = db.execute(select(DbSession).where(DbSession.id == sess_id)).scalar_one_or_none()
        if not sess:
            print(f"Session {sess_id} not found", file=sys.stderr)
            return 1

        reason = args.reason or "DATA_ERROR"
        sess.status = "REJECTED"
        sess.rejected_reason = reason

        # Delete images on disk
        captures = db.execute(select(DbCapture).where(DbCapture.session_id == sess_id)).scalars().all()
        store_dir = Path(settings.IMAGE_STORE_DIR)
        for cap in captures:
            if cap.object_key:
                img_path = store_dir / cap.object_key
                img_path.unlink(missing_ok=True)
                cap.verified = False

        db.commit()

        record_admin_audit(
            db,
            action="SESSION_REJECTED",
            target_type="session",
            target_id=str(sess_id),
            details={"reason": reason},
        )
        print(f"Session {sess_id} marked REJECTED ({reason}); images deleted")
        return 0
    finally:
        db.close()


def handle_participant_withdraw(args: argparse.Namespace) -> int:
    db = get_purge_db()
    try:
        code = args.participant_code
        participant = db.execute(select(Participant).where(Participant.participant_code == code)).scalar_one_or_none()
        if not participant:
            print(f"Participant {code} not found", file=sys.stderr)
            return 1

        zone = db.execute(
            select(Study.time_zone).join(Site, Site.study_id == Study.id).where(Site.id == participant.site_id)
        ).scalar_one()
        participant.withdrawn_on = study_today(zone)  # the study's calendar day (doc 7 Section 1)

        # Reject all sessions and delete their images
        sessions = db.execute(select(DbSession).where(DbSession.participant_id == participant.id)).scalars().all()
        store_dir = Path(settings.IMAGE_STORE_DIR)
        for sess in sessions:
            sess.status = "REJECTED"
            sess.rejected_reason = "CONSENT_WITHDRAWN"
            captures = db.execute(select(DbCapture).where(DbCapture.session_id == sess.id)).scalars().all()
            for cap in captures:
                if cap.object_key:
                    img_path = store_dir / cap.object_key
                    img_path.unlink(missing_ok=True)
                    cap.verified = False

        # Delete TSB measurements
        db.execute(delete(TsbMeasurement).where(TsbMeasurement.participant_id == participant.id))
        db.commit()

        # Audit log must NEVER contain participant code!
        record_admin_audit(
            db,
            action="PARTICIPANT_WITHDRAWN",
            target_type="participant",
            target_id=str(participant.id),
            details={"session_count": len(sessions)},
        )
        print(f"Participant consent withdrawn; {len(sessions)} sessions rejected, images and TSB values deleted")
        return 0
    finally:
        db.close()


def handle_restore_mark(args: argparse.Namespace) -> int:
    db = SessionLocal()
    try:
        now_ts = int(time.time())
        studies = db.execute(select(Study)).scalars().all()
        for study in studies:
            study.data_generation = now_ts
        db.commit()

        record_admin_audit(
            db,
            action="RESTORE_MARKED",
            details={"data_generation": now_ts},
        )
        print(f"Data generation marked to Unix time {now_ts}")
        return 0
    finally:
        db.close()


def handle_selftest(args: argparse.Namespace) -> int:
    db = SessionLocal()
    try:
        # 1. DB reachable
        db.execute(text("SELECT 1"))

        # 2. Schema check with Alembic
        from alembic.config import Config
        from alembic.migration import MigrationContext
        from alembic.script import ScriptDirectory

        backend_dir = Path(__file__).resolve().parent.parent
        alembic_ini = backend_dir / "alembic.ini"
        if alembic_ini.exists():
            cfg = Config(str(alembic_ini))
            cfg.set_main_option("script_location", str(backend_dir / "alembic"))
            script = ScriptDirectory.from_config(cfg)
            heads = script.get_heads()

            context = MigrationContext.configure(db.connection())
            current_rev = context.get_current_revision()

            allow_newer = os.getenv("NEOGOLD_ALLOW_NEWER_SCHEMA", "0") in ("1", "true")
            if current_rev not in heads and not allow_newer:
                print(f"Self-test FAIL: Schema mismatch (db: {current_rev}, head: {heads})", file=sys.stderr)
                return 1

        # 3. Image store writable
        store_dir = Path(settings.IMAGE_STORE_DIR) / ".tmp"
        store_dir.mkdir(parents=True, exist_ok=True)
        probe = store_dir / f".selftest_{uuid4()}.tmp"
        try:
            with open(probe, "wb") as f:
                f.write(b"probe")
                f.flush()
                os.fsync(f.fileno())
            probe.unlink(missing_ok=True)
        except Exception as e:
            print(f"Self-test FAIL: Image store not writable: {e}", file=sys.stderr)
            return 1

        # 4. Backup check
        backup_dir = Path(os.getenv("BACKUP_DIR", "/srv/neogold/backup"))
        skip_backup = os.getenv("NEOGOLD_SKIP_BACKUP_CHECK", "0") in ("1", "true")
        if not skip_backup and backup_dir.exists():
            files = [f for f in backup_dir.iterdir() if f.is_file()]
            if files:
                newest = max(f.stat().st_mtime for f in files)
                age_hours = (time.time() - newest) / 3600.0
                if age_hours > 26.0:
                    print(f"Self-test FAIL: Last backup is {age_hours:.1f}h old (> 26h)", file=sys.stderr)
                    return 1

        # 5. The database roles hold the privileges of doc 5 Section 3.5 (a migration run before the bootstrap skips them)
        problems = privilege_problems(db)
        if problems:
            print("Self-test FAIL: database privileges are wrong: " + "; ".join(problems), file=sys.stderr)
            return 1

        record_admin_audit(
            db,
            action="SELFTEST_RUN",
            details={"status": "PASS"},
        )
        print("Self-test passed: DB reachable, schema verified, image store writable, backup checked.")
        return 0
    except Exception as e:
        print(f"Self-test FAIL: {e}", file=sys.stderr)
        return 1
    finally:
        db.close()


def handle_audit_since(args: argparse.Namespace) -> int:
    db = SessionLocal()
    try:
        since_str = args.time.strip()
        if since_str.endswith("Z"):
            since_str = since_str[:-1] + "+00:00"
        since_dt = datetime.datetime.fromisoformat(since_str)

        entries = (
            db.execute(select(AuditLog).where(AuditLog.occurred_at >= since_dt).order_by(AuditLog.occurred_at.asc()))
            .scalars()
            .all()
        )

        record_admin_audit(
            db,
            action="AUDIT_VIEWED",
            details={"since": args.time, "entries_found": len(entries)},
        )

        for entry in entries:
            dt_iso = entry.occurred_at.strftime("%Y-%m-%dT%H:%M:%SZ")
            print(f"{dt_iso} | {entry.actor_type} | {entry.actor_id} | {entry.action} | target={entry.target_type}:{entry.target_id} | {entry.details}")
        return 0
    finally:
        db.close()


def handle_export(args: argparse.Namespace) -> int:
    db = get_export_db()
    try:
        study = db.execute(select(Study).where(Study.study_code == args.study)).scalar_one_or_none()
        if not study:
            print(f"Study {args.study} not found", file=sys.stderr)
            return 1

        out_path = Path(args.out)
        out_path.mkdir(parents=True, exist_ok=True)
        images_out_dir = out_path / "images"
        images_out_dir.mkdir(parents=True, exist_ok=True)

        # Exclude test sites (is_test == True) and withdrawn participants (withdrawn_on is not None)
        participants = (
            db.execute(
                select(Participant, Site.site_code)
                .join(Site, Participant.site_id == Site.id)
                .where(
                    Site.study_id == study.id,
                    Site.is_test.is_(False),
                    Participant.withdrawn_on.is_(None),
                )
            )
            .all()
        )

        # Compute deterministic date shift per participant (-180 to +180 days)
        # Keeps intervals intact while protecting exact timing (doc 7 Section 7, doc 8 Section 3)
        part_shifts: dict[UUID, datetime.timedelta] = {}
        part_anon_ids: dict[UUID, str] = {}
        for idx, (p, site_code) in enumerate(participants, 1):
            seed_val = int(hashlib.sha256(str(p.id).encode("utf-8")).hexdigest()[:8], 16)
            rng = random.Random(seed_val)  # nosec B311
            offset = rng.randint(-180, 180)
            if offset == 0:
                offset = 1
            part_shifts[p.id] = datetime.timedelta(days=offset)
            part_anon_ids[p.id] = f"P{idx:05d}"

        valid_part_ids = list(part_shifts.keys())

        # 1. participants.csv
        p_file = out_path / "participants.csv"
        with open(p_file, "w", newline="", encoding="utf-8") as f:
            writer = csv.writer(f)
            writer.writerow(["deidentified_id", "site_code", "consent_date", "created_on"])
            for p, site_code in participants:
                delta = part_shifts[p.id]
                shifted_consent = (p.consent_date + delta).isoformat()
                shifted_created = (p.created_on + delta).isoformat()
                writer.writerow([part_anon_ids[p.id], site_code, shifted_consent, shifted_created])

        # 2. sessions.csv
        sessions = (
            db.execute(
                select(DbSession).where(
                    DbSession.participant_id.in_(valid_part_ids),
                    DbSession.status != "REJECTED",
                )
            )
            .scalars()
            .all()
        )
        s_file = out_path / "sessions.csv"
        valid_session_ids = []
        with open(s_file, "w", newline="", encoding="utf-8") as f:
            writer = csv.writer(f)
            writer.writerow([
                "session_id", "deidentified_participant_id", "card_status", "card_batch_id",
                "capture_date", "age_hours", "gestational_age_weeks", "flag_jaundice_under_24h",
                "flag_danger_signs", "flag_prior_phototherapy", "estimate_mg_dl", "estimate_low_mg_dl",
                "estimate_high_mg_dl", "app_version", "model_version", "config_version", "status",
                "received_on", "completed_on",
            ])
            for s in sessions:
                valid_session_ids.append(s.id)
                delta = part_shifts[s.participant_id]
                shifted_cap_date = (s.capture_date + delta).isoformat()
                shifted_recv = (s.received_on + delta).isoformat()
                shifted_comp = (s.completed_on + delta).isoformat() if s.completed_on else ""
                writer.writerow([
                    str(s.id), part_anon_ids[s.participant_id], s.card_status, s.card_batch_id or "",
                    shifted_cap_date, str(s.age_hours), s.gestational_age_weeks, s.flag_jaundice_under_24h,
                    s.flag_danger_signs, s.flag_prior_phototherapy, str(s.estimate_mg_dl or ""),
                    str(s.estimate_low_mg_dl or ""), str(s.estimate_high_mg_dl or ""), s.app_version,
                    s.model_version or "", s.config_version, s.status, shifted_recv, shifted_comp,
                ])

        # 3. captures.csv and copy images
        captures = (
            db.execute(select(DbCapture).where(DbCapture.session_id.in_(valid_session_ids))).scalars().all()
            if valid_session_ids
            else []
        )
        c_file = out_path / "captures.csv"
        store_dir = Path(settings.IMAGE_STORE_DIR)
        with open(c_file, "w", newline="", encoding="utf-8") as f:
            writer = csv.writer(f)
            writer.writerow([
                "capture_id", "session_id", "attempt_number", "site", "quality_status",
                "quality_fail_reasons", "correction_residual_de", "size_bytes", "verified", "image_file",
            ])
            for cap in captures:
                img_dest_name = ""
                if cap.has_image and cap.verified and cap.object_key:
                    src_img = store_dir / cap.object_key
                    if src_img.exists():
                        img_dest_name = f"{cap.id}.jpg"
                        shutil.copyfile(src_img, images_out_dir / img_dest_name)
                writer.writerow([
                    str(cap.id), str(cap.session_id), cap.attempt_number, cap.site, cap.quality_status,
                    json_str(cap.quality_fail_reasons), str(cap.correction_residual_de or ""),
                    cap.size_bytes or "", cap.verified, img_dest_name,
                ])

        # 4. tsb_measurements.csv
        tsbs = (
            db.execute(select(TsbMeasurement).where(TsbMeasurement.participant_id.in_(valid_part_ids))).scalars().all()
            if valid_part_ids
            else []
        )
        t_file = out_path / "tsb_measurements.csv"
        with open(t_file, "w", newline="", encoding="utf-8") as f:
            writer = csv.writer(f)
            writer.writerow([
                "tsb_id", "deidentified_participant_id", "session_id", "tsb_mg_dl",
                "minutes_from_capture", "method", "received_on", "linked_on",
            ])
            for tsb in tsbs:
                delta = part_shifts[tsb.participant_id]
                shifted_recv = (tsb.received_on + delta).isoformat()
                shifted_linked = (tsb.linked_on + delta).isoformat() if tsb.linked_on else ""
                writer.writerow([
                    str(tsb.id), part_anon_ids[tsb.participant_id], str(tsb.session_id or ""),
                    str(tsb.tsb_mg_dl), tsb.minutes_from_capture or "", tsb.method,
                    shifted_recv, shifted_linked,
                ])

        # 5. SHA-256 manifest
        manifest_file = out_path / "manifest.sha256"
        manifest_lines = []
        for root, _dirs, files in os.walk(out_path):
            for file_name in sorted(files):
                if file_name == "manifest.sha256":
                    continue
                full_f = Path(root) / file_name
                rel_f = full_f.relative_to(out_path).as_posix()
                with open(full_f, "rb") as f_in:
                    f_hash = hashlib.sha256(f_in.read()).hexdigest()
                manifest_lines.append(f"{f_hash}  {rel_f}\n")

        with open(manifest_file, "w", encoding="utf-8") as f:
            f.writelines(manifest_lines)

        manifest_digest = hashlib.sha256("".join(manifest_lines).encode("utf-8")).hexdigest()

        # Record ExportJob
        job = ExportJob(
            study_id=study.id,
            requested_by="neogold-admin",
            filters={"exclude_test_sites": True, "exclude_withdrawn": True, "date_shifted": True},
            status="DONE",
            output_path=str(out_path),
            output_sha256=manifest_digest,
            finished_at=datetime.datetime.now(datetime.UTC),
        )
        db.add(job)
        db.commit()

        record_admin_audit(
            db,
            action="EXPORT_CREATED",
            target_type="export_job",
            target_id=str(job.id),
            details={"study_code": study.study_code, "output_path": str(out_path), "participants": len(participants)},
        )
        print(f"Export created at {out_path} (manifest SHA-256: {manifest_digest})")
        return 0
    finally:
        db.close()


def json_str(val: Any) -> str:
    import json
    return json.dumps(val) if val is not None else ""


def handle_retention_check(args: argparse.Namespace) -> int:
    db = SessionLocal()
    try:
        q = select(Study)
        if args.study:
            q = q.where(Study.study_code == args.study)
        study = db.execute(q).scalar_one_or_none()
        if not study:
            print("Study not found", file=sys.stderr)
            return 1

        today = datetime.datetime.now(datetime.UTC).date()
        expired = today > study.retention_until

        expired_sessions = 0
        if expired:
            expired_sessions = db.execute(
                select(func.count(DbSession.id))
                .join(Participant, DbSession.participant_id == Participant.id)
                .join(Site, Participant.site_id == Site.id)
                .where(Site.study_id == study.id)
            ).scalar_one()

        record_admin_audit(
            db,
            action="RETENTION_CHECKED",
            target_type="study",
            target_id=str(study.id),
            details={"retention_until": study.retention_until.isoformat(), "expired": expired, "expired_sessions": expired_sessions},
        )

        if expired:
            print(f"WARNING: Study {study.study_code} retention date was {study.retention_until.isoformat()} (EXPIRED).")
            print(f"  {expired_sessions} sessions eligible for purge.")
        else:
            print(f"Study {study.study_code} retention date is {study.retention_until.isoformat()} (active).")
        return 0
    finally:
        db.close()


def handle_retention_purge(args: argparse.Namespace) -> int:
    if not args.confirm:
        print("Error: retention purge requires --confirm flag to proceed", file=sys.stderr)
        return 1

    db = get_purge_db()
    try:
        q = select(Study)
        if args.study:
            q = q.where(Study.study_code == args.study)
        study = db.execute(q).scalar_one_or_none()
        if not study:
            print("Study not found", file=sys.stderr)
            return 1

        today = datetime.datetime.now(datetime.UTC).date()
        if today <= study.retention_until:
            print(f"Study {study.study_code} retention date {study.retention_until} has not passed; nothing to purge.")
            record_admin_audit(
                db,
                action="RETENTION_PURGED",
                target_type="study",
                target_id=str(study.id),
                details={"purged_participants": 0, "purged_sessions": 0, "reason": "retention_date_active"},
            )
            return 0

        # Purge all sessions, images, captures, tsb, participants for this study
        participants = (
            db.execute(select(Participant).join(Site, Participant.site_id == Site.id).where(Site.study_id == study.id))
            .scalars()
            .all()
        )
        part_ids = [p.id for p in participants]

        sessions = (
            db.execute(select(DbSession).where(DbSession.participant_id.in_(part_ids))).scalars().all()
            if part_ids
            else []
        )
        sess_ids = [s.id for s in sessions]

        captures = (
            db.execute(select(DbCapture).where(DbCapture.session_id.in_(sess_ids))).scalars().all()
            if sess_ids
            else []
        )
        store_dir = Path(settings.IMAGE_STORE_DIR)
        for cap in captures:
            if cap.object_key:
                img_path = store_dir / cap.object_key
                img_path.unlink(missing_ok=True)

        if sess_ids:
            db.execute(delete(DbCapture).where(DbCapture.session_id.in_(sess_ids)))
            db.execute(delete(DbSession).where(DbSession.id.in_(sess_ids)))

        if part_ids:
            db.execute(delete(TsbMeasurement).where(TsbMeasurement.participant_id.in_(part_ids)))
            db.execute(delete(ParticipantAlias).where(ParticipantAlias.participant_id.in_(part_ids)))
            db.execute(delete(Participant).where(Participant.id.in_(part_ids)))

        db.commit()

        record_admin_audit(
            db,
            action="RETENTION_PURGED",
            target_type="study",
            target_id=str(study.id),
            details={"purged_participants": len(part_ids), "purged_sessions": len(sess_ids)},
        )
        print(f"Purged {len(sess_ids)} sessions and {len(part_ids)} participants past retention date {study.retention_until}")
        return 0
    finally:
        db.close()


def handle_decrypt_export(args: argparse.Namespace) -> int:
    db = SessionLocal()
    try:
        record_admin_audit(
            db,
            action="DECRYPT_EXPORT",
            details={"file": str(args.file)},
        )
        print(f"decrypt-export called on file: {args.file} (offline tool stub)")
        return 0
    finally:
        db.close()


# ---------------------------------------------------------
# CLI Parser Setup
# ---------------------------------------------------------


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(prog="neogold-admin", description="NeoGold Study Intake Admin CLI")
    subparsers = parser.add_subparsers(dest="command", required=True)

    # study
    study_parser = subparsers.add_parser("study", help="Study operations")
    study_sub = study_parser.add_subparsers(dest="study_command", required=True)

    s_create = study_sub.add_parser("create", help="Create a study")
    s_create.add_argument("--code", required=True, help="Study code (e.g. SKP)")
    s_create.add_argument("--name", required=True, help="Study name")
    s_create.add_argument("--partner", required=True, help="Partner institution")
    s_create.add_argument("--ethics-ref", required=True, help="Ethics reference")
    s_create.add_argument("--retention-until", required=True, help="Retention until date YYYY-MM-DD")
    s_create.add_argument("--timestamp-precision", choices=["DAY_PLUS_OFFSETS", "FULL"], default="DAY_PLUS_OFFSETS")
    s_create.add_argument("--time-zone", default="Asia/Dhaka")
    s_create.add_argument("--min-app-version", default="1.0.0")

    s_pause = study_sub.add_parser("pause", help="Pause intake")
    s_pause.add_argument("--code", required=False)

    s_resume = study_sub.add_parser("resume", help="Resume intake")
    s_resume.add_argument("--code", required=False)

    s_close = study_sub.add_parser("close", help="Close intake")
    s_close.add_argument("--code", required=False)

    s_min = study_sub.add_parser("set-min-app-version", help="Set minimum app version")
    s_min.add_argument("version", help="Semver version (e.g. 1.0.2)")
    s_min.add_argument("--code", required=False)

    # site
    site_parser = subparsers.add_parser("site", help="Site operations")
    site_sub = site_parser.add_subparsers(dest="site_command", required=True)
    site_add = site_sub.add_parser("add", help="Add a site")
    site_add.add_argument("--study", required=True, help="Study code")
    site_add.add_argument("--code", required=True, help="Site code")
    site_add.add_argument("--name", required=True, help="Site name")
    site_add.add_argument("--test", action="store_true", help="Mark site as test site")

    # code
    code_parser = subparsers.add_parser("code", help="Code operations")
    code_sub = code_parser.add_subparsers(dest="code_command", required=True)
    c_issue = code_sub.add_parser("issue", help="Issue enrollment codes")
    c_issue.add_argument("--site", required=True, help="Site code")
    c_issue.add_argument("--count", type=int, default=1, help="Number of codes to issue")
    c_issue.add_argument("--expires", default="72h", help="Expiry duration (e.g. 72h)")

    # device
    dev_parser = subparsers.add_parser("device", help="Device operations")
    dev_sub = dev_parser.add_subparsers(dest="device_command", required=True)
    d_list = dev_sub.add_parser("list", help="List enrolled devices")
    d_list.add_argument("--site", required=False, help="Filter by site code")
    d_revoke = dev_sub.add_parser("revoke", help="Revoke device")
    d_revoke.add_argument("device_id", help="Device UUID")
    d_revoke.add_argument("--reason", required=True, choices=["LOST", "STOLEN", "REASSIGNED", "COMPROMISED", "STUDY_ENDED"])

    # status
    st_parser = subparsers.add_parser("status", help="Session upload and device status")
    st_parser.add_argument("--older-than", required=False, help="Filter sessions older than (e.g. 7d)")

    # session
    sess_parser = subparsers.add_parser("session", help="Session operations")
    sess_sub = sess_parser.add_subparsers(dest="session_command", required=True)
    s_reject = sess_sub.add_parser("reject", help="Reject a session")
    s_reject.add_argument("session_id", help="Session UUID")
    s_reject.add_argument("--reason", choices=["DATA_ERROR", "CONSENT_WITHDRAWN"], default="DATA_ERROR")

    # participant
    part_parser = subparsers.add_parser("participant", help="Participant operations")
    part_sub = part_parser.add_subparsers(dest="participant_command", required=True)
    p_with = part_sub.add_parser("withdraw", help="Withdraw participant consent")
    p_with.add_argument("participant_code", help="Participant code (e.g. SKP-0142)")

    # restore
    rest_parser = subparsers.add_parser("restore", help="Restore operations")
    rest_sub = rest_parser.add_subparsers(dest="restore_command", required=True)
    rest_sub.add_parser("mark", help="Mark restore generation")

    # selftest
    subparsers.add_parser("selftest", help="Run deployment smoke self-test")

    # audit
    audit_parser = subparsers.add_parser("audit", help="Audit log operations")
    audit_sub = audit_parser.add_subparsers(dest="audit_command", required=True)
    a_since = audit_sub.add_parser("since", help="List actions since time")
    a_since.add_argument("time", help="ISO-8601 timestamp")

    # decrypt-export
    dec_parser = subparsers.add_parser("decrypt-export", help="Decrypt phone export")
    dec_parser.add_argument("--key", required=True, help="Study export private key")
    dec_parser.add_argument("file", help="Export file")

    # export
    exp_parser = subparsers.add_parser("export", help="Export de-identified data")
    exp_parser.add_argument("--study", required=True, help="Study code")
    exp_parser.add_argument("--out", required=True, help="Output directory")

    # retention
    ret_parser = subparsers.add_parser("retention", help="Retention operations")
    ret_sub = ret_parser.add_subparsers(dest="retention_command", required=True)
    r_check = ret_sub.add_parser("check", help="Check retention status")
    r_check.add_argument("--study", required=False)
    r_purge = ret_sub.add_parser("purge", help="Purge data past retention")
    r_purge.add_argument("--confirm", action="store_true", help="Confirm deletion")
    r_purge.add_argument("--study", required=False)

    return parser


def main(argv: list[str] | None = None) -> int:
    parser = build_parser()
    args = parser.parse_args(argv)

    if args.command == "study":
        if args.study_command == "create":
            return handle_study_create(args)
        elif args.study_command == "pause":
            return handle_study_pause(args)
        elif args.study_command == "resume":
            return handle_study_resume(args)
        elif args.study_command == "close":
            return handle_study_close(args)
        elif args.study_command == "set-min-app-version":
            return handle_study_set_min_app_version(args)
    elif args.command == "site":
        if args.site_command == "add":
            return handle_site_add(args)
    elif args.command == "code":
        if args.code_command == "issue":
            return handle_code_issue(args)
    elif args.command == "device":
        if args.device_command == "list":
            return handle_device_list(args)
        elif args.device_command == "revoke":
            return handle_device_revoke(args)
    elif args.command == "status":
        return handle_status(args)
    elif args.command == "session":
        if args.session_command == "reject":
            return handle_session_reject(args)
    elif args.command == "participant":
        if args.participant_command == "withdraw":
            return handle_participant_withdraw(args)
    elif args.command == "restore":
        if args.restore_command == "mark":
            return handle_restore_mark(args)
    elif args.command == "selftest":
        return handle_selftest(args)
    elif args.command == "audit":
        if args.audit_command == "since":
            return handle_audit_since(args)
    elif args.command == "decrypt-export":
        return handle_decrypt_export(args)
    elif args.command == "export":
        return handle_export(args)
    elif args.command == "retention":
        if args.retention_command == "check":
            return handle_retention_check(args)
        elif args.retention_command == "purge":
            return handle_retention_purge(args)

    return 0


if __name__ == "__main__":
    sys.exit(main())
