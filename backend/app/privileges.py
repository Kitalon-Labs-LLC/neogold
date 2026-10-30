"""Checks that the database roles hold the privileges doc 5 Section 3.5 gives them (BUG-2026-096).

The privilege migration does nothing when a role does not exist yet (tests, development), so on a deployed server a migration run
before the bootstrap would leave `audit_log` writable for good without any error. The self-test calls this to catch that.
"""

from __future__ import annotations

from sqlalchemy import text
from sqlalchemy.orm import Session

# (role, table, privilege, must it be held)
EXPECTED = [
    ("neogold_app", "audit_log", "INSERT", True),
    ("neogold_app", "audit_log", "UPDATE", False),
    ("neogold_app", "audit_log", "DELETE", False),
    ("neogold_app", "auth_nonce", "DELETE", True),
    ("neogold_app", "idempotency_record", "DELETE", True),
    ("neogold_purge", "audit_log", "INSERT", True),
    ("neogold_purge", "audit_log", "UPDATE", False),
    ("neogold_purge", "audit_log", "DELETE", False),
    ("neogold_purge", "participant", "UPDATE", True),
    ("neogold_export", "audit_log", "INSERT", True),
    ("neogold_export", "export_job", "INSERT", True),
]


def privilege_problems(db: Session) -> list[str]:
    """One line for each privilege that is wrong, for every role that exists. Empty when all are right (or no role exists)."""
    roles = sorted({role for role, _t, _p, _w in EXPECTED})
    existing = {row[0] for row in db.execute(text("SELECT rolname FROM pg_roles WHERE rolname = ANY(:roles)"), {"roles": roles}).all()}
    problems = []
    for role, table, privilege, wanted in EXPECTED:
        if role not in existing:
            continue
        held = bool(
            db.execute(
                text("SELECT has_table_privilege(:r, :t, :p)"), {"r": role, "t": table, "p": privilege}
            ).scalar()
        )
        if held != wanted:
            problems.append(f"{role} {'lacks' if wanted else 'has'} {privilege} on {table}")
    return problems
