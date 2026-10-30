#!/usr/bin/env python3
"""Generates a CycloneDX 1.5 JSON Software Bill of Materials (SBOM) for Android.

Usage:
    python tools/generate_sbom.py --lockfile android/app/gradle.lockfile --output android/app/build/reports/bom.json [--app-version 0.5.0]

The components are the libraries of the release runtime classpath (what ships in the app), not every configuration in the
lockfile: build tools, lint and test libraries are not in the app and do not belong in its bill of materials (BUG-2026-093).
Each component carries the SHA-256 of its main artifact from gradle/verification-metadata.xml when that file lists it.

Gate S3, doc 8 Section 10, TC-SEC-05.
"""
import argparse
import datetime as dt
import json
from pathlib import Path
import sys
import uuid
import xml.etree.ElementTree as ET

RELEASE_CONFIGURATION = "releaseRuntimeClasspath"
VERIFICATION_METADATA = Path(__file__).resolve().parent.parent / "android" / "gradle" / "verification-metadata.xml"
_MAIN_ARTIFACT_SUFFIXES = (".aar", ".jar")

# Re-use license mapping from check_licenses
sys.path.insert(0, str(Path(__file__).resolve().parent))
from check_licenses import resolve_dependency_license


def load_artifact_hashes(metadata_path: Path) -> dict:
    """Map "group:name:version" to the SHA-256 of its .aar or .jar from Gradle's verification metadata."""
    if not metadata_path.is_file():
        return {}
    hashes = {}
    for component in ET.parse(metadata_path).getroot().iter():
        if not component.tag.endswith("component"):
            continue
        coord = f"{component.get('group')}:{component.get('name')}:{component.get('version')}"
        for artifact in component:
            name = artifact.get("name") or ""
            if not name.endswith(_MAIN_ARTIFACT_SUFFIXES) or "-sources" in name:
                continue
            for digest in artifact:
                if digest.tag.endswith("sha256") and digest.get("value"):
                    hashes.setdefault(coord, digest.get("value"))
    return hashes


def generate_cyclonedx_sbom(
    lockfile_path: Path,
    output_path: Path,
    app_version: str = "1.0.0",
    configuration: str | None = RELEASE_CONFIGURATION,
    verification_metadata: Path | None = None,
) -> dict:
    """[configuration] limits the components to one Gradle configuration; None lists every locked dependency."""
    if not lockfile_path.is_file():
        raise FileNotFoundError(f"Lockfile not found at {lockfile_path}")
    artifact_hashes = load_artifact_hashes(verification_metadata or VERIFICATION_METADATA)

    now_iso = dt.datetime.now(dt.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")
    serial_uuid = str(uuid.uuid4())

    root_bom_ref = f"pkg:maven/com.kitalonlabs.neogold/app@{app_version}?type=aab"

    components = []
    dep_refs = []

    for line in lockfile_path.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        coord, _, configurations = line.partition("=")
        coord = coord.strip()
        if ":" not in coord:
            continue
        if configuration is not None and configuration not in configurations.split(","):
            continue

        parts = coord.split(":")
        if len(parts) < 3:
            continue
        group, name, version = parts[0], parts[1], parts[2]
        purl = f"pkg:maven/{group}/{name}@{version}"
        lic_id = resolve_dependency_license(coord)

        component_entry = {
            "bom-ref": purl,
            "type": "library",
            "group": group,
            "name": name,
            "version": version,
            "purl": purl,
            "scope": "required",
            "licenses": [
                {
                    "license": {
                        "id": lic_id if lic_id != "UNKNOWN" else "Proprietary",
                        "name": lic_id,
                    }
                }
            ],
        }
        digest = artifact_hashes.get(f"{group}:{name}:{version}")
        if digest:
            component_entry["hashes"] = [{"alg": "SHA-256", "content": digest}]
        components.append(component_entry)
        dep_refs.append(purl)

    # Sort components deterministically by group, name, version
    components.sort(key=lambda c: (c["group"], c["name"], c["version"]))

    sbom = {
        "$schema": "http://cyclonedx.org/schema/bom-1.5.schema.json",
        "bomFormat": "CycloneDX",
        "specVersion": "1.5",
        "serialNumber": f"urn:uuid:{serial_uuid}",
        "version": 1,
        "metadata": {
            "timestamp": now_iso,
            "tools": [
                {
                    "vendor": "Kitalon Labs",
                    "name": "neogold-sbom-generator",
                    "version": "1.0.0",
                }
            ],
            "component": {
                "bom-ref": root_bom_ref,
                "type": "application",
                "group": "com.kitalonlabs.neogold",
                "name": "neogold-android",
                "version": app_version,
                "description": "NeoGold Android Application - Newborn Jaundice Screening Aid",
                "licenses": [
                    {
                        "license": {
                            "id": "MIT",
                            "name": "MIT License",
                        }
                    }
                ],
            },
        },
        "components": components,
        "dependencies": [
            {
                "ref": root_bom_ref,
                "dependsOn": sorted(dep_refs),
            }
        ],
    }

    output_path.parent.mkdir(parents=True, exist_ok=True)
    with open(output_path, "w", encoding="utf-8") as f:
        json.dump(sbom, f, indent=2, sort_keys=True)
        f.write("\n")

    return sbom


def main() -> int:
    parser = argparse.ArgumentParser(description="Generate CycloneDX 1.5 JSON SBOM for Android.")
    parser.add_argument("--lockfile", type=Path, default=Path("android/app/gradle.lockfile"), help="Path to gradle.lockfile")
    parser.add_argument("--output", type=Path, default=Path("android/app/build/reports/bom.json"), help="Output bom.json path")
    parser.add_argument("--app-version", type=str, default="1.0.0", help="App version string")

    args = parser.parse_args()

    # Resolve relative paths
    root = Path(__file__).resolve().parent.parent
    lockfile = args.lockfile if args.lockfile.is_absolute() else root / args.lockfile
    output = args.output if args.output.is_absolute() else root / args.output

    try:
        sbom = generate_cyclonedx_sbom(lockfile, output, args.app_version)
        print(f"PASS [Gate S3]: Generated CycloneDX 1.5 SBOM at {output}")
        print(f"  Total Components: {len(sbom['components'])}")
        print(f"  App Version: {args.app_version}")
        return 0
    except Exception as e:
        print(f"FAIL [Gate S3]: SBOM generation failed: {e}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
