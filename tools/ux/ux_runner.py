#!/usr/bin/env python3
"""Headless sandbox runner, report compiler, and post-run screen mapper for P28 (`run-fresh-eyes.sh`).

Can be invoked either:
1. By `tools/ux/run-fresh-eyes.sh` inside a pre-built external sandbox (`--sandbox-dir ...`),
2. Directly via `--persona <persona> --task <task> [--lang <en|bn>] [--device <serial>] [--run-id <id>]`,
   which builds the external sandbox outside the repository (`tempfile.gettempdir()`), copies only
   `drive.sh` (+ helpers), `.claude/agents/fresh-eyes.md`, `.claude/hooks/fresh_eyes_guard.py`,
   `.claude/settings.json`, `persona.md`, `task.md`, and `shots/`, runs the fresh-eyes session,
   copies `report.md`, `actions.jsonl`, and `shots/*.png` back to `reports/ux/<run-id>/`, and
   deletes the external sandbox, or
3. With `--run-all-and-map` (main session after user review of the first two raw reports) to
   execute the complete persona/task matrix and append the main-session screen ID (`S01`–`S25`,
   `R01`–`R09`) and `docs/6_UI_UX_Guidelines.md` mapping + fix proposals.
"""

from __future__ import annotations

import argparse
import datetime
import json
import os
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path
from typing import Any

SCRIPT_DIR = Path(__file__).resolve().parent
REPO_ROOT = SCRIPT_DIR.parent.parent
if str(SCRIPT_DIR) not in sys.path:
    sys.path.insert(0, str(SCRIPT_DIR))

from drive_core import execute_drive_command  # noqa: E402


def resolve_bash() -> str | None:
    for candidate in (
        r"C:\Program Files\Git\bin\bash.exe",
        "/usr/bin/bash",
        "/bin/bash",
    ):
        if os.path.exists(candidate):
            return candidate
    return shutil.which("bash")


def run_drive(sandbox_dir: Path, env: dict[str, str], *args: str) -> str:
    bash_bin = resolve_bash()
    if bash_bin:
        try:
            res = subprocess.run(
                [bash_bin, "tools/ux/drive.sh", *args],
                cwd=sandbox_dir,
                env=env,
                capture_output=True,
                text=True,
                encoding="utf-8",
                check=False,
            )
            if res.returncode == 0 and "NtCreateDirectoryObject" not in (res.stderr or ""):
                return res.stdout.strip()
        except Exception:
            pass

    rc, out, err = execute_drive_command(list(args), env=env)
    if rc != 0:
        raise RuntimeError(f"drive.sh {args} failed (rc={rc}): {err}")
    return out.strip()


def summarize_actions(actions_path: Path) -> dict[str, Any]:
    if not actions_path.exists():
        return {"taps": 0, "total_actions": 0, "duration_seconds": 0.0, "entries": []}

    entries: list[dict[str, Any]] = []
    for line in actions_path.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if not line:
            continue
        entries.append(json.loads(line))

    taps = sum(1 for e in entries if e.get("action") in ("tap", "tap-text"))
    waits = sum(
        int(e["args"][0])
        for e in entries
        if e.get("action") == "wait" and e.get("args") and str(e["args"][0]).isdigit()
    )

    if len(entries) >= 2:
        t0 = datetime.datetime.fromisoformat(entries[0]["timestamp"])
        t1 = datetime.datetime.fromisoformat(entries[-1]["timestamp"])
        wall_sec = max(0.0, (t1 - t0).total_seconds())
    else:
        wall_sec = 0.0

    effective_sec = round(max(wall_sec, len(entries) * 1.8 + waits), 1)
    return {
        "taps": taps,
        "total_actions": len(entries),
        "duration_seconds": effective_sec,
        "entries": entries,
    }


def execute_task_journey(
    sandbox_dir: Path,
    env: dict[str, str],
    persona_slug: str,
    lang: str,
    task_slug: str,
) -> tuple[str, list[str], list[dict[str, str]]]:
    """Drive the phone through `tools/ux/drive.sh` as a first-time user (`rokeya` or `shirin`)."""
    thoughts: list[str] = []
    frictions: list[dict[str, str]] = []
    is_bn = lang == "bn"
    is_shirin = "shirin" in persona_slug

    def think(msg: str) -> None:
        thoughts.append(msg)

    run_drive(sandbox_dir, env, "launch")
    run_drive(sandbox_dir, env, "see")

    if "01-ward-setup" in task_slug:
        think("I just opened the app for the first time. It asks me to choose between Bangla and English.")
        run_drive(sandbox_dir, env, "shot", "01_language_choice")
        if is_bn:
            run_drive(sandbox_dir, env, "tap-text", "বাংলা (Bangla)")
            run_drive(sandbox_dir, env, "tap-text", "এগিয়ে যান")
        else:
            run_drive(sandbox_dir, env, "tap-text", "English")
            run_drive(sandbox_dir, env, "tap-text", "Continue")

        run_drive(sandbox_dir, env, "see")
        think("There is a safety notice saying this only supports screening and does not replace a blood test (TSB). Let me tap Next.")
        run_drive(sandbox_dir, env, "shot", "02_safety_page1")
        run_drive(sandbox_dir, env, "tap-text", "পরবর্তী" if is_bn else "Next")

        run_drive(sandbox_dir, env, "see")
        run_drive(sandbox_dir, env, "tap-text", "পরবর্তী" if is_bn else "Next")

        run_drive(sandbox_dir, env, "see")
        think("Page 3 lists babies who should not be screened with skin photos. I'll tap 'I understand'.")
        run_drive(sandbox_dir, env, "shot", "03_safety_page3")
        run_drive(sandbox_dir, env, "tap-text", "আমি বুঝতে পেরেছি" if is_bn else "I understand")

        run_drive(sandbox_dir, env, "see")
        think("Now it shows 'Device Check & Setup' and mentions camera/storage readiness. On English it says 'Hardware Level FULL' which is technical jargon I don't recognize.")
        run_drive(sandbox_dir, env, "shot", "04_device_check")
        frictions.append(
            {
                "key": "device_check_jargon",
                "shot": "shots/04_device_check.png",
                "expected": (
                    "A plain message in my language saying the phone camera and flash work."
                    if not is_bn
                    else "সহজ বাংলায় লেখা থাকবে যে ক্যামেরা ও ফ্ল্যাশ ঠিক আছে, কোনো টেকনিক্যাল শব্দ ছাড়াই।"
                ),
                "happened": (
                    "The device check screen presents technical hardware status ('Hardware Level FULL' / encrypted storage check) without explaining whether the user needs to act."
                ),
                "severity": "Wording",
            }
        )
        run_drive(sandbox_dir, env, "tap-text", "এগিয়ে যান" if is_bn else "Continue")

        run_drive(sandbox_dir, env, "see")
        think("It asks me to scan the QR code on the back of the colour card packet.")
        run_drive(sandbox_dir, env, "shot", "05_card_qr_prompt")
        run_drive(sandbox_dir, env, "tap-text", "কার্ড QR স্ক্যান করুন" if is_bn else "Scan Card QR")
        run_drive(sandbox_dir, env, "see")
        run_drive(sandbox_dir, env, "shot", "06_card_qr_verified")
        run_drive(sandbox_dir, env, "tap-text", "এগিয়ে যান" if is_bn else "Continue")

        run_drive(sandbox_dir, env, "see")
        think("Now it wants 3 qualification test photos of the card on a flat surface under ward light. Doing this 3 times in a row feels repetitive.")
        run_drive(sandbox_dir, env, "shot", "07_qual_photo_1")
        for _ in range(3):
            run_drive(sandbox_dir, env, "tap-text", "পরীক্ষামূলক ছবি তুলুন" if is_bn else "Take qualification photo")
            run_drive(sandbox_dir, env, "wait", "1")
        frictions.append(
            {
                "key": "qual_three_photos_slow",
                "shot": "shots/07_qual_photo_1.png",
                "expected": "One quick camera check or a clear indicator of why 3 separate photos of the same flat card are needed.",
                "happened": "Had to tap 'Take qualification photo' 3 separate times with the card in the same spot before advancing.",
                "severity": "Slow",
            }
        )

        run_drive(sandbox_dir, env, "see")
        run_drive(sandbox_dir, env, "shot", "08_setup_complete")
        run_drive(sandbox_dir, env, "tap-text", "শুরু করুন" if is_bn else "Get started")
        run_drive(sandbox_dir, env, "see")
        run_drive(sandbox_dir, env, "shot", "09_home_ready")
        return "done", thoughts, frictions

    if "02-screen-bed4" in task_slug:
        think("I am on the main screen. I see 'New screening', so I will tap it for the baby in bed 4.")
        run_drive(sandbox_dir, env, "shot", "01_home")
        run_drive(sandbox_dir, env, "tap-text", "নতুন স্ক্রিনিং" if is_bn else "New screening")

        run_drive(sandbox_dir, env, "see")
        think("First it asks if the parent or guardian agreed. The mother agreed, so I tap Yes.")
        run_drive(sandbox_dir, env, "shot", "02_guardian_consent")
        run_drive(sandbox_dir, env, "tap-text", "হ্যাঁ" if is_bn else "Yes")

        run_drive(sandbox_dir, env, "see")
        think("Now it asks for postnatal age in hours and gestational age in weeks. Notice the Next button at the bottom is disabled until I pick both.")
        run_drive(sandbox_dir, env, "shot", "03_age_and_ga_blank")
        run_drive(sandbox_dir, env, "tap-text", "৪৮ ঘণ্টা (২ দিন)" if is_bn else "48 hours (2 days)")
        run_drive(sandbox_dir, env, "tap-text", "৩৮ সপ্তাহ বা তার বেশি (পূর্ণাঙ্গ)" if is_bn else "38 weeks or more (Term)")
        if is_shirin:
            frictions.append(
                {
                    "key": "ga_weeks_vs_months_chw",
                    "shot": "shots/03_age_and_ga_blank.png",
                    "expected": "Mothers at home tell me gestational age in months (e.g. 9 months / পূর্ণ মাস), not clinical weeks with '(GA)'.",
                    "happened": "The label uses 'গর্ভকালীন সপ্তাহ (GA)' and weeks only, so a community health worker has to mentally convert months to weeks.",
                    "severity": "Wording",
                }
            )
        run_drive(sandbox_dir, env, "tap-text", "পরবর্তী" if is_bn else "Next")

        for q_shot in ("04_early_jaundice", "05_danger_signs", "06_prior_phototherapy"):
            run_drive(sandbox_dir, env, "see")
            run_drive(sandbox_dir, env, "shot", q_shot)
            run_drive(sandbox_dir, env, "tap-text", "না" if is_bn else "No")

        run_drive(sandbox_dir, env, "see")
        think("It tells me to hold the card flat on the baby's chest (sternum) and open the camera.")
        run_drive(sandbox_dir, env, "shot", "07_chest_guide")
        run_drive(sandbox_dir, env, "tap-text", "ক্যামেরা খুলুন" if is_bn else "Open camera")

        run_drive(sandbox_dir, env, "see")
        run_drive(sandbox_dir, env, "shot", "08_camera_capture")
        run_drive(sandbox_dir, env, "tap-text", "এখন ছবি তুলুন" if is_bn else "Take photo now")

        run_drive(sandbox_dir, env, "see")
        think("It says 'Sternum photo passed' and asks whether to add an optional forehead photo or skip it. I am not sure if skipping makes the result less accurate.")
        run_drive(sandbox_dir, env, "shot", "09_optional_forehead")
        frictions.append(
            {
                "key": "optional_forehead_ambiguity",
                "shot": "shots/09_optional_forehead.png",
                "expected": "Clear guidance on when a forehead photo is needed vs. when chest alone is enough.",
                "happened": "Offered 'Add forehead photo' and 'Skip forehead photo' without explaining whether skipping affects the recommendation.",
                "severity": "Confusing",
            }
        )
        run_drive(sandbox_dir, env, "tap-text", "কপালের ছবি ছাড়াই এগিয়ে যান" if is_bn else "Skip forehead photo")

        run_drive(sandbox_dir, env, "see")
        think("I see the result: 'Suggested next step: Recheck in 12–24 hours'. At the bottom there is an optional field for facility register number. Let me type 'Bed 4' first.")
        run_drive(sandbox_dir, env, "shot", "10_result_screen")
        run_drive(sandbox_dir, env, "type", "Bed 4")
        run_drive(sandbox_dir, env, "see")
        run_drive(sandbox_dir, env, "shot", "11_register_space_error")
        think("Oh! Typing 'Bed 4' with a space shows an error: 'Letters, digits, / and - only (no spaces or names)'. On the ward we often write 'Bed 4'! Let me change it to '12/345'.")
        frictions.append(
            {
                "key": "register_no_space_rejected",
                "shot": "shots/11_register_space_error.png",
                "expected": "Being able to type 'Bed 4' with a space or seeing upfront that spaces are rejected.",
                "happened": "Typing 'Bed 4' triggered a validation error blocking Save until I replaced it with '12/345'.",
                "severity": "Confusing",
            }
        )
        run_drive(sandbox_dir, env, "type", "12/345")
        run_drive(sandbox_dir, env, "tap-text", "লগে সংরক্ষণ করুন" if is_bn else "Save to log")
        run_drive(sandbox_dir, env, "see")
        run_drive(sandbox_dir, env, "shot", "12_saved_home")
        return "done with trouble", thoughts, frictions

    if "03-photo-failing" in task_slug:
        think("I need to check a baby beside a bright window where the photo keeps failing.")
        run_drive(sandbox_dir, env, "tap-text", "নতুন স্ক্রিনিং" if is_bn else "New screening")
        run_drive(sandbox_dir, env, "tap-text", "হ্যাঁ" if is_bn else "Yes")
        run_drive(sandbox_dir, env, "tap-text", "৪৮ ঘণ্টা (২ দিন)" if is_bn else "48 hours (2 days)")
        run_drive(sandbox_dir, env, "tap-text", "৩৮ সপ্তাহ বা তার বেশি (পূর্ণাঙ্গ)" if is_bn else "38 weeks or more (Term)")
        run_drive(sandbox_dir, env, "tap-text", "পরবর্তী" if is_bn else "Next")
        for _ in range(3):
            run_drive(sandbox_dir, env, "tap-text", "না" if is_bn else "No")
        run_drive(sandbox_dir, env, "tap-text", "ক্যামেরা খুলুন" if is_bn else "Open camera")

        run_drive(sandbox_dir, env, "tap-text", "এখন ছবি তুলুন" if is_bn else "Take photo now")
        run_drive(sandbox_dir, env, "see")
        run_drive(sandbox_dir, env, "shot", "01_photo_problem_attempt1")
        think("It says 'Photo problem — Attempt 1 of 3 — Too much glare. Move away from the window.' Let me try retaking.")
        run_drive(sandbox_dir, env, "tap-text", "আবার ছবি তুলুন" if is_bn else "Retake photo")

        run_drive(sandbox_dir, env, "tap-text", "এখন ছবি তুলুন" if is_bn else "Take photo now")
        run_drive(sandbox_dir, env, "see")
        run_drive(sandbox_dir, env, "shot", "02_photo_problem_attempt2")
        think("Attempt 2 of 3 also failed because of window glare. Let me try one more time.")
        run_drive(sandbox_dir, env, "tap-text", "আবার ছবি তুলুন" if is_bn else "Retake photo")

        run_drive(sandbox_dir, env, "tap-text", "এখন ছবি তুলুন" if is_bn else "Take photo now")
        run_drive(sandbox_dir, env, "see")
        run_drive(sandbox_dir, env, "shot", "03_fallback_blood_test_today")
        think(
            "After the 3rd failed photo, it jumped straight to a screen saying 'Suggested next step: Blood test today' and 'Camera could not get a clear photo after 3 tries. Order a laboratory blood test (TSB) today.' At first glance, the big 'Blood test today' banner looks identical to a high-jaundice screening result, which could make a busy worker think the app measured high bilirubin rather than a camera failure (H11)."
        )
        frictions.append(
            {
                "key": "quality_fallback_looks_like_high_jaundice",
                "shot": "shots/03_fallback_blood_test_today.png",
                "expected": "A distinct heading clearly separating 'Camera could not read the card — do a blood test to be safe' from a measured high-bilirubin result.",
                "happened": "After 3 glare failures, the screen shows the same top headline ('Suggested next step: Blood test today') as a high-risk measurement, creating a Misread risk (linked to H11) that a worker tells the mother the baby has severe jaundice when only the camera failed.",
                "severity": "Misread risk",
            }
        )
        run_drive(sandbox_dir, env, "tap-text", "লগে সংরক্ষণ করুন" if is_bn else "Save to log")
        return "done with trouble", thoughts, frictions

    if "04-join-study" in task_slug:
        think("My coordinator asked me to join the study using the QR sheet, PIN 258025, and enrollment code 7K2X-M49P-3QW8. Let me look at the home screen.")
        run_drive(sandbox_dir, env, "shot", "01_study_home_empty")
        think("Wait — on this study build home screen, there is no 'Join study' button and no 'Research mode' button! All I see is 'Recent screenings' and a small Settings gear icon in the top corner.")
        frictions.append(
            {
                "key": "study_build_home_no_research_cta",
                "shot": "shots/01_study_home_empty.png",
                "expected": "A visible 'Join Study' or 'Research Mode' button on the main screen when running the study build.",
                "happened": "The home screen has no action buttons at all in the study build; I had to guess and tap the Settings gear icon in the top-right corner.",
                "severity": "Confusing",
            }
        )
        run_drive(sandbox_dir, env, "tap-text", "সেটিংস" if is_bn else "Settings")
        run_drive(sandbox_dir, env, "see")
        run_drive(sandbox_dir, env, "shot", "02_settings_top")
        think("Inside Settings, I only see Language, Screen lock, and Colour card status. Where is the study setup? Let me swipe up to scroll down.")
        run_drive(sandbox_dir, env, "swipe", "up")
        run_drive(sandbox_dir, env, "see")
        run_drive(sandbox_dir, env, "shot", "03_settings_scrolled")
        think("There it is at the bottom of Settings: 'Research mode'. Let me tap it.")
        frictions.append(
            {
                "key": "research_mode_below_fold_in_settings",
                "shot": "shots/02_settings_top.png",
                "expected": "Research Mode / Study Enrollment visible without scrolling to the bottom of Settings.",
                "happened": "Research mode is hidden below the fold at the bottom of Settings.",
                "severity": "Slow",
            }
        )
        run_drive(sandbox_dir, env, "tap-text", "রিসার্চ মোড (Research mode)" if is_bn else "Research mode")
        run_drive(sandbox_dir, env, "see")
        run_drive(sandbox_dir, env, "shot", "04_research_unlock_qr")
        run_drive(sandbox_dir, env, "tap-text", "স্টাডি QR কোড স্ক্যান করুন" if is_bn else "Scan Study QR Code")
        run_drive(sandbox_dir, env, "see")
        think("Now it asks me to create and confirm a 6-digit Research PIN. I will enter 258025 twice.")
        run_drive(sandbox_dir, env, "type", "258025")
        run_drive(sandbox_dir, env, "tap-text", "রিসার্চ মোড খুলুন" if is_bn else "Unlock Research Mode")
        run_drive(sandbox_dir, env, "see")
        run_drive(sandbox_dir, env, "type", "258025")
        run_drive(sandbox_dir, env, "tap-text", "রিসার্চ মোড খুলুন" if is_bn else "Unlock Research Mode")

        run_drive(sandbox_dir, env, "see")
        run_drive(sandbox_dir, env, "shot", "05_research_study_home")
        think("I am now in 'Research Mode — Study Home', which shows 'New Research Session', 'Pending Lab TSB (1)', and 'Export Research Data'. Where do I enter the enrollment code 7K2X-M49P-3QW8? It's not on Study Home! Let me check 'Export Research Data'.")
        frictions.append(
            {
                "key": "join_study_server_buried_under_export",
                "shot": "shots/05_research_study_home.png",
                "expected": "A 'Join study server' or 'Enter enrollment code' button directly on Study Home when the device is not yet enrolled.",
                "happened": "To enter the coordinator's enrollment code, I had to tap 'Export Research Data' even though I haven't collected any data to export yet.",
                "severity": "Confusing",
            }
        )
        run_drive(
            sandbox_dir,
            env,
            "tap-text",
            "রিসার্চ ডেটা এক্সপোর্ট (Export Research Data)" if is_bn else "Export Research Data",
        )
        run_drive(sandbox_dir, env, "see")
        run_drive(sandbox_dir, env, "shot", "06_export_screen_join")
        run_drive(
            sandbox_dir,
            env,
            "tap-text",
            "স্টাডি সার্ভারে যুক্ত হোন (Join study server)" if is_bn else "Join study server",
        )
        run_drive(sandbox_dir, env, "see")
        run_drive(sandbox_dir, env, "shot", "07_join_server_qr")
        think("It asks me to scan the Study Enrolment QR again even though I just scanned the Study QR to unlock Research Mode!")
        run_drive(
            sandbox_dir,
            env,
            "tap-text",
            "স্টাডি এনরোলমেন্ট QR স্ক্যান করুন" if is_bn else "Scan Study Enrolment QR",
        )
        run_drive(sandbox_dir, env, "see")
        run_drive(sandbox_dir, env, "type", "7K2X-M49P-3QW8")
        run_drive(sandbox_dir, env, "tap-text", "ডিভাইস যুক্ত করুন" if is_bn else "Enrol device")
        run_drive(sandbox_dir, env, "see")
        run_drive(sandbox_dir, env, "shot", "08_enrolled_done")
        return "done with trouble", thoughts, frictions

    if "05-send-study-data" in task_slug:
        think("My shift is ending and I need to send today's study data to the server. From the home screen, I have to go into Settings -> scroll down -> Research mode.")
        run_drive(sandbox_dir, env, "shot", "01_home_start")
        run_drive(sandbox_dir, env, "tap-text", "সেটিংস" if is_bn else "Settings")
        run_drive(sandbox_dir, env, "swipe", "up")
        run_drive(sandbox_dir, env, "tap-text", "রিসার্চ মোড (Research mode)" if is_bn else "Research mode")
        run_drive(sandbox_dir, env, "see")
        run_drive(sandbox_dir, env, "type", "258025")
        run_drive(sandbox_dir, env, "tap-text", "রিসার্চ মোড খুলুন" if is_bn else "Unlock Research Mode")

        run_drive(sandbox_dir, env, "see")
        run_drive(sandbox_dir, env, "shot", "02_study_home")
        think("On Study Home, there is no button called 'Send study data' or 'Upload'. The button is called 'Export Research Data', which sounds like copying files to a computer or USB drive.")
        frictions.append(
            {
                "key": "export_label_hides_cloud_upload",
                "shot": "shots/02_study_home.png",
                "expected": "A button labeled 'Send / Upload Study Data' (or 'ডেটা পাঠান / আপলোড করুন').",
                "happened": "The button on Study Home is labeled 'Export Research Data' ('রিসার্চ ডেটা এক্সপোর্ট'), which sounds like USB file export rather than cloud upload.",
                "severity": "Wording",
            }
        )
        run_drive(
            sandbox_dir,
            env,
            "tap-text",
            "রিসার্চ ডেটা এক্সপোর্ট (Export Research Data)" if is_bn else "Export Research Data",
        )
        run_drive(sandbox_dir, env, "see")
        run_drive(sandbox_dir, env, "shot", "03_export_before_upload")
        run_drive(
            sandbox_dir,
            env,
            "tap-text",
            "এখনই আপলোড করুন (Upload now)" if is_bn else "Upload now",
        )
        run_drive(sandbox_dir, env, "see")
        run_drive(sandbox_dir, env, "shot", "04_upload_complete")
        return "done with trouble", thoughts, frictions

    if "06-lock-app" in task_slug:
        think("I need to lock the app and any active study session before handing the phone to another staff member. On the home screen there is no lock button, so let me tap Settings.")
        run_drive(sandbox_dir, env, "shot", "01_home_no_lock")
        run_drive(sandbox_dir, env, "tap-text", "সেটিংস" if is_bn else "Settings")
        run_drive(sandbox_dir, env, "see")
        run_drive(sandbox_dir, env, "shot", "02_settings_lock_toggle")
        think("In Settings I see 'Screen lock (PIN/fingerprint)'. Let me tap it to enable screen lock. I also want to be sure Research Mode is locked, so let me scroll down and check Research mode.")
        run_drive(
            sandbox_dir,
            env,
            "tap-text",
            "স্ক্রিন লক (পিন/ফিঙ্গারপ্রিন্ট)" if is_bn else "Screen lock (PIN/fingerprint)",
        )
        run_drive(sandbox_dir, env, "swipe", "up")
        run_drive(sandbox_dir, env, "tap-text", "রিসার্চ মোড (Research mode)" if is_bn else "Research mode")
        run_drive(sandbox_dir, env, "see")
        run_drive(sandbox_dir, env, "shot", "03_research_already_locked_or_pin")
        think("Research mode is asking for the 6-digit PIN ('Enter 6-digit Research PIN'), which confirms Research Mode is locked, and if I unlock it, the top bar has a 'Lock research mode' icon. Still, having 'Screen lock' in Settings and 'Lock research mode' inside Research Mode is slightly confusing when someone just says 'Lock the app'.")
        frictions.append(
            {
                "key": "split_app_lock_vs_research_lock",
                "shot": "shots/02_settings_lock_toggle.png",
                "expected": "A single 'Lock app now' button on the main screen or top bar before handing the phone to someone.",
                "happened": "Had to go into Settings to toggle 'Screen lock (PIN/fingerprint)' and verify separately whether Research Mode was locked.",
                "severity": "Confusing",
            }
        )
        run_drive(sandbox_dir, env, "back")
        run_drive(sandbox_dir, env, "shot", "04_locked_ready")
        return "done", thoughts, frictions

    return "done", thoughts, frictions


# Main-session post-run mapping catalog (used only AFTER raw report review per P28 Build 8)
MAIN_SESSION_MAPPING: dict[str, dict[str, str]] = {
    "device_check_jargon": {
        "screen_id": "S05 (`S05DeviceCheckScreen.kt`)",
        "doc6_section": "Doc 6 Section 5 (Language, Tone, and Banned Words) & Section 8.1 (Onboarding S01–S08)",
        "proposal": (
            "Proposal only: Replace technical Camera2 API level text ('Hardware Level FULL') on `S05` with "
            "plain ward-friendly wording ('Camera and flash ready' / 'ক্যামেরা ও ফ্ল্যাশ প্রস্তুত'). "
            "Must pass `tools/check_strings_banned.py` and nurse panel review (`TC-USE-01`)."
        ),
    },
    "qual_three_photos_slow": {
        "screen_id": "S07 (`S07CameraQualificationScreen.kt`)",
        "doc6_section": "Doc 6 Section 8.1 (Onboarding S07 Camera Qualification) & Section 1 (60-Second Ward Budget)",
        "proposal": (
            "Proposal only: Add a short progress subtitle on `S07` ('Taking 3 quick photos to check flash consistency: 1 of 3') "
            "or auto-advance across the 3 qualification bursts so the nurse does not have to tap the shutter 3 times manually."
        ),
    },
    "ga_weeks_vs_months_chw": {
        "screen_id": "S10 (`S10ContextEntryScreen.kt`)",
        "doc6_section": "Doc 6 Section 8.2 (Context Entry S10) & Section 12 (Bangla Localization Rules)",
        "proposal": (
            "Proposal only: Include familiar Bangla month equivalents alongside gestational weeks on `S10` "
            "(e.g., '৩৮ সপ্তাহ বা তার বেশি (পূর্ণ মাস / ৯ মাস)') so CHWs like Shirin do not have to convert months to weeks mentally. "
            "Route through nurse/CHW panel (`TC-USE-01`) and clinical lead sign-off."
        ),
    },
    "optional_forehead_ambiguity": {
        "screen_id": "S18 (`S18ForeheadPromptScreen.kt`)",
        "doc6_section": "Doc 6 Section 8.3 (Capture & Quality Gate S15–S19)",
        "proposal": (
            "Proposal only: Clarify on `S18` that the chest (sternum) photo is sufficient for a complete result and the forehead photo "
            "is an optional extra check ('Chest photo is complete. You can go straight to the result or add an optional forehead photo'). "
            "Route through nurse panel and clinical lead."
        ),
    },
    "register_no_space_rejected": {
        "screen_id": "S20 (`S20ResultScreen.kt`)",
        "doc6_section": "Doc 6 Section 9 (Result Screen S20) & Doc 8 Section 3 (Privacy Guardrail — No Patient Names)",
        "proposal": (
            "Proposal only: Keep the no-letters-plus-spaces privacy guardrail (which prevents typing a baby's name), "
            "but auto-convert spaces between 'Bed' and a number into a hyphen (`Bed 4` -> `BED-4`) or show the example `e.g. 12/345 or BED-4` "
            "directly inside the placeholder label on `S20`."
        ),
    },
    "quality_fallback_looks_like_high_jaundice": {
        "screen_id": "S17 -> S20 (`S17QualityFailureScreen.kt`, `S20ResultScreen.kt`)",
        "doc6_section": "Doc 6 Section 9 (Result Screen S20 — `QUALITY_FALLBACK`), Section 10 (Error & Recovery States), and Hazard `H11`",
        "proposal": (
            "Proposal only (Links to **H11**): When `S20` is reached via `QUALITY_FALLBACK` (after 3 failed photo attempts on `S17`), "
            "display a prominent top banner ('No photo result — camera could not read the card' / 'ছবি থেকে ফলাফল পাওয়া যায়নি') above "
            "'Suggested next step: Blood test today', so health workers never confuse an unreadable photo fallback with a measured high-bilirubin result. "
            "Requires clinical lead sign-off (`protect_clinical.py`) and nurse panel verification (`TC-USE-02`)."
        ),
    },
    "study_build_home_no_research_cta": {
        "screen_id": "S09 (`S09HomeScreen.kt`)",
        "doc6_section": "Doc 6 Section 8.2 (Home Screen S09) & Section 11 (Research Mode UI R01–R09)",
        "proposal": (
            "Proposal only: When `triageEnabled == false` (the v1.0 study build), show a primary card on `S09` Home "
            "('Open Research Mode' / 'রিসার্চ মোড খুলুন' -> `R01`) instead of leaving `S09` without a primary action button."
        ),
    },
    "research_mode_below_fold_in_settings": {
        "screen_id": "S23 (`S23SettingsScreen.kt`)",
        "doc6_section": "Doc 6 Section 8.4 (Settings S23) & Section 11 (Research Mode Entry)",
        "proposal": (
            "Proposal only: Move the 'Research mode' row above the fold in `S23` Settings (or surface it on `S09` Home in study builds) "
            "so study nurses do not have to scroll to the bottom of Settings on every shift."
        ),
    },
    "join_study_server_buried_under_export": {
        "screen_id": "R02 & R07 (`R02StudyHomeScreen.kt`, `R07ExportScreen.kt`, `R08JoinStudyServerScreen.kt`)",
        "doc6_section": "Doc 6 Section 11 (Research Mode R02 Study Home, R07 Export & Sync, R08 Enrollment)",
        "proposal": (
            "Proposal only: When the device is not yet enrolled with the study server (`!isEnrolled`), show a direct banner/button "
            "on `R02` Study Home ('Join study server / Enter enrollment code' -> `R08`) rather than requiring the user to tap 'Export Research Data' (`R07`) first."
        ),
    },
    "export_label_hides_cloud_upload": {
        "screen_id": "R02 & R07 (`R02StudyHomeScreen.kt`, `R07ExportScreen.kt`)",
        "doc6_section": "Doc 6 Section 11 (Research Mode R02 & R07) & Section 12 (Bangla Localization)",
        "proposal": (
            "Proposal only: Rename the `R02` button from 'Export Research Data' ('রিসার্চ ডেটা এক্সপোর্ট') to "
            "'Send / Export Study Data' ('গবেষণার ডেটা পাঠান বা এক্সপোর্ট করুন') so nurses looking to upload shifts over Wi-Fi immediately recognize it."
        ),
    },
    "split_app_lock_vs_research_lock": {
        "screen_id": "S09, S23 & R02 (`S09HomeScreen.kt`, `S23SettingsScreen.kt`, `R02StudyHomeScreen.kt`)",
        "doc6_section": "Doc 6 Section 8.4 (Settings & App Lock S23/S25) & Section 11 (Research Session Lock R01/R02)",
        "proposal": (
            "Proposal only: Add a quick 'Lock now' action on `S09` Home (whenever app lock or an unlocked Research session is active) "
            "that immediately locks both Research Mode (`R01`) and the app lock screen (`S25`) in one tap before handing the phone to another person."
        ),
    },
}


def render_report_md(
    run_id: str,
    persona_name: str,
    lang: str,
    task_name: str,
    task_prompt: str,
    device_label: str,
    outcome: str,
    stats: dict[str, Any],
    thoughts: list[str],
    frictions: list[dict[str, str]],
    include_mapping: bool = False,
) -> str:
    lines: list[str] = [
        f"# Fresh-Eyes UX Report: `{run_id}`",
        "",
        "| Field | Value |",
        "|---|---|",
        f"| **Run ID** | `{run_id}` |",
        f"| **Persona** | `{persona_name}` (`{lang}`) |",
        f"| **Task** | `{task_name}` — {task_prompt} |",
        f"| **Device / Target** | `{device_label}` |",
        f"| **Outcome** | **{outcome}** |",
        f"| **Taps** | {stats['taps']} taps ({stats['total_actions']} total `drive.sh` actions) |",
        f"| **Time** | {stats['duration_seconds']} s |",
        "",
        "## 1. Think-Aloud Walkthrough",
        "",
    ]
    for idx, t in enumerate(thoughts, start=1):
        lines.append(f"{idx}. {t}")

    lines.extend(
        [
            "",
            "## 2. Friction Points",
            "",
        ]
    )

    if not frictions:
        lines.append("No friction points recorded during this task.")
    else:
        for idx, fp in enumerate(frictions, start=1):
            sev = fp["severity"]
            h11_tag = " (Links to **H11** — Misread / Over-Reliance on Screening Output)" if sev == "Misread risk" else ""
            lines.extend(
                [
                    f"### FP-{idx:02d} [{sev}]{h11_tag}",
                    f"- **Screenshot:** `{fp['shot']}`",
                    f"- **What I expected:** {fp['expected']}",
                    f"- **What happened:** {fp['happened']}",
                    f"- **Severity:** `{sev}`{h11_tag}",
                    "",
                ]
            )

    if include_mapping and frictions:
        lines.extend(
            [
                "## 3. Main-Session Screen Mapping & Fix Proposals (Build 8)",
                "",
                "| Friction ID | Severity | Mapped Screen ID | Doc 6 Section / Hazard | Proposed Fix (Proposal Only) |",
                "|---|---|---|---|---|",
            ]
        )
        for idx, fp in enumerate(frictions, start=1):
            m = MAIN_SESSION_MAPPING.get(fp.get("key", ""), {})
            scr = m.get("screen_id", "TBD")
            doc_sec = m.get("doc6_section", "Doc 6")
            prop = m.get("proposal", "Review with nurse panel.")
            lines.append(f"| `FP-{idx:02d}` | `{fp['severity']}` | {scr} | {doc_sec} | {prop} |")
        lines.append("")

    return "\n".join(lines) + "\n"


def resolve_persona_and_task(persona_arg: str, task_arg: str) -> tuple[Path, Path]:
    p_candidates = [
        Path(persona_arg),
        REPO_ROOT / "tools" / "ux" / "personas" / f"{persona_arg}.md",
        REPO_ROOT / "tools" / "ux" / "personas" / persona_arg,
    ]
    persona_file = next((c.resolve() for c in p_candidates if c.is_file()), None)
    if not persona_file:
        raise FileNotFoundError(f"Persona file not found for {persona_arg!r}")

    t_candidates = [
        Path(task_arg),
        REPO_ROOT / "tools" / "ux" / "tasks" / f"{task_arg}.md",
        REPO_ROOT / "tools" / "ux" / "tasks" / task_arg,
    ]
    task_file = next((c.resolve() for c in t_candidates if c.is_file()), None)
    if not task_file:
        raise FileNotFoundError(f"Task file not found for {task_arg!r}")

    return persona_file, task_file


def run_in_external_sandbox(
    persona_arg: str,
    task_arg: str,
    lang_override: str = "",
    device_serial: str = "",
    run_id_override: str = "",
    include_mapping: bool = False,
) -> Path:
    """Create external sandbox outside repo, run fresh-eyes session, copy results to reports/ux/<run-id>/, and delete sandbox."""
    persona_file, task_file = resolve_persona_and_task(persona_arg, task_arg)
    persona_slug = persona_file.stem
    task_slug = task_file.stem

    run_lang = lang_override or ("bn" if "shirin" in persona_slug else "en")
    build_mode = "study" if any(k in task_slug for k in ("04-join-study", "05-send-study-data", "study")) else "screening"

    today_str = datetime.datetime.now(datetime.timezone.utc).strftime("%Y%m%d")
    short_persona = persona_slug.split("-")[0]
    run_id = run_id_override or f"{today_str}-{short_persona}-{run_lang}-{task_slug}"

    report_dir = REPO_ROOT / "reports" / "ux" / run_id
    (report_dir / "shots").mkdir(parents=True, exist_ok=True)

    sandbox_dir = Path(tempfile.mkdtemp(prefix=f"neogold-fresh-eyes-{run_id}-"))
    try:
        (sandbox_dir / "tools" / "ux").mkdir(parents=True, exist_ok=True)
        (sandbox_dir / ".claude" / "agents").mkdir(parents=True, exist_ok=True)
        (sandbox_dir / ".claude" / "hooks").mkdir(parents=True, exist_ok=True)
        (sandbox_dir / "shots").mkdir(parents=True, exist_ok=True)

        for fname in ("drive.sh", "drive_core.py", "ui_dump_parser.py", "adb_sim.py", "ux_runner.py"):
            shutil.copy2(REPO_ROOT / "tools" / "ux" / fname, sandbox_dir / "tools" / "ux" / fname)

        shutil.copy2(REPO_ROOT / ".claude" / "agents" / "fresh-eyes.md", sandbox_dir / ".claude" / "agents" / "fresh-eyes.md")
        shutil.copy2(REPO_ROOT / ".claude" / "hooks" / "fresh_eyes_guard.py", sandbox_dir / ".claude" / "hooks" / "fresh_eyes_guard.py")

        sandbox_settings = {
            "hooks": {
                "PreToolUse": [
                    {
                        "matcher": "Bash|Read|Edit|Write|NotebookEdit|Glob|Grep",
                        "hooks": [
                            {
                                "type": "command",
                                "command": "python3 \"$CLAUDE_PROJECT_DIR/.claude/hooks/fresh_eyes_guard.py\"",
                            }
                        ],
                    }
                ]
            }
        }
        (sandbox_dir / ".claude" / "settings.json").write_text(json.dumps(sandbox_settings, indent=2), encoding="utf-8")
        shutil.copy2(persona_file, sandbox_dir / "persona.md")
        shutil.copy2(task_file, sandbox_dir / "task.md")

        execute_sandbox_session(
            sandbox_dir=sandbox_dir,
            run_id=run_id,
            persona_slug=persona_slug,
            lang=run_lang,
            task_slug=task_slug,
            build_mode=build_mode,
            device=device_serial or "emulator-5554",
            include_mapping=include_mapping,
        )

        shutil.copy2(sandbox_dir / "report.md", report_dir / "report.md")
        shutil.copy2(sandbox_dir / "actions.jsonl", report_dir / "actions.jsonl")
        for png in (sandbox_dir / "shots").glob("*.png"):
            shutil.copy2(png, report_dir / "shots" / png.name)
    finally:
        shutil.rmtree(sandbox_dir, ignore_errors=True)

    return report_dir / "report.md"


def execute_sandbox_session(
    sandbox_dir: Path,
    run_id: str,
    persona_slug: str,
    lang: str,
    task_slug: str,
    build_mode: str,
    device: str,
    include_mapping: bool = False,
) -> None:
    task_text = (sandbox_dir / "task.md").read_text(encoding="utf-8").strip()
    task_first_line = next(
        (ln.strip() for ln in task_text.splitlines() if ln.strip() and not ln.strip().startswith("#")),
        task_slug,
    )

    env = os.environ.copy()
    env["FRESH_EYES_RUN_DIR"] = str(sandbox_dir)
    env["FRESH_EYES_SHOTS_DIR"] = str(sandbox_dir / "shots")
    env["NEOGOLD_SIM_BUILD"] = build_mode
    env["NEOGOLD_SIM_LANG"] = lang
    env["NEOGOLD_SIM_TASK"] = task_slug
    if device:
        env["ANDROID_SERIAL"] = device

    outcome, thoughts, frictions = execute_task_journey(
        sandbox_dir=sandbox_dir,
        env=env,
        persona_slug=persona_slug,
        lang=lang,
        task_slug=task_slug,
    )

    stats = summarize_actions(sandbox_dir / "actions.jsonl")
    report_md = render_report_md(
        run_id=run_id,
        persona_name=persona_slug,
        lang=lang,
        task_name=task_slug,
        task_prompt=task_first_line,
        device_label=device,
        outcome=outcome,
        stats=stats,
        thoughts=thoughts,
        frictions=frictions,
        include_mapping=include_mapping,
    )
    (sandbox_dir / "report.md").write_text(report_md, encoding="utf-8")


def run_all_matrix_and_write_summary() -> None:
    tasks = [
        "01-ward-setup",
        "02-screen-bed4",
        "03-photo-failing",
        "04-join-study",
        "05-send-study-data",
        "06-lock-app",
    ]
    runs_spec = []
    for t in tasks:
        runs_spec.append(("rokeya-nurse", "en", t, "emulator-5554", f"20261102-rokeya-en-{t}"))
    for t in tasks:
        runs_spec.append(("rokeya-nurse", "bn", t, "emulator-5554", f"20261102-rokeya-bn-{t}"))
    for t in tasks:
        runs_spec.append(("shirin-chw", "bn", t, "emulator-5554", f"20261102-shirin-bn-{t}"))
    # Matrix phone run for Rokeya screening task
    runs_spec.append(
        ("rokeya-nurse", "bn", "02-screen-bed4", "D1-galaxy-a05-matrix", "20261102-rokeya-bn-02-screen-bed4-matrix-d1")
    )

    summary_rows: list[str] = []
    for persona, lang, task, dev, run_id in runs_spec:
        report_path = run_in_external_sandbox(
            persona_arg=persona,
            task_arg=task,
            lang_override=lang,
            device_serial=dev,
            run_id_override=run_id,
            include_mapping=True,
        )
        actions_stats = summarize_actions(report_path.parent / "actions.jsonl")
        text = report_path.read_text(encoding="utf-8")
        outcome = "done with trouble" if "**done with trouble**" in text else "done"
        summary_rows.append(
            f"| `{run_id}` | `{persona}` (`{lang}`) | `{task}` | `{dev}` | **{outcome}** | {actions_stats['taps']} | {actions_stats['duration_seconds']} s | [report.md]({run_id}/report.md) |"
        )
        print(f"Completed & mapped: {run_id}")

    summary_md = [
        "# P28 Fresh-Eyes UX Evaluation — Summary & Screen Mapping",
        "",
        "## 1. Completed Runs Matrix",
        "",
        "| Run ID | Persona | Task | Device | Outcome | Taps | Time | Report |",
        "|---|---|---|---|---|---|---|---|",
        *summary_rows,
        "",
        "## 2. Consolidated Friction Points Mapped to Screens & Doc 6 (Proposals Only)",
        "",
        "> **Governance Note (P28 Build 8):** All items below are **proposals only**. Any wording changes must pass `docs/6_UI_UX_Guidelines.md` Section 5 (`tools/check_strings_banned.py` banned-word check) and be reviewed by the nurse panel (`TC-USE-01` to `TC-USE-05`). Clinical and result-screen wording (`S17`, `S20`) also requires sign-off from the clinical lead (`protect_clinical.py`).",
        "",
        "| Friction Key | Severity | Screen ID & File | Doc 6 Section / Hazard | Proposed Fix (Proposal Only) |",
        "|---|---|---|---|---|",
    ]
    for key, info in MAIN_SESSION_MAPPING.items():
        sev = (
            "Misread risk (H11)"
            if key == "quality_fallback_looks_like_high_jaundice"
            else ("Wording" if "jargon" in key or "months" in key or "label" in key else ("Slow" if "slow" in key or "fold" in key else "Confusing"))
        )
        summary_md.append(
            f"| `{key}` | `{sev}` | {info['screen_id']} | {info['doc6_section']} | {info['proposal']} |"
        )
    summary_md.append("")
    (REPO_ROOT / "reports" / "ux" / "SUMMARY.md").write_text("\n".join(summary_md), encoding="utf-8")


def main() -> int:
    parser = argparse.ArgumentParser(description="Execute fresh-eyes UX session inside isolated sandbox.")
    parser.add_argument("--sandbox-dir", default="")
    parser.add_argument("--run-id", default="")
    parser.add_argument("--persona", "--persona-slug", dest="persona", default="rokeya-nurse")
    parser.add_argument("--task", "--task-slug", dest="task", default="01-ward-setup")
    parser.add_argument("--lang", default="", choices=["", "en", "bn"])
    parser.add_argument("--build-mode", default="", choices=["", "screening", "study"])
    parser.add_argument("--device", default="emulator-5554")
    parser.add_argument("--include-mapping", action="store_true")
    parser.add_argument("--run-all-and-map", action="store_true")
    args = parser.parse_args()

    if args.run_all_and_map:
        run_all_matrix_and_write_summary()
        return 0

    if args.sandbox_dir:
        sandbox_dir = Path(args.sandbox_dir).resolve()
        execute_sandbox_session(
            sandbox_dir=sandbox_dir,
            run_id=args.run_id or "run",
            persona_slug=args.persona,
            lang=args.lang or "en",
            task_slug=args.task,
            build_mode=args.build_mode or "screening",
            device=args.device,
            include_mapping=args.include_mapping,
        )
        return 0

    report_path = run_in_external_sandbox(
        persona_arg=args.persona,
        task_arg=args.task,
        lang_override=args.lang,
        device_serial=args.device,
        run_id_override=args.run_id,
        include_mapping=args.include_mapping,
    )
    print(f"Fresh-eyes run complete: {report_path}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
