"""Tooling tests for tools/drift_inventory.py and the trace.py --tests extension (P24).

Each test builds a small synthetic repository in tmp_path, so the checks never depend on
the state of the real tree (except the smoke test at the end).
"""

import json
import shutil
import subprocess
import sys
import textwrap
from pathlib import Path

import pytest

REPO = Path(__file__).resolve().parent.parent.parent
sys.path.insert(0, str(REPO / "tools"))

import trace as trace_tool  # noqa: E402

import drift_inventory as di  # noqa: E402

DOC4 = """\
# Architecture

### 4.2 Module map

```mermaid
flowchart TB
    subgraph app[":app"]
      NAV["Navigation"]
    end
    subgraph features["Feature modules"]
      F3[":feature:screening"]
    end
    subgraph engines["Engine modules"]
      E5[":engine:triage<br/>pure Kotlin"]
    end
    subgraph core["Core modules"]
      C1[":core:model"]
    end
    S1[":sync:research<br/>uploader"]
    app --> features
    features --> engines
    features --> core
    engines --> C1
```

| Module | Pure Kotlin | Key responsibility |
|---|---|---|
| `:engine:triage` | Yes | Rules |

### 4.3 Technology choices

| Concern | Choice | Why |
|---|---|---|

### 4.3a Approved libraries

| Library | Allowed in | Use | Source |
|---|---|---|---|
| `junit:junit` | any | test | P01 |
| `com.squareup.okhttp3:okhttp` | `:sync:research` | all | Section 4.3 |

### 4.4 Next

### 6.1 Signed configuration

```json
{
  "configVersion": "x",
  "triageEnabled": true,
  "scope": { "maxAgeHours": 336 },
  "qualityThresholds": { "...": "..." }
}
```

### 6.2 Decision algorithm
"""

DOC5 = """\
### 2.2 Tables

#### `outbox`

| Column | Type | Null | Notes |
|---|---|---|---|
| `id` | TEXT | No | PK |
| `state` | TEXT | No | |

### 2.3 Indexes

### 3.2 DDL

```sql
CREATE TABLE study (
    id          uuid PRIMARY KEY,
    study_code  text NOT NULL UNIQUE,
    CHECK (study_code <> '')
);
```

### 3.3 Indexes
"""

DOC6 = """\
## 5. Screen Inventory

| ID | Screen | Primary action | Backlog |
|---|---|---|---|
| S01 | Language | Choose | NG-002 |
| S02 to S04 | Safety notice | OK | NG-003 |
| S10a | Consent | Yes | NG-056 |

## 6. Key Flows

### 10.4 Core microcopy (English and draft Bangla)

| Key | English | Bangla (draft) |
|---|---|---|
| `confirm_blood` | Always confirm with a blood test. | x |

## 11. Accessibility
"""

DOC8 = "| Permissions | `CAMERA`; `INTERNET` |\n"

DOC3 = """\
## MUST HAVE

| ID | Epic | Item | Description | Size | Release | Depends on | PRD ref |
|---|---|---|---|---|---|---|---|
| NG-001 | E1 | Scaffold | x | M | v0.1 | None | x |
| NG-002 | E1 | Language | x | S | v1.0 | NG-001 | x |
| NG-003 | E1 | Notice | x | S | v1.0 | NG-002 | x |

## SHOULD HAVE
"""

DOC14 = """\
## 1. Phases and Work Packages

| WP | Name | Phase | Depends on | Backlog | Tests | Sessions |
|---|---|---|---|---|---|---|
| P13 | Shell | 5 | P01 | NG-002 | TC-ONB-01 | 3 |

## 2. Dependency Graph
"""

DOC10 = """\
| ID | Title | Verifies | Steps | Expected | Level | Priority |
|---|---|---|---|---|---|---|
| TC-TRI-06 | Rule order | FR-08 | x | y | U | P1 |
| TC-TRI-07 | Threshold | FR-20 | x | y | U | P1 |

## 20. Hazard Verification Map
"""

OPENAPI = """\
openapi: 3.1.0
paths:
  /health:
    get:
      responses:
        "200": {description: ok}
components:
  schemas:
    ErrorCode:
      type: string
      enum: [VALIDATION_FAILED]
"""

FAKE_APP = """\
class _App:
    def openapi(self):
        return {"paths": {"/health": {"get": {"responses": {"200": {}}}}%s}}
app = _App()
"""

MIGRATION = """\
from alembic import op
import sqlalchemy as sa
revision = "0001"
down_revision = None
def upgrade():
    op.create_table(
        "study",
        sa.Column("id", sa.Text(), nullable=False),
        sa.Column("study_code", sa.Text(), nullable=False),
    )
"""


def write(root, path, text):
    p = root / path
    p.parent.mkdir(parents=True, exist_ok=True)
    p.write_text(textwrap.dedent(text) if text.startswith("\n") else text)
    return p


def make_repo(root):
    write(root, "docs/4_System_Architecture.md", DOC4)
    write(root, "docs/5_Database_Schema.md", DOC5)
    write(root, "docs/6_UI_UX_Guidelines.md", DOC6)
    write(root, "docs/8_Security_Compliance_Plan.md", DOC8)
    write(root, "docs/3_Feature_Backlog.md", DOC3)
    write(root, "docs/14_Build_Plan.md", DOC14)
    write(root, "docs/10_Test_Cases.md", DOC10)
    write(root, "docs/api/openapi.yaml", OPENAPI)
    shutil.copy(REPO / "tools/check_manifest.py", write(root, "tools/check_manifest.py", ""))
    shutil.copy(REPO / "tools/trace.py", write(root, "tools/trace.py", ""))
    write(root, "android/settings.gradle.kts",
          'include(":app")\ninclude(":feature:screening")\ninclude(":engine:triage")\n'
          'include(":core:model")\ninclude(":sync:research")\n')
    write(root, "android/gradle/libs.versions.toml",
          '[versions]\nokhttp = "4.12.0"\n\n[libraries]\n'
          'junit = { group = "junit", name = "junit", version = "4.13.2" }\n'
          'okhttp = { group = "com.squareup.okhttp3", name = "okhttp", version.ref = "okhttp" }\n'
          'leftpad = { module = "com.example:leftpad", version = "1.0" }\n')
    write(root, "android/app/build.gradle.kts",
          'android { namespace = "com.kitalonlabs.neogold" }\n'
          'dependencies {\n    implementation(project(":feature:screening"))\n}\n')
    write(root, "android/feature/screening/build.gradle.kts",
          'dependencies {\n    implementation(project(":engine:triage"))\n'
          '    implementation(project(":core:model"))\n    testImplementation(libs.junit)\n}\n')
    write(root, "android/engine/triage/build.gradle.kts", 'dependencies {\n    implementation(project(":core:model"))\n}\n')
    write(root, "android/core/model/build.gradle.kts", "dependencies {\n}\n")
    write(root, "android/sync/research/build.gradle.kts", "dependencies {\n    implementation(libs.okhttp)\n}\n")
    write(root, "android/app/src/main/AndroidManifest.xml", """\
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
    <uses-permission android:name="android.permission.CAMERA" />
    <uses-permission android:name="android.permission.INTERNET" />
    <application android:allowBackup="false">
        <activity android:name=".MainActivity" android:exported="true">
            <intent-filter><action android:name="android.intent.action.MAIN" /></intent-filter>
        </activity>
    </application>
</manifest>
""")
    write(root, "android/core/model/schemas/com.x.Db/1.json", json.dumps({"database": {"entities": [
        {"tableName": "outbox", "fields": [{"columnName": "id"}]}]}}))
    write(root, "android/core/model/schemas/com.x.Db/2.json", json.dumps({"database": {"entities": [
        {"tableName": "outbox", "fields": [{"columnName": "id"}, {"columnName": "state"}]}]}}))
    write(root, "android/app/src/main/kotlin/x/navigation/Destinations.kt",
          'object Destinations {\n    const val S01_LANGUAGE = "s01"\n'
          '    const val S02_S04_SAFETY_NOTICE = "s02"\n    const val S10A_GUARDIAN_CONSENT = "s10a"\n}\n')
    write(root, "android/app/src/main/kotlin/x/MainActivity.kt",
          "fun nav() {\n    composable(Destinations.S01_LANGUAGE) { Language() }\n"
          '    composable(Destinations.S10A_GUARDIAN_CONSENT) { PlaceholderScreen("S10a") }\n}\n')
    write(root, "android/app/src/main/res/values/strings.xml",
          '<resources><string name="confirm_blood">Always confirm</string><string name="app_name">N</string></resources>')
    write(root, "android/app/src/main/res/values-bn/strings.xml",
          '<resources><string name="confirm_blood">x</string></resources>')
    write(root, "android/engine/triage/src/test/kotlin/TriageTest.kt",
          "class T {\n    fun test_TC_TRI_06_rule_order() {}\n    // TC-TRI-06 again\n}\n")
    write(root, "config/triage-config.json", json.dumps({
        "configVersion": "x", "triageEnabled": False, "scope": {"maxAgeHours": 336},
        "qualityThresholds": {"minSharpness": 1}}))
    write(root, "backend/alembic/versions/0001_initial.py", MIGRATION)
    write(root, "backend/app/__init__.py", "")
    write(root, "backend/app/main.py", FAKE_APP % "")
    write(root, "backend/app/errors.py", 'E = dict(code="VALIDATION_FAILED")\n')
    git(root, "init", "-q")
    git(root, "add", "-A")
    git(root, "commit", "-q", "-m", "P13: shell")
    return root


def git(root, *args):
    subprocess.run(["git", "-C", str(root), "-c", "user.email=t@example.invalid", "-c", "user.name=t",
                    "-c", "commit.gpgsign=false", *args], check=True, capture_output=True)


@pytest.fixture
def repo(tmp_path):
    return make_repo(tmp_path / "repo")


def run(root, *args):
    return di.main(["--root", str(root), *args])


def unplanned(root, cat):
    return di.inventory(root)["categories"][cat]["unplanned"]


def test_clean_fixture_has_no_unplanned_items(repo):
    inv = di.inventory(repo)
    for cat in di.GATED:
        assert inv["categories"][cat]["status"] == "ok", cat
        assert inv["categories"][cat]["unplanned"] == [], (cat, inv["categories"][cat]["unplanned"])


def test_check_fails_on_undeclared_dependency_and_passes_once_doc_row_added(repo, capsys):
    assert run(repo, "--write-baseline") == 0
    assert run(repo, "--check") == 0

    bf = repo / "android/engine/triage/build.gradle.kts"
    bf.write_text(bf.read_text().replace("}\n", "    implementation(libs.leftpad)\n}\n"))
    capsys.readouterr()
    assert run(repo, "--check") == 1
    out = capsys.readouterr().out
    assert "NEW UNPLANNED [libraries]: :engine:triage com.example:leftpad prod" in out

    doc = repo / "docs/4_System_Architecture.md"
    doc.write_text(doc.read_text().replace(
        "| `junit:junit` | any | test | P01 |",
        "| `junit:junit` | any | test | P01 |\n| `com.example:leftpad` | any | all | ADR-099 |"))
    assert run(repo, "--check") == 0


def test_library_outside_its_allowed_module_is_unplanned_and_reaches_screening(repo):
    bf = repo / "android/engine/triage/build.gradle.kts"
    bf.write_text(bf.read_text().replace("}\n", '    implementation(libs.findLibrary("okhttp").get())\n}\n'))
    inv = di.inventory(repo)["categories"]
    assert ":engine:triage com.squareup.okhttp3:okhttp prod" in inv["libraries"]["unplanned"]
    assert inv["libraries"]["details"]["reasons"][":engine:triage com.squareup.okhttp3:okhttp prod"] \
        == "approved only in :sync:research"
    assert any("reaches com.squareup.okhttp3:okhttp" in k for k in inv["screening_network"]["unplanned"])


def test_test_only_library_used_in_production_is_unplanned(repo):
    bf = repo / "android/core/model/build.gradle.kts"
    bf.write_text("dependencies {\n    implementation(libs.junit)\n}\n")
    assert ":core:model junit:junit prod" in unplanned(repo, "libraries")


def test_undocumented_module_and_edge(repo):
    s = repo / "android/settings.gradle.kts"
    s.write_text(s.read_text() + 'include(":benchmark")\n')
    write(repo, "android/benchmark/build.gradle.kts", 'dependencies {\n    implementation(project(":engine:triage"))\n}\n')
    bf = repo / "android/engine/triage/build.gradle.kts"
    bf.write_text(bf.read_text().replace("}\n", '    implementation(project(":sync:research"))\n}\n'))
    inv = di.inventory(repo)["categories"]
    assert inv["modules"]["unplanned"] == [":benchmark"]
    assert ":engine:triage -> :sync:research" in inv["module_edges"]["unplanned"]
    assert ":engine:triage reaches :sync:research" in inv["screening_network"]["unplanned"]


def test_network_api_in_screening_source_is_reported(repo):
    write(repo, "android/feature/screening/src/main/kotlin/Up.kt",
          'val c = java.net.URL("https://example.invalid").openConnection() as HttpURLConnection\n')
    keys = unplanned(repo, "screening_network")
    assert any("HttpURLConnection" in k or "java.net.URL(" in k for k in keys)


def test_new_permission_fails_check(repo, capsys):
    run(repo, "--write-baseline")
    mf = repo / "android/app/src/main/AndroidManifest.xml"
    mf.write_text(mf.read_text().replace(
        "<application", '<uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" />\n    <application'))
    capsys.readouterr()
    assert run(repo, "--check") == 1
    assert "NEW UNPLANNED [permissions]: android.permission.ACCESS_FINE_LOCATION" in capsys.readouterr().out
    assert di.inventory(repo)["wont_have_signals"]["permissions"] == ["android.permission.ACCESS_FINE_LOCATION"]


def test_new_exported_component_is_unplanned(repo):
    mf = repo / "android/app/src/main/AndroidManifest.xml"
    mf.write_text(mf.read_text().replace(
        "</application>", '<receiver android:name=".Leak" android:exported="true" />\n    </application>'))
    assert unplanned(repo, "exported_components") == ["com.kitalonlabs.neogold.Leak"]


def test_room_uses_latest_schema_and_flags_new_column(repo):
    write(repo, "android/core/model/schemas/com.x.Db/10.json", json.dumps({"database": {"entities": [
        {"tableName": "outbox", "fields": [{"columnName": "id"}, {"columnName": "state"},
                                           {"columnName": "phone_number"}]}]}}))
    inv = di.inventory(repo)["categories"]
    assert inv["room_columns"]["unplanned"] == ["outbox.phone_number"]
    assert inv["room_columns"]["details"]["schemas"] == ["android/core/model/schemas/com.x.Db/10.json"]


def test_alembic_head_follows_revision_chain(repo):
    write(repo, "backend/alembic/versions/0002_add.py", textwrap.dedent("""\
        from alembic import op
        import sqlalchemy as sa
        revision = "0002"
        down_revision = "0001"
        def upgrade():
            op.add_column("study", sa.Column("location", sa.Text(), nullable=True))
            op.create_table("extra", sa.Column("id", sa.Text()))
            op.execute("SELECT 1")
        """))
    inv = di.inventory(repo)["categories"]
    assert inv["pg_columns"]["unplanned"] == ["extra.id", "study.location"]
    assert inv["pg_tables"]["unplanned"] == ["extra"]
    assert any("op.execute" in u for u in inv["pg_tables"]["details"]["unparsed_operations"])


def test_doc_ddl_ignores_constraint_lines(repo):
    assert di.doc_ddl(repo) == {"study": {"id", "study_code"}}


def test_new_endpoint_and_error_code(repo):
    (repo / "backend/app/main.py").write_text(
        FAKE_APP % ', "/export": {"post": {"responses": {"200": {}}}}')
    (repo / "backend/app/errors.py").write_text('E = dict(code="VALIDATION_FAILED")\nF = dict(code="SECRET_DUMP")\n')
    inv = di.inventory(repo)["categories"]
    assert inv["endpoints"]["unplanned"] == ["POST /export"]
    assert inv["error_codes"]["unplanned"] == ["SECRET_DUMP"]


def test_endpoint_response_difference_is_detailed(repo):
    (repo / "backend/app/main.py").write_text(FAKE_APP.replace('{"200": {}}', '{"200": {}, "418": {}, "422": {}}') % "")
    diffs = di.inventory(repo)["categories"]["endpoints"]["details"]["operation_differences"]
    assert diffs == {"GET /health": {"responses_only_in_code": ["418"]}}


def test_backend_import_failure_skips_and_check_refuses(repo, capsys):
    run(repo, "--write-baseline")
    (repo / "backend/app/main.py").write_text("raise RuntimeError('no db')\n")
    inv = di.inventory(repo)["categories"]
    assert inv["endpoints"]["status"].startswith("skipped: backend import failed")
    capsys.readouterr()
    assert run(repo, "--check") == 2
    assert "CANNOT CHECK [endpoints]" in capsys.readouterr().out
    assert run(repo, "--check", "--allow-skipped") == 0


def test_screens_expand_ranges_and_find_placeholders(repo):
    c = di.inventory(repo)["categories"]["screens"]
    assert c["actual"] == ["S01", "S02", "S03", "S04", "S10a"]
    assert c["unplanned"] == [] and c["missing"] == []
    assert c["details"]["placeholders"] == ["S10a android/app/src/main/kotlin/x/MainActivity.kt:3"]


def test_strings_are_report_only(repo):
    c = di.inventory(repo)["categories"]["strings"]
    assert c["unplanned"] == []
    assert c["details"]["in_values_not_values_bn"] == ["app_name"]
    assert "strings" not in di.GATED


def test_config_key_added_outside_wildcard_is_unplanned(repo):
    cfg = repo / "config/triage-config.json"
    data = json.loads(cfg.read_text())
    data["debugOverride"] = True
    data["scope"]["minAgeHours"] = 0
    cfg.write_text(json.dumps(data))
    c = di.inventory(repo)["categories"]["config_keys"]
    assert c["unplanned"] == ["debugOverride", "scope.minAgeHours"]
    assert c["details"]["triageEnabled"] is False


def test_triage_gate_finds_true_defaults(repo):
    write(repo, "android/feature/screening/src/main/kotlin/Result.kt",
          "fun Result(triageEnabled: Boolean = true) {}\n")
    c = di.inventory(repo)["categories"]["triage_gate"]
    assert c["unplanned"] == ["android/feature/screening/src/main/kotlin/Result.kt: Kotlin parameter defaults to true"]


def test_tc_counts_accept_kotlin_underscore_names(repo):
    c = di.inventory(repo)["categories"]["test_cases"]
    assert c["details"]["tests_per_tc"] == {"TC-TRI-06": 2, "TC-TRI-07": 0}
    assert c["details"]["zero_tests"] == ["TC-TRI-07"]


def test_backlog_coverage_via_work_package(repo):
    c = di.inventory(repo)["categories"]["backlog_refs"]
    assert c["details"]["covered_only_via_work_package"] == ["NG-002"]
    assert c["missing"] == ["NG-001", "NG-003"]


def test_unreferenced_commits_listed_not_gated(repo):
    (repo / "x.txt").write_text("x")
    git(repo, "add", "x.txt")
    git(repo, "commit", "-q", "-m", "chore: tidy")
    inv = di.inventory(repo)
    assert [c.split(" ", 1)[1] for c in inv["commits"]["unreferenced"]] == ["chore: tidy"]
    assert "commits" not in di.GATED


def test_since_limits_commit_range(repo):
    head = subprocess.run(["git", "-C", str(repo), "rev-parse", "HEAD"], capture_output=True, text=True).stdout.strip()
    (repo / "y.txt").write_text("y")
    git(repo, "add", "y.txt")
    git(repo, "commit", "-q", "-m", "docs: update NG-003")
    inv = di.inventory(repo, since=head)
    assert inv["commits"]["unreferenced"] == []
    assert inv["commits"]["range"] == f"{head}..HEAD"


def test_resolved_items_are_reported(repo, capsys):
    bf = repo / "android/core/model/build.gradle.kts"
    bf.write_text("dependencies {\n    implementation(libs.leftpad)\n}\n")
    run(repo, "--write-baseline")
    bf.write_text("dependencies {\n}\n")
    capsys.readouterr()
    assert run(repo, "--check") == 0
    assert "RESOLVED [libraries]: :core:model com.example:leftpad prod" in capsys.readouterr().out


def test_output_is_confined_to_reports_direction(repo):
    with pytest.raises(SystemExit, match="must be under"):
        run(repo, "--out", "docs/evil.json")
    with pytest.raises(SystemExit, match="must be under"):
        run(repo, "--out", "reports/direction/../../config/x.json")
    assert run(repo, "--out", "reports/direction/2026-01-01/inventory.json") == 0
    assert (repo / "reports/direction/2026-01-01/inventory.json").exists()


def test_check_without_baseline_fails_closed(repo):
    assert run(repo, "--check") == 2


def test_inventory_is_deterministic(repo):
    a = json.dumps(di.inventory(repo), sort_keys=True)
    b = json.dumps(di.inventory(repo), sort_keys=True)
    assert a == b


def test_trace_default_output_unchanged_by_tests_flag(capsys):
    assert trace_tool.main(["trace.py", str(REPO / "docs")]) == 0
    plain = capsys.readouterr().out
    assert "TESTS:" not in plain
    trace_tool.main(["trace.py", str(REPO / "docs"), "--tests"])
    with_tests = capsys.readouterr().out
    assert with_tests.startswith(plain)
    assert "TESTS:" in with_tests


def test_real_repository_smoke():
    inv = di.inventory(REPO)
    for cat in ("modules", "module_edges", "libraries", "permissions", "room_tables", "room_columns",
                "pg_tables", "pg_columns", "screens", "config_keys", "triage_gate", "test_cases"):
        assert inv["categories"][cat]["status"] == "ok", cat
    assert inv["categories"]["config_keys"]["details"]["triageEnabled"] is False
