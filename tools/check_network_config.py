#!/usr/bin/env python3
"""Release gate S6 (doc 8 Section 5.3): network security config.

Usage: check_network_config.py android/app/src/main/res/xml/network_security_config.xml
Passes only if cleartext is off everywhere, trust anchors are system CAs only, and no
debug overrides or domain configs weaken that.
"""
import sys
import xml.etree.ElementTree as ET

def main(path):
    root = ET.parse(path).getroot()
    errors = []
    base = root.find("base-config")
    if base is None:
        errors.append("no <base-config>")
    else:
        if base.get("cleartextTrafficPermitted") != "false":
            errors.append('base-config must set cleartextTrafficPermitted="false"')
        anchors = base.find("trust-anchors")
        srcs = [c.get("src") for c in anchors.findall("certificates")] if anchors is not None else []
        if srcs != ["system"]:
            errors.append(f"base-config trust anchors must be exactly ['system'], found {srcs}")
    for dc in root.iter("domain-config"):
        if dc.get("cleartextTrafficPermitted") == "true":
            errors.append("a domain-config permits cleartext")
        for c in dc.iter("certificates"):
            if c.get("src") != "system":
                errors.append(f"a domain-config trusts {c.get('src')}")
    if root.find("debug-overrides") is not None:
        errors.append("debug-overrides present in the release config")
    for e in errors:
        print("S6 FAIL:", e)
    if not errors:
        print("S6 PASS: cleartext off, system CAs only")
    return 1 if errors else 0

if __name__ == "__main__":
    sys.exit(main(sys.argv[1]))
