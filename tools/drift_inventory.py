#!/usr/bin/env python3
"""Direction-check inventory (P24): what the code contains, compared with what docs/ plan.

Usage:
  drift_inventory.py [--root DIR] [--out PATH] [--since REF] [--merged-manifest PATH]
  drift_inventory.py --check [--baseline PATH] [--allow-skipped]
  drift_inventory.py --write-baseline

Deterministic and model-free. Every category lists `actual` (found in code), `documented`
(found in docs), `unplanned` (actual with no doc anchor), and `missing` (documented, not
found). Keys never carry line numbers, so a baseline survives unrelated edits; file and
line evidence goes in `details`.

--check compares `unplanned` in the gated categories with reports/direction/baseline.json
and exits 1 on any new item, 2 if a gated category could not be inventoried. The script
only writes under <root>/reports/direction/. It never edits android/, backend/, docs/, or
config/.
"""
import argparse
import ast
import importlib.util
import json
import pathlib
import re
import subprocess
import sys
import tomllib
import xml.etree.ElementTree as ET

REPO = pathlib.Path(__file__).resolve().parent.parent
A = "{http://schemas.android.com/apk/res/android}"

# Categories whose new unplanned items fail --check (P24 Build 1: a dependency,
# permission, endpoint, table, column, screen, or config key; plus the two privacy and
# PRD D10 checks the skill must also report).
GATED = (
    "modules", "module_edges", "libraries", "permissions", "exported_components",
    "room_tables", "room_columns", "pg_tables", "pg_columns", "endpoints", "error_codes",
    "screens", "config_keys", "screening_network", "triage_gate",
)
PROD_CONFIGS = {"implementation", "api", "compileOnly", "runtimeOnly", "ksp", "kapt",
                "coreLibraryDesugaring", "detektPlugins", "lintChecks"}
SCREENING_MODULES = (":feature:screening", ":feature:result", ":feature:log",
                     ":feature:calibration")
HTTP_LIBS = ("com.squareup.okhttp3:", "com.squareup.retrofit2:", "io.ktor:",
             "com.android.volley:", "org.apache.httpcomponents:")
# Word tokens keep \b on both sides; the URL( forms end in punctuation, where a trailing
# \b could never match before the opening quote.
NETWORK_SOURCE_RE = re.compile(
    r"(\b(?:HttpURLConnection|HttpsURLConnection|java\.net\.Socket|OkHttpClient|DatagramSocket)\b"
    r"|\bjava\.net\.URL\(|\bURL\(\s*\"https?:)")
WONT_HAVE_SIGNALS = {
    "library": ("com.android.billingclient:", "com.google.android.gms:play-services-ads",
                "com.google.firebase:", "io.sentry:", "com.google.android.gms:play-services-analytics",
                "com.crashlytics", "com.google.android.gms:play-services-auth"),
    "permission": ("com.android.vending.BILLING", "android.permission.ACCESS_FINE_LOCATION",
                   "android.permission.ACCESS_COARSE_LOCATION", "android.permission.READ_CONTACTS",
                   "android.permission.GET_ACCOUNTS", "com.google.android.gms.permission.AD_ID"),
}
REF_RE = re.compile(r"\bP\d{2}\b|\bNG-\d{3}\b|\bTC[-_][A-Z][A-Z0-9]*[-_]\d{2}\b|\bBUG-\d+\b")


# ---------------------------------------------------------------- helpers

def rel(root, path):
    return pathlib.Path(path).resolve().relative_to(root.resolve()).as_posix()


def section(text, start_heading, stop_re=r"^#{1,4} "):
    """Text from the line that starts with start_heading to the next heading of any level."""
    lines = text.splitlines()
    out, inside = [], False
    for line in lines:
        if line.startswith(start_heading):
            inside = True
            continue
        if inside and re.match(stop_re, line):
            break
        if inside:
            out.append(line)
    return "\n".join(out)


def category(actual, documented, details=None, status="ok", unplanned=None, missing=None):
    actual, documented = sorted(set(actual)), sorted(set(documented))
    return {
        "status": status,
        "actual": actual,
        "documented": documented,
        "unplanned": sorted(set(unplanned if unplanned is not None else set(actual) - set(documented))),
        "missing": sorted(set(missing if missing is not None else set(documented) - set(actual))),
        "details": details or {},
    }


def skipped(reason):
    return category([], [], status=f"skipped: {reason}")


def read(root, path):
    p = root / path
    return p.read_text(errors="replace") if p.exists() else ""


def load_module(name, path):
    spec = importlib.util.spec_from_file_location(name, path)
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod


# ---------------------------------------------------------------- Gradle

def gradle_projects(root):
    """Map Gradle path (':core:data') to its directory, from settings.gradle.kts."""
    settings = read(root, "android/settings.gradle.kts")
    projects = {}
    for m in re.finditer(r'^\s*include\("(:[^"]+)"\)', settings, re.M):
        projects[m.group(1)] = root / "android" / m.group(1).strip(":").replace(":", "/")
    for m in re.finditer(r'project\("(:[^"]+)"\)\.projectDir\s*=\s*file\("([^"]+)"\)', settings):
        projects[m.group(1)] = (root / "android" / m.group(2)).resolve()
    return projects


def doc_module_map(root):
    """Modules and allowed edges from the doc 4 Section 4.2 mermaid diagram and table."""
    text = section(read(root, "docs/4_System_Architecture.md"), "### 4.2 Module map")
    m = re.search(r"```mermaid\n(.*?)```", text, re.S)
    nodes, groups, edges = {}, {}, []
    current = None
    for line in (m.group(1) if m else "").splitlines():
        line = line.strip()
        sg = re.match(r'subgraph (\w+)\["([^"]*)"\]', line)
        if sg:
            current = sg.group(1)
            groups[current] = set()
            label = sg.group(2)
            if label.startswith(":"):
                nodes[current] = label
            continue
        if line == "end":
            current = None
            continue
        for nid, label in re.findall(r'(\w+)\["(:[a-z:]+)', line):
            nodes[nid] = label
            if current:
                groups[current].add(label)
        e = re.match(r"(\w+)\s*-->\s*(\w+)", line)
        if e:
            edges.append((e.group(1), e.group(2)))

    def expand(name):
        if name in groups and groups[name]:
            return groups[name]
        return {nodes[name]} if name in nodes else set()

    allowed = {(a, b) for x, y in edges for a in expand(x) for b in expand(y) if a != b}
    modules = set(nodes.values()) | set(re.findall(r"^\| `(:[a-z:]+)` \|", text, re.M))
    return modules, allowed


def catalog(root):
    path = root / "android/gradle/libs.versions.toml"
    if not path.exists():
        return {}
    data = tomllib.loads(path.read_text())
    libs = {}
    for alias, spec in data.get("libraries", {}).items():
        if isinstance(spec, str):
            coord = ":".join(spec.split(":")[:2])
        elif "module" in spec:
            coord = spec["module"]
        else:
            coord = f'{spec["group"]}:{spec["name"]}'
        libs[norm_alias(alias)] = coord
    return libs


def norm_alias(alias):
    return re.sub(r"[-_.]", ".", alias)


def convention_plugin_deps(root):
    """Library deps that each convention plugin id adds, from android/build-logic."""
    build = read(root, "android/build-logic/build.gradle.kts")
    ids = {cls.split(".")[-1]: pid for pid, cls in re.findall(
        r'id\s*=\s*"([^"]+)"\s*\n\s*implementationClass\s*=\s*"([^"]+)"', build)}
    out = {}
    for path in sorted((root / "android/build-logic/src").rglob("*.kt")):
        pid = ids.get(path.stem)
        if not pid:
            continue
        out[pid] = deps_in_text(path.read_text())
    return out


LIB_REF_RE = re.compile(r'libs\.findLibrary\("([^"]+)"\)|\blibs\.((?!plugins\b|versions\b|bundles\b)[A-Za-z][\w.]*?)(?=\s*[),]|\.get\(\))')


def deps_in_text(text):
    """[(configuration, kind, ref)] where kind is 'lib' (catalog alias) or 'project'."""
    found, variables = [], {}
    for line in text.splitlines():
        if line.strip().startswith("//"):
            continue
        v = re.match(r'\s*val (\w+)\s*=\s*libs\.findLibrary\("([^"]+)"\)', line)
        if v:
            variables[v.group(1)] = v.group(2)
            continue
        c = re.match(r'\s*"?([A-Za-z]+)"?\s*\(', line)
        if not c:
            continue
        conf = c.group(1)
        for p in re.findall(r'project\("(:[^"]+)"\)', line):
            found.append((conf, "project", p))
        for a, b in LIB_REF_RE.findall(line):
            found.append((conf, "lib", a or b))
        for var in re.findall(r"platform\((\w+)\)", line):
            if var in variables:
                found.append((conf, "lib", variables[var]))
    return found


def is_test_conf(conf):
    return conf.startswith(("test", "androidTest", "debug"))


def approved_libraries(root):
    text = section(read(root, "docs/4_System_Architecture.md"), "### 4.3a Approved libraries")
    rows = []
    for line in text.splitlines():
        cells = [c.strip() for c in line.strip().strip("|").split("|")]
        if len(cells) < 4 or not cells[0].startswith("`"):
            continue
        coords = re.findall(r"`([^`]+)`", cells[0])
        allowed = "any" if cells[1] == "any" else set(re.findall(r"`?(:[a-z:]+)`?", cells[1]))
        rows.append({"coords": coords, "allowed": allowed, "use": cells[2], "source": cells[3]})
    return rows


def coord_matches(pattern, coord):
    return coord == pattern or (pattern.endswith(":*") and coord.split(":")[0] == pattern[:-2])


def gradle_inventory(root):
    projects = gradle_projects(root)
    if not projects:
        return {k: skipped("android/settings.gradle.kts not found") for k in
                ("modules", "module_edges", "libraries", "screening_network")}
    doc_modules, allowed_edges = doc_module_map(root)
    libs = catalog(root)
    plugins = convention_plugin_deps(root)
    approved = approved_libraries(root)

    edges, test_edges, uses, unresolved = set(), set(), [], []
    for gpath, pdir in sorted(projects.items()):
        bf = pdir / "build.gradle.kts"
        if not bf.exists():
            continue
        text = bf.read_text()
        deps = deps_in_text(text)
        for pid in re.findall(r'id\("([^"]+)"\)', text):
            deps += plugins.get(pid, [])
        for conf, kind, ref in deps:
            if kind == "project":
                (test_edges if is_test_conf(conf) else edges).add((gpath, ref))
                continue
            coord = libs.get(norm_alias(ref))
            if coord is None:
                unresolved.append(f"{gpath}: {ref}")
                continue
            uses.append((gpath, coord, "test" if is_test_conf(conf) else "prod", conf))

    # Libraries against doc 4 Section 4.3a.
    lib_keys, violations, used_rows, why = set(), set(), set(), {}
    for gpath, coord, use, conf in uses:
        key = f"{gpath} {coord} {use}"
        lib_keys.add(key)
        row = next((i for i, r in enumerate(approved) if any(coord_matches(p, coord) for p in r["coords"])), None)
        if row is None:
            violations.add(key)
            why[key] = "no row in doc 4 Section 4.3a"
            continue
        r = approved[row]
        used_rows.add(row)
        if r["allowed"] != "any" and gpath not in r["allowed"]:
            violations.add(key)
            why[key] = f"approved only in {', '.join(sorted(r['allowed']))}"
        elif r["use"] == "test" and use == "prod":
            violations.add(key)
            why[key] = f"approved for test use only (configuration {conf})"
    unused_rows = [", ".join(approved[i]["coords"]) for i in range(len(approved)) if i not in used_rows]
    libraries = category(lib_keys, lib_keys - violations, unplanned=violations, missing=unused_rows,
                         details={"reasons": why, "unresolved_catalog_refs": unresolved,
                                  "approved_rows": len(approved)})

    edge_keys = {f"{a} -> {b}" for a, b in edges}
    modules = category(projects, doc_modules)
    module_edges = category(edge_keys, {f"{a} -> {b}" for a, b in allowed_edges},
                            unplanned={f"{a} -> {b}" for a, b in edges if (a, b) not in allowed_edges},
                            missing=[],
                            details={"test_only_edges": sorted(f"{a} -> {b}" for a, b in test_edges)})

    # Screening network reach: transitive production edges from screening modules and
    # engines, then HTTP libraries and network APIs in their main sources.
    graph = {}
    for a, b in edges:
        graph.setdefault(a, set()).add(b)
    starts = [m for m in projects if m in SCREENING_MODULES or m.startswith(":engine:")]
    reach_keys, evidence = set(), {}
    for start in starts:
        seen, stack = {start}, [start]
        while stack:
            for nxt in graph.get(stack.pop(), ()):
                if nxt not in seen:
                    seen.add(nxt)
                    stack.append(nxt)
        if ":sync:research" in seen:
            k = f"{start} reaches :sync:research"
            reach_keys.add(k)
        for gpath, coord, use, _ in uses:
            if use == "prod" and gpath in seen and coord.startswith(HTTP_LIBS):
                k = f"{start} reaches {coord} via {gpath}"
                reach_keys.add(k)
        for mod in sorted(seen):
            src = projects.get(mod, pathlib.Path("/nonexistent")) / "src/main"
            for f in sorted(src.rglob("*.kt")) if src.exists() else []:
                for n, line in enumerate(f.read_text(errors="replace").splitlines(), 1):
                    hit = NETWORK_SOURCE_RE.search(line)
                    if hit:
                        k = f"{start} reaches network API {hit.group(1)} in {rel(root, f)}"
                        reach_keys.add(k)
                        evidence.setdefault(k, f"{rel(root, f)}:{n}")
    screening_network = category(reach_keys, [], details={"start_modules": sorted(starts),
                                                          "evidence": evidence})
    wont = sorted({f"{g} {c}" for g, c, _, _ in uses if c.startswith(WONT_HAVE_SIGNALS["library"])})
    return {"modules": modules, "module_edges": module_edges, "libraries": libraries,
            "screening_network": screening_network, "_wont_libraries": wont}


# ---------------------------------------------------------------- manifest

def manifest_inventory(root, merged=None):
    cm_path = root / "tools/check_manifest.py"
    if not cm_path.exists():
        return {"permissions": skipped("tools/check_manifest.py not found"),
                "exported_components": skipped("tools/check_manifest.py not found")}
    cm = load_module("_check_manifest", cm_path)
    projects = gradle_projects(root)
    manifests = [pathlib.Path(merged)] if merged else sorted(
        p / "src/main/AndroidManifest.xml" for p in projects.values() if (p / "src/main/AndroidManifest.xml").exists())
    perms, exported, evidence = set(), set(), {}
    for mf in manifests:
        tree = ET.parse(mf).getroot()
        bf = mf.parent.parent.parent / "build.gradle.kts"
        ns = re.search(r'namespace\s*=\s*"([^"]+)"', bf.read_text()) if bf.exists() else None
        pkg = tree.get("package") or (ns.group(1) if ns else "")
        where = rel(root, mf) if mf.resolve().is_relative_to(root.resolve()) else str(mf)
        for p in tree.findall("uses-permission") + tree.findall("uses-permission-sdk-23"):
            name = p.get(A + "name")
            if name.endswith(".DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION"):
                continue
            perms.add(name)
            evidence.setdefault(name, where)
        app = tree.find("application")
        for tag in ("activity", "activity-alias", "service", "receiver", "provider"):
            for c in (app.findall(tag) if app is not None else []):
                name = c.get(A + "name") or ""
                if name.startswith("."):
                    name = pkg + name
                flag = c.get(A + "exported")
                if flag == "true" or (flag is None and c.find("intent-filter") is not None):
                    exported.add(name)
                    evidence.setdefault(name, where)
    doc_perm_row = next((row for row in read(root, "docs/8_Security_Compliance_Plan.md").splitlines()
                         if row.startswith("| Permissions |")), "")
    doc_perms = {f"android.permission.{t}" for t in re.findall(r"`([A-Z][A-Z_]+)`", doc_perm_row)
                 if t != "DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION"}
    allow = set(cm.ALLOWED_PERMISSIONS)
    details = {"manifests": [rel(root, m) if m.resolve().is_relative_to(root.resolve()) else str(m) for m in manifests],
               "source": "merged manifest" if merged else "source manifests (library-contributed entries need --merged-manifest)",
               "evidence": evidence,
               "check_manifest_vs_doc8": {"in_tool_not_doc": sorted(allow - doc_perms),
                                          "in_doc_not_tool": sorted(doc_perms - allow)}}
    wont = sorted(p for p in perms if p.startswith(WONT_HAVE_SIGNALS["permission"]))
    return {"permissions": category(perms, doc_perms, details=details,
                                    missing=[] if not merged else None),
            "exported_components": category(exported, cm.ALLOWED_EXPORTED, details={"evidence": evidence},
                                            missing=[] if not merged else None),
            "_wont_permissions": wont}


# ---------------------------------------------------------------- Room

def room_inventory(root):
    schemas = sorted((root / "android").glob("**/schemas/*/[0-9]*.json")) if (root / "android").exists() else []
    schemas = [s for s in schemas if "/build/" not in s.as_posix()]
    if not schemas:
        return {"room_tables": skipped("no exported Room schema"), "room_columns": skipped("no exported Room schema")}
    latest = {}
    for s in schemas:
        if s.parent not in latest or int(s.stem) > int(latest[s.parent].stem):
            latest[s.parent] = s
    tables, cols = set(), set()
    for s in latest.values():
        for e in json.loads(s.read_text())["database"]["entities"]:
            tables.add(e["tableName"])
            cols |= {f'{e["tableName"]}.{f["columnName"]}' for f in e["fields"]}
    text = section(read(root, "docs/5_Database_Schema.md"), "### 2.2 Tables", stop_re=r"^#{1,3} ")
    dt, dc, current = set(), set(), None
    for line in text.splitlines():
        h = re.match(r"^#### `(\w+)`", line)
        if h:
            current = h.group(1)
            dt.add(current)
            continue
        c = re.match(r"^\| `(\w+)` \|", line)
        if c and current:
            dc.add(f"{current}.{c.group(1)}")
    src = sorted(rel(root, s) for s in latest.values())
    return {"room_tables": category(tables, dt, details={"schemas": src}),
            "room_columns": category(cols, dc, details={"schemas": src})}


# ---------------------------------------------------------------- PostgreSQL (Alembic head)

def _const(node):
    return node.value if isinstance(node, ast.Constant) and isinstance(node.value, str) else None


def _column_name(node):
    if isinstance(node, ast.Call) and getattr(node.func, "attr", getattr(node.func, "id", "")) == "Column" and node.args:
        return _const(node.args[0])
    return None


def alembic_head(root):
    versions = root / "backend/alembic/versions"
    files = sorted(versions.glob("*.py")) if versions.exists() else []
    revs, unparsed = {}, []
    for f in files:
        tree = ast.parse(f.read_text())
        info = {"file": f, "tree": tree, "rev": None, "down": None}
        for node in tree.body:
            if isinstance(node, (ast.Assign, ast.AnnAssign)):
                target = node.targets[0] if isinstance(node, ast.Assign) else node.target
                name = getattr(target, "id", None)
                if name == "revision":
                    info["rev"] = _const(node.value)
                elif name == "down_revision":
                    info["down"] = _const(node.value) if node.value is not None else None
        revs[info["rev"]] = info
    order, cur = [], next((r for r, i in revs.items() if i["down"] is None), None)
    while cur is not None:
        order.append(revs[cur])
        cur = next((r for r, i in revs.items() if i["down"] == cur), None)
    tables = {}
    for info in order:
        up = next((n for n in info["tree"].body if isinstance(n, ast.FunctionDef) and n.name == "upgrade"), None)
        for node in ast.walk(up) if up else []:
            if not (isinstance(node, ast.Call) and isinstance(node.func, ast.Attribute)
                    and getattr(node.func.value, "id", "") == "op"):
                continue
            op, args = node.func.attr, node.args
            if op == "create_table" and args:
                tables[_const(args[0])] = {c for c in map(_column_name, args[1:]) if c}
            elif op == "add_column" and len(args) >= 2:
                tables.setdefault(_const(args[0]), set()).add(_column_name(args[1]))
            elif op == "drop_column" and len(args) >= 2:
                tables.get(_const(args[0]), set()).discard(_const(args[1]))
            elif op == "drop_table" and args:
                tables.pop(_const(args[0]), None)
            elif op in ("execute", "batch_alter_table", "alter_column", "rename_table"):
                unparsed.append(f"{rel(root, info['file'])}:{node.lineno} op.{op}")
    return tables, [rel(root, i["file"]) for i in order], unparsed


def doc_ddl(root):
    text = section(read(root, "docs/5_Database_Schema.md"), "### 3.2 DDL", stop_re=r"^#{1,3} ")
    tables = {}
    for name, body in re.findall(r"CREATE TABLE (\w+)\s*\((.*?)\n\);", text, re.S):
        cols = set()
        for line in body.splitlines():
            s = line.strip()
            if not s or s.startswith(("--", "PRIMARY", "UNIQUE", "CHECK", "FOREIGN", "CONSTRAINT", "EXCLUDE", ")")):
                continue
            m = re.match(r"([a-z_][a-z0-9_]*)\s+[a-z]", s)
            if m:
                cols.add(m.group(1))
        tables[name] = cols
    for t, c in re.findall(r"ALTER TABLE (\w+) ADD COLUMN (?:IF NOT EXISTS )?(\w+)", text):
        tables.setdefault(t, set()).add(c)
    return tables


ORM_SCRIPT = """
import json, sys
sys.path.insert(0, ".")
from app.models.db import Base
print(json.dumps({t.name: sorted(c.name for c in t.columns) for t in Base.metadata.sorted_tables}))
"""


def pg_inventory(root, python):
    head, files, unparsed = alembic_head(root)
    if not files:
        return {"pg_tables": skipped("no Alembic migrations"), "pg_columns": skipped("no Alembic migrations")}
    doc = doc_ddl(root)
    details = {"migrations": files, "unparsed_operations": unparsed,
               "source": "static parse of Alembic upgrade() chain"}
    orm = run_backend(root, python, ORM_SCRIPT)
    if isinstance(orm, dict):
        details["orm_vs_migration"] = {
            "tables_only_in_orm": sorted(set(orm) - set(head)),
            "tables_only_in_migration": sorted(set(head) - set(orm)),
            "columns_only_in_orm": sorted(f"{t}.{c}" for t in orm for c in orm[t] if t in head and c not in head[t]),
            "columns_only_in_migration": sorted(f"{t}.{c}" for t in head for c in head[t] if t in orm and c not in orm[t]),
        }
    else:
        details["orm_vs_migration"] = f"not compared: {orm}"
    cols = {f"{t}.{c}" for t, cs in head.items() for c in cs}
    dcols = {f"{t}.{c}" for t, cs in doc.items() for c in cs}
    return {"pg_tables": category(head, doc, details=details),
            "pg_columns": category(cols, dcols, details={"migrations": files})}


# ---------------------------------------------------------------- OpenAPI

OPENAPI_SCRIPT = """
import json, sys
sys.path.insert(0, ".")
from app.main import app
print(json.dumps(app.openapi()))
"""


def run_backend(root, python, script):
    backend = root / "backend"
    if not (backend / "app").exists():
        return "backend/app not found"
    try:
        res = subprocess.run([python, "-c", script], cwd=backend, capture_output=True, text=True, timeout=120)
    except (OSError, subprocess.TimeoutExpired) as e:
        return f"could not run {python}: {e.__class__.__name__}"
    if res.returncode != 0:
        last = (res.stderr.strip().splitlines() or ["unknown error"])[-1]
        return f"backend import failed ({last[:200]})"
    return json.loads(res.stdout.strip().splitlines()[-1])


def _schema_props(spec, schema, depth=0):
    if not isinstance(schema, dict) or depth > 5:
        return set()
    if "$ref" in schema:
        node = spec
        for part in schema["$ref"].lstrip("#/").split("/"):
            node = node.get(part, {})
        return _schema_props(spec, node, depth + 1)
    props = set(schema.get("properties", {}))
    for key in ("allOf", "oneOf", "anyOf"):
        for sub in schema.get(key, []):
            props |= _schema_props(spec, sub, depth + 1)
    return props


def _ops(spec):
    out = {}
    for path, item in (spec.get("paths") or {}).items():
        for method, op in item.items():
            if method not in ("get", "put", "post", "delete", "patch", "head", "options"):
                continue
            body = (((op.get("requestBody") or {}).get("content") or {}).get("application/json") or {}).get("schema")
            out[f"{method.upper()} {path}"] = {
                "responses": sorted(str(c) for c in (op.get("responses") or {})),
                "request_properties": sorted(_schema_props(spec, body)) if body else None,
            }
    return out


def openapi_inventory(root, python):
    yaml_path = root / "docs/api/openapi.yaml"
    if not yaml_path.exists():
        return {"endpoints": skipped("docs/api/openapi.yaml not found"), "error_codes": skipped("docs/api/openapi.yaml not found")}
    try:
        import yaml
    except ImportError:
        return {"endpoints": skipped("PyYAML not installed"), "error_codes": skipped("PyYAML not installed")}
    doc = yaml.safe_load(yaml_path.read_text())
    doc_ops = _ops(doc)
    doc_codes = set(((doc.get("components") or {}).get("schemas") or {}).get("ErrorCode", {}).get("enum") or [])
    code_codes, code_ev = set(), {}
    for f in sorted((root / "backend/app").rglob("*.py")) if (root / "backend/app").exists() else []:
        for n, line in enumerate(f.read_text().splitlines(), 1):
            for c in re.findall(r'\bcode\s*=\s*"([A-Z][A-Z0-9_]+)"', line):
                code_codes.add(c)
                code_ev.setdefault(c, f"{rel(root, f)}:{n}")
    error_codes = category(code_codes, doc_codes, details={"evidence": code_ev,
                                                           "source": "code=\"...\" literals in backend/app"})
    gen = run_backend(root, python, OPENAPI_SCRIPT)
    if not isinstance(gen, dict):
        return {"endpoints": skipped(gen), "error_codes": error_codes}
    gen_ops = _ops(gen)
    diffs = {}
    for k in sorted(set(gen_ops) & set(doc_ops)):
        g, d = gen_ops[k], doc_ops[k]
        entry = {}
        if set(g["responses"]) != set(d["responses"]):
            entry["responses_only_in_code"] = sorted(set(g["responses"]) - set(d["responses"]) - {"422"})
            entry["responses_only_in_spec"] = sorted(set(d["responses"]) - set(g["responses"]))
        if (g["request_properties"] or d["request_properties"]) and set(g["request_properties"] or []) != set(d["request_properties"] or []):
            entry["request_properties_only_in_code"] = sorted(set(g["request_properties"] or []) - set(d["request_properties"] or []))
            entry["request_properties_only_in_spec"] = sorted(set(d["request_properties"] or []) - set(g["request_properties"] or []))
        entry = {k2: v for k2, v in entry.items() if v}
        if entry:
            diffs[k] = entry
    return {"endpoints": category(gen_ops, doc_ops, details={
                "operation_differences": diffs,
                "note": "FastAPI's automatic 422 response is ignored; the API returns 400 VALIDATION_FAILED (doc 7)"}),
            "error_codes": error_codes}


# ---------------------------------------------------------------- navigation, strings, config

def _screen_ids(const_name):
    m = re.match(r"^(S\d{2}A?|R\d{2})(?:_(S\d{2}))?(?:_|$)", const_name)
    if not m:
        return []
    first = m.group(1).replace("A", "a") if m.group(1).endswith("A") else m.group(1)
    if m.group(2):
        lo, hi = int(m.group(1)[1:3]), int(m.group(2)[1:])
        return [f"S{n:02d}" for n in range(lo, hi + 1)]
    return [first]


def screens_inventory(root):
    dest = sorted((root / "android").glob("**/src/main/**/Destinations.kt")) if (root / "android").exists() else []
    if not dest:
        return {"screens": skipped("Destinations.kt not found")}
    actual, ev = set(), {}
    for f in dest:
        for n, line in enumerate(f.read_text().splitlines(), 1):
            m = re.match(r"\s*const val (\w+)\s*=", line)
            if m:
                for sid in _screen_ids(m.group(1)):
                    actual.add(sid)
                    ev[sid] = f"{rel(root, f)}:{n}"
    text = section(read(root, "docs/6_UI_UX_Guidelines.md"), "## 5. Screen Inventory", stop_re=r"^## ")
    documented = set()
    for a, b in re.findall(r"^\| (S\d{2}a?|R\d{2})(?: to (S\d{2}|R\d{2}))? \|", text, re.M):
        if b:
            documented |= {f"{a[0]}{n:02d}" for n in range(int(a[1:3]), int(b[1:]) + 1)}
        else:
            documented.add(a)
    placeholders = []
    for f in sorted((root / "android").glob("**/src/main/**/*.kt")):
        lines = f.read_text(errors="replace").splitlines()
        for n, line in enumerate(lines, 1):
            m = re.search(r"composable\(Destinations\.(\w+)\)", line)
            window = [line, *lines[n:n + 2]]
            cut = next((i for i, w in enumerate(window[1:], 1) if "composable(" in w), len(window))
            if m and "PlaceholderScreen(" in " ".join(window[:cut]):
                placeholders.append(f"{'/'.join(_screen_ids(m.group(1)))} {rel(root, f)}:{n}")
    return {"screens": category(actual, documented, details={"evidence": ev, "placeholders": placeholders})}


def _string_keys(path):
    root = ET.parse(path).getroot()
    return {e.get("name") for e in root if e.tag in ("string", "plurals", "string-array") and e.get("name")}


def strings_inventory(root):
    base = root / "android"
    en, bn = set(), set()
    files = {"values": [], "values-bn": []}
    for f in sorted(base.glob("**/src/main/res/values/strings.xml")) if base.exists() else []:
        en |= _string_keys(f)
        files["values"].append(rel(root, f))
    for f in sorted(base.glob("**/src/main/res/values-bn/strings.xml")) if base.exists() else []:
        bn |= _string_keys(f)
        files["values-bn"].append(rel(root, f))
    if not files["values"]:
        return {"strings": skipped("no values/strings.xml")}
    text = section(read(root, "docs/6_UI_UX_Guidelines.md"), "### 10.4 Core microcopy")
    doc = set(re.findall(r"^\| `(\w+)` \|", text, re.M))
    return {"strings": category(en | bn, doc, unplanned=[], missing=doc - (en & bn), details={
        "files": files,
        "doc_keys_missing_in_values": sorted(doc - en),
        "doc_keys_missing_in_values_bn": sorted(doc - bn),
        "in_values_not_values_bn": sorted(en - bn),
        "in_values_bn_not_values": sorted(bn - en),
        "note": "report only: UI strings beyond doc 6 Section 10.4 are expected",
    })}


def _config_paths(obj, prefix="", depth=0):
    paths, wild = set(), set()
    for k, v in obj.items():
        if k == "...":
            wild.add(prefix.rstrip("."))
            continue
        paths.add(prefix + k)
        if isinstance(v, dict) and depth < 1:
            p, w = _config_paths(v, prefix + k + ".", depth + 1)
            paths |= p
            wild |= w
    return paths, wild


def config_inventory(root):
    cfg_path = root / "config/triage-config.json"
    if not cfg_path.exists():
        return {"config_keys": skipped("config/triage-config.json not found")}
    cfg = json.loads(cfg_path.read_text())
    actual, _ = _config_paths(cfg)
    text = section(read(root, "docs/4_System_Architecture.md"), "### 6.1 Signed configuration")
    m = re.search(r"```json\n(.*?)```", text, re.S)
    documented, wild = _config_paths(json.loads(m.group(1))) if m else (set(), set())
    unplanned = {p for p in actual - documented if not any(p.startswith(w + ".") for w in wild)}
    return {"config_keys": category(actual, documented, unplanned=unplanned, details={
        "wildcards_in_doc": sorted(wild),
        "triageEnabled": cfg.get("triageEnabled"),
        "validUntil": cfg.get("validUntil"),
        "clinicalSignoff_status": (cfg.get("clinicalSignoff") or {}).get("status"),
        "note": "keys only; this tool never reads or reports threshold values",
    })}


TRIAGE_TRUE = (
    (re.compile(r"triageEnabled\s*:\s*Boolean\s*=\s*true"), "Kotlin parameter defaults to true"),
    (re.compile(r"(?<![\w.])triageEnabled\s*=\s*true\b"), "Kotlin literal assignment of true"),
    (re.compile(r'"triageEnabled"\s*:\s*true'), "JSON value true"),
    (re.compile(r"buildConfigField\([^)]*TRIAGE", re.I), "buildConfigField touching triage"),
)


def triage_gate_inventory(root):
    """PRD D10: every place in shipped code or bundled config that can make triageEnabled true."""
    files = []
    if (root / "android").exists():
        files += [f for f in sorted((root / "android").glob("**/src/main/**/*")) if f.suffix in (".kt", ".json")]
        files += sorted((root / "android").glob("**/build.gradle.kts"))
    files += sorted((root / "config").glob("*.json")) if (root / "config").exists() else []
    keys, ev = set(), {}
    for f in files:
        if "/build/" in f.as_posix() or not f.is_file():
            continue
        for n, line in enumerate(f.read_text(errors="replace").splitlines(), 1):
            for rx, what in TRIAGE_TRUE:
                if rx.search(line):
                    k = f"{rel(root, f)}: {what}"
                    keys.add(k)
                    ev.setdefault(k, []).append(f"{rel(root, f)}:{n}: {line.strip()[:160]}")
    return {"triage_gate": category(keys, [], details={"evidence": ev})}


# ---------------------------------------------------------------- tests and git

def tc_inventory(root):
    trace_path, doc10 = root / "tools/trace.py", root / "docs/10_Test_Cases.md"
    if not (trace_path.exists() and doc10.exists()):
        return {"test_cases": skipped("tools/trace.py or doc 10 not found")}
    trace = load_module("_trace", trace_path)
    cases, _ = trace.test_cases(doc10.read_text())
    sites = trace.count_tests(root, cases)
    cited = {t for t, s in sites.items() if s}
    return {"test_cases": category(cited, cases, unplanned=cited - set(cases), details={
        "tests_per_tc": {t: len(sites.get(t, [])) for t in cases},
        "zero_tests": [t for t in cases if not sites.get(t)],
        "sites_for_unknown": {t: sites[t][:5] for t in sorted(cited - set(cases))},
        "count_rule": "a test is a file:line in test code that cites the TC ID (TC-X-NN or TC_X_NN)",
    })}


def git(root, *args):
    res = subprocess.run(["git", "-C", str(root), *args], capture_output=True, text=True)
    return res.stdout if res.returncode == 0 else None


def git_inventory(root, since):
    head = git(root, "rev-parse", "HEAD")
    if head is None:
        return {"backlog_refs": skipped("not a git repository"), "_commits": {}}
    log_all = git(root, "log", "--format=%H%x1f%s%x1f%b%x1e") or ""
    refs = set()
    for entry in log_all.split("\x1e"):
        refs |= set(re.findall(r"\bNG-\d{3}\b", entry))
        for lo, hi in re.findall(r"\bNG-(\d{3})(?:\.\.|\s+to\s+)NG-(\d{3})\b", entry):
            refs |= {f"NG-{n:03d}" for n in range(int(lo), int(hi) + 1)}
    text = read(root, "docs/3_Feature_Backlog.md")
    must_text = section(text, "## MUST HAVE", stop_re=r"^## ")
    must = {}
    for line in must_text.splitlines():
        cells = [c.strip() for c in line.strip().strip("|").split("|")]
        if len(cells) >= 6 and re.fullmatch(r"NG-\d{3}", cells[0]):
            must[cells[0]] = cells[5]
    v1_must = {k for k, rel_ in must.items() if rel_ in ("v0.1", "v0.5", "v1.0")}
    # Commits mostly cite their work package (P13); doc 14 Section 1 maps packages to NG items.
    wp_ng = {}
    for line in section(read(root, "docs/14_Build_Plan.md"), "## 1. Phases", stop_re=r"^## ").splitlines():
        cells = [c.strip() for c in line.strip().strip("|").split("|")]
        if len(cells) >= 5 and re.fullmatch(r"P\d{2}", cells[0]):
            ids = set(re.findall(r"NG-\d{3}", cells[4]))
            for lo, hi in re.findall(r"NG-(\d{3}) to NG-(\d{3})", cells[4]):
                ids |= {f"NG-{n:03d}" for n in range(int(lo), int(hi) + 1)}
            wp_ng[cells[0]] = ids
    wps = set(re.findall(r"\bP\d{2}\b", log_all))
    via_wp = set().union(*(wp_ng.get(p, set()) for p in wps)) if wps else set()
    if since:
        # Resolve to a commit first: a value such as "--output=x" must never reach git
        # as an option (P24 privacy review, finding 1).
        resolved = None if since.startswith("-") else git(
            root, "rev-parse", "--verify", "--quiet", "--end-of-options", f"{since}^{{commit}}")
        if not resolved:
            return {"backlog_refs": skipped(f"--since {since!r} is not a commit"), "_commits": {}}
        since = resolved.strip()
    rng = f"{since}..HEAD" if since else "HEAD"
    log = git(root, "log", "--no-merges", "--format=%H%x1f%s%x1f%b%x1e", "--end-of-options", rng)
    if log is None:
        return {"backlog_refs": skipped(f"git log failed for {rng}"), "_commits": {}}
    commits = []
    for entry in log.split("\x1e"):
        parts = entry.strip("\n").split("\x1f")
        if len(parts) < 2 or not parts[0]:
            continue
        sha, subject, body = parts[0], parts[1], parts[2] if len(parts) > 2 else ""
        if not REF_RE.search(subject + "\n" + body):
            commits.append(f"{sha[:10]} {subject}")
    backlog = category((refs | via_wp) & set(must), v1_must, unplanned=[], missing=v1_must - refs - via_wp, details={
        "referenced_ng_ids": sorted(refs),
        "referenced_work_packages": sorted(wps),
        "covered_only_via_work_package": sorted((v1_must & via_wp) - refs),
        "v1_must_items": sorted(v1_must),
        "source": "git commit messages only (PR descriptions are not visible without credentials)",
    })
    return {"backlog_refs": backlog, "_commits": {
        "head": head.strip(), "since": since, "range": rng,
        "unreferenced": commits,
        "note": "non-merge commits whose message cites no P, NG, TC, or BUG ID; reported, not gated",
    }}


# ---------------------------------------------------------------- driver

def inventory(root, since=None, merged_manifest=None, python=None):
    python = python or sys.executable
    cats = {}
    gradle = gradle_inventory(root)
    manifest = manifest_inventory(root, merged_manifest)
    for part in (gradle, manifest, room_inventory(root), pg_inventory(root, python),
                 openapi_inventory(root, python), screens_inventory(root), strings_inventory(root),
                 config_inventory(root), triage_gate_inventory(root), tc_inventory(root)):
        cats.update({k: v for k, v in part.items() if not k.startswith("_")})
    gitinv = git_inventory(root, since)
    cats["backlog_refs"] = gitinv["backlog_refs"]
    return {
        "schema": 1,
        "tool": "tools/drift_inventory.py",
        "commit": gitinv["_commits"].get("head"),
        "gated": list(GATED),
        "categories": dict(sorted(cats.items())),
        "commits": gitinv["_commits"],
        "wont_have_signals": {"libraries": gradle.get("_wont_libraries", []),
                              "permissions": manifest.get("_wont_permissions", [])},
        "summary": {k: {"status": v["status"], "unplanned": len(v["unplanned"]), "missing": len(v["missing"])}
                    for k, v in sorted(cats.items())},
    }


def safe_out(root, path):
    p = (root / path).resolve() if not pathlib.Path(path).is_absolute() else pathlib.Path(path).resolve()
    allowed = root.resolve() / "reports" / "direction"
    if (root / "reports").is_symlink() or (root / "reports/direction").is_symlink():
        raise SystemExit("refusing to write: reports/ or reports/direction/ is a symlink")
    if not p.is_relative_to(allowed):
        raise SystemExit(f"refusing to write {p}: output must be under {allowed}")
    p.parent.mkdir(parents=True, exist_ok=True)
    return p


def check(inv, baseline, allow_skipped=False):
    """Return (exit_code, lines)."""
    lines, new, blocked = [], 0, False
    base = baseline.get("unplanned", {})
    for cat in GATED:
        c = inv["categories"].get(cat)
        if c is None or c["status"] != "ok":
            status = c["status"] if c else "missing from inventory"
            lines.append(f"CANNOT CHECK [{cat}]: {status}")
            blocked = blocked or not allow_skipped
            continue
        known = set(base.get(cat, []))
        for key in c["unplanned"]:
            if key not in known:
                new += 1
                lines.append(f"NEW UNPLANNED [{cat}]: {key}")
        for key in sorted(known - set(c["unplanned"])):
            lines.append(f"RESOLVED [{cat}]: {key} (remove it from the baseline)")
    if new:
        lines.append(f"FAIL: {new} new unplanned item(s). Anchor each in docs (for a library, a row in doc 4 "
                     "Section 4.3a) or remove it. The baseline changes only with the doc change that explains it.")
        return 1, lines
    if blocked:
        lines.append("FAIL: a gated category could not be inventoried (install backend/requirements.lock, or pass --allow-skipped)")
        return 2, lines
    lines.append("PASS: no new unplanned items")
    return 0, lines


def baseline_from(inv):
    return {"schema": 1, "commit": inv["commit"],
            "note": "Accepted unplanned items. Change only in a PR that also updates the doc explaining the change (P24).",
            "unplanned": {cat: inv["categories"][cat]["unplanned"] for cat in GATED if cat in inv["categories"]}}


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--root", default=str(REPO))
    ap.add_argument("--out", help="write the inventory JSON here (under reports/direction/); default stdout")
    ap.add_argument("--since", help="git ref; unreferenced commits are listed from here (default: all history)")
    ap.add_argument("--merged-manifest", help="merged AndroidManifest.xml from a release build")
    ap.add_argument("--python", help="interpreter with backend/requirements.lock installed (default: this one)")
    ap.add_argument("--check", action="store_true")
    ap.add_argument("--baseline", default="reports/direction/baseline.json")
    ap.add_argument("--allow-skipped", action="store_true")
    ap.add_argument("--write-baseline", action="store_true")
    args = ap.parse_args(argv)
    root = pathlib.Path(args.root).resolve()
    inv = inventory(root, args.since, args.merged_manifest, args.python)
    if args.write_baseline:
        out = safe_out(root, args.baseline)
        out.write_text(json.dumps(baseline_from(inv), indent=2) + "\n")
        print(f"wrote {rel(root, out)}")
        return 0
    if args.check:
        bpath = root / args.baseline
        if not bpath.exists():
            print(f"FAIL: baseline {args.baseline} not found")
            return 2
        code, lines = check(inv, json.loads(bpath.read_text()), args.allow_skipped)
        print("\n".join(lines))
        return code
    text = json.dumps(inv, indent=2) + "\n"
    if args.out:
        out = safe_out(root, args.out)
        out.write_text(text)
        print(f"wrote {rel(root, out)}")
    else:
        sys.stdout.write(text)
    for k, s in inv["summary"].items():
        print(f"{k}: {s['status']}, {s['unplanned']} unplanned, {s['missing']} missing", file=sys.stderr)
    return 0


if __name__ == "__main__":
    sys.exit(main())
