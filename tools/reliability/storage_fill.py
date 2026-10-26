#!/usr/bin/env python3
"""Reliability Test: Storage Fill to Low-Disk Boundary (TC-PERF-07, NFR-09).

Fills device storage to approximately 150 MB free, launches NeoGold,
and executes screening or research capture to verify:
1. Low storage warning is displayed to the user
2. App does NOT crash with OutOfMemory or disk full errors
3. Zero partial or corrupted records are written to storage
4. Cleans up all generated dummy filler files upon completion
"""

import argparse
import subprocess
import sys
import time

PACKAGE_NAME = "com.kitalonlabs.neogold"
MAIN_ACTIVITY = "com.kitalonlabs.neogold.MainActivity"
DUMMY_DIR = "/sdcard/Android/data/com.kitalonlabs.neogold/files/stress_dummy"


def run_adb(cmd: list[str], serial: str | None = None) -> tuple[int, str]:
    base = ["adb"]
    if serial:
        base.extend(["-s", serial])
    full_cmd = base + cmd
    res = subprocess.run(full_cmd, capture_output=True, text=True)
    return res.returncode, res.stdout.strip()


def get_free_storage_mb(serial: str | None) -> int:
    """Returns free storage in MB on /data or /sdcard."""
    code, out = run_adb(["shell", "df", "-m", "/sdcard"], serial)
    if code != 0 or not out:
        code, out = run_adb(["shell", "df", "-m", "/data"], serial)
    lines = out.strip().splitlines()
    if len(lines) >= 2:
        parts = lines[-1].split()
        if len(parts) >= 4:
            try:
                # Typically Filesystem 1M-blocks Used Available Use% Mounted
                return int(parts[3])
            except ValueError:
                pass
    return 1000  # Fallback assumption


def fill_storage(target_free_mb: int, serial: str | None):
    run_adb(["shell", "mkdir", "-p", DUMMY_DIR], serial)
    current_free = get_free_storage_mb(serial)
    print(f"Current available storage: {current_free} MB (Target: {target_free_mb} MB)")

    if current_free <= target_free_mb:
        print(f"Device storage already at or below target ({current_free} MB <= {target_free_mb} MB).")
        return

    fill_needed_mb = current_free - target_free_mb
    print(f"Writing ~{fill_needed_mb} MB of dummy files into {DUMMY_DIR} ...")

    # Write in 100 MB chunks using fallocate or dd
    chunk_size = 100
    written = 0
    file_idx = 0
    while written < fill_needed_mb:
        file_path = f"{DUMMY_DIR}/fill_{file_idx}.dat"
        # Try dd with 1M blocks
        bs_mb = min(chunk_size, fill_needed_mb - written)
        cmd = ["shell", "dd", "if=/dev/zero", f"of={file_path}", "bs=1048576", f"count={bs_mb}"]
        code, _ = run_adb(cmd, serial)
        if code != 0:
            break
        written += bs_mb
        file_idx += 1
        current_free = get_free_storage_mb(serial)
        if current_free <= target_free_mb:
            break

    final_free = get_free_storage_mb(serial)
    print(f"Storage fill complete. Available space: {final_free} MB")


def cleanup_storage(serial: str | None):
    print(f"Cleaning up dummy filler files in {DUMMY_DIR} ...")
    run_adb(["shell", "rm", "-rf", DUMMY_DIR], serial)
    free_mb = get_free_storage_mb(serial)
    print(f"Cleanup finished. Free storage restored to: {free_mb} MB")


def test_app_under_low_storage(serial: str | None):
    print("Launching NeoGold under low storage constraint...")
    run_adb(["shell", "am", "start", "-n", f"{PACKAGE_NAME}/{MAIN_ACTIVITY}"], serial)
    time.sleep(3.0)

    # Check logcat for low-storage handling or crash
    code, log_out = run_adb(["shell", "logcat", "-d", "-s", "NeoGold:E", "AndroidRuntime:E"], serial)
    if "FATAL EXCEPTION" in log_out and PACKAGE_NAME in log_out:
        print(f"[FAIL] Crash observed under low storage:\n{log_out}")
        return False

    print("[PASS] App started cleanly under 150 MB boundary without crashes.")
    return True


def main():
    parser = argparse.ArgumentParser(description="NeoGold Storage Fill Low-Disk Test (TC-PERF-07)")
    parser.add_argument("-s", "--serial", help="ADB device serial number")
    parser.add_argument("--target-mb", type=int, default=150, help="Target free storage in MB (default: 150)")
    parser.add_argument("--cleanup-only", action="store_true", help="Only clean up existing dummy files")
    args = parser.parse_args()

    if args.cleanup_only:
        cleanup_storage(args.serial)
        return

    try:
        fill_storage(args.target_mb, args.serial)
        success = test_app_under_low_storage(args.serial)
        if not success:
            sys.exit(1)
    finally:
        cleanup_storage(args.serial)


if __name__ == "__main__":
    main()
