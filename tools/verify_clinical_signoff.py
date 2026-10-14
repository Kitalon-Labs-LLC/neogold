#!/usr/bin/env python3
"""Release gate G1 (doc 8 Section 13.3): the triage config is clinically signed off.

Usage: verify_clinical_signoff.py config/triage-config.json CLINICAL_SIGNOFF.md [--today YYYY-MM-DD]

A config with "triageEnabled": false (research builds, Path B, or a clinical emergency that
disables triage) passes without a sign-off, because such a build never shows a triage result.

The config hash is SHA-256 over canonical JSON (sorted keys, no whitespace, UTF-8) with the
`clinicalSignoff` block removed, which avoids the circular dependency between the config and
the sign-off record that hashes it.
"""
import datetime as dt, hashlib, json, re, sys

def canonical_hash(cfg: dict) -> str:
    body = {k: v for k, v in cfg.items() if k != "clinicalSignoff"}
    data = json.dumps(body, sort_keys=True, separators=(",", ":"), ensure_ascii=False).encode()
    return hashlib.sha256(data).hexdigest()

def main(argv):
    cfg_path, record_path = argv[1], argv[2]
    today = dt.date.fromisoformat(argv[argv.index("--today") + 1]) if "--today" in argv else dt.date.today()
    cfg = json.load(open(cfg_path, encoding="utf-8"))
    if cfg.get("triageEnabled") is False:
        print(f"G1 PASS: config {cfg.get('configVersion')} has triage disabled; no triage result can be shown")
        return 0
    if cfg.get("triageEnabled") is not True:
        print("G1 FAIL: triageEnabled must be true or false")
        return 1
    record_bytes = open(record_path, "rb").read()
    fields = dict(re.findall(r"^- \*\*(\w+):\*\* (.+)$", record_bytes.decode("utf-8"), re.M))
    errors = []
    signoff = cfg.get("clinicalSignoff", {})
    if signoff.get("status") != "SIGNED":
        errors.append(f"config clinicalSignoff.status is {signoff.get('status')!r}, not 'SIGNED'")
    if fields.get("config_sha256") != canonical_hash(cfg):
        errors.append("config hash does not match config_sha256 in the sign-off record")
    if fields.get("config_version") != cfg.get("configVersion"):
        errors.append("configVersion differs from config_version in the sign-off record")
    if signoff.get("signoffRecordSha256") != hashlib.sha256(record_bytes).hexdigest():
        errors.append("clinicalSignoff.signoffRecordSha256 does not match the sign-off record file")
    try:
        signed_on = dt.date.fromisoformat(fields["signed_on"])
        valid_until = dt.date.fromisoformat(cfg["validUntil"])
        if valid_until <= today:
            errors.append(f"validUntil {valid_until} is not in the future")
        if valid_until > signed_on + dt.timedelta(days=366):
            errors.append("validUntil is more than 12 months after signed_on")
    except (KeyError, ValueError) as e:
        errors.append(f"missing or malformed date: {e}")
    for name in ("signed_by", "registration"):
        if not fields.get(name):
            errors.append(f"sign-off record lacks {name}")
    for e in errors:
        print("G1 FAIL:", e)
    if not errors:
        print(f"G1 PASS: config {cfg['configVersion']} signed by {fields['signed_by']} on {fields['signed_on']}")
    return 1 if errors else 0

if __name__ == "__main__":
    sys.exit(main(sys.argv))
