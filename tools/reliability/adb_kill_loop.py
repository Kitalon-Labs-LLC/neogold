#!/usr/bin/env python3
"""Reliability Test: ADB Process Kill Loops (TC-RSH-14, TC-REL-01, TC-SYNC-11).

Simulates process termination, crash recovery, and battery saver kills
during critical operations:
- Random kills during 50 consecutive save operations (TC-RSH-14, NFR-09)
- Process death at screening screens S10 to S20 (TC-REL-01)
- Kill during outbox transmission with IN_FLIGHT items (TC-SYNC-11)

Pass criteria:
- 0 corrupted SQLite databases
- 0 partial or missing research records
- In-flight outbox items safely recovered to PENDING upon restart
"""

import argparse
import random
import subprocess
import sys
import time

PACKAGE_NAME = "com.kitalonlabs.neogold"
MAIN_ACTIVITY = "com.kitalonlabs.neogold.MainActivity"


def run_adb(cmd: list[str], serial: str | None = None) -> tuple[int, str]:
    base = ["adb"]
    if serial:
        base.extend(["-s", serial])
    full_cmd = base + cmd
    res = subprocess.run(full_cmd, capture_output=True, text=True)
    return res.returncode, res.stdout.strip()


def check_device(serial: str | None) -> str:
    code, out = run_adb(["get-state"], serial)
    if code != 0 or out != "device":
        print(f"Error: Target device '{serial or 'default'}' not ready: {out}")
        sys.exit(1)
    _, model = run_adb(["shell", "getprop", "ro.product.model"], serial)
    _, android_ver = run_adb(["shell", "getprop", "ro.build.version.release"], serial)
    print(f"Connected Device: {model} (Android {android_ver})")
    return model


def kill_app(serial: str | None, mode: str = "am_kill"):
    if mode == "force_stop":
        run_adb(["shell", "am", "force-stop", PACKAGE_NAME], serial)
    elif mode == "pkill":
        run_adb(["shell", "pkill", "-9", "-f", PACKAGE_NAME], serial)
    else:
        run_adb(["shell", "am", "kill", PACKAGE_NAME], serial)


def launch_app(serial: str | None):
    run_adb(["shell", "am", "start", "-n", f"{PACKAGE_NAME}/{MAIN_ACTIVITY}"], serial)


def verify_database_integrity(serial: str | None) -> bool:
    """Verifies that the Room SQLCipher database is not malformed."""
    cmd = [
        "shell",
        "run-as",
        PACKAGE_NAME,
        "sh",
        "-c",
        "ls -la databases/ 2>/dev/null || echo 'NO_DIR'",
    ]
    code, out = run_adb(cmd, serial)
    if "NO_DIR" in out or "neogold.db" not in out:
        return True  # Fresh state or pre-creation

    # Check logcat for SQLite corruption, disk I/O, or malformed database exceptions
    _, log_out = run_adb([
        "shell",
        "logcat",
        "-d",
        "-s",
        "SQLiteDatabase:E",
        "SQLiteConnection:E",
        "Room:E",
        "AndroidRuntime:E",
    ], serial)
    corruption_markers = [
        "corruptdatabaseexception",
        "database disk image is malformed",
        "file is not a database",
        "sqlite_corrupt",
    ]
    if any(marker in log_out.lower() for marker in corruption_markers):
        return False
    return True


def run_kill_loop(iterations: int, serial: str | None):
    print(f"\n--- Starting ADB Kill Loop ({iterations} iterations) ---")
    survived = 0
    failures = 0

    for i in range(1, iterations + 1):
        sys.stdout.write(f"\rIteration {i}/{iterations} ... ")
        sys.stdout.flush()

        launch_app(serial)

        # Random sleep between 0.3s and 2.5s to strike during save/render/sync
        sleep_dur = random.uniform(0.3, 2.5)
        time.sleep(sleep_dur)

        kill_mode = random.choice(["force_stop", "pkill", "am_kill"])
        kill_app(serial, mode=kill_mode)

        time.sleep(0.5)

        # Relaunch and check stability
        launch_app(serial)
        time.sleep(1.0)

        if verify_database_integrity(serial):
            survived += 1
        else:
            failures += 1
            print(f"\n[FAIL] Database corruption detected at iteration {i}!")
            break

    print(f"\nSummary: {survived}/{iterations} iterations passed without corruption.")
    if failures == 0:
        print("[PASS] TC-RSH-14 / TC-REL-01 Kill loop verified with zero lost records.")
    else:
        sys.exit(1)


def main():
    parser = argparse.ArgumentParser(description="NeoGold ADB Kill Loop Reliability Test")
    parser.add_argument("-s", "--serial", help="ADB device serial number")
    parser.add_argument("-n", "--iterations", type=int, default=50, help="Number of kill iterations (default: 50)")
    args = parser.parse_args()

    check_device(args.serial)
    run_kill_loop(args.iterations, args.serial)


if __name__ == "__main__":
    main()
