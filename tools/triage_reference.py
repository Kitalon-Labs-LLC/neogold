#!/usr/bin/env python3
"""Independent reference implementation of the NeoGold triage rules (docs/4_System_Architecture.md Section 6.2).

Written from the document, not from the Kotlin engine, so the differential test (TC-TRI-24) compares two
independent readings of the same spec. Integer tenths of mg/dL; rounding toward more care (threshold down,
estimate and upper bound up, lower bound down); one first-match reason code.

SYNTH below is the synthetic test config from docs/10_Test_Cases.md Section 0. These are NOT clinical values.
Work package P03 adapts this file to read testdata/triage/config.synthetic.json instead of the dictionary.
Running it directly prints the expected result for each boundary case in docs/10_Test_Cases.md Section 6.
"""
from decimal import Decimal, ROUND_HALF_UP, ROUND_FLOOR, ROUND_CEILING

def tenths(x, mode=ROUND_HALF_UP):  # integer tenths of mg/dL
    return int((Decimal(str(x)) * 10).quantize(Decimal('1'), rounding=mode))

SYNTH = {  # synthetic test config, NOT clinical values
  "scope": {"minGA": 35, "maxAgeHours": 336},
  "bands": {"ga35": [[0, 8.0], [24, 10.0], [48, 14.0], [96, 17.0], [336, 17.0]],
            "ga36": [[0, 9.0], [24, 11.0], [48, 15.5], [96, 18.5], [336, 18.5]],
            "ga38plus": [[0, 10.0], [24, 12.0], [48, 17.0], [96, 20.0], [336, 20.0]]},
  "bandFor": lambda ga: "ga35" if ga == 35 else ("ga36" if ga in (36, 37) else "ga38plus"),
  "bloodWithin": 3.0, "bloodAbs": 15.0, "recheckWithin": 5.0, "recheckHours": 24, "maxWidth": 6.0,
}

def threshold_tenths(cfg, ga, age_h):
    pts = cfg["bands"][cfg["bandFor"](ga)]
    for (a0, t0), (a1, t1) in zip(pts, pts[1:]):
        if a0 <= age_h <= a1:
            t = Decimal(str(t0)) + (Decimal(str(t1)) - Decimal(str(t0))) * (Decimal(str(age_h)) - a0) / (a1 - a0)
            return tenths(t, ROUND_FLOOR)   # threshold rounded down: toward more care
    raise ValueError("age outside table")

def triage(cfg, *, danger=False, onset24=False, ga=40, photo=False, age_h=48.0,
           quality_failed_3=False, E=None, L=None, U=None):
    if danger: return ("REFER_URGENT", "R1")
    if onset24: return ("REFER_URGENT", "R2")
    if ga < cfg["scope"]["minGA"]: return ("CANNOT_SCREEN_BLOOD_TEST", "R3")
    if photo: return ("CANNOT_SCREEN_BLOOD_TEST", "R4")
    if age_h > cfg["scope"]["maxAgeHours"]: return ("CANNOT_SCREEN_CLINICAL_REVIEW", "R5")
    if quality_failed_3: return ("BLOOD_TEST_TODAY", "QUALITY_FALLBACK")
    T = threshold_tenths(cfg, ga, age_h)
    e, l, u = tenths(E, ROUND_CEILING), tenths(L, ROUND_FLOOR), tenths(U, ROUND_CEILING)  # toward more care
    if e >= T: return ("REFER_URGENT", "ABOVE_THRESHOLD")
    if u >= T - tenths(cfg["bloodWithin"]): return ("BLOOD_TEST_TODAY", "ZONE_WITHIN3")
    if u >= tenths(cfg["bloodAbs"]): return ("BLOOD_TEST_TODAY", "ZONE_ABS15")
    if u - l > tenths(cfg["maxWidth"]): return ("BLOOD_TEST_TODAY", "WIDE_INTERVAL")
    if u >= T - tenths(cfg["recheckWithin"]): return ("RECHECK", "RECHECK_ZONE")
    return ("NO_FURTHER_ACTION_FROM_SCREEN", "-")

if __name__ == "__main__":
    c = SYNTH
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
    print("T(40,36h) tenths", threshold_tenths(c, 40, 36), "T(40,48h)", threshold_tenths(c,40,48), "T(40,96h)", threshold_tenths(c,40,96))
