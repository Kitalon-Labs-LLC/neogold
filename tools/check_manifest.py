#!/usr/bin/env python3
"""Release gate S5 (doc 8 Section 6.1): merged-manifest allow-lists.

Usage: check_manifest.py path/to/merged/AndroidManifest.xml
"""
import sys
import xml.etree.ElementTree as ET

A = "{http://schemas.android.com/apk/res/android}"
ALLOWED_PERMISSIONS = {
    "android.permission.CAMERA",
    "android.permission.INTERNET",
    "android.permission.ACCESS_NETWORK_STATE",
    "android.permission.FOREGROUND_SERVICE",
    "android.permission.FOREGROUND_SERVICE_DATA_SYNC",
    "android.permission.WAKE_LOCK",
    "android.permission.RECEIVE_BOOT_COMPLETED",
    "android.permission.POST_NOTIFICATIONS",
    "android.permission.USE_BIOMETRIC",
    "android.permission.USE_FINGERPRINT",
}
# exported components allowed, with the permission that must guard them (None = launcher)
ALLOWED_EXPORTED = {
    "com.kitalonlabs.neogold.MainActivity": None,
    "androidx.work.impl.background.systemjob.SystemJobService": "android.permission.BIND_JOB_SERVICE",
    "androidx.work.impl.diagnostics.DiagnosticsReceiver": "android.permission.DUMP",
    "androidx.profileinstaller.ProfileInstallReceiver": "android.permission.DUMP",
    "androidx.profileinstaller.ProfileInstallerReceiver": "android.permission.DUMP",
}

def main(path):
    root = ET.parse(path).getroot()
    pkg = root.get("package", "")
    app = root.find("application")
    errors = []
    if app.get(A + "allowBackup") != "false":
        errors.append("allowBackup must be false")
    for attr in ("dataExtractionRules", "fullBackupContent", "networkSecurityConfig"):
        if not app.get(A + attr):
            errors.append(f"application lacks android:{attr}")
    if app.get(A + "debuggable") == "true":
        errors.append("release manifest is debuggable")
    if app.get(A + "usesCleartextTraffic") == "true":
        errors.append("usesCleartextTraffic is true")
    for p in root.findall("uses-permission") + root.findall("uses-permission-sdk-23"):
        name = p.get(A + "name")
        own = name == f"{pkg}.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION" or name.endswith(".DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION")
        if name not in ALLOWED_PERMISSIONS and not own:
            errors.append(f"permission not allow-listed: {name}")
    for tag in ("activity", "activity-alias", "service", "receiver", "provider"):
        for c in app.findall(tag):
            name = c.get(A + "name")
            exported = c.get(A + "exported")
            if exported is None and c.find("intent-filter") is not None:
                errors.append(f"{tag} {name} has an intent-filter but no explicit android:exported")
            if exported == "true":
                if name not in ALLOWED_EXPORTED:
                    errors.append(f"exported {tag} not allow-listed: {name}")
                elif ALLOWED_EXPORTED[name] and c.get(A + "permission") != ALLOWED_EXPORTED[name]:
                    errors.append(f"exported {tag} {name} is not guarded by {ALLOWED_EXPORTED[name]}")
    for e in errors:
        print("S5 FAIL:", e)
    if not errors:
        print("S5 PASS: manifest allow-lists satisfied")
    return 1 if errors else 0

if __name__ == "__main__":
    sys.exit(main(sys.argv[1]))
