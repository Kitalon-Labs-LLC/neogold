from __future__ import annotations

import importlib.util
from pathlib import Path

from sqlalchemy import text

from app.privileges import privilege_problems

MIGRATION = Path(__file__).resolve().parent.parent / "alembic" / "versions" / "0002_app_role_privileges.py"


def _privilege_sql() -> str:
    spec = importlib.util.spec_from_file_location("migration_0002", MIGRATION)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module.PRIVILEGE_SQL


def _can(db, table: str, privilege: str) -> bool:
    return bool(db.execute(text("SELECT has_table_privilege('neogold_app', :t, :p)"), {"t": table, "p": privilege}).scalar())


def test_tc_sec_audit_log_is_append_only_for_the_app_role_and_two_tables_may_be_cleaned(db):
    """Doc 5 Sections 3.4 and 3.5: the app role appends to the audit log and cannot change it, and may delete
    nonces and in-progress idempotency rows."""
    db.execute(text("DO $$ BEGIN IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'neogold_app') THEN CREATE ROLE neogold_app; END IF; END $$"))
    # what the bootstrap's default privileges give the role on every table
    db.execute(text("GRANT SELECT, INSERT, UPDATE ON ALL TABLES IN SCHEMA public TO neogold_app"))
    assert _can(db, "audit_log", "UPDATE")  # the starting point the audit found
    assert not _can(db, "auth_nonce", "DELETE")

    db.execute(text(_privilege_sql()))

    assert _can(db, "audit_log", "INSERT")
    assert _can(db, "audit_log", "SELECT")
    assert not _can(db, "audit_log", "UPDATE")
    assert not _can(db, "audit_log", "DELETE")
    assert _can(db, "auth_nonce", "DELETE")
    assert _can(db, "idempotency_record", "DELETE")
    # nothing else gained DELETE
    assert not _can(db, "device", "DELETE")
    assert not _can(db, "session", "DELETE")
    db.rollback()


def test_the_migration_does_nothing_where_the_role_does_not_exist():
    assert "pg_roles WHERE rolname = 'neogold_app'" in _privilege_sql()


def _roles(db):
    for role in ("neogold_app", "neogold_purge", "neogold_export"):
        db.execute(text(f"DO $$ BEGIN IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = '{role}') THEN CREATE ROLE {role}; END IF; END $$"))
    # what the bootstrap's default privileges give each role
    db.execute(text("GRANT SELECT, INSERT, UPDATE ON ALL TABLES IN SCHEMA public TO neogold_app"))
    db.execute(text("GRANT SELECT, DELETE ON ALL TABLES IN SCHEMA public TO neogold_purge"))
    db.execute(text("GRANT SELECT ON ALL TABLES IN SCHEMA public TO neogold_export"))


def _has(db, role: str, table: str, privilege: str) -> bool:
    return bool(db.execute(text("SELECT has_table_privilege(:r, :t, :p)"), {"r": role, "t": table, "p": privilege}).scalar())


def test_the_purge_and_export_roles_can_do_their_commands_and_cannot_change_the_audit_log(db):
    """The admin commands: `participant withdraw` updates three tables and appends an audit row; `export` writes an export row;
    neither may rewrite or delete the audit trail."""
    _roles(db)
    db.execute(text("REVOKE ALL ON audit_log FROM neogold_app, neogold_purge, neogold_export"))
    db.execute(text("GRANT SELECT, INSERT, UPDATE ON audit_log TO neogold_app"))
    db.execute(text("GRANT SELECT, DELETE ON audit_log TO neogold_purge"))
    db.execute(text("GRANT SELECT ON audit_log TO neogold_export"))

    db.execute(text(_privilege_sql()))

    assert _has(db, "neogold_purge", "audit_log", "INSERT")
    assert not _has(db, "neogold_purge", "audit_log", "DELETE")
    assert not _has(db, "neogold_purge", "audit_log", "UPDATE")
    for table in ("participant", "session", "capture"):
        assert _has(db, "neogold_purge", table, "UPDATE")
        assert _has(db, "neogold_purge", table, "DELETE")
    assert _has(db, "neogold_export", "export_job", "INSERT")
    assert _has(db, "neogold_export", "audit_log", "INSERT")
    assert not _has(db, "neogold_export", "participant", "UPDATE")
    assert privilege_problems(db) == []
    db.rollback()


def test_the_self_test_reports_a_privilege_that_the_migration_never_applied(db):
    _roles(db)
    db.execute(text("GRANT UPDATE ON audit_log TO neogold_app"))  # as if the migration ran before the roles existed

    problems = privilege_problems(db)

    assert "neogold_app has UPDATE on audit_log" in problems
    db.rollback()
