#!/usr/bin/env python3
"""Coverage and traceability check for NeoGold docs (doc 9 Section 10).

Usage: trace.py <docs_dir> [--write] [--tests]
Fails (exit 1) if a requirement, hazard, threat, or gate has no test case, or a test
case cites an ID that does not exist. With --write, regenerates doc 10 Section 21.
With --tests, also counts the test-code sites that cite each TC ID and lists the TCs
that no test code cites (report only; it never changes the exit code).
"""
import re, sys, pathlib, collections

PATTERN = {
    "FR": r"\bFR-\d+[a-z]?\b", "NFR": r"\bNFR-\d+[a-z]?\b", "H": r"\bH\d+\b",
    "T": r"\bT\d+\b", "Gate": r"\bGate S\d\b", "NG": r"\bNG-\d{3}\b",
}
# Test code cites IDs as TC-TRI-06 (comments, strings) or TC_TRI_06 (Kotlin test names).
TC_RE = re.compile(r"(?<![A-Za-z0-9])TC[-_]([A-Z][A-Z0-9]*)[-_](\d{2})(?!\d)")
# Test code roots, relative to the repository root (P24).
TEST_GLOBS = (
    "android/**/src/test/**/*.kt", "android/**/src/androidTest/**/*.kt",
    "android/**/src/test/**/*.java", "android/**/src/androidTest/**/*.java",
    "tools/neogold-decrypt/src/test/**/*.kt",
    "backend/tests/**/*.py", "training/tests/**/*.py", "tools/tests/**/*.py",
    "card/tests/**/*.py", "tools/reliability/**/*.py", "tools/reliability/**/*.sh",
)


def known_ids(docs):
    prd = (docs / "2_PRD.md").read_text()
    sec = (docs / "8_Security_Compliance_Plan.md").read_text()
    backlog = (docs / "3_Feature_Backlog.md").read_text()
    return {
        "FR": set(re.findall(r"^- (FR-\d+[a-z]?) ", prd, re.M)),
        "NFR": set(re.findall(r"^\| (NFR-\d+[a-z]?) \|", prd, re.M)),
        "H": set(re.findall(r"^\| (H\d+) \|", sec, re.M)),
        "T": set(re.findall(r"^\| (T\d+) \|", sec, re.M)),
        "Gate": {f"Gate {g}" for g in re.findall(r"^\| \*{0,2}(S\d)\*{0,2} ", sec, re.M)},
        "NG": set(re.findall(r"^\| (NG-\d{3}) \|", backlog, re.M)),
    }


def test_cases(tc_text):
    cases = collections.OrderedDict()
    body = tc_text.split("## 20. Hazard Verification Map")[0]
    for line in body.splitlines():
        m = re.match(r"^\| (TC-[A-Z0-9]+-\d{2}) \| [^|]+ \| ([^|]+) \|", line)
        if m:
            cases[m.group(1)] = m.group(2)
    return cases, body


def count_tests(repo_root, case_ids):
    """Map each TC ID to the sorted test-code sites ("path:line") that cite it."""
    repo_root = pathlib.Path(repo_root)
    sites = {tcid: [] for tcid in case_ids}
    seen = set()
    for glob in TEST_GLOBS:
        for path in sorted(repo_root.glob(glob)):
            if path in seen or "/build/" in path.as_posix() or not path.is_file():
                continue
            seen.add(path)
            rel = path.relative_to(repo_root).as_posix()
            for n, line in enumerate(path.read_text(errors="replace").splitlines(), 1):
                for tcid in {f"TC-{c}-{n}" for c, n in TC_RE.findall(line)}:
                    sites.setdefault(tcid, []).append(f"{rel}:{n}")
    return {k: sorted(v) for k, v in sites.items()}


def natural(x): return [int(t) if t.isdigit() else t for t in re.split(r"(\d+)", x)]


def main(argv):
    docs = pathlib.Path(argv[1])
    known = known_ids(docs)
    tc_path = docs / "10_Test_Cases.md"
    tc = tc_path.read_text()
    cases, body = test_cases(tc)
    cov = {k: collections.defaultdict(list) for k in known}
    problems = []
    for tcid, verifies in cases.items():
        for kind, pat in PATTERN.items():
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

    if "--write" in argv:
        out = ["| Requirement | Test cases |", "|---|---|"]
        for kind in ("FR", "NFR", "NG", "H", "T", "Gate"):
            for ref in sorted(cov[kind], key=natural):
                out.append(f"| {ref} | {', '.join(cov[kind][ref])} |")
        head = tc.split("Generated from the tables above by `tools/trace.py`; do not edit by hand.")[0]
        tc_path.write_text(head + "Generated from the tables above by `tools/trace.py`; do not edit by hand.\n\n" + "\n".join(out) + "\n")
    print(f"{len(cases)} test cases; " + ", ".join(f"{k}: {len(cov[k])}/{len(known[k])} covered" for k in known))
    for p in problems: print("PROBLEM:", p)
    if "--tests" in argv:
        sites = count_tests(docs.resolve().parent, cases)
        zero = [t for t in cases if not sites.get(t)]
        unknown = sorted((t for t in sites if t not in cases and sites[t]), key=natural)
        print(f"TESTS: {len(cases) - len(zero)}/{len(cases)} test cases cited by test code")
        for t in cases:
            print(f"TESTS: {t} {len(sites.get(t, []))}")
        for t in zero: print("ZERO TESTS:", t)
        for t in unknown: print("UNKNOWN TC IN TEST CODE:", t, ", ".join(sites[t][:3]))
    return 1 if problems else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
