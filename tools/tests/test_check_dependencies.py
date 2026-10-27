"""
tools/tests/test_check_dependencies.py

Proves tools/check_dependencies.py correctly validates allowlist membership
and flags unapproved dependencies or isolated HTTP client violations.
"""

from pathlib import Path
from tools.check_dependencies import (
    load_allowlist,
    is_runtime_entry,
    check_pip_dependencies,
)


def test_load_allowlist(tmp_path: Path):
    allowlist_file = tmp_path / "allowlist.txt"
    allowlist_file.write_text(
        """# Comment
gradle:com.squareup.okhttp3:okhttp
pip:fastapi
""",
        encoding="utf-8",
    )
    loaded = load_allowlist(allowlist_file)
    assert "gradle:com.squareup.okhttp3:okhttp" in loaded
    assert "pip:fastapi" in loaded
    assert len(loaded) == 2


def test_is_runtime_entry():
    assert is_runtime_entry("releaseRuntimeClasspath") is True
    assert is_runtime_entry("debugRuntimeClasspath,releaseRuntimeClasspath") is True
    assert is_runtime_entry("compileClasspath") is False
    assert is_runtime_entry("lintClassPath") is False


def test_check_pip_dependencies_unapproved(tmp_path: Path):
    backend_dir = tmp_path / "backend"
    backend_dir.mkdir()
    req_file = backend_dir / "requirements.lock"
    req_file.write_text(
        """unapproved-pkg==1.0.0 --hash=sha256:123
""",
        encoding="utf-8",
    )
    allowlist = {"pip:approved-pkg"}
    violations = check_pip_dependencies(tmp_path, allowlist)
    assert len(violations) == 1
    assert "unapproved-pkg" in violations[0]


def test_check_pip_dependencies_approved(tmp_path: Path):
    backend_dir = tmp_path / "backend"
    backend_dir.mkdir()
    req_file = backend_dir / "requirements.lock"
    req_file.write_text(
        """fastapi==0.115.0 --hash=sha256:123
""",
        encoding="utf-8",
    )
    allowlist = {"pip:fastapi"}
    violations = check_pip_dependencies(tmp_path, allowlist)
    assert len(violations) == 0
