"""
tools/tests/test_check_migrations.py

Enforces P25 / Doc 8 Section 8.4 / Doc 12 Section 2:
Tests that check_migrations detects destructive migrations in upgrade()
and respects the contract-migration flag.
"""

import sys
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent.parent.parent
sys.path.insert(0, str(REPO_ROOT / "tools"))

from check_migrations import check_alembic_migrations


def test_safe_alembic_migration_passes(tmp_path):
    mig = tmp_path / "0002_add_column.py"
    mig.write_text(
        """
def upgrade() -> None:
    op.add_column('study', sa.Column('description', sa.Text(), nullable=True))
    op.create_table('new_feature', sa.Column('id', sa.Integer()))

def downgrade() -> None:
    op.drop_table('new_feature')
    op.drop_column('study', 'description')
""",
        encoding="utf-8",
    )
    violations = check_alembic_migrations(REPO_ROOT, files_to_check=[mig])
    assert violations == []


def test_destructive_alembic_migration_fails_without_contract_label(tmp_path):
    mig = tmp_path / "0003_drop_column.py"
    mig.write_text(
        """
def upgrade() -> None:
    op.drop_column('study', 'old_field')

def downgrade() -> None:
    pass
""",
        encoding="utf-8",
    )
    violations = check_alembic_migrations(REPO_ROOT, files_to_check=[mig], allow_contract_migration=False)
    assert len(violations) == 1
    assert "op.drop_column" in violations[0]


def test_destructive_alembic_migration_allowed_with_contract_label(tmp_path):
    mig = tmp_path / "0003_drop_column.py"
    mig.write_text(
        """
def upgrade() -> None:
    op.drop_column('study', 'old_field')

def downgrade() -> None:
    pass
""",
        encoding="utf-8",
    )
    violations = check_alembic_migrations(REPO_ROOT, files_to_check=[mig], allow_contract_migration=True)
    assert violations == []


def test_alter_column_nullable_false_fails_without_contract_label(tmp_path):
    mig = tmp_path / "0004_make_required.py"
    mig.write_text(
        """
def upgrade() -> None:
    op.alter_column('study', 'name', nullable=False)

def downgrade() -> None:
    pass
""",
        encoding="utf-8",
    )
    violations = check_alembic_migrations(REPO_ROOT, files_to_check=[mig], allow_contract_migration=False)
    assert len(violations) == 1
    assert "nullable=False" in violations[0]
