#!/usr/bin/env python3
"""Independent reference implementation of the NeoGold triage rules (docs/4_System_Architecture.md Section 6.2).

Written from the document, not from the Kotlin engine, so the differential test (TC-TRI-24) compares two
independent readings of the same spec. Integer tenths of mg/dL; rounding toward more care (threshold down,
estimate and upper bound up, lower bound down); one first-match reason code.

Reads testdata/triage/config.synthetic.json (or a path passed via CLI) instead of a hardcoded dictionary.
Running it directly prints the expected result for each boundary case in docs/10_Test_Cases.md Section 6.
Supports '--batch' to stream test cases via JSON for differential verification (TC-TRI-24).
"""
from decimal import Decimal, ROUND_HALF_UP, ROUND_FLOOR, ROUND_CEILING
import json
import sys
from pathlib import Path

def tenths(x, mode=ROUND_HALF_UP):  # integer tenths of mg/dL
    return int((Decimal(str(x)) * 10).quantize(Decimal('1'), rounding=mode))

def load_config(path="testdata/triage/config.synthetic.json"):
    p = Path(path)
    with open(p, "r", encoding="utf-8") as f:
        raw = json.load(f)
    return raw

def band_for_ga(ga: int) -> str:
    if ga == 35:
        return "ga35"
    elif ga in (36, 37):
        return "ga36"
    else:
        return "ga38plus"

def threshold_tenths(cfg, ga, age_h):
    bands = cfg.get("phototherapyThresholds") or cfg.get("bands")
    pts = bands[band_for_ga(ga)]
    age_dec = Decimal(str(age_h))
    for (a0, t0), (a1, t1) in zip(pts, pts[1:]):
        a0_dec, a1_dec = Decimal(str(a0)), Decimal(str(a1))
        if a0_dec <= age_dec <= a1_dec:
            t0_dec, t1_dec = Decimal(str(t0)), Decimal(str(t1))
            if a1_dec == a0_dec:
                t = t0_dec
            else:
                t = t0_dec + (t1_dec - t0_dec) * (age_dec - a0_dec) / (a1_dec - a0_dec)
            return tenths(t, ROUND_FLOOR)   # threshold rounded down: toward more care
    raise ValueError(f"age {age_h} outside table (0 to 336)")

def triage(cfg, *, danger=False, onset24=False, ga=40, photo=False, age_h=48.0,
           quality_failed_3=False, E=None, L=None, U=None):
    min_ga = cfg["scope"].get("minGestationalAgeWeeks", cfg["scope"].get("minGA", 35))
    max_age_h = cfg["scope"].get("maxAgeHours", 336)
    blood_within = cfg.get("bloodTestZone", {}).get("withinOfThreshold", cfg.get("bloodWithin", 3.0))
    blood_abs = cfg.get("bloodTestZone", {}).get("absoluteAtOrAbove", cfg.get("bloodAbs", 15.0))
    recheck_within = cfg.get("recheck", {}).get("withinOfThreshold", cfg.get("recheckWithin", 5.0))
    max_width = cfg.get("maxIntervalWidth", cfg.get("maxWidth", 6.0))

    if danger: return ("REFER_URGENT", "R1")
    if onset24: return ("REFER_URGENT", "R2")
    if ga < min_ga: return ("CANNOT_SCREEN_BLOOD_TEST", "R3")
    if photo: return ("CANNOT_SCREEN_BLOOD_TEST", "R4")
    if Decimal(str(age_h)) > Decimal(str(max_age_h)): return ("CANNOT_SCREEN_CLINICAL_REVIEW", "R5")
    if quality_failed_3: return ("BLOOD_TEST_TODAY", "QUALITY_FALLBACK")
    T = threshold_tenths(cfg, ga, age_h)
    e = tenths(E, ROUND_CEILING) if E is not None else None
    l = tenths(L, ROUND_FLOOR) if L is not None else None
    u = tenths(U, ROUND_CEILING) if U is not None else None

    if e is not None and e >= T: return ("REFER_URGENT", "ABOVE_THRESHOLD")
    if u is not None and u >= T - tenths(blood_within): return ("BLOOD_TEST_TODAY", "ZONE_WITHIN3")
    if u is not None and u >= tenths(blood_abs): return ("BLOOD_TEST_TODAY", "ZONE_ABS15")
    if u is not None and l is not None and u - l > tenths(max_width): return ("BLOOD_TEST_TODAY", "WIDE_INTERVAL")
    if u is not None and u >= T - tenths(recheck_within): return ("RECHECK", "RECHECK_ZONE")
    return ("NO_FURTHER_ACTION_FROM_SCREEN", "-")

if __name__ == "__main__":
    if "--batch" in sys.argv:
        # Batch evaluation for differential testing (TC-TRI-24)
        cfg_arg = [a for a in sys.argv[1:] if a != "--batch"]
        cfg_path = cfg_arg[0] if cfg_arg else "testdata/triage/config.synthetic.json"
        c = load_config(cfg_path)
        batch = json.load(sys.stdin)
        out = []
        for item in batch:
            act, rsn = triage(
                c,
                danger=item.get("danger", False),
                onset24=item.get("onset24", False),
                ga=item.get("ga", 40),
                photo=item.get("photo", False),
                age_h=item.get("age_h", 48.0),
                quality_failed_3=item.get("quality_failed_3", False),
                E=item.get("E"),
                L=item.get("L"),
                U=item.get("U")
            )
            out.append([act, rsn])
        json.dump(out, sys.stdout)
        sys.exit(0)

    cfg_path = sys.argv[1] if len(sys.argv) > 1 else "testdata/triage/config.synthetic.json"
    c = load_config(cfg_path)
    V = [
     ("TRI-01 danger signs", dict(danger=True, E=5, L=3, U=7)),
     ("TRI-02 onset under 24h", dict(onset24=True, E=5, L=3, U=7)),
     ("TRI-03a GA 34", dict(ga=34, E=5, L=3, U=7)),
     ("TRI-03b GA 35", dict(ga=35, age_h=48, E=5, L=3, U=7)),
     ("TRI-04 phototherapy", dict(photo=True, E=5, L=3, U=7)),
     ("TRI-05a age 336.1", dict(age_h=336.1, E=5, L=3, U=7)),
     ("TRI-05b age 336.0", dict(age_h=336.0, E=5, L=3, U=7)),
     ("TRI-06 danger + GA 34", dict(danger=True, ga=34, E=5, L=3, U=7)),
     ("TRI-07 E = T (48h, T=17.0)", dict(age_h=48, E=17.0, L=15.0, U=19.0)),
     ("TRI-08 E = T-0.1", dict(age_h=48, E=16.9, L=14.9, U=18.9)),
     ("TRI-09a U = T-3 (14.0)", dict(age_h=48, E=12.0, L=10.0, U=14.0)),
     ("TRI-09b U = T-3.1 (13.9)", dict(age_h=48, E=11.9, L=9.9, U=13.9)),
     ("TRI-10a U = 15.0 at 96h (T=20)", dict(age_h=96, E=12.0, L=10.0, U=15.0)),
     ("TRI-10b U = 14.9 at 96h", dict(age_h=96, E=11.9, L=9.9, U=14.9)),
     ("TRI-11a width 6.1", dict(age_h=48, E=6.0, L=3.0, U=9.1)),
     ("TRI-11b width 6.0", dict(age_h=48, E=6.0, L=3.0, U=9.0)),
     ("TRI-12 U = T-5 (12.0)", dict(age_h=48, E=10.0, L=8.0, U=12.0)),
     ("TRI-13 U = 11.9", dict(age_h=48, E=10.0, L=8.1, U=11.9)),
     ("TRI-14a 36h T=14.5 E=14.5", dict(age_h=36, E=14.5, L=12.5, U=16.5)),
     ("TRI-14b 36h E=14.4", dict(age_h=36, E=14.4, L=12.4, U=16.4)),
     ("TRI-15 quality fallback", dict(quality_failed_3=True)),
     ("TRI-16 GA 37 uses ga36 band 48h T=15.5 E=15.5", dict(ga=37, age_h=48, E=15.5, L=13.5, U=17.5)),
     ("TRI-16b GA 38 same E", dict(ga=38, age_h=48, E=15.5, L=13.5, U=17.5)),
    ]
    for name, kw in V:
        print(f"{name:45s} -> {triage(c, **kw)}")
    print("T(40,36h) tenths", threshold_tenths(c, 40, 36), "T(40,48h)", threshold_tenths(c, 40, 48), "T(40,96h)", threshold_tenths(c, 40, 96))
