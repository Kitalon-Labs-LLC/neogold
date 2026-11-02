"""Disposable in-process FastAPI + SQLAlchemy harness for P27 Breaker attacks.

Always runs `breaker/env/guard.sh` first. Connects to `BREAKER_DATABASE_URL` when a
disposable Postgres instance (`neogold-breaker`) is running, or spins up an isolated
disposable SQLite database (with full PostgreSQL constraint and type compatibility)
inside a temporary directory that is wiped on teardown.
"""

from __future__ import annotations

import datetime
import json
import os
import shutil
import subprocess
import sys
import tempfile
import uuid
from contextlib import contextmanager
from dataclasses import dataclass
from pathlib import Path
from typing import Iterator

REPO_ROOT = Path(__file__).resolve().parents[2]
BACKEND_DIR = REPO_ROOT / "backend"
GUARD_SH = Path(__file__).resolve().parent / "guard.sh"

if str(BACKEND_DIR) not in sys.path:
    sys.path.insert(0, str(BACKEND_DIR))

if str(REPO_ROOT) not in sys.path:
    sys.path.insert(0, str(REPO_ROOT))

os.environ.setdefault("ENROLLMENT_CODE_SECRET", "breaker-only-" + "0123456789abcdef" * 3)
os.environ.setdefault("NEOGOLD_SKIP_BACKUP_CHECK", "1")


def resolve_bash_bin() -> str:
    """Prefer Git Bash on Windows over the System32 WSL bash stub."""
    if os.name == "nt":
        for candidate in (
            r"C:\Program Files\Git\bin\bash.exe",
            r"C:\Program Files\Git\usr\bin\bash.exe",
        ):
            if Path(candidate).exists():
                return candidate
    return shutil.which("bash") or "bash"


def run_guard(api_url: str = "http://127.0.0.1:18000/v1") -> None:
    """Invoke `breaker/env/guard.sh` before any attack or harness setup."""
    bash_bin = resolve_bash_bin()
    proc = subprocess.run(
        [bash_bin, GUARD_SH.as_posix(), "--api-url", api_url],
        capture_output=True,
        text=True,
        check=False,
    )
    if proc.returncode != 0:
        raise RuntimeError(
            f"breaker/env/guard.sh refused execution (exit {proc.returncode}): "
            f"{proc.stderr.strip() or proc.stdout.strip()}"
        )


def _install_sqlite_compat() -> None:
    """Register SQLite dialect compilers and type adapters for Postgres column types."""
    import sqlite3
    from sqlalchemy import BigInteger, DateTime
    from sqlalchemy.dialects.postgresql import ARRAY, INET, JSONB, UUID as PG_UUID
    from sqlalchemy.dialects.sqlite.base import DATETIME as SQLiteDateTime, SQLiteTypeCompiler
    from sqlalchemy.ext.compiler import compiles

    sqlite3.register_adapter(uuid.UUID, lambda u: str(u))
    sqlite3.register_adapter(dict, lambda d: json.dumps(d))
    sqlite3.register_adapter(list, lambda lst: json.dumps([str(x) if isinstance(x, uuid.UUID) else x for x in lst]))

    if not hasattr(SQLiteTypeCompiler, "_breaker_patched"):
        for dt_cls in (DateTime, SQLiteDateTime):
            orig_dt_result = dt_cls.result_processor

            def _make_dt_result(orig):
                def _patched_dt_result(self, dialect, coltype):
                    base_proc = orig(self, dialect, coltype)
                    if dialect.name == "sqlite":
                        def _process(value):
                            res = base_proc(value) if base_proc else value
                            if isinstance(res, str):
                                res = datetime.datetime.fromisoformat(res)
                            if isinstance(res, datetime.datetime) and res.tzinfo is None:
                                return res.replace(tzinfo=datetime.UTC)
                            return res
                        return _process
                    return base_proc
                return _patched_dt_result

            dt_cls.result_processor = _make_dt_result(orig_dt_result)  # type: ignore[method-assign]
        @compiles(JSONB, "sqlite")
        def _compile_jsonb(element, compiler, **kw):
            return "JSON"

        @compiles(INET, "sqlite")
        def _compile_inet(element, compiler, **kw):
            return "VARCHAR(64)"

        @compiles(ARRAY, "sqlite")
        def _compile_array(element, compiler, **kw):
            return "JSON"

        @compiles(PG_UUID, "sqlite")
        def _compile_uuid(element, compiler, **kw):
            return "CHAR(36)"

        @compiles(BigInteger, "sqlite")
        def _compile_bigint(element, compiler, **kw):
            return "INTEGER"

        orig_uuid_bind = PG_UUID.bind_processor
        orig_uuid_result = PG_UUID.result_processor

        def _patched_uuid_bind(self, dialect):
            if dialect.name == "sqlite":
                return lambda value: str(value) if value is not None else None
            return orig_uuid_bind(self, dialect)

        def _patched_uuid_result(self, dialect, coltype):
            if dialect.name == "sqlite":
                def _process(value):
                    if value is None or isinstance(value, uuid.UUID):
                        return value
                    return uuid.UUID(str(value))
                return _process
            return orig_uuid_result(self, dialect, coltype)

        PG_UUID.bind_processor = _patched_uuid_bind  # type: ignore[method-assign]
        PG_UUID.result_processor = _patched_uuid_result  # type: ignore[method-assign]

        def _patched_array_bind(self, dialect):
            if dialect.name == "sqlite":
                def _process(value):
                    if value is None:
                        return None
                    return json.dumps([str(v) for v in value])
                return _process
            return None

        def _patched_array_result(self, dialect, coltype):
            if dialect.name == "sqlite":
                def _process(value):
                    if value is None:
                        return None
                    raw = json.loads(value) if isinstance(value, str) else value
                    out = []
                    for item in raw:
                        try:
                            out.append(uuid.UUID(str(item)))
                        except ValueError:
                            out.append(item)
                    return out
                return _process
            return None

        ARRAY.bind_processor = _patched_array_bind  # type: ignore[method-assign]
        ARRAY.result_processor = _patched_array_result  # type: ignore[method-assign]

        SQLiteTypeCompiler._breaker_patched = True  # type: ignore[attr-defined]


@dataclass
class DisposableEnvironment:
    client: object  # fastapi.testclient.TestClient
    session_factory: object
    temp_dir: Path
    image_store_dir: Path
    export_store_dir: Path
    db_url: str


@contextmanager
def disposable_breaker_env(api_url: str = "http://127.0.0.1:18000/v1") -> Iterator[DisposableEnvironment]:
    """Create a disposable copy of the backend stack with synthetic storage and DB."""
    run_guard(api_url)
    _install_sqlite_compat()

    temp_root = Path(tempfile.mkdtemp(prefix="neogold-breaker-env-"))
    image_dir = temp_root / "images"
    export_dir = temp_root / "exports"
    (image_dir / ".tmp").mkdir(parents=True, exist_ok=True)
    export_dir.mkdir(parents=True, exist_ok=True)

    db_path = temp_root / "breaker.sqlite3"
    db_url = os.environ.get("BREAKER_DATABASE_URL") or f"sqlite:///{db_path.as_posix()}"

    from sqlalchemy import create_engine, event
    from sqlalchemy.orm import sessionmaker

    import app.database as app_db
    from app.config import settings
    from app.main import app
    from app.models.db import Base

    old_image_dir = settings.IMAGE_STORE_DIR
    settings.IMAGE_STORE_DIR = str(image_dir)
    os.environ["IMAGE_STORE_DIR"] = str(image_dir)
    os.environ["EXPORT_STORE_DIR"] = str(export_dir)

    connect_args = {"check_same_thread": False} if db_url.startswith("sqlite") else {}
    engine = create_engine(db_url, connect_args=connect_args, hide_parameters=True)

    if db_url.startswith("sqlite"):
        @event.listens_for(engine, "connect")
        def _on_sqlite_connect(dbapi_conn, _connection_record):
            dbapi_conn.create_function(
                "now",
                0,
                lambda: datetime.datetime.now(datetime.UTC).isoformat(),
            )
            cursor = dbapi_conn.cursor()
            cursor.execute("PRAGMA foreign_keys=ON")
            cursor.close()

    Base.metadata.create_all(bind=engine)
    app_db.engine = engine
    app_db.SessionLocal.configure(bind=engine)
    session_factory = sessionmaker(autocommit=False, autoflush=False, bind=engine)

    from fastapi.testclient import TestClient

    try:
        with TestClient(app, raise_server_exceptions=False) as client:
            yield DisposableEnvironment(
                client=client,
                session_factory=session_factory,
                temp_dir=temp_root,
                image_store_dir=image_dir,
                export_store_dir=export_dir,
                db_url=db_url,
            )
    finally:
        engine.dispose()
        settings.IMAGE_STORE_DIR = old_image_dir
        shutil.rmtree(temp_root, ignore_errors=True)
