from __future__ import annotations

import base64
import datetime
import hashlib
import hmac
import json
import os
import sys
from pathlib import Path
from uuid import UUID, uuid4

import pytest
from cryptography import x509
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec, utils
from cryptography.x509.oid import NameOID
from fastapi.testclient import TestClient

# Ensure backend is on sys.path
backend_dir = Path(__file__).resolve().parent.parent
if str(backend_dir) not in sys.path:
    sys.path.insert(0, str(backend_dir))

from app.config import settings
from app.database import SessionLocal
from app.main import app
from app.models.db import EnrollmentCode, Site, Study
from app.security.jwk import (
    base64url_encode,
    compute_bsh,
)


def pytest_addoption(parser):
    # Used by the schema-compat CI job (doc 12 Section 2, TC-OPS-04): a later release runs
    # this release's tests against its newer, already-migrated schema. Older code cannot
    # migrate a schema whose head it does not know, so migration must be skipped.
    parser.addoption(
        "--no-migrate",
        action="store_true",
        default=False,
        help="Do not run 'alembic upgrade head'; use the schema already in DATABASE_URL.",
    )


@pytest.fixture(scope="session")
def postgres_url():
    url = os.getenv("DATABASE_URL")
    if url:
        yield url
    else:
        from testcontainers.postgres import PostgresContainer
        with PostgresContainer("postgres:16") as pg:
            conn_url = pg.get_connection_url().replace("+psycopg2", "")
            os.environ["DATABASE_URL"] = conn_url
            yield conn_url


@pytest.fixture(scope="session", autouse=True)
def setup_image_store(tmp_path_factory):
    configured = os.getenv("IMAGE_STORE_DIR", settings.IMAGE_STORE_DIR)
    store_path = Path(configured)
    try:
        (store_path / ".tmp").mkdir(parents=True, exist_ok=True)
    except (PermissionError, OSError):
        temp_store = tmp_path_factory.mktemp("image_store")
        (temp_store / ".tmp").mkdir(parents=True, exist_ok=True)
        os.environ["IMAGE_STORE_DIR"] = str(temp_store)
        settings.IMAGE_STORE_DIR = str(temp_store)
    os.environ.setdefault("NEOGOLD_SKIP_BACKUP_CHECK", "1")
    yield


@pytest.fixture(scope="session", autouse=True)
def setup_database(postgres_url, request):
    import subprocess
    if not request.config.getoption("--no-migrate"):
        env = os.environ.copy()
        env["DATABASE_URL"] = postgres_url
        subprocess.run(
            [sys.executable, "-m", "alembic", "upgrade", "head"],
            cwd=str(backend_dir),
            env=env,
            check=True,
        )
    from app.database import configure_engine
    configure_engine(postgres_url)
    yield



@pytest.fixture
def db():
    session = SessionLocal()
    try:
        yield session
    finally:
        session.rollback()
        session.close()


@pytest.fixture
def client():
    with TestClient(app) as c:
        yield c


@pytest.fixture
def test_study_and_site(db):
    study = db.query(Study).filter_by(study_code="SKP").first()
    if not study:
        study = Study(
            id=uuid4(),
            study_code="SKP",
            name="NeoGold Clinical Study",
            partner_institution="ICDDR,B",
            ethics_reference="ETH-2026-001",
            timestamp_precision="DAY_PLUS_OFFSETS",
            retention_until=datetime.date(2029, 12, 31),
            time_zone="Asia/Dhaka",
            min_app_version="1.0.0",
            wifi_only=True,
            status="ACTIVE",
        )
        db.add(study)
        db.commit()
    else:
        study.status = "ACTIVE"
        study.min_app_version = "1.0.0"
        db.commit()

    site = db.query(Site).filter_by(study_id=study.id, site_code="SKP01").first()
    if not site:
        site = Site(
            id=uuid4(),
            study_id=study.id,
            site_code="SKP01",
            name="Dhaka Medical College Hospital",
        )
        db.add(site)
        db.commit()

    return study, site


def mint_device_key() -> tuple[ec.EllipticCurvePrivateKey, dict]:
    privkey = ec.generate_private_key(ec.SECP256R1())
    pub_numbers = privkey.public_key().public_numbers()
    x = base64url_encode(pub_numbers.x.to_bytes(32, "big"))
    y = base64url_encode(pub_numbers.y.to_bytes(32, "big"))
    jwk = {"kty": "EC", "crv": "P-256", "x": x, "y": y}
    return privkey, jwk


def mint_device_token(
    privkey: ec.EllipticCurvePrivateKey,
    device_id: str | UUID,
    method: str,
    path: str,
    body_bytes: bytes = b"",
    iat: int | None = None,
    exp: int | None = None,
    jti: str | UUID | None = None,
    aud: str | None = None,
    htm: str | None = None,
    htu: str | None = None,
    bsh: str | None = None,
    typ: str = "ng-device+jwt",
    alg: str = "ES256",
    kid: str | None = None,
) -> str:
    now = int(datetime.datetime.now(datetime.UTC).timestamp())
    if iat is None:
        iat = now
    if exp is None:
        exp = iat + 120
    if jti is None:
        jti = str(uuid4())
    if aud is None:
        aud = settings.PUBLIC_BASE_URL
    if htm is None:
        htm = method.upper()
    if htu is None:
        clean_path = path
        clean_path = clean_path.removeprefix("/v1")
        base = settings.PUBLIC_BASE_URL.rstrip("/")
        htu = f"{base}{clean_path}" if base.endswith("/v1") else f"{base}/v1{clean_path}"
    if bsh is None:
        bsh = compute_bsh(body_bytes)
    if kid is None:
        kid = str(device_id)

    header = {"alg": alg, "typ": typ, "kid": kid}
    claims = {
        "iss": str(device_id),
        "aud": aud,
        "iat": iat,
        "exp": exp,
        "jti": str(jti),
        "htm": htm,
        "htu": htu,
        "bsh": bsh,
    }

    hdr_b64 = base64url_encode(json.dumps(header, separators=(",", ":")).encode("utf-8"))
    payload_b64 = base64url_encode(json.dumps(claims, separators=(",", ":")).encode("utf-8"))
    signing_input = f"{hdr_b64}.{payload_b64}".encode("ascii")

    der_sig = privkey.sign(signing_input, ec.ECDSA(hashes.SHA256()))
    r, s = utils.decode_dss_signature(der_sig)
    sig_bytes = r.to_bytes(32, "big") + s.to_bytes(32, "big")
    sig_b64 = base64url_encode(sig_bytes)

    return f"{hdr_b64}.{payload_b64}.{sig_b64}"


def create_enrollment_code(
    db,
    study_id: UUID,
    site_id: UUID,
    code_str: str = "K7QM-3XRA-9TPD",
    expires_delta: datetime.timedelta = datetime.timedelta(hours=72),
) -> tuple[EnrollmentCode, str]:
    code_hash = hmac.new(
        settings.ENROLLMENT_CODE_SECRET.encode("utf-8"),
        code_str.encode("utf-8"),
        hashlib.sha256,
    ).hexdigest()

    now = datetime.datetime.now(datetime.UTC)
    code_row = EnrollmentCode(
        id=uuid4(),
        study_id=study_id,
        site_id=site_id,
        code_hash=code_hash,
        expires_at=now + expires_delta,
        created_at=now,
    )
    db.add(code_row)
    db.commit()
    return code_row, code_str


def build_test_attestation_chain(
    leaf_privkey: ec.EllipticCurvePrivateKey,
    challenge: bytes,
    match_key: bool = True,
) -> list[str]:
    """Generates an X.509 cert chain with Key Attestation extension containing challenge."""
    if match_key:
        cert_pubkey = leaf_privkey.public_key()
    else:
        other_key = ec.generate_private_key(ec.SECP256R1())
        cert_pubkey = other_key.public_key()

    # Build Key Attestation DER sequence:
    # KeyDescription ::= SEQUENCE {
    #   INTEGER (version),
    #   ENUMERATED/INTEGER (securityLevel),
    #   INTEGER (keymasterVersion),
    #   ENUMERATED/INTEGER (securityLevel),
    #   OCTET STRING (attestationChallenge),
    #   OCTET STRING (uniqueId)
    # }
    seq_items = bytearray()
    # 1. attestationVersion = 3 (INTEGER)
    seq_items.extend(b"\x02\x01\x03")
    # 2. attestationSecurityLevel = 1 (ENUMERATED)
    seq_items.extend(b"\x0a\x01\x01")
    # 3. keymasterVersion = 4 (INTEGER)
    seq_items.extend(b"\x02\x01\x04")
    # 4. keymasterSecurityLevel = 1 (ENUMERATED)
    seq_items.extend(b"\x0a\x01\x01")
    # 5. attestationChallenge (OCTET STRING)
    seq_items.extend(b"\x04" + bytes([len(challenge)]) + challenge)
    # 6. uniqueId (OCTET STRING empty)
    seq_items.extend(b"\x04\x00")

    seq_der = b"\x30" + bytes([len(seq_items)]) + seq_items

    subject = issuer = x509.Name([
        x509.NameAttribute(NameOID.COMMON_NAME, "Android Key Attestation Leaf"),
    ])
    now = datetime.datetime.now(datetime.UTC)
    cert = (
        x509.CertificateBuilder()
        .subject_name(subject)
        .issuer_name(issuer)
        .public_key(cert_pubkey)
        .serial_number(x509.random_serial_number())
        .not_valid_before(now - datetime.timedelta(days=1))
        .not_valid_after(now + datetime.timedelta(days=10))
        .add_extension(
            x509.UnrecognizedExtension(
                x509.ObjectIdentifier("1.3.6.1.4.1.11129.2.1.17"),
                bytes(seq_der),
            ),
            critical=False,
        )
        .sign(leaf_privkey, hashes.SHA256())
    )

    cert_b64 = base64.b64encode(cert.public_bytes(serialization.Encoding.DER)).decode("ascii")
    return [cert_b64]


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

