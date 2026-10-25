from __future__ import annotations

import sys
from pathlib import Path

import yaml

# Add backend directory to sys.path so app can be imported
backend_dir = Path(__file__).resolve().parent.parent
if str(backend_dir) not in sys.path:
    sys.path.insert(0, str(backend_dir))

from app.main import app


def diff_spec(spec_path: str) -> list[str]:
    with open(spec_path, encoding="utf-8") as f:
        spec = yaml.safe_load(f)

    gen = app.openapi()

    diffs: list[str] = []

    # 1. Compare Paths
    spec_paths = set(spec.get("paths", {}).keys())
    gen_paths = set(gen.get("paths", {}).keys())

    missing_paths = spec_paths - gen_paths
    if missing_paths:
        diffs.append(f"Missing paths in generated schema: {sorted(missing_paths)}")

    extra_paths = gen_paths - spec_paths
    if extra_paths:
        diffs.append(f"Extra paths in generated schema: {sorted(extra_paths)}")

    # 2. Compare Operations and Status Codes
    for path in sorted(spec_paths & gen_paths):
        spec_ops = spec["paths"][path]
        gen_ops = gen["paths"][path]

        spec_methods = {m.lower() for m in spec_ops if m.lower() in ("get", "post", "put", "delete", "patch")}
        gen_methods = {m.lower() for m in gen_ops if m.lower() in ("get", "post", "put", "delete", "patch")}

        missing_methods = spec_methods - gen_methods
        if missing_methods:
            diffs.append(f"Path {path}: missing methods {sorted(missing_methods)}")

        extra_methods = gen_methods - spec_methods
        if extra_methods:
            diffs.append(f"Path {path}: extra methods {sorted(extra_methods)}")

        for method in sorted(spec_methods & gen_methods):
            spec_responses = {str(k) for k in spec_ops[method].get("responses", {})}
            gen_responses = {str(k) for k in gen_ops[method].get("responses", {})}

            missing_codes = spec_responses - gen_responses
            if missing_codes:
                diffs.append(f"Path {path} [{method.upper()}]: missing response status codes {sorted(missing_codes)}")

    # 3. Compare Components Schemas
    spec_schemas = spec.get("components", {}).get("schemas", {})
    gen_schemas = gen.get("components", {}).get("schemas", {})

    missing_schemas = set(spec_schemas.keys()) - set(gen_schemas.keys())
    if missing_schemas:
        diffs.append(f"Missing component schemas: {sorted(missing_schemas)}")

    for schema_name in sorted(set(spec_schemas.keys()) & set(gen_schemas.keys())):
        s_spec = spec_schemas[schema_name]
        s_gen = gen_schemas[schema_name]

        # Compare required fields if object
        if s_spec.get("type") == "object":
            req_spec = set(s_spec.get("required", []))
            req_gen = set(s_gen.get("required", []))
            if req_spec != req_gen:
                diffs.append(
                    f"Schema '{schema_name}': required fields mismatch. Expected {sorted(req_spec)}, got {sorted(req_gen)}"
                )

            # Compare properties
            props_spec = set(s_spec.get("properties", {}).keys())
            props_gen = set(s_gen.get("properties", {}).keys())
            if props_spec != props_gen:
                diffs.append(
                    f"Schema '{schema_name}': properties mismatch. Missing in generated: {sorted(props_spec - props_gen)}, Extra: {sorted(props_gen - props_spec)}"
                )

    return diffs


def main():
    spec_path = sys.argv[1] if len(sys.argv) > 1 else "docs/api/openapi.yaml"

    if not Path(spec_path).exists():
        # try repo root
        root_spec = Path(__file__).resolve().parents[2] / spec_path
        if root_spec.exists():
            spec_path = str(root_spec)
        else:
            print(f"Error: Spec file not found at {spec_path}", file=sys.stderr)
            sys.exit(1)

    print(f"Comparing FastAPI generated schema with {spec_path}...")
    diffs = diff_spec(spec_path)

    if diffs:
        print(f"FAIL: {len(diffs)} differences found between FastAPI schema and openapi.yaml:", file=sys.stderr)
        for d in diffs:
            print(f"  - {d}", file=sys.stderr)
        sys.exit(1)
    else:
        print("OK: FastAPI schema matches openapi.yaml exactly (paths, methods, status codes, schemas).")
        sys.exit(0)


if __name__ == "__main__":
    main()
