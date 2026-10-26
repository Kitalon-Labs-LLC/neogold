#!/usr/bin/env python3
"""PreToolUse guard for the doc-auditor subagent (P24): Bash stays read-only.

Reads the hook input JSON on stdin. Exit 0 allows the command; exit 2 blocks it and the
reason on stderr goes back to the subagent.

Allowed, as one simple command with no shell operators:
  git log | git show | git diff        (no --output, --ext-diff, --textconv, --no-index, -c,
                                        and no paths outside the repository)
  python3 tools/drift_inventory.py     (only --since <git ref>, --check, --allow-skipped)
  python3 tools/trace.py docs [--tests] (no --write)

Stricter than "python3 tools/" on purpose: trace.py --write rewrites doc 10 and other tools
write reports, so a prefix match would let a read-only agent change files.
"""
import json
import re
import shlex
import sys

SHELL_OPERATORS = (";", "&", "|", ">", "<", "`", "$", "\n", "\r", "(", ")", "{", "}", "\\")
GIT_SUBCOMMANDS = {"log", "show", "diff"}
GIT_BANNED = ("--output", "--ext-diff", "--textconv", "--no-index", "--exec", "--upload-pack")
# --python and --merged-manifest are not offered: either would let the auditor run or
# read an arbitrary path (P24 privacy review, findings 2 and 4).
PYTHON_TOOLS = {
    "tools/drift_inventory.py": {"--since", "--check", "--allow-skipped"},
    "tools/trace.py": {"docs", "--tests"},
}
OPTIONS_WITH_VALUE = {"--since"}
GIT_REF_RE = re.compile(r"^[A-Za-z0-9._/~^-]+$")


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
            if arg.startswith(("/", "~")) or ".." in arg.split("/"):
                return f"git path {arg} points outside the repository"
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
            if name in OPTIONS_WITH_VALUE:
                if "=" in args[i]:
                    value = args[i].split("=", 1)[1]
                else:
                    value = args[i + 1] if i + 1 < len(args) else ""
                    i += 1
                if not value or value.startswith("-") or not GIT_REF_RE.match(value):
                    return f"{name} must be a plain git ref, not {value!r}"
            i += 1
        if argv[1] == "tools/trace.py" and (not args or args[0] != "docs"):
            return "run trace.py as `python3 tools/trace.py docs [--tests]`"
        return None
    return "the doc-auditor may run only git log/show/diff and the read-only P24 tools"


def main():
    # Project-level registration (.claude/settings.json) sees every Bash call, so it
    # enforces only inside the doc-auditor subagent and never blocks anyone else on bad
    # input. Once the caller is the doc-auditor (or in the frontmatter registration),
    # any failure blocks.
    project = "--project" in sys.argv[1:]
    try:
        data = json.load(sys.stdin)
        if not isinstance(data, dict):
            raise ValueError("hook input is not an object")
    except (ValueError, UnicodeDecodeError):
        if project:
            return 0
        print("doc-auditor guard: unreadable hook input", file=sys.stderr)
        return 2
    if project and data.get("agent_type") != "doc-auditor":
        return 0
    try:
        if data.get("tool_name") not in (None, "Bash"):
            return 0
        command = (data.get("tool_input") or {}).get("command", "")
        if not isinstance(command, str):
            raise TypeError("command is not a string")
        reason = verdict(command)
    except Exception as e:  # noqa: BLE001 - fail closed for the auditor on anything unexpected
        reason = f"guard error ({e.__class__.__name__})"
    if reason:
        print(f"doc-auditor guard blocked this command: {reason}", file=sys.stderr)
        return 2
    return 0


if __name__ == "__main__":
    sys.exit(main())
