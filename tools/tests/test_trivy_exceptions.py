"""
tools/tests/test_trivy_exceptions.py

Guards the exceptions in .trivyignore.yaml (Gate S2, doc 8 Section 10; BUG-2026-059).

An exception is scoped to one package version, not to a Gradle configuration. Its reason can be
"this library is build tooling or test code, not in the app" (the statement starts with
"Build-time only:" or "Test-only:"). Trivy would go on hiding that version if a later change pulled
it into a classpath an APK is built from, so the reason is checked here against the lockfiles Trivy
scans (android/**/gradle.lockfile). A configuration name that is not known to be tooling fails
until a person has looked at it and added it below.
"""

import os
import re
from datetime import date
from pathlib import Path

import pytest
import yaml

REPO_ROOT = Path(__file__).resolve().parent.parent.parent

SKIPPED_FOLDERS = {"build", ".gradle", ".git", ".idea"}

# Configurations that no app APK is built from: lint, annotation and compiler processors, and test
# code (the instrumented-test APK of :engine:vision is built only on a developer's device).
NOT_SHIPPED = re.compile(
    r"androidLintTool"
    r"|_agp_internal_.+_(kapt|ksp)Classpath"
    r"|kapt.*"
    r"|ksp.+ProcessorClasspath"
    r"|(test|(debug|release)UnitTest)(Compile|Runtime)Classpath"
    r"|(debug|release)(UnitTest|AndroidTest)LintChecksClasspath"
    r"|(debug|release)AndroidTest(Compile|Runtime)Classpath"
    r"|(test|androidTest)ImplementationDependenciesMetadata"
)

VERSIONED_MAVEN_PURL = re.compile(r"pkg:maven/(?P<group>[^/@]+)/(?P<name>[^/@]+)@(?P<version>[^/@]+)")

TOOLING_ONLY_PREFIXES = ("Build-time only:", "Test-only:")


def load_exceptions(root):
    data = yaml.safe_load((root / ".trivyignore.yaml").read_text(encoding="utf-8"))
    return data["vulnerabilities"]


def entry_problems(entry):
    """What the header of .trivyignore.yaml asks of every entry."""
    problems = []
    if not entry.get("id"):
        problems.append("no id")
    if not str(entry.get("statement", "")).strip():
        problems.append("no written reason (statement)")
    if not isinstance(entry.get("expired_at"), date):
        problems.append("no expiry date (expired_at)")
    purls = entry.get("purls") or []
    if not purls:
        problems.append("no purls: an id with no purl hides the finding in every package")
    for purl in purls:
        if not VERSIONED_MAVEN_PURL.fullmatch(purl):
            problems.append(f"{purl} is not a versioned maven purl (pkg:maven/<group>/<name>@<version>)")
    return problems


def is_tooling_only(entry):
    return str(entry.get("statement", "")).startswith(TOOLING_ONLY_PREFIXES)


def coordinate(purl):
    m = VERSIONED_MAVEN_PURL.fullmatch(purl)
    return f"{m.group('group')}:{m.group('name')}:{m.group('version')}"


def module_lockfiles(root):
    """Every android/**/gradle.lockfile, which is what Trivy scans, without walking build output."""
    found = []
    for folder, dirs, files in os.walk(root / "android"):
        dirs[:] = [d for d in dirs if d not in SKIPPED_FOLDERS]
        if "gradle.lockfile" in files:
            found.append(Path(folder) / "gradle.lockfile")
    return sorted(found)


def lockfile_configurations(root, coord):
    """Lockfile path -> the configurations that list the coordinate."""
    found = {}
    for lockfile in module_lockfiles(root):
        for line in lockfile.read_text(encoding="utf-8").splitlines():
            if line.startswith(coord + "="):
                found[lockfile.relative_to(root).as_posix()] = set(line.split("=", 1)[1].split(","))
    return found


def shipped_configurations(root, coord):
    """'<lockfile>: <configuration>' for every configuration that is not known to be tooling."""
    return [
        f"{path}: {config}"
        for path, configs in lockfile_configurations(root, coord).items()
        for config in sorted(configs)
        if not NOT_SHIPPED.fullmatch(config)
    ]


def write_lockfile(root, module, lines):
    folder = root / "android" / module
    folder.mkdir(parents=True)
    body = "# This is a Gradle generated file for dependency locking.\n" + "\n".join(lines) + "\nempty=\n"
    (folder / "gradle.lockfile").write_text(body, encoding="utf-8")


# --- the real repository --------------------------------------------------------------------


def test_every_exception_is_versioned_reasoned_and_expiring():
    for entry in load_exceptions(REPO_ROOT):
        assert entry_problems(entry) == [], f"{entry.get('id')}: {entry_problems(entry)}"


def test_tooling_only_exceptions_name_no_configuration_an_apk_is_built_from():
    for entry in load_exceptions(REPO_ROOT):
        if not is_tooling_only(entry):
            continue
        for purl in entry["purls"]:
            assert shipped_configurations(REPO_ROOT, coordinate(purl)) == [], (
                f"{entry['id']} for {purl} says it is not shipped, but a lockfile lists it in a configuration "
                "that is not known to be tooling: remove the exception and fix the dependency, or have the "
                "owner decide"
            )


def test_a_tooling_only_exception_still_matches_a_lockfile():
    for entry in load_exceptions(REPO_ROOT):
        if not is_tooling_only(entry):
            continue
        for purl in entry["purls"]:
            assert lockfile_configurations(REPO_ROOT, coordinate(purl)), (
                f"{purl} is in no lockfile any more: delete its {entry['id']} entry from .trivyignore.yaml"
            )


# --- the guard itself: it must fail when it should ------------------------------------------


def test_guard_flags_a_version_that_reaches_the_release_runtime_classpath(tmp_path):
    write_lockfile(
        tmp_path,
        "app",
        [
            "com.google.guava:guava:33.4.0-jre=androidLintTool,releaseRuntimeClasspath,debugUnitTestRuntimeClasspath",
            "com.google.guava:guava:33.3.1-jre=androidLintTool",
        ],
    )
    assert shipped_configurations(tmp_path, "com.google.guava:guava:33.4.0-jre") == [
        "android/app/gradle.lockfile: releaseRuntimeClasspath"
    ]
    assert shipped_configurations(tmp_path, "com.google.guava:guava:33.3.1-jre") == []


def test_guard_reads_every_module_lockfile(tmp_path):
    write_lockfile(tmp_path, "core/data", ["com.google.guava:guava:31.1-jre=kaptRelease"])
    write_lockfile(tmp_path, "feature/log", ["com.google.guava:guava:31.1-jre=debugRuntimeClasspath"])
    assert shipped_configurations(tmp_path, "com.google.guava:guava:31.1-jre") == [
        "android/feature/log/gradle.lockfile: debugRuntimeClasspath"
    ]


@pytest.mark.parametrize(
    "config",
    [
        "releaseRuntimeClasspath",
        "debugRuntimeClasspath",
        "releaseCompileClasspath",
        "debugCompileClasspath",
        "runtimeClasspath",
        "compileClasspath",
        "implementationDependenciesMetadata",
        "releaseLintChecksClasspath",
        "aConfigurationNobodyHasSeenBefore",
    ],
)
def test_guard_rejects_shipped_and_unknown_configurations(config):
    assert not NOT_SHIPPED.fullmatch(config)


@pytest.mark.parametrize(
    "config",
    [
        "androidLintTool",
        "_agp_internal_release_kaptClasspath",
        "_agp_internal_debug_kspClasspath",
        "kapt",
        "kaptAndroidTestRelease",
        "kaptClasspath_kaptReleaseKotlin",
        "kspReleaseKotlinProcessorClasspath",
        "testCompileClasspath",
        "testRuntimeClasspath",
        "debugUnitTestCompileClasspath",
        "debugUnitTestRuntimeClasspath",
        "debugUnitTestLintChecksClasspath",
        "debugAndroidTestCompileClasspath",
        "debugAndroidTestRuntimeClasspath",
        "debugAndroidTestLintChecksClasspath",
        "testImplementationDependenciesMetadata",
        "androidTestImplementationDependenciesMetadata",
    ],
)
def test_guard_accepts_lint_processor_and_test_configurations(config):
    assert NOT_SHIPPED.fullmatch(config)


def test_an_entry_without_expiry_reason_or_a_versioned_purl_is_a_problem():
    good = {
        "id": "CVE-2026-0001",
        "purls": ["pkg:maven/com.example/lib@1.0"],
        "statement": "Build-time only: example.",
        "expired_at": date(2026, 11, 28),
    }
    assert entry_problems(good) == []
    assert entry_problems({**good, "expired_at": None}) == ["no expiry date (expired_at)"]
    assert entry_problems({**good, "statement": "  "}) == ["no written reason (statement)"]
    assert len(entry_problems({**good, "purls": ["pkg:maven/com.example/lib"]})) == 1
    assert len(entry_problems({k: v for k, v in good.items() if k != "purls"})) == 1
