#!/usr/bin/env python3
"""Repository rule: no AI attribution on any commit.

Commits carry no `Co-Authored-By: Claude ...` trailer, no `noreply@anthropic.com` address, and no
"Generated with Claude Code" line. Commits are pushed as the repository owner's own GitHub account.
Ordinary mentions are fine ("update CLAUDE.md", "move .claude/settings.json"); a human co-author is fine.

Usage:
  python3 tools/check_commit_attribution.py REV_RANGE            check every commit in a range, e.g. origin/dev..HEAD
  python3 tools/check_commit_attribution.py --message-file FILE  check one message (used by the commit-msg hook)

Exits 0 when clean, 1 when attribution is found, 2 on a usage error.
"""

import subprocess
import sys
from pathlib import Path

def is_attribution(line):
    """True when one line is AI attribution. Case-insensitive.

    Plain substring tests on purpose: a regex with nested wildcards can backtrack badly, and a
    crafted 120 KB line took 11 seconds to match, enough to stall a CI runner.
    """
    low = line.lower()
    # Co-Authored-By: Claude ... / a trailer that names Anthropic (a human co-author is fine).
    if low.lstrip().startswith("co-authored-by:") and ("claude" in low or "anthropic" in low):
        return True
    # Any address at the vendor's domain, human-looking or not.
    if "@anthropic.com" in low:
        return True
    # "Generated with [Claude Code](...)": the footer wording, in this order.
    start = low.find("generated with")
    return start >= 0 and "claude" in low[start:]


def find_attribution(message):
    """Return the stripped offending lines of a commit message (empty means clean)."""
    return [line.strip() for line in message.splitlines() if is_attribution(line)]


def check_range(rev_range, cwd=None):
    """Return one problem string per commit in the range that carries attribution."""
    log = subprocess.run(
        ["git", "log", "--format=%H%x1f%s%x1f%B%x1e", rev_range],
        cwd=cwd, capture_output=True, text=True,
    )
    if log.returncode != 0:
        return [f"git log {rev_range} failed: {log.stderr.strip()}"]
    problems = []
    for record in log.stdout.split("\x1e"):
        if not record.strip():
            continue
        sha, subject, body = record.strip("\n").split("\x1f", 2)
        for line in find_attribution(body):
            problems.append(f"{sha[:7]} {subject}: {line}")
    return problems


def main(argv):
    if len(argv) == 2 and argv[0] == "--message-file":
        offending = find_attribution(Path(argv[1]).read_text(encoding="utf-8", errors="replace"))
        if offending:
            print("Commit message rejected: AI attribution is not allowed on any commit.", file=sys.stderr)
            for line in offending:
                print(f"  {line}", file=sys.stderr)
            return 1
        return 0
    if len(argv) == 1 and not argv[0].startswith("-"):
        problems = check_range(argv[0])
        if problems:
            print("AI attribution found in commit messages:", file=sys.stderr)
            for problem in problems:
                print(f"  {problem}", file=sys.stderr)
            print(f"FAIL: {len(problems)} offending line(s) in {argv[0]}", file=sys.stderr)
            return 1
        print(f"PASS: no AI attribution in {argv[0]}")
        return 0
    print(__doc__, file=sys.stderr)
    return 2


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
