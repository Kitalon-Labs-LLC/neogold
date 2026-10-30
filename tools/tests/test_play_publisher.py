"""
tools/tests/test_play_publisher.py

TC-OPS-02, TC-OPS-03, doc 12 Section 4: the publisher fails closed (BUG-2026-092). It never prints a success for an upload that
did not happen: no credentials, or no client library, is a failure; only an explicit --dry-run validates without publishing.
"""

import builtins
import sys
import zipfile
from pathlib import Path

import pytest

REPO_ROOT = Path(__file__).resolve().parent.parent.parent
sys.path.insert(0, str(REPO_ROOT / "tools"))

import play_publisher  # noqa: E402


@pytest.fixture
def bundle(tmp_path):
    path = tmp_path / "app-release.aab"
    with zipfile.ZipFile(path, "w") as z:
        z.writestr("base/manifest/AndroidManifest.xml", "<manifest/>")
    return path


def publish(bundle, credentials, dry_run=False):
    return play_publisher.publish_to_play(bundle, "internal", 2, "com.kitalonlabs.neogold", credentials, dry_run)


def test_without_credentials_nothing_is_published_and_the_exit_code_is_not_zero(bundle, capsys):
    code = publish(bundle, None)

    assert code == play_publisher.EXIT_NO_CREDENTIALS
    assert code != 0
    assert "nothing was published" in capsys.readouterr().err


def test_an_explicit_dry_run_validates_the_bundle_and_says_it_published_nothing(bundle, capsys):
    code = publish(bundle, None, dry_run=True)

    out = capsys.readouterr().out
    assert code == 0
    assert "DRY-RUN" in out
    assert "SUCCESS (Simulated)" not in out


def test_credentials_without_the_client_library_fail_instead_of_pretending(bundle, monkeypatch, capsys):
    real_import = builtins.__import__

    def refuse(name, *args, **kwargs):
        if name.startswith(("google", "googleapiclient")):
            raise ImportError(name)
        return real_import(name, *args, **kwargs)

    monkeypatch.setattr(builtins, "__import__", refuse)

    code = publish(bundle, '{"client_email": "x@example.invalid"}')

    assert code == play_publisher.EXIT_NO_CLIENT_LIBRARY
    out = capsys.readouterr()
    assert "SUCCESS" not in out.out
    assert "nothing was published" in out.err


def test_unparseable_credentials_fail(bundle):
    assert publish(bundle, "{not json") == 1


def test_a_bundle_that_is_not_an_aab_is_refused(tmp_path):
    bad = tmp_path / "app-release.aab"
    bad.write_bytes(b"not a zip")

    with pytest.raises(ValueError):
        play_publisher.inspect_bundle(bad)
