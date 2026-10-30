"""
tools/tests/test_release_gates.py

Proves the release gates fail when they should, not only that they pass on today's build (package D1, doc 9 Section 7):
TC-SEC-01 (manifest allow-lists, foreground service type), TC-SEC-02 (network config), TC-SEC-04 (16 KB script),
TC-SEC-05 (SBOM and licenses), TC-SEC-06 (secrets-scan configuration), TC-OPS-08 (licenses).
Each test builds a small fixture and runs the real gate against it.
"""

import json
import shutil
import subprocess
import sys
from pathlib import Path

import pytest

from tools.check_licenses import check_dependencies
from tools.check_manifest import main as check_manifest
from tools.check_network_config import main as check_network_config
from tools.generate_sbom import generate_cyclonedx_sbom

ROOT = Path(__file__).resolve().parents[2]

GOOD_MANIFEST = """<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="com.kitalonlabs.neogold">
  <uses-permission android:name="android.permission.CAMERA"/>
  <uses-permission android:name="android.permission.INTERNET"/>
  <uses-permission android:name="android.permission.FOREGROUND_SERVICE"/>
  <uses-permission android:name="android.permission.FOREGROUND_SERVICE_DATA_SYNC"/>
  <application android:allowBackup="false" android:dataExtractionRules="@xml/d" android:fullBackupContent="@xml/f"
      android:networkSecurityConfig="@xml/n">
    <activity android:name="com.kitalonlabs.neogold.MainActivity" android:exported="true">
      <intent-filter/>
    </activity>
    <service android:name="androidx.work.impl.foreground.SystemForegroundService" android:exported="false"
        android:foregroundServiceType="dataSync"/>
  </application>
</manifest>
"""


def run_manifest(tmp_path: Path, xml: str, capsys) -> tuple[int, str]:
    path = tmp_path / "AndroidManifest.xml"
    path.write_text(xml, encoding="utf-8")
    code = check_manifest(str(path))
    return code, capsys.readouterr().out


def test_tc_sec_01_a_clean_manifest_passes(tmp_path, capsys):
    code, out = run_manifest(tmp_path, GOOD_MANIFEST, capsys)
    assert code == 0, out


@pytest.mark.parametrize(
    "mutation, expected",
    [
        ('<uses-permission android:name="android.permission.READ_CONTACTS"/>', "permission not allow-listed"),
        ('<uses-permission android:name="android.permission.ACCESS_FINE_LOCATION"/>', "permission not allow-listed"),
    ],
)
def test_tc_sec_01_a_permission_outside_the_allow_list_fails(tmp_path, capsys, mutation, expected):
    xml = GOOD_MANIFEST.replace("<application", mutation + "\n  <application", 1)
    code, out = run_manifest(tmp_path, xml, capsys)
    assert code == 1
    assert expected in out


def test_tc_sec_01_an_exported_component_outside_the_allow_list_fails(tmp_path, capsys):
    xml = GOOD_MANIFEST.replace(
        "</application>", '<receiver android:name="com.example.Evil" android:exported="true"/></application>', 1
    )
    code, out = run_manifest(tmp_path, xml, capsys)
    assert code == 1
    assert "exported receiver not allow-listed: com.example.Evil" in out


def test_tc_sec_01_a_component_with_an_intent_filter_must_say_whether_it_is_exported(tmp_path, capsys):
    xml = GOOD_MANIFEST.replace(
        "</application>",
        '<service android:name="com.example.Implicit"><intent-filter/></service></application>',
        1,
    )
    code, out = run_manifest(tmp_path, xml, capsys)
    assert code == 1
    assert "no explicit android:exported" in out


@pytest.mark.parametrize(
    "old, new, expected",
    [
        ('android:allowBackup="false"', 'android:allowBackup="true"', "allowBackup must be false"),
        ("<application ", '<application android:debuggable="true" ', "debuggable"),
        ("<application ", '<application android:usesCleartextTraffic="true" ', "usesCleartextTraffic"),
        ('android:networkSecurityConfig="@xml/n"', "", "lacks android:networkSecurityConfig"),
    ],
)
def test_tc_sec_01_application_flags_are_enforced(tmp_path, capsys, old, new, expected):
    code, out = run_manifest(tmp_path, GOOD_MANIFEST.replace(old, new, 1), capsys)
    assert code == 1
    assert expected in out


def test_tc_sec_01_the_upload_service_must_declare_the_data_sync_type(tmp_path, capsys):
    # Android 14 stops a foreground service that has no type; the upload worker would die the first time it starts.
    xml = GOOD_MANIFEST.replace(' android:foregroundServiceType="dataSync"', "", 1)
    code, out = run_manifest(tmp_path, xml, capsys)
    assert code == 1
    assert "lacks foregroundServiceType dataSync" in out


def test_tc_sec_01_the_upload_service_must_not_be_exported(tmp_path, capsys):
    xml = GOOD_MANIFEST.replace(
        'android:name="androidx.work.impl.foreground.SystemForegroundService" android:exported="false"',
        'android:name="androidx.work.impl.foreground.SystemForegroundService" android:exported="true"',
        1,
    )
    code, out = run_manifest(tmp_path, xml, capsys)
    assert code == 1
    assert "SystemForegroundService" in out


GOOD_NETWORK = """<network-security-config>
  <base-config cleartextTrafficPermitted="false"><trust-anchors><certificates src="system"/></trust-anchors></base-config>
</network-security-config>
"""


def run_network(tmp_path: Path, xml: str, capsys) -> tuple[int, str]:
    path = tmp_path / "network_security_config.xml"
    path.write_text(xml, encoding="utf-8")
    code = check_network_config(str(path))
    return code, capsys.readouterr().out


def test_tc_sec_02_a_clean_network_config_passes(tmp_path, capsys):
    code, out = run_network(tmp_path, GOOD_NETWORK, capsys)
    assert code == 0, out


@pytest.mark.parametrize(
    "xml, expected",
    [
        (GOOD_NETWORK.replace('cleartextTrafficPermitted="false"', 'cleartextTrafficPermitted="true"'), "cleartext"),
        (GOOD_NETWORK.replace('src="system"', 'src="user"'), "trust anchors"),
        (
            GOOD_NETWORK.replace(
                "</network-security-config>",
                '<domain-config cleartextTrafficPermitted="true"><domain>x</domain></domain-config></network-security-config>',
            ),
            "permits cleartext",
        ),
        (
            GOOD_NETWORK.replace(
                "</network-security-config>",
                '<debug-overrides><trust-anchors><certificates src="user"/></trust-anchors></debug-overrides></network-security-config>',
            ),
            "debug-overrides",
        ),
    ],
)
def test_tc_sec_02_a_weakened_network_config_fails(tmp_path, capsys, xml, expected):
    code, out = run_network(tmp_path, xml, capsys)
    assert code == 1
    assert expected in out


def test_tc_sec_02_the_apps_real_network_config_passes(capsys):
    code = check_network_config(str(ROOT / "android/app/src/main/res/xml/network_security_config.xml"))
    assert code == 0, capsys.readouterr().out


def _bash() -> str:
    """Git Bash on Windows (plain `bash` there can be the WSL launcher, which waits for a distribution); `bash` elsewhere."""
    for candidate in ("C:/Program Files/Git/bin/bash.exe", "C:/Program Files (x86)/Git/bin/bash.exe"):
        if sys.platform == "win32" and Path(candidate).is_file():
            return candidate
    found = shutil.which("bash")
    if found is None:
        pytest.fail("bash is needed to run tools/check_16kb.sh")
    return found


def _bash_path(path: Path) -> str:
    """The path as bash sees it: on Windows (Git Bash) a drive path becomes /c/..., because a colon would split PATH."""
    posix = path.as_posix()
    if len(posix) > 1 and posix[1] == ":":
        return "/" + posix[0].lower() + posix[2:]
    return posix


def test_tc_sec_04_the_alignment_script_fails_when_zipalign_fails(tmp_path):
    # TC-SEC-04: zipalign and llvm-objdump come from the SDK; here a fake zipalign that refuses proves the script stops.
    fake = tmp_path / "bin"
    fake.mkdir()
    zipalign = fake / "zipalign"
    zipalign.write_text("#!/usr/bin/env bash\necho 'not aligned'\nexit 1\n", encoding="utf-8")
    zipalign.chmod(0o755)
    apk = tmp_path / "app.apk"
    apk.write_bytes(b"PK")
    result = subprocess.run(
        [_bash(), _bash_path(ROOT / "tools/check_16kb.sh"), _bash_path(apk)],
        capture_output=True,
        text=True,
        timeout=60,
        env={"PATH": f"{_bash_path(fake)}:/usr/bin:/bin", "TMPDIR": _bash_path(tmp_path)},
    )
    assert result.returncode == 1
    assert "S4 FAIL: zip alignment" in result.stdout


def test_tc_sec_04_the_alignment_script_demands_an_apk_argument():
    result = subprocess.run(
        [_bash(), _bash_path(ROOT / "tools/check_16kb.sh")], capture_output=True, text=True, timeout=60
    )
    assert result.returncode != 0


def test_tc_sec_05_the_sbom_lists_every_locked_dependency_with_a_license(tmp_path):
    lock = tmp_path / "gradle.lockfile"
    lock.write_text(
        "# lock\ncom.squareup.okhttp3:okhttp:4.12.0=releaseRuntimeClasspath\n"
        "com.google.crypto.tink:tink-android:1.24.0=releaseRuntimeClasspath\nempty=\n",
        encoding="utf-8",
    )
    out = tmp_path / "bom.json"
    sbom = generate_cyclonedx_sbom(lock, out, app_version="1.0.0")
    assert sbom["bomFormat"] == "CycloneDX"
    assert sbom["specVersion"] == "1.5"
    names = {c["name"] for c in sbom["components"]}
    assert {"okhttp", "tink-android"} <= names
    assert json.loads(out.read_text(encoding="utf-8"))["serialNumber"].startswith("urn:uuid:")
    for component in sbom["components"]:
        assert component.get("licenses"), component["name"]


def test_tc_sec_05_a_missing_lockfile_fails_the_sbom(tmp_path):
    with pytest.raises(FileNotFoundError):
        generate_cyclonedx_sbom(tmp_path / "missing.lockfile", tmp_path / "bom.json")


def test_tc_ops_08_a_copyleft_dependency_fails_the_license_check(tmp_path, capsys):
    lock = tmp_path / "gradle.lockfile"
    lock.write_text("org.gnu.fake:copyleft-lib:1.0=releaseRuntimeClasspath\n", encoding="utf-8")
    count, messages = check_dependencies(lock)
    capsys.readouterr()
    assert count == 1  # an unknown license is blocked until reviewed
    assert "org.gnu.fake:copyleft-lib:1.0" in messages[0]


def test_tc_ops_08_the_apps_own_lockfile_has_only_reviewed_licenses(capsys):
    count, messages = check_dependencies(ROOT / "android/app/gradle.lockfile")
    capsys.readouterr()
    assert count == 0, messages


def test_tc_ops_08_the_code_and_card_and_docs_licenses_are_present():
    assert "MIT License" in (ROOT / "LICENSE").read_text(encoding="utf-8")
    assert "Creative Commons Attribution 4.0" in (ROOT / "card/LICENSE").read_text(encoding="utf-8")
    assert "Creative Commons Attribution 4.0" in (ROOT / "docs/LICENSE").read_text(encoding="utf-8")


def test_tc_sec_06_every_secrets_scan_exception_is_exact_and_has_a_reason():
    # Gate S9: the scanner starts from the default rules; an exception is one exact finding with a reason, never a path or rule.
    toml = (ROOT / ".gitleaks.toml").read_text(encoding="utf-8")
    assert "useDefault = true" in toml
    blocks = toml.split("[[allowlists]]")[1:]
    assert blocks, "expected the triaged allowlists"
    for block in blocks:
        assert "description =" in block
        assert 'condition = "AND"' in block  # a path alone would silence a whole file
        assert "regexes = [" in block  # an exact value, so any other secret in the same file still matches

    ignore = [
        line
        for line in (ROOT / ".gitleaksignore").read_text(encoding="utf-8").splitlines()
        if line.strip() and not line.startswith("#")
    ]
    for line in ignore:
        commit, _, rest = line.partition(":")
        assert len(commit) == 40 and all(c in "0123456789abcdef" for c in commit), line  # a commit, not a wildcard
        assert rest.count(":") >= 2, line  # commit:file:rule:line
        assert "*" not in line, line
    # every fingerprint is preceded by a comment block that says why
    lines = (ROOT / ".gitleaksignore").read_text(encoding="utf-8").splitlines()
    for i, line in enumerate(lines):
        if line.strip() and not line.startswith("#"):
            assert lines[i - 1].startswith("#"), f"fingerprint without a reason above it: {line}"


def test_tc_sec_06_the_ci_scan_runs_the_pinned_scanner_over_history():
    workflow = (ROOT / ".github/workflows/android-ci.yml").read_text(encoding="utf-8")
    assert "git --redact -v --config .gitleaks.toml" in workflow
    assert "--exit-code 1" in workflow
    assert "sha256sum -c" in workflow  # the downloaded scanner is checked against a pinned hash


def test_the_gate_scripts_are_run_with_this_interpreter():
    # keeps the module importable under the CI interpreter
    assert sys.version_info >= (3, 10)
