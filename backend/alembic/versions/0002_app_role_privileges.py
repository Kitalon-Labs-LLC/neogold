"""Privileges of the application role (doc 5 Sections 3.4 and 3.5, BUG-2026-096).

Revision ID: 0002
Revises: 0001
Create Date: 2026-10-31 12:00:00.000000

The bootstrap script gives `neogold_app` SELECT, INSERT and UPDATE on every table the migrations create. Two rows of the doc 5
table were never applied:

- `audit_log` is append-only: the application role keeps INSERT and SELECT only, so a bug or an injection in the service cannot
  rewrite or remove the audit trail.
- `auth_nonce` and `idempotency_record` need DELETE for the application role: the authentication middleware removes an
  in-progress idempotency row after a transient error, and without the privilege that cleanup fails.

This changes who may do what, not the shape of any table, so it is safe to run while the previous release is running
(expand-only). The role exists only on a deployed server (created by `neogold-bootstrap`); where it does not exist, as in tests
and development, nothing happens.
"""
from collections.abc import Sequence

from alembic import op

revision: str = "0002"
down_revision: str | None = "0001"
branch_labels: str | Sequence[str] | None = None
depends_on: str | Sequence[str] | None = None

PRIVILEGE_SQL = """
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'neogold_app') THEN
        REVOKE UPDATE, DELETE, TRUNCATE ON audit_log FROM neogold_app;
        GRANT DELETE ON auth_nonce, idempotency_record TO neogold_app;
    END IF;
END
$$;
"""


def upgrade() -> None:
    op.execute(PRIVILEGE_SQL)


def downgrade() -> None:
    # Privileges are not schema: a downgrade leaves them as they are.
    pass
