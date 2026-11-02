#!/usr/bin/env bash
# tools/ux/drive.sh — P28 Fresh-eyes agent phone driver.
# Strictly validates all arguments, never uses shell eval, strips resource-id/class/package
# from UI dumps, and logs every action with an ISO-8601 UTC timestamp to actions.jsonl.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
RUN_DIR="${FRESH_EYES_RUN_DIR:-$(pwd)}"
SHOTS_DIR="${FRESH_EYES_SHOTS_DIR:-${RUN_DIR}/shots}"
ACTIONS_LOG="${RUN_DIR}/actions.jsonl"
APP_PACKAGE="${NEOGOLD_APP_PACKAGE:-com.kitalonlabs.neogold.debug}"
MAIN_ACTIVITY="${NEOGOLD_MAIN_ACTIVITY:-com.kitalonlabs.neogold.MainActivity}"

mkdir -p "${SHOTS_DIR}"

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

use_simulator() {
    if [[ "${NEOGOLD_UX_SIM:-0}" == "1" ]]; then
        return 0
    fi
    if ! command -v adb >/dev/null 2>&1; then
        return 0
    fi
    local dev_count
    if [[ -n "${ANDROID_SERIAL:-}" ]]; then
        dev_count="$(adb devices | grep -c "^${ANDROID_SERIAL}[[:space:]]*device$" || true)"
    else
        dev_count="$(adb devices | grep -c "[[:space:]]device$" || true)"
    fi
    if [[ "${dev_count}" -eq 0 ]]; then
        return 0
    fi
    return 1
}

run_adb() {
    if [[ -n "${ANDROID_SERIAL:-}" ]]; then
        adb -s "${ANDROID_SERIAL}" "$@"
    else
        adb "$@"
    fi
}

log_action() {
    local action_name="$1"
    shift
    "${PY_BIN}" - "${ACTIONS_LOG}" "${action_name}" "$@" <<'PY'
import datetime
import json
import sys
from pathlib import Path

log_path = Path(sys.argv[1])
action = sys.argv[2]
args = sys.argv[3:]
entry = {
    "timestamp": datetime.datetime.now(datetime.timezone.utc).isoformat(),
    "action": action,
    "args": args,
}
log_path.parent.mkdir(parents=True, exist_ok=True)
with log_path.open("a", encoding="utf-8") as fh:
    fh.write(json.dumps(entry, ensure_ascii=False) + "\n")
PY
}

dump_ui_xml() {
    local xml_dest="$1"
    if use_simulator; then
        "${PY_BIN}" "${SCRIPT_DIR}/adb_sim.py" --run-dir "${RUN_DIR}" dump "${xml_dest}"
    else
        run_adb shell uiautomator dump /sdcard/neogold_uidump.xml >/dev/null
        run_adb pull /sdcard/neogold_uidump.xml "${xml_dest}" >/dev/null
    fi
}

if [[ $# -lt 1 ]]; then
    echo "Usage: drive.sh <shot|see|tap|tap-text|type|swipe|back|home|wait|launch> [args...]" >&2
    exit 1
fi

CMD="$1"
shift

case "${CMD}" in
    shot)
        if [[ $# -ne 1 ]]; then
            echo "Error: 'shot' requires exactly 1 argument: <name>" >&2
            exit 1
        fi
        NAME="$1"
        if [[ ! "${NAME}" =~ ^[A-Za-z0-9_-]{1,64}$ ]]; then
            echo "Error: invalid screenshot name '${NAME}' (use letters, digits, _, - only)" >&2
            exit 1
        fi
        OUT_PNG="${SHOTS_DIR}/${NAME}.png"
        if use_simulator; then
            "${PY_BIN}" "${SCRIPT_DIR}/adb_sim.py" --run-dir "${RUN_DIR}" shot "${OUT_PNG}"
        else
            run_adb exec-out screencap -p > "${OUT_PNG}"
        fi
        log_action "shot" "${NAME}"
        printf 'shots/%s.png\n' "${NAME}"
        ;;

    see)
        if [[ $# -ne 0 ]]; then
            echo "Error: 'see' takes no arguments" >&2
            exit 1
        fi
        TMP_XML="${RUN_DIR}/.uidump_latest.xml"
        dump_ui_xml "${TMP_XML}"
        log_action "see"
        "${PY_BIN}" "${SCRIPT_DIR}/ui_dump_parser.py" see "${TMP_XML}"
        rm -f "${TMP_XML}"
        ;;

    tap)
        if [[ $# -ne 2 ]]; then
            echo "Error: 'tap' requires 2 integer arguments: <x> <y>" >&2
            exit 1
        fi
        X="$1"
        Y="$2"
        if [[ ! "${X}" =~ ^[0-9]{1,5}$ ]] || [[ ! "${Y}" =~ ^[0-9]{1,5}$ ]]; then
            echo "Error: 'tap' coordinates must be non-negative integers" >&2
            exit 1
        fi
        if use_simulator; then
            "${PY_BIN}" "${SCRIPT_DIR}/adb_sim.py" --run-dir "${RUN_DIR}" tap "${X}" "${Y}"
        else
            run_adb shell input tap "${X}" "${Y}"
        fi
        log_action "tap" "${X}" "${Y}"
        printf 'Tapped (%s, %s)\n' "${X}" "${Y}"
        ;;

    tap-text)
        if [[ $# -ne 1 ]]; then
            echo "Error: 'tap-text' requires 1 argument: \"<label>\"" >&2
            exit 1
        fi
        LABEL="$1"
        if [[ -z "${LABEL}" ]] || [[ ${#LABEL} -gt 120 ]]; then
            echo "Error: 'tap-text' label must be non-empty and <= 120 chars" >&2
            exit 1
        fi
        TMP_XML="${RUN_DIR}/.uidump_latest.xml"
        dump_ui_xml "${TMP_XML}"
        COORDS="$("${PY_BIN}" "${SCRIPT_DIR}/ui_dump_parser.py" find-label "${TMP_XML}" "${LABEL}")"
        rm -f "${TMP_XML}"
        TAP_X="${COORDS%% *}"
        TAP_Y="${COORDS##* }"
        if use_simulator; then
            "${PY_BIN}" "${SCRIPT_DIR}/adb_sim.py" --run-dir "${RUN_DIR}" tap "${TAP_X}" "${TAP_Y}"
        else
            run_adb shell input tap "${TAP_X}" "${TAP_Y}"
        fi
        log_action "tap-text" "${LABEL}" "${TAP_X}" "${TAP_Y}"
        printf 'Tapped label "%s" at (%s, %s)\n' "${LABEL}" "${TAP_X}" "${TAP_Y}"
        ;;

    type)
        if [[ $# -ne 1 ]]; then
            echo "Error: 'type' requires 1 argument: \"<text>\"" >&2
            exit 1
        fi
        TEXT="$1"
        if [[ ! "${TEXT}" =~ ^[A-Za-z0-9\ ./_-]{1,64}$ ]]; then
            echo "Error: 'type' accepts only digits, Latin letters, space, ., /, _, -" >&2
            exit 1
        fi
        if use_simulator; then
            "${PY_BIN}" "${SCRIPT_DIR}/adb_sim.py" --run-dir "${RUN_DIR}" type "${TEXT}"
        else
            ESCAPED_TEXT="${TEXT// /%s}"
            run_adb shell input text "${ESCAPED_TEXT}"
        fi
        log_action "type" "${TEXT}"
        printf 'Typed "%s"\n' "${TEXT}"
        ;;

    swipe)
        if [[ $# -ne 1 ]]; then
            echo "Error: 'swipe' requires 1 argument: up|down|left|right" >&2
            exit 1
        fi
        DIR="$1"
        case "${DIR}" in
            up)    SX1=540; SY1=1600; SX2=540; SY2=500 ;;
            down)  SX1=540; SY1=500;  SX2=540; SY2=1600 ;;
            left)  SX1=900; SY1=1080; SX2=180; SY2=1080 ;;
            right) SX1=180; SY1=1080; SX2=900; SY2=1080 ;;
            *)
                echo "Error: 'swipe' direction must be one of: up, down, left, right" >&2
                exit 1
                ;;
        esac
        if use_simulator; then
            "${PY_BIN}" "${SCRIPT_DIR}/adb_sim.py" --run-dir "${RUN_DIR}" swipe "${DIR}"
        else
            run_adb shell input swipe "${SX1}" "${SY1}" "${SX2}" "${SY2}" 300
        fi
        log_action "swipe" "${DIR}"
        printf 'Swiped %s\n' "${DIR}"
        ;;

    back)
        if [[ $# -ne 0 ]]; then
            echo "Error: 'back' takes no arguments" >&2
            exit 1
        fi
        if use_simulator; then
            "${PY_BIN}" "${SCRIPT_DIR}/adb_sim.py" --run-dir "${RUN_DIR}" back
        else
            run_adb shell input keyevent 4
        fi
        log_action "back"
        printf 'Pressed back\n'
        ;;

    home)
        if [[ $# -ne 0 ]]; then
            echo "Error: 'home' takes no arguments" >&2
            exit 1
        fi
        if use_simulator; then
            "${PY_BIN}" "${SCRIPT_DIR}/adb_sim.py" --run-dir "${RUN_DIR}" home
        else
            run_adb shell input keyevent 3
        fi
        log_action "home"
        printf 'Pressed home\n'
        ;;

    wait)
        if [[ $# -ne 1 ]] || [[ ! "$1" =~ ^[0-9]{1,2}$ ]]; then
            echo "Error: 'wait' requires integer seconds (0-99)" >&2
            exit 1
        fi
        SECS="$1"
        if ! use_simulator; then
            sleep "${SECS}"
        fi
        log_action "wait" "${SECS}"
        printf 'Waited %s seconds\n' "${SECS}"
        ;;

    launch)
        if [[ $# -ne 0 ]]; then
            echo "Error: 'launch' takes no arguments" >&2
            exit 1
        fi
        if use_simulator; then
            "${PY_BIN}" "${SCRIPT_DIR}/adb_sim.py" --run-dir "${RUN_DIR}" launch
        else
            run_adb shell am start -n "${APP_PACKAGE}/${MAIN_ACTIVITY}" >/dev/null
        fi
        log_action "launch"
        printf 'Launched application\n'
        ;;

    *)
        echo "Error: unknown command '${CMD}'" >&2
        exit 1
        ;;
esac

