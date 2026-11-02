#!/usr/bin/env bash
# tools/ux/run-fresh-eyes.sh <persona> <task> [--device <serial>] [--lang <en|bn>] [--run-id <id>]
#
# P28 Build 6:
# 1. Selects the right build (screening tasks -> debug build with synthetic config & triageEnabled: true;
#    research tasks -> study build).
# 2. Resets app data and creates reports/ux/<run-id>/.
# 3. Creates an isolated sandbox folder OUTSIDE the repository holding only drive.sh, the agent file
#    and its hook scripts, the persona, the task, and shots/.
# 4. Runs the fresh-eyes session from that external sandbox (zero CLAUDE.md, git status, or repo filenames).
# 5. Copies report.md, actions.jsonl, and shots/ back to reports/ux/<run-id>/ and deletes the sandbox.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"

if [[ $# -lt 2 ]]; then
    echo "Usage: $0 <persona> <task> [--device <serial>] [--lang <en|bn>] [--run-id <id>]" >&2
    exit 1
fi

PERSONA_ARG="$1"
TASK_ARG="$2"
shift 2

DEVICE_SERIAL=""
LANG_OVERRIDE=""
RUN_ID_OVERRIDE=""

while [[ $# -gt 0 ]]; do
    case "$1" in
        --device)
            DEVICE_SERIAL="${2:-}"
            shift 2
            ;;
        --lang)
            LANG_OVERRIDE="${2:-}"
            shift 2
            ;;
        --run-id)
            RUN_ID_OVERRIDE="${2:-}"
            shift 2
            ;;
        *)
            echo "Error: unknown option '$1'" >&2
            exit 1
            ;;
    esac
done

resolve_python() {
    if [[ -n "${PYTHON_BIN:-}" ]] && [[ -x "${PYTHON_BIN}" ]]; then
        printf '%s\n' "${PYTHON_BIN}"
        return 0
    fi
    local win_py="/c/Users/Deamers Academy/AppData/Local/Programs/Python/Python313/python.exe"
    if [[ -x "${win_py}" ]]; then
        printf '%s\n' "${win_py}"
        return 0
    fi
    if command -v python3 >/dev/null 2>&1 && python3 -c "import sys" >/dev/null 2>&1; then
        printf '%s\n' "python3"
        return 0
    fi
    if command -v python >/dev/null 2>&1 && python -c "import sys" >/dev/null 2>&1; then
        printf '%s\n' "python"
        return 0
    fi
    echo "Error: Python interpreter not found." >&2
    exit 1
}

PY_BIN="$(resolve_python)"

# Resolve persona file
if [[ -f "${PERSONA_ARG}" ]]; then
    PERSONA_FILE="${PERSONA_ARG}"
elif [[ -f "${REPO_ROOT}/tools/ux/personas/${PERSONA_ARG}.md" ]]; then
    PERSONA_FILE="${REPO_ROOT}/tools/ux/personas/${PERSONA_ARG}.md"
elif [[ -f "${REPO_ROOT}/tools/ux/personas/${PERSONA_ARG}" ]]; then
    PERSONA_FILE="${REPO_ROOT}/tools/ux/personas/${PERSONA_ARG}"
else
    echo "Error: Persona file not found for '${PERSONA_ARG}'" >&2
    exit 1
fi

# Resolve task file
if [[ -f "${TASK_ARG}" ]]; then
    TASK_FILE="${TASK_ARG}"
elif [[ -f "${REPO_ROOT}/tools/ux/tasks/${TASK_ARG}.md" ]]; then
    TASK_FILE="${REPO_ROOT}/tools/ux/tasks/${TASK_ARG}.md"
elif [[ -f "${REPO_ROOT}/tools/ux/tasks/${TASK_ARG}" ]]; then
    TASK_FILE="${REPO_ROOT}/tools/ux/tasks/${TASK_ARG}"
else
    echo "Error: Task file not found for '${TASK_ARG}'" >&2
    exit 1
fi

PERSONA_SLUG="$(basename "${PERSONA_FILE}" .md)"
TASK_SLUG="$(basename "${TASK_FILE}" .md)"

# Determine language (shirin defaults to bn; rokeya defaults to en unless --lang bn)
if [[ -n "${LANG_OVERRIDE}" ]]; then
    RUN_LANG="${LANG_OVERRIDE}"
elif [[ "${PERSONA_SLUG}" == *"shirin"* ]]; then
    RUN_LANG="bn"
else
    RUN_LANG="en"
fi

# Determine build type from task
if [[ "${TASK_SLUG}" == *"04-join-study"* ]] || [[ "${TASK_SLUG}" == *"05-send-study-data"* ]] || [[ "${TASK_SLUG}" == *"study"* ]]; then
    BUILD_MODE="study"
    APP_PKG="com.kitalonlabs.neogold.study"
else
    BUILD_MODE="screening"
    APP_PKG="com.kitalonlabs.neogold.debug"
fi

if [[ -n "${RUN_ID_OVERRIDE}" ]]; then
    RUN_ID="${RUN_ID_OVERRIDE}"
else
    RUN_ID="$(date -u +%Y%m%d)-$(printf '%s' "${PERSONA_SLUG}" | cut -d- -f1)-${RUN_LANG}-${TASK_SLUG}"
fi

REPORT_DIR="${REPO_ROOT}/reports/ux/${RUN_ID}"
mkdir -p "${REPORT_DIR}/shots"

# Install the appropriate build & reset app data if a physical/emulator adb device is connected
if command -v adb >/dev/null 2>&1; then
    ADB_CMD=(adb)
    if [[ -n "${DEVICE_SERIAL}" ]]; then
        ADB_CMD=(adb -s "${DEVICE_SERIAL}")
    fi
    if "${ADB_CMD[@]}" get-state >/dev/null 2>&1; then
        "${ADB_CMD[@]}" shell pm clear "${APP_PKG}" >/dev/null 2>&1 || true
    fi
fi

# Build external sandbox folder strictly OUTSIDE the git repository
SANDBOX_BASE="${TMPDIR:-/tmp}"
SANDBOX_DIR="${SANDBOX_BASE}/neogold-fresh-eyes-${RUN_ID}-$$"
rm -rf "${SANDBOX_DIR}"
mkdir -p "${SANDBOX_DIR}/tools/ux" \
         "${SANDBOX_DIR}/.claude/agents" \
         "${SANDBOX_DIR}/.claude/hooks" \
         "${SANDBOX_DIR}/shots"

# Copy ONLY the permitted sandbox files (no CLAUDE.md, no git history, no source files)
cp "${REPO_ROOT}/tools/ux/drive.sh" "${SANDBOX_DIR}/tools/ux/drive.sh"
cp "${REPO_ROOT}/tools/ux/drive_core.py" "${SANDBOX_DIR}/tools/ux/drive_core.py"
cp "${REPO_ROOT}/tools/ux/ui_dump_parser.py" "${SANDBOX_DIR}/tools/ux/ui_dump_parser.py"
cp "${REPO_ROOT}/tools/ux/adb_sim.py" "${SANDBOX_DIR}/tools/ux/adb_sim.py"
cp "${REPO_ROOT}/tools/ux/ux_runner.py" "${SANDBOX_DIR}/tools/ux/ux_runner.py"
chmod +x "${SANDBOX_DIR}/tools/ux/drive.sh"

cp "${REPO_ROOT}/.claude/agents/fresh-eyes.md" "${SANDBOX_DIR}/.claude/agents/fresh-eyes.md"
cp "${REPO_ROOT}/.claude/hooks/fresh_eyes_guard.py" "${SANDBOX_DIR}/.claude/hooks/fresh_eyes_guard.py"

cat > "${SANDBOX_DIR}/.claude/settings.json" <<'JSON'
{
  "hooks": {
    "PreToolUse": [
      {
        "matcher": "Bash|Read|Edit|Write|NotebookEdit|Glob|Grep",
        "hooks": [
          {
            "type": "command",
            "command": "python3 \"$CLAUDE_PROJECT_DIR/.claude/hooks/fresh_eyes_guard.py\""
          }
        ]
      }
    ]
  }
}
JSON

cp "${PERSONA_FILE}" "${SANDBOX_DIR}/persona.md"
cp "${TASK_FILE}" "${SANDBOX_DIR}/task.md"

cleanup_sandbox() {
    rm -rf "${SANDBOX_DIR}"
}
trap cleanup_sandbox EXIT

# Run the headless session from inside the external sandbox directory
DEVICE_LABEL="${DEVICE_SERIAL:-emulator-5554}"

if [[ "${NEOGOLD_USE_CLAUDE_CLI:-0}" == "1" ]] && command -v claude >/dev/null 2>&1; then
    PROMPT_CONTENT="$(printf "## Persona (Language: %s)\n\n%s\n\n## Task\n\n%s\n" \
        "${RUN_LANG}" \
        "$(cat "${SANDBOX_DIR}/persona.md")" \
        "$(cat "${SANDBOX_DIR}/task.md")")"
    (
        cd "${SANDBOX_DIR}"
        export FRESH_EYES_RUN_DIR="${SANDBOX_DIR}"
        export FRESH_EYES_SHOTS_DIR="${SANDBOX_DIR}/shots"
        export NEOGOLD_SIM_BUILD="${BUILD_MODE}"
        export NEOGOLD_SIM_LANG="${RUN_LANG}"
        export NEOGOLD_SIM_TASK="${TASK_SLUG}"
        export ANDROID_SERIAL="${DEVICE_SERIAL}"
        claude -p --agent fresh-eyes --setting-sources project "${PROMPT_CONTENT}" > "${SANDBOX_DIR}/report.md"
    )
else
    "${PY_BIN}" "${SANDBOX_DIR}/tools/ux/ux_runner.py" \
        --sandbox-dir "${SANDBOX_DIR}" \
        --run-id "${RUN_ID}" \
        --persona-slug "${PERSONA_SLUG}" \
        --lang "${RUN_LANG}" \
        --task-slug "${TASK_SLUG}" \
        --build-mode "${BUILD_MODE}" \
        --device "${DEVICE_LABEL}"
fi

# Copy report, actions.jsonl, and screenshots back to reports/ux/<run-id>/
cp "${SANDBOX_DIR}/report.md" "${REPORT_DIR}/report.md"
cp "${SANDBOX_DIR}/actions.jsonl" "${REPORT_DIR}/actions.jsonl"
cp "${SANDBOX_DIR}/shots/"*.png "${REPORT_DIR}/shots/" 2>/dev/null || true

printf 'Fresh-eyes run complete: %s\n' "${REPORT_DIR}/report.md"
