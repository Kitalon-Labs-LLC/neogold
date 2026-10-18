#!/usr/bin/env python3
"""
Generates pinned training/requirements.txt with SHA-256 hashes for reproducible,
tamper-evident, and network-isolated builds.
"""
import json
import sys
import urllib.request

PINNED_PACKAGES = {
    "numpy": "2.5.4",
    "scipy": "1.18.1",
    "scikit-learn": "1.9.1",
    "joblib": "1.6.0",
    "threadpoolctl": "3.7.0",
    "narwhals": "2.27.1",
    "cloudpickle": "3.1.2",
    "jsonschema": "4.26.0",
    "jsonschema-specifications": "2025.9.1",
    "referencing": "0.37.0",
    "rpds-py": "2026.9.1",
    "attrs": "26.1.0",
    "pytest": "9.1.1",
    "iniconfig": "2.3.1",
    "packaging": "26.3",
    "pluggy": "1.7.0",
    "colorama": "0.4.6",
}

def fetch_package_hashes(package: str, version: str) -> list[str]:
    url = f"https://pypi.org/pypi/{package}/{version}/json"
    req = urllib.request.Request(url, headers={"User-Agent": "NeoGold-Lockfile-Generator"})
    try:
        with urllib.request.urlopen(req, timeout=15) as resp:
            data = json.loads(resp.read().decode("utf-8"))
            hashes = []
            for item in data.get("urls", []):
                digest = item.get("digests", {}).get("sha256")
                filename = item.get("filename", "")
                if digest:
                    hashes.append((filename, digest))
            return hashes
    except Exception as e:
        print(f"Warning: Could not fetch remote hashes for {package}=={version}: {e}", file=sys.stderr)
        return []

def main():
    lines = [
        "# NeoGold Training Pipeline Pinned Dependencies (TC-OPS-07, NFR-11)",
        "# Generated with exact SHA-256 hashes for offline, reproducible builds.",
        "# Usage: pip install --require-hashes -r requirements.txt",
        "",
    ]
    
    for pkg, ver in sorted(PINNED_PACKAGES.items()):
        print(f"Fetching hashes for {pkg}=={ver}...")
        artifacts = fetch_package_hashes(pkg, ver)
        if artifacts:
            line = f"{pkg}=={ver} \\\n"
            hash_lines = []
            for fname, sha in sorted(artifacts, key=lambda x: x[0]):
                hash_lines.append(f"    --hash=sha256:{sha}  # {fname}")
            line += " \\\n".join(hash_lines)
            lines.append(line)
        else:
            lines.append(f"{pkg}=={ver}")
        lines.append("")

    with open("training/requirements.txt", "w", encoding="utf-8") as f:
        f.write("\n".join(lines).strip() + "\n")
    print("Wrote training/requirements.txt successfully.")

if __name__ == "__main__":
    main()
