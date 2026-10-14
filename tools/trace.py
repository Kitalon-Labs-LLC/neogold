#!/usr/bin/env python3
"""Coverage and traceability check for NeoGold docs (doc 9 Section 10).

Usage: trace.py <docs_dir> [--write]
Fails (exit 1) if a requirement, hazard, threat, or gate has no test case, or a test
case cites an ID that does not exist. With --write, regenerates doc 10 Section 21.
"""
import re, sys, pathlib, collections

docs = pathlib.Path(sys.argv[1])
prd = (docs / "2_PRD.md").read_text()
sec = (docs / "8_Security_Compliance_Plan.md").read_text()
backlog = (docs / "3_Feature_Backlog.md").read_text()
tc_path = docs / "10_Test_Cases.md"
tc = tc_path.read_text()

known = {
    "FR": set(re.findall(r"^- (FR-\d+[a-z]?) ", prd, re.M)),
    "NFR": set(re.findall(r"^\| (NFR-\d+[a-z]?) \|", prd, re.M)),
    "H": set(re.findall(r"^\| (H\d+) \|", sec, re.M)),
    "T": set(re.findall(r"^\| (T\d+) \|", sec, re.M)),
    "Gate": {f"Gate {g}" for g in re.findall(r"^\| \*{0,2}(S\d)\*{0,2} ", sec, re.M)},
    "NG": set(re.findall(r"^\| (NG-\d{3}) \|", backlog, re.M)),
}
pattern = {
    "FR": r"\bFR-\d+[a-z]?\b", "NFR": r"\bNFR-\d+[a-z]?\b", "H": r"\bH\d+\b",
    "T": r"\bT\d+\b", "Gate": r"\bGate S\d\b", "NG": r"\bNG-\d{3}\b",
}
cases = collections.OrderedDict()
body = tc.split("## 20. Hazard Verification Map")[0]
for line in body.splitlines():
    m = re.match(r"^\| (TC-[A-Z0-9]+-\d{2}) \| [^|]+ \| ([^|]+) \|", line)
    if m:
        cases[m.group(1)] = m.group(2)
cov = {k: collections.defaultdict(list) for k in known}
problems = []
for tcid, verifies in cases.items():
    for kind, pat in pattern.items():
        for ref in re.findall(pat, verifies):
            if ref not in known[kind]:
                problems.append(f"{tcid} cites unknown {ref}")
            cov[kind][ref].append(tcid)
for kind in ("FR", "NFR", "H", "T", "Gate"):
    for ref in sorted(known[kind], key=lambda x: (len(x), x)):
        if ref not in cov[kind]:
            problems.append(f"no test case covers {ref}")
dupes = [k for k, v in collections.Counter(re.findall(r"^\| (TC-[A-Z0-9]+-\d{2}) \|", body, re.M)).items() if v > 1]
problems += [f"duplicate test case ID {d}" for d in dupes]

def natural(x): return [int(t) if t.isdigit() else t for t in re.split(r"(\d+)", x)]
if "--write" in sys.argv:
    out = ["| Requirement | Test cases |", "|---|---|"]
    for kind in ("FR", "NFR", "NG", "H", "T", "Gate"):
        for ref in sorted(cov[kind], key=natural):
            out.append(f"| {ref} | {', '.join(cov[kind][ref])} |")
    head = tc.split("Generated from the tables above by `tools/trace.py`; do not edit by hand.")[0]
    tc_path.write_text(head + "Generated from the tables above by `tools/trace.py`; do not edit by hand.\n\n" + "\n".join(out) + "\n")
print(f"{len(cases)} test cases; " + ", ".join(f"{k}: {len(cov[k])}/{len(known[k])} covered" for k in known))
for p in problems: print("PROBLEM:", p)
sys.exit(1 if problems else 0)
