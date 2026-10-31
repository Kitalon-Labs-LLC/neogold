"""
tools/tests/test_check_actions_pinned.py

Enforces P25 / Doc 8 Section 10 / Doc 12 Section 3:
Tests that check_actions_pinned catches unpinned or branch/tag-pinned GitHub actions.
"""

import sys
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent.parent.parent
sys.path.insert(0, str(REPO_ROOT / "tools"))

from check_actions_pinned import check_workflow_file


def test_pinned_actions_pass(tmp_path):
    wf = tmp_path / "test.yml"
    wf.write_text(
        """
name: test
jobs:
  build:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@3d3c42e5aac5ba805825da76410c181273ba90b1 # v7.0.1
      - uses: actions/setup-python@5fda3b95a4ea91299a34e894583c3862153e4b97 # v7.0.0
      - uses: ./.github/actions/local-action
""",
        encoding="utf-8",
    )
    violations = check_workflow_file(wf)
    assert violations == []


def test_tag_or_branch_pinned_actions_fail(tmp_path):
    wf = tmp_path / "bad.yml"
    wf.write_text(
        """
name: bad
jobs:
  build:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: docker/login-action@master
""",
        encoding="utf-8",
    )
    violations = check_workflow_file(wf)
    assert len(violations) == 2
    assert "actions/checkout" in violations[0]
    assert "docker/login-action" in violations[1]
