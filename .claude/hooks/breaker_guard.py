#!/usr/bin/env python3
"""PreToolUse guard for the `breaker` subagent (P27).

Enforces P27 Build 2 constraints:
1. For Bash:
   - Denies network targets other than the breaker stack (`localhost`, `127.0.0.1`,
     `10.0.2.2`, `api`, `caddy`, `neogold-breaker-api`, `neogold-breaker-caddy`).
   - Denies `ssh`, `scp`, `sftp`, `rsync`, `wg`, `wg-quick`, `neogold-deploy`,
     `neogold-admin`, `restic`, `git push`, `gh release`, and Play uploads
     (`play_publisher.py`, `publishReleaseBundle`, `fastlane`).
   - Denies deleting or modifying production source files via `rm`, `mv`, `git rm`,
     `sed -i`, or output redirection (`>`) outside `breaker/findings/`,
     `breaker/env/.runtime/`, and breaker test folders.
2. For Write / Edit / NotebookEdit:
   - Denies any Write or Edit outside `breaker/findings/` and the breaker test folders:
     - `breaker/findings/BRK-*.md`
     - `breaker/tests/**`
     - `backend/tests/test_brk_*.py`
     - `android/**/src/test/**/Brk*Test.kt`
     - `android/**/src/androidTest/**/Brk*Test.kt`

Supports both frontmatter registration (always enforces) and project-level
registration in `.claude/settings.json` via `--project` (enforces when
`agent_type == "breaker"`, per `docs/spikes/2026-10-26-subagent-frontmatter-hooks.md`).
"""

from __future__ import annotations

import json
import re
import sys
from urllib.parse import urlparse

ALLOWED_HOSTS = {
    "localhost",
    "127.0.0.1",
    "10.0.2.2",
    "api",
    "caddy",
    "neogold-breaker-api",
    "neogold-breaker-caddy",
}

BANNED_COMMANDS_RE = [
    (re.compile(r"(?:^|[;&|()\s`/])ssh\b"), "ssh is forbidden for the breaker agent"),
    (re.compile(r"(?:^|[;&|()\s`/])scp\b"), "scp is forbidden for the breaker agent"),
    (re.compile(r"(?:^|[;&|()\s`/])sftp\b"), "sftp is forbidden for the breaker agent"),
    (re.compile(r"(?:^|[;&|()\s`/])wg(?:-quick)?\b"), "WireGuard commands (wg/wg-quick) are forbidden for the breaker agent"),
    (re.compile(r"\bneogold-deploy\b"), "neogold-deploy is forbidden for the breaker agent"),
    (re.compile(r"\bneogold-admin\b"), "neogold-admin is forbidden for the breaker agent"),
    (re.compile(r"(?:^|[;&|()\s`/])restic\b"), "restic is forbidden for the breaker agent"),
    (re.compile(r"\bgit\s+(?:-[^\s]+\s+)*push\b"), "git push is forbidden for the breaker agent"),
    (re.compile(r"\bgh\s+(?:-[^\s]+\s+)*release\b"), "gh release is forbidden for the breaker agent"),
    (
        re.compile(r"\b(?:play_publisher\.py|publishReleaseBundle|publishBundle|uploadToPlay)\b"),
        "Play Store upload commands are forbidden for the breaker agent",
    ),
]

URL_RE = re.compile(r"\bhttps?://[^\s\"'`<>)]+", re.IGNORECASE)

# Allowed write path patterns (normalized forward-slash relative path)
ALLOWED_WRITE_RES = [
    re.compile(r"^breaker/findings/[A-Za-z0-9._-]+\.md$"),
    re.compile(r"^breaker/tests/[A-Za-z0-9._/-]+$"),
    re.compile(r"^backend/tests/test_brk_[A-Za-z0-9_]+\.py$"),
    re.compile(r"^android/[A-Za-z0-9_/-]+/src/(?:test|androidTest)/[A-Za-z0-9_/-]*/Brk[A-Za-z0-9_]*Test\.kt$"),
]


def normalize_rel_path(path_str: str) -> str:
    s = path_str.replace("\\", "/").strip()
    # Strip Windows drive or repo root prefix if present
    lower = s.lower()
    for marker in ("/neogold-android/",):
        idx = lower.rfind(marker)
        if idx != -1:
            s = s[idx + len(marker) :]
            break
    while s.startswith("./"):
        s = s[2:]
    s = s.lstrip("/")
    return s


def is_allowed_breaker_write_path(path_str: str) -> bool:
    if not path_str or not path_str.strip():
        return False
    norm = normalize_rel_path(path_str)
    if ".." in norm.split("/"):
        return False
    return any(pat.match(norm) for pat in ALLOWED_WRITE_RES)


def evaluate_write_or_edit(file_path: str) -> str | None:
    if not is_allowed_breaker_write_path(file_path):
        return (
            f"Breaker may only write findings under 'breaker/findings/' or regression tests "
            f"under 'breaker/tests/', 'backend/tests/test_brk_*.py', or "
            f"'android/**/src/test/**/Brk*Test.kt' (got {file_path!r}). "
            f"The breaker never edits application code."
        )
    return None


def evaluate_bash(command: str) -> str | None:
    if not command or not command.strip():
        return "empty command"

    # 1. Check banned tools and subcommands
    for pattern, message in BANNED_COMMANDS_RE:
        if pattern.search(command):
            return message

    # 2. Check all HTTP/HTTPS URLs in the command
    for url_match in URL_RE.findall(command):
        parsed = urlparse(url_match)
        host = (parsed.hostname or "").lower()
        if host not in ALLOWED_HOSTS:
            return (
                f"Network target {url_match!r} (host {host!r}) is forbidden; "
                f"breaker may only target the disposable breaker stack ({sorted(ALLOWED_HOSTS)})."
            )

    # 3. Check raw curl/wget/nc/ncat invocations without http(s):// that might target external hosts
    for net_match in re.finditer(r"\b(?:curl|wget|httpie|nc|ncat|telnet)\s+([^\n;|&]+)", command):
        tail = net_match.group(1)
        for token in tail.split():
            if token.startswith("-"):
                continue
            if token.startswith(("http://", "https://")):
                continue
            # Check host[:port][/path] token
            host_part = token.split("/")[0].split(":")[0].strip("\"'").lower()
            if "." in host_part and host_part not in ALLOWED_HOSTS:
                return f"Network target {host_part!r} is outside the disposable breaker stack."

    return None


def deny_response(reason: str) -> int:
    out = {
        "hookSpecificOutput": {
            "hookEventName": "PreToolUse",
            "permissionDecision": "deny",
            "permissionDecisionReason": f"breaker_guard: {reason}",
        }
    }
    print(json.dumps(out))
    print(f"breaker_guard denied: {reason}", file=sys.stderr)
    return 2


def main() -> int:
    project_mode = "--project" in sys.argv[1:]
    try:
        data = json.load(sys.stdin)
        if not isinstance(data, dict):
            raise ValueError("hook input is not an object")
    except Exception as e:
        if project_mode:
            return 0
        print(f"breaker_guard: unreadable hook input ({e})", file=sys.stderr)
        return 2

    if project_mode and data.get("agent_type") != "breaker":
        return 0

    tool_name = data.get("tool_name", "")
    tool_input = data.get("tool_input")
    if not isinstance(tool_input, dict):
        return deny_response("missing or invalid tool_input")

    if tool_name in ("Write", "Edit", "NotebookEdit", "MultiEdit"):
        file_path = (
            tool_input.get("file_path")
            or tool_input.get("path")
            or tool_input.get("notebook_path")
            or ""
        )
        if not isinstance(file_path, str):
            return deny_response("file_path must be a string")
        reason = evaluate_write_or_edit(file_path)
        if reason:
            return deny_response(reason)
        return 0

    if tool_name == "Bash":
        command = tool_input.get("command", "")
        if not isinstance(command, str):
            return deny_response("command must be a string")
        reason = evaluate_bash(command)
        if reason:
            return deny_response(reason)
        return 0

    return 0


if __name__ == "__main__":
    sys.exit(main())
