#!/usr/bin/env python3
"""PreToolUse guard for the doc-auditor subagent (P24): Bash stays read-only.

Reads the hook input JSON on stdin. Exit 0 allows the command; exit 2 blocks it and the
reason on stderr goes back to the subagent.

Allowed, as one simple command with no shell operators:
  git log | git show | git diff        (no --output, --ext-diff, --textconv, --no-index, -c)
  python3 tools/drift_inventory.py     (no --write-baseline, --out, --baseline, --root)
  python3 tools/trace.py docs [--tests] (no --write)

Stricter than "python3 tools/" on purpose: trace.py --write rewrites doc 10 and other tools
write reports, so a prefix match would let a read-only agent change files.
"""
import json
import shlex
import sys

SHELL_OPERATORS = (";", "&", "|", ">", "<", "`", "$", "\n", "\r", "(", ")", "{", "}", "\\")
GIT_SUBCOMMANDS = {"log", "show", "diff"}
GIT_BANNED = ("--output", "--ext-diff", "--textconv", "--no-index", "--exec", "--upload-pack")
PYTHON_TOOLS = {
    "tools/drift_inventory.py": {"--since", "--merged-manifest", "--python", "--check", "--allow-skipped"},
    "tools/trace.py": {"docs", "--tests"},
}
OPTIONS_WITH_VALUE = {"--since", "--merged-manifest", "--python"}


def verdict(command):
    """Return None if allowed, else the reason it is blocked."""
    if not command or not command.strip():
        return "empty command"
    for op in SHELL_OPERATORS:
        if op in command:
            return f"shell operator {op!r} is not allowed; run one plain command"
    try:
        argv = shlex.split(command)
    except ValueError as e:
        return f"cannot parse command: {e}"
    if argv[0] == "git":
        if len(argv) < 2 or argv[1] not in GIT_SUBCOMMANDS:
            return "only `git log`, `git show`, and `git diff` are allowed (no global options)"
        for arg in argv[2:]:
            if arg.startswith(GIT_BANNED) or arg == "-o" or arg.startswith("-c"):
                return f"git option {arg} is not allowed"
        return None
    if argv[0] in ("python3", "python"):
        if len(argv) < 2 or argv[1] not in PYTHON_TOOLS:
            return f"only {', '.join(sorted(PYTHON_TOOLS))} may be run"
        allowed = PYTHON_TOOLS[argv[1]]
        args, i = argv[2:], 0
        while i < len(args):
            name = args[i].split("=", 1)[0]
            if name not in allowed:
                return f"argument {args[i]} is not allowed for {argv[1]}"
            if name in OPTIONS_WITH_VALUE and "=" not in args[i]:
                i += 1
            i += 1
        if argv[1] == "tools/trace.py" and (not args or args[0] != "docs"):
            return "run trace.py as `python3 tools/trace.py docs [--tests]`"
        return None
    return "the doc-auditor may run only git log/show/diff and the read-only P24 tools"


def main():
    try:
        data = json.load(sys.stdin)
    except json.JSONDecodeError:
        print("doc-auditor guard: unreadable hook input", file=sys.stderr)
        return 2
    if data.get("tool_name") not in (None, "Bash"):
        return 0
    reason = verdict((data.get("tool_input") or {}).get("command", ""))
    if reason:
        print(f"doc-auditor guard blocked this command: {reason}", file=sys.stderr)
        return 2
    return 0


if __name__ == "__main__":
    sys.exit(main())
