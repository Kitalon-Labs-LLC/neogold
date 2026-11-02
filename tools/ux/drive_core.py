#!/usr/bin/env python3
"""Core implementation for `tools/ux/drive.sh` (P28).

Provides the exact argument validation, `actions.jsonl` logging, `ui_dump_parser.py`
invocation, and `adb` / `adb_sim.py` dispatch used by `tools/ux/drive.sh`.
Allows `drive.sh` to work identically in Bash on Linux/macOS/Git-Bash and in native
Windows Python environments where MSYS2 shared memory is restricted by AppContainer.
"""

from __future__ import annotations

import datetime
import json
import os
import re
import shutil
import subprocess
import sys
import time
from pathlib import Path

import adb_sim
from ui_dump_parser import find_label_center, format_see_output

SHOT_NAME_RE = re.compile(r"^[A-Za-z0-9_-]{1,64}$")
COORD_RE = re.compile(r"^\d{1,5}$")
WAIT_RE = re.compile(r"^\d{1,2}$")
TYPE_TEXT_RE = re.compile(r"^[A-Za-z0-9 ./_-]{1,64}$")


def use_simulator(serial: str = "") -> bool:
    if os.environ.get("NEOGOLD_UX_SIM", "0") == "1":
        return True
    adb_bin = shutil.which("adb")
    if not adb_bin:
        return True
    try:
        res = subprocess.run([adb_bin, "devices"], capture_output=True, text=True, timeout=5, check=False)
        lines = [ln.strip() for ln in res.stdout.splitlines()[1:] if ln.strip()]
        if serial:
            return not any(ln.startswith(f"{serial}\tdevice") for ln in lines)
        return not any(ln.endswith("\tdevice") for ln in lines)
    except Exception:
        return True


def run_adb(serial: str, *args: str) -> subprocess.CompletedProcess[bytes]:
    cmd = ["adb"]
    if serial:
        cmd.extend(["-s", serial])
    cmd.extend(args)
    return subprocess.run(cmd, capture_output=True, check=True)


def log_action(actions_log: Path, action: str, args: list[str]) -> None:
    entry = {
        "timestamp": datetime.datetime.now(datetime.timezone.utc).isoformat(),
        "action": action,
        "args": args,
    }
    actions_log.parent.mkdir(parents=True, exist_ok=True)
    with actions_log.open("a", encoding="utf-8") as fh:
        fh.write(json.dumps(entry, ensure_ascii=False) + "\n")


def dump_ui_xml(run_dir: Path, serial: str, xml_dest: Path) -> None:
    if use_simulator(serial):
        state = adb_sim.load_state(run_dir)
        adb_sim.write_xml_dump(state, xml_dest)
    else:
        run_adb(serial, "shell", "uiautomator", "dump", "/sdcard/neogold_uidump.xml")
        run_adb(serial, "pull", "/sdcard/neogold_uidump.xml", str(xml_dest))


def execute_drive_command(argv: list[str], env: dict[str, str] | None = None) -> tuple[int, str, str]:
    """Execute a `drive.sh` command and return `(returncode, stdout, stderr)`."""
    active_env = env if env is not None else os.environ
    run_dir = Path(active_env.get("FRESH_EYES_RUN_DIR", os.getcwd())).resolve()
    shots_dir = Path(active_env.get("FRESH_EYES_SHOTS_DIR", str(run_dir / "shots"))).resolve()
    actions_log = run_dir / "actions.jsonl"
    serial = active_env.get("ANDROID_SERIAL", "").strip()
    app_pkg = active_env.get("NEOGOLD_APP_PACKAGE", "com.kitalonlabs.neogold.debug")
    main_act = active_env.get("NEOGOLD_MAIN_ACTIVITY", "com.kitalonlabs.neogold.MainActivity")

    shots_dir.mkdir(parents=True, exist_ok=True)

    # Propagate simulator env vars into os.environ temporarily if custom env passed
    old_env = os.environ.copy()
    if env is not None:
        os.environ.update(env)

    try:
        if not argv:
            return 1, "", "Usage: drive.sh <shot|see|tap|tap-text|type|swipe|back|home|wait|launch> [args...]\n"

        cmd = argv[0]
        args = argv[1:]
        sim = use_simulator(serial)

        if cmd == "shot":
            if len(args) != 1 or not SHOT_NAME_RE.match(args[0]):
                return 1, "", "Error: invalid screenshot name (use letters, digits, _, - only)\n"
            name = args[0]
            out_png = shots_dir / f"{name}.png"
            if sim:
                state = adb_sim.load_state(run_dir)
                adb_sim.write_png_screenshot(state, out_png)
            else:
                res = run_adb(serial, "exec-out", "screencap", "-p")
                out_png.write_bytes(res.stdout)
            log_action(actions_log, "shot", [name])
            return 0, f"shots/{name}.png\n", ""

        if cmd == "see":
            if len(args) != 0:
                return 1, "", "Error: 'see' takes no arguments\n"
            tmp_xml = run_dir / ".uidump_latest.xml"
            dump_ui_xml(run_dir, serial, tmp_xml)
            log_action(actions_log, "see", [])
            xml_text = tmp_xml.read_text(encoding="utf-8")
            tmp_xml.unlink(missing_ok=True)
            return 0, format_see_output(xml_text) + "\n", ""

        if cmd == "tap":
            if len(args) != 2 or not (COORD_RE.match(args[0]) and COORD_RE.match(args[1])):
                return 1, "", "Error: 'tap' coordinates must be non-negative integers\n"
            x, y = int(args[0]), int(args[1])
            if sim:
                subprocess_sim(run_dir, "tap", str(x), str(y))
            else:
                run_adb(serial, "shell", "input", "tap", str(x), str(y))
            log_action(actions_log, "tap", [str(x), str(y)])
            return 0, f"Tapped ({x}, {y})\n", ""

        if cmd == "tap-text":
            if len(args) != 1 or not args[0].strip() or len(args[0]) > 120:
                return 1, "", "Error: 'tap-text' label must be non-empty and <= 120 chars\n"
            label = args[0]
            tmp_xml = run_dir / ".uidump_latest.xml"
            dump_ui_xml(run_dir, serial, tmp_xml)
            xml_text = tmp_xml.read_text(encoding="utf-8")
            tmp_xml.unlink(missing_ok=True)
            center = find_label_center(xml_text, label)
            if not center:
                return 1, "", f"Error: Label not found on screen: {label!r}\n"
            tx, ty = center
            if sim:
                subprocess_sim(run_dir, "tap", str(tx), str(ty))
            else:
                run_adb(serial, "shell", "input", "tap", str(tx), str(ty))
            log_action(actions_log, "tap-text", [label, str(tx), str(ty)])
            return 0, f'Tapped label "{label}" at ({tx}, {ty})\n', ""

        if cmd == "type":
            if len(args) != 1 or not TYPE_TEXT_RE.match(args[0]):
                return 1, "", "Error: 'type' accepts only digits, Latin letters, space, ., /, _, -\n"
            text = args[0]
            if sim:
                subprocess_sim(run_dir, "type", text)
            else:
                escaped = text.replace(" ", "%s")
                run_adb(serial, "shell", "input", "text", escaped)
            log_action(actions_log, "type", [text])
            return 0, f'Typed "{text}"\n', ""

        if cmd == "swipe":
            if len(args) != 1 or args[0] not in ("up", "down", "left", "right"):
                return 1, "", "Error: 'swipe' direction must be one of: up, down, left, right\n"
            direction = args[0]
            coords = {
                "up": ("540", "1600", "540", "500"),
                "down": ("540", "500", "540", "1600"),
                "left": ("900", "1080", "180", "1080"),
                "right": ("180", "1080", "900", "1080"),
            }[direction]
            if sim:
                subprocess_sim(run_dir, "swipe", direction)
            else:
                run_adb(serial, "shell", "input", "swipe", *coords, "300")
            log_action(actions_log, "swipe", [direction])
            return 0, f"Swiped {direction}\n", ""

        if cmd == "back":
            if len(args) != 0:
                return 1, "", "Error: 'back' takes no arguments\n"
            if sim:
                subprocess_sim(run_dir, "back")
            else:
                run_adb(serial, "shell", "input", "keyevent", "4")
            log_action(actions_log, "back", [])
            return 0, "Pressed back\n", ""

        if cmd == "home":
            if len(args) != 0:
                return 1, "", "Error: 'home' takes no arguments\n"
            if sim:
                subprocess_sim(run_dir, "home")
            else:
                run_adb(serial, "shell", "input", "keyevent", "3")
            log_action(actions_log, "home", [])
            return 0, "Pressed home\n", ""

        if cmd == "wait":
            if len(args) != 1 or not WAIT_RE.match(args[0]):
                return 1, "", "Error: 'wait' requires integer seconds (0-99)\n"
            secs = int(args[0])
            if not sim:
                time.sleep(secs)
            log_action(actions_log, "wait", [str(secs)])
            return 0, f"Waited {secs} seconds\n", ""

        if cmd == "launch":
            if len(args) != 0:
                return 1, "", "Error: 'launch' takes no arguments\n"
            if sim:
                subprocess_sim(run_dir, "launch")
            else:
                run_adb(serial, "shell", "am", "start", "-n", f"{app_pkg}/{main_act}")
            log_action(actions_log, "launch", [])
            return 0, "Launched application\n", ""

        return 1, "", f"Error: unknown command '{cmd}'\n"
    finally:
        if env is not None:
            os.environ.clear()
            os.environ.update(old_env)


def subprocess_sim(run_dir: Path, cmd: str, *args: str) -> None:
    state = adb_sim.load_state(run_dir)
    if cmd == "launch":
        if not state.get("screen"):
            state["screen"] = "S09" if state.get("onboarded") else "S01"
    elif cmd == "tap":
        tx, ty = int(args[0]), int(args[1])
        for n in adb_sim.build_screen_nodes(state):
            if not n["clickable"] or not n["enabled"]:
                continue
            parsed = adb_sim.parse_bounds(n["bounds"])
            if parsed and parsed[0] <= tx <= parsed[2] and parsed[1] <= ty <= parsed[3]:
                adb_sim.apply_node_action(state, n.get("action", ""))
                break
    elif cmd == "type":
        text = args[0]
        scr = state.get("screen")
        if scr == "S20":
            state["register_text"] = text
            state["register_error"] = any(ch == " " for ch in text)
        elif scr == "R01":
            state["typed_buffer"] = text
        elif scr == "R08":
            state["enrolment_code"] = text
    elif cmd == "swipe":
        direction = args[0]
        scr = state.get("screen")
        if direction == "up":
            if scr == "S23":
                state["settings_scrolled"] = True
            elif scr == "R07":
                state["r07_scrolled"] = True
        elif direction == "down" and scr == "S23":
            state["settings_scrolled"] = False
    elif cmd == "back":
        hist = state.get("history") or []
        if hist:
            state["screen"] = hist.pop()
    elif cmd == "home":
        state["screen"] = "S09" if state.get("onboarded") else "S01"
    adb_sim.save_state(run_dir, state)


def main() -> int:
    rc, out, err = execute_drive_command(sys.argv[1:])
    if out:
        sys.stdout.write(out)
    if err:
        sys.stderr.write(err)
    return rc


if __name__ == "__main__":
    sys.exit(main())

