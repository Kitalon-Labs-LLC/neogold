#!/usr/bin/env python3
"""Deterministic Android UI / ADB simulator fixture for `tools/ux/drive.sh` (P28).

Models the exact screen graph (`S01`..`S25`, `R01`..`R09`) and string resources (English & Bangla)
from the NeoGold Android codebase so `drive.sh` and `run-fresh-eyes.sh` can execute full user
journeys when no physical ADB device or emulator is attached.

Every XML dump includes realistic `resource-id`, `class`, and `package` attributes so
`ui_dump_parser.py` is exercised on stripping them on every `drive.sh see` call.
"""

from __future__ import annotations

import argparse
import json
import os
import struct
import sys
import xml.etree.ElementTree as ET
import zlib
from pathlib import Path
from typing import Any

from ui_dump_parser import parse_bounds

# Exact strings from android/*/src/main/res/values/strings.xml and values-bn/strings.xml
STRINGS: dict[str, dict[str, str]] = {
    "en": {
        "s01_title": "Choose Language / ভাষা নির্বাচন করুন",
        "s01_bn": "বাংলা (Bangla)",
        "s01_en": "English",
        "continue": "Continue",
        "next": "Next",
        "back": "Back",
        "s02_title": "Screening Support Only",
        "s02_body": "NeoGold estimates jaundice risk from a skin and colour-card photo. It does not replace a laboratory blood test (TSB).",
        "s03_title": "Always Confirm With Blood Test",
        "s03_body": "When NeoGold suggests a blood test or hospital referral, always follow your facility protocol and clinical judgment.",
        "s04_title": "Who Not To Screen",
        "s04_body": "Do not rely on skin photo screening if the baby is under 24 hours old, has danger signs, or has already received phototherapy.",
        "i_understand": "I understand",
        "s05_title": "Device Check & Setup",
        "s05_cam_ok": "Camera & Torch: Ready (Hardware Level FULL)",
        "s05_storage_ok": "Encrypted Storage: Ready",
        "s06_title": "Scan NeoGold Card QR",
        "s06_hint": "Align the QR code on the back of your NeoGold colour card inside the frame.",
        "s06_scan_btn": "Scan Card QR",
        "s06_verified": "Card verified: Batch NG-2026-04 (0 uses / 200 max)",
        "s07_title": "Camera Qualification (3 Test Photos)",
        "s07_sub": "Place the NeoGold card on a flat surface under ward light and take 3 test photos.",
        "s07_take_photo": "Take qualification photo",
        "s08_title": "Qualification Complete",
        "s08_sub": "Your phone camera and colour card are ready for ward screening.",
        "get_started": "Get started",
        "s09_title": "NeoGold",
        "s09_sub_screening": "Ready to screen",
        "s09_sub_study": "Study data collection build",
        "s09_new_screening": "New screening",
        "s09_recent": "Recent screenings",
        "s09_settings_desc": "Settings",
        "s10a_title": "Guardian Consent",
        "s10a_q": "Has the baby's parent or guardian agreed to this screening?",
        "s10a_note": "Explain that a photo of the chest with the colour card will be used to check jaundice risk.",
        "yes": "Yes",
        "no": "No",
        "s10_title": "Baby's Age & Gestation",
        "s10_age_label": "Postnatal age (hours since birth)",
        "s10_age_opt_48": "48 hours (2 days)",
        "s10_ga_label": "Gestational age at birth",
        "s10_ga_opt_38": "38 weeks or more (Term)",
        "s10_ga_opt_36": "35 to 37 weeks",
        "s11_title": "Jaundice in First 24 Hours",
        "s11_q": "Was yellow skin or eyes seen in the first 24 hours after birth?",
        "s12_title": "Danger Signs Check",
        "s12_q": "Does the baby have any danger signs (poor feeding, lethargy, fever, convulsions, stiff body)?",
        "s13_title": "Prior Phototherapy",
        "s13_q": "Has this baby already received phototherapy?",
        "s15_title": "Place Card on Baby's Chest",
        "s15_body": "Hold the NeoGold card flat against the baby's sternum. Keep window glare off the card.",
        "open_camera": "Open camera",
        "s16_status_ready": "Hold still — capturing",
        "s16_capture_btn": "Take photo now",
        "s17_title": "Photo problem",
        "s17_glare": "Too much glare. Move away from the window.",
        "s17_retake": "Retake photo",
        "s17_use_fallback": "Cannot get clear photo — view guidance",
        "s18_title": "Sternum photo passed",
        "s18_sub": "You can add an optional forehead photo or continue straight to the result.",
        "s18_add_forehead": "Add forehead photo",
        "s18_skip_forehead": "Skip forehead photo",
        "s20_header": "Suggested next step",
        "s20_band_blood": "Blood test today",
        "s20_band_recheck": "Recheck in 12–24 hours",
        "s20_authority": "You decide, using your clinical judgment",
        "s20_confirm_blood": "Always confirm with a blood test",
        "s20_fallback_note": "Camera could not get a clear photo after 3 tries. Order a laboratory blood test (TSB) today.",
        "s20_reg_label": "Facility register no. (optional, e.g. 12/345)",
        "s20_reg_error": "Letters, digits, / and - only (no spaces or names)",
        "s20_save": "Save to log",
        "s23_title": "Settings",
        "s23_lang": "Language / ভাষা",
        "s23_lock": "Screen lock (PIN/fingerprint)",
        "s23_card": "Colour card status (Batch NG-2026-04)",
        "s23_research": "Research mode",
        "s23_about": "About NeoGold",
        "r01_title": "Research Unlock",
        "r01_scan_qr": "Scan Study QR Code",
        "r01_pin_label": "Enter 6-digit Research PIN",
        "r01_pin_create": "Create 6-digit Research PIN",
        "r01_pin_confirm": "Confirm 6-digit Research PIN",
        "r01_unlock_btn": "Unlock Research Mode",
        "r02_title": "Research Mode — Study Home",
        "r02_lock_desc": "Lock research mode",
        "r02_new_session": "New Research Session",
        "r02_pending_tsb": "Pending Lab TSB (1)",
        "r02_export": "Export Research Data",
        "r07_title": "Export Research Data",
        "r07_usb": "Export encrypted ZIP to USB",
        "r07_cloud_title": "Cloud sync status",
        "r07_join_server": "Join study server",
        "r07_upload_now": "Upload now",
        "r07_uploaded_ok": "Last upload: Just now (0 queued packets remaining)",
        "r08_title": "Join study server",
        "r08_scan_qr": "Scan Study Enrolment QR",
        "r08_code_label": "One-time enrolment code (XXXX-XXXX-XXXX)",
        "r08_submit": "Enrol device",
        "r08_enrolled_ok": "Device enrolled in study SKP-2026-DHK",
    },
    "bn": {
        "s01_title": "Choose Language / ভাষা নির্বাচন করুন",
        "s01_bn": "বাংলা (Bangla)",
        "s01_en": "English",
        "continue": "এগিয়ে যান",
        "next": "পরবর্তী",
        "back": "পেছনে",
        "s02_title": "শুধুমাত্র স্ক্রিনিং সহায়তার জন্য",
        "s02_body": "নিওগোল্ড ত্বক ও কালার কার্ডের ছবি থেকে জন্ডিসের ঝুঁকি অনুমান করে। এটি রক্ত পরীক্ষার (TSB) বিকল্প নয়।",
        "s03_title": "সর্বদা রক্ত পরীক্ষার মাধ্যমে নিশ্চিত করুন",
        "s03_body": "নিওগোল্ড রক্ত পরীক্ষা বা হাসপাতালে রেফার করার পরামর্শ দিলে সর্বদা আপনার হাসপাতালের নিয়ম ও ক্লিনিকাল সিদ্ধান্ত অনুসরণ করুন।",
        "s04_title": "কাদের স্ক্রিনিং করবেন না",
        "s04_body": "শিশুর বয়স ২৪ ঘণ্টার কম হলে, বিপদচিহ্ন থাকলে, বা আগে ফটোথেরাপি পেয়ে থাকলে এই অ্যাপের ওপর নির্ভর করবেন না।",
        "i_understand": "আমি বুঝতে পেরেছি",
        "s05_title": "ফোন পরীক্ষা ও সেটআপ",
        "s05_cam_ok": "ক্যামেরা ও ফ্ল্যাশ: প্রস্তুত",
        "s05_storage_ok": "সুরক্ষিত স্টোরেজ: প্রস্তুত",
        "s06_title": "নিওগোল্ড কার্ডের QR স্ক্যান করুন",
        "s06_hint": "নিওগোল্ড কালার কার্ডের পেছনের QR কোডটি ফ্রেমের ভেতরে রাখুন।",
        "s06_scan_btn": "কার্ড QR স্ক্যান করুন",
        "s06_verified": "কার্ড যাচাইকৃত: ব্যাচ NG-2026-04 (০ বার ব্যবহৃত / সর্বোচ্চ ২০০)",
        "s07_title": "ক্যামেরা যাচাইকরণ (৩টি পরীক্ষামূলক ছবি)",
        "s07_sub": "ওয়ার্ডের আলোতে সমতল স্থানে নিওগোল্ড কার্ড রেখে ৩টি পরীক্ষামূলক ছবি তুলুন।",
        "s07_take_photo": "পরীক্ষামূলক ছবি তুলুন",
        "s08_title": "যাচাইকরণ সম্পন্ন হয়েছে",
        "s08_sub": "আপনার ফোনের ক্যামেরা এবং কালার কার্ড স্ক্রিনিংয়ের জন্য প্রস্তুত।",
        "get_started": "শুরু করুন",
        "s09_title": "নিওগোল্ড (NeoGold)",
        "s09_sub_screening": "স্ক্রিনিংয়ের জন্য প্রস্তুত",
        "s09_sub_study": "গবেষণা তথ্য সংগ্রহের সংস্করণ",
        "s09_new_screening": "নতুন স্ক্রিনিং",
        "s09_recent": "সাম্প্রতিক স্ক্রিনিং",
        "s09_settings_desc": "সেটিংস",
        "s10a_title": "অভিভাবকের সম্মতি",
        "s10a_q": "শিশুর মা-বাবা বা অভিভাবক কি এই স্ক্রিনিংয়ে সম্মতি দিয়েছেন?",
        "s10a_note": "বুঝিয়ে বলুন যে জন্ডিসের ঝুঁকি দেখার জন্য বুকের ওপর কালার কার্ড রেখে একটি ছবি তোলা হবে।",
        "yes": "হ্যাঁ",
        "no": "না",
        "s10_title": "শিশুর বয়স ও গর্ভকালীন সপ্তাহ",
        "s10_age_label": "জন্মের পর থেকে বয়স (ঘণ্টা)",
        "s10_age_opt_48": "৪৮ ঘণ্টা (২ দিন)",
        "s10_ga_label": "জন্মের সময় গর্ভকালীন সপ্তাহ (GA)",
        "s10_ga_opt_38": "৩৮ সপ্তাহ বা তার বেশি (পূর্ণাঙ্গ)",
        "s10_ga_opt_36": "৩৫ থেকে ৩৭ সপ্তাহ",
        "s11_title": "প্রথম ২৪ ঘণ্টায় জন্ডিস",
        "s11_q": "জন্মের প্রথম ২৪ ঘণ্টার মধ্যে কি ত্বক বা চোখ হলুদ দেখা গিয়েছিল?",
        "s12_title": "বিপদচিহ্ন পরীক্ষা",
        "s12_q": "শিশুর কি কোনো বিপদচিহ্ন আছে (বুকের দুধ না টানা, নিস্তেজ থাকা, জ্বর, খিঁচুনি, শরীর শক্ত হয়ে যাওয়া)?",
        "s13_title": "পূর্ববর্তী ফটোথেরাপি",
        "s13_q": "এই শিশু কি আগে ফটোথেরাপি পেয়েছে?",
        "s15_title": "শিশুর বুকে কার্ড রাখুন",
        "s15_body": "নিওগোল্ড কার্ডটি শিশুর বুকের মাঝখানে (স্টার্নাম) সমানভাবে ধরুন। জানালার তীব্র আলো যেন কার্ডে না পড়ে।",
        "open_camera": "ক্যামেরা খুলুন",
        "s16_status_ready": "স্থির হয়ে ধরুন — ছবি তোলা হচ্ছে",
        "s16_capture_btn": "এখন ছবি তুলুন",
        "s17_title": "ছবিতে সমস্যা হয়েছে",
        "s17_glare": "অতিরিক্ত আলোর ঝলকানি (Glare)। জানালা থেকে সরে দাঁড়ান।",
        "s17_retake": "আবার ছবি তুলুন",
        "s17_use_fallback": "স্পষ্ট ছবি পাওয়া যাচ্ছে না — করণীয় দেখুন",
        "s18_title": "বুকের ছবি সঠিক হয়েছে",
        "s18_sub": "আপনি চাইলে কপালের একটি ঐচ্ছিক ছবি যোগ করতে পারেন অথবা সরাসরি ফলাফলে যেতে পারেন।",
        "s18_add_forehead": "কপালের ছবি যোগ করুন",
        "s18_skip_forehead": "কপালের ছবি ছাড়াই এগিয়ে যান",
        "s20_header": "পরবর্তী করণীয় (পরামর্শ)",
        "s20_band_blood": "আজই রক্ত পরীক্ষা করুন",
        "s20_band_recheck": "১২–২৪ ঘণ্টার মধ্যে পুনরায় পরীক্ষা করুন",
        "s20_authority": "আপনার ক্লিনিকাল বিবেচনা অনুযায়ী চূড়ান্ত সিদ্ধান্ত নিন",
        "s20_confirm_blood": "সর্বদা রক্ত পরীক্ষার মাধ্যমে নিশ্চিত করুন",
        "s20_fallback_note": "৩ বার চেষ্টার পরেও স্পষ্ট ছবি পাওয়া যায়নি। আজই ল্যাবরেটরি রক্ত পরীক্ষা (TSB) করান।",
        "s20_reg_label": "রেজিস্টার নম্বর (ঐচ্ছিক, যেমন 12/345)",
        "s20_reg_error": "শুধুমাত্র ইংরেজি অক্ষর, সংখ্যা, / এবং - ব্যবহার করুন (ফাঁকা স্থান বা নাম নয়)",
        "s20_save": "লগে সংরক্ষণ করুন",
        "s23_title": "সেটিংস",
        "s23_lang": "Language / ভাষা",
        "s23_lock": "স্ক্রিন লক (পিন/ফিঙ্গারপ্রিন্ট)",
        "s23_card": "কালার কার্ডের অবস্থা (ব্যাচ NG-2026-04)",
        "s23_research": "রিসার্চ মোড (Research mode)",
        "s23_about": "নিওগোল্ড সম্পর্কে",
        "r01_title": "রিসার্চ আনলক (Research Unlock)",
        "r01_scan_qr": "স্টাডি QR কোড স্ক্যান করুন",
        "r01_pin_label": "৬-সংখ্যার রিসার্চ পিন দিন",
        "r01_pin_create": "নতুন ৬-সংখ্যার রিসার্চ পিন তৈরি করুন",
        "r01_pin_confirm": "৬-সংখ্যার রিসার্চ পিন নিশ্চিত করুন",
        "r01_unlock_btn": "রিসার্চ মোড খুলুন",
        "r02_title": "রিসার্চ মোড — স্টাডি হোম",
        "r02_lock_desc": "রিসার্চ মোড লক করুন",
        "r02_new_session": "নতুন রিসার্চ সেশন",
        "r02_pending_tsb": "বাকি থাকা ল্যাব TSB (১)",
        "r02_export": "রিসার্চ ডেটা এক্সপোর্ট (Export Research Data)",
        "r07_title": "রিসার্চ ডেটা এক্সপোর্ট",
        "r07_usb": "USB-তে এনক্রিপ্টেড ZIP এক্সপোর্ট করুন",
        "r07_cloud_title": "ক্লাউড সিঙ্ক স্ট্যাটাস",
        "r07_join_server": "স্টাডি সার্ভারে যুক্ত হোন (Join study server)",
        "r07_upload_now": "এখনই আপলোড করুন (Upload now)",
        "r07_uploaded_ok": "সর্বশেষ আপলোড: এইমাত্র সম্পন্ন (০টি প্যাকেট বাকি)",
        "r08_title": "স্টাডি সার্ভারে যুক্ত হোন",
        "r08_scan_qr": "স্টাডি এনরোলমেন্ট QR স্ক্যান করুন",
        "r08_code_label": "এককালীন এনরোলমেন্ট কোড (XXXX-XXXX-XXXX)",
        "r08_submit": "ডিভাইস যুক্ত করুন",
        "r08_enrolled_ok": "ডিভাইসটি SKP-2026-DHK গবেষণায় যুক্ত হয়েছে",
    },
}


def default_state() -> dict[str, Any]:
    mode = os.environ.get("NEOGOLD_SIM_BUILD", "screening").strip()
    lang = os.environ.get("NEOGOLD_SIM_LANG", "en").strip()
    task_slug = os.environ.get("NEOGOLD_SIM_TASK", "01-ward-setup").strip()

    onboarded = task_slug != "01-ward-setup"
    initial_screen = "S01" if not onboarded else "S09"
    bench_scenario = "bad_05_glare" if "photo-failing" in task_slug or "03" in task_slug else "good_01_nominal"
    research_pin_set = task_slug in ("05-send-study-data", "06-lock-app")
    study_enrolled = task_slug in ("05-send-study-data", "06-lock-app")

    return {
        "build": mode,  # "screening" (triageEnabled=True) or "study" (triageEnabled=False)
        "lang": lang if lang in ("en", "bn") else "en",
        "task_slug": task_slug,
        "bench_scenario": bench_scenario,
        "screen": initial_screen,
        "history": [],
        "onboarded": onboarded,
        "card_qr_scanned": onboarded,
        "qual_photos_taken": 3 if onboarded else 0,
        "age_selected": False,
        "ga_selected": False,
        "photo_attempts": 0,
        "result_is_fallback": False,
        "register_text": "",
        "register_error": False,
        "saved_to_log": False,
        "settings_scrolled": False,
        "screen_lock_enabled": False,
        "research_qr_scanned": research_pin_set,
        "research_pin_set": research_pin_set,
        "research_pin": "258025" if research_pin_set else "",
        "typed_buffer": "",
        "pin_stage": "enter" if research_pin_set else "create",
        "r07_scrolled": False,
        "r08_qr_scanned": False,
        "enrolment_code": "",
        "study_enrolled": study_enrolled,
        "study_uploaded": False,
        "research_locked": False,
    }


def state_file_path(run_dir: Path) -> Path:
    return run_dir / ".sim_state.json"


def load_state(run_dir: Path) -> dict[str, Any]:
    sf = state_file_path(run_dir)
    if sf.exists():
        try:
            return json.loads(sf.read_text(encoding="utf-8"))
        except Exception:
            pass
    st = default_state()
    save_state(run_dir, st)
    return st


def save_state(run_dir: Path, state: dict[str, Any]) -> None:
    sf = state_file_path(run_dir)
    sf.write_text(json.dumps(state, indent=2, ensure_ascii=False), encoding="utf-8")


def build_screen_nodes(state: dict[str, Any]) -> list[dict[str, Any]]:
    """Return UI nodes for current screen with resource_id, class_name, package, text, desc, bounds, clickable."""
    lang = state.get("lang", "en")
    t = STRINGS[lang]
    scr = state.get("screen", "S01")
    nodes: list[dict[str, Any]] = []

    def add(
        res_id: str,
        text: str = "",
        desc: str = "",
        bounds: str = "[60,120][1020,220]",
        clickable: bool = False,
        enabled: bool = True,
        checked: bool = False,
        scrollable: bool = False,
        focused: bool = False,
        action: str = "",
    ) -> None:
        nodes.append(
            {
                "resource_id": f"com.kitalonlabs.neogold:id/{res_id}",
                "class": "android.widget.Button" if clickable else "android.widget.TextView",
                "package": "com.kitalonlabs.neogold",
                "text": text,
                "desc": desc,
                "bounds": bounds,
                "clickable": clickable,
                "enabled": enabled,
                "checked": checked,
                "scrollable": scrollable,
                "focused": focused,
                "action": action,
            }
        )

    if scr == "S01":
        add("s01_title", text=t["s01_title"], bounds="[60,140][1020,240]")
        add(
            "s01_lang_bn",
            text=t["s01_bn"],
            bounds="[80,360][1000,520]",
            clickable=True,
            checked=(lang == "bn"),
            action="lang_bn",
        )
        add(
            "s01_lang_en",
            text=t["s01_en"],
            bounds="[80,560][1000,720]",
            clickable=True,
            checked=(lang == "en"),
            action="lang_en",
        )
        add("s01_continue", text=t["continue"], bounds="[80,1920][1000,2080]", clickable=True, action="goto:S02")

    elif scr == "S02":
        add("s02_step", text="1 / 3", bounds="[60,100][240,160]")
        add("s02_title", text=t["s02_title"], bounds="[60,200][1020,300]")
        add("s02_body", text=t["s02_body"], bounds="[60,340][1020,640]")
        add("s02_next", text=t["next"], bounds="[80,1920][1000,2080]", clickable=True, action="goto:S03")

    elif scr == "S03":
        add("s03_step", text="2 / 3", bounds="[60,100][240,160]")
        add("s03_title", text=t["s03_title"], bounds="[60,200][1020,300]")
        add("s03_body", text=t["s03_body"], bounds="[60,340][1020,640]")
        add("s03_next", text=t["next"], bounds="[80,1920][1000,2080]", clickable=True, action="goto:S04")

    elif scr == "S04":
        add("s04_step", text="3 / 3", bounds="[60,100][240,160]")
        add("s04_title", text=t["s04_title"], bounds="[60,200][1020,300]")
        add("s04_body", text=t["s04_body"], bounds="[60,340][1020,640]")
        add("s04_understand", text=t["i_understand"], bounds="[80,1920][1000,2080]", clickable=True, action="goto:S05")

    elif scr == "S05":
        add("s05_title", text=t["s05_title"], bounds="[60,140][1020,240]")
        add("s05_cam", text=t["s05_cam_ok"], bounds="[60,320][1020,420]")
        add("s05_storage", text=t["s05_storage_ok"], bounds="[60,460][1020,560]")
        add("s05_continue", text=t["continue"], bounds="[80,1920][1000,2080]", clickable=True, action="goto:S06")

    elif scr == "S06":
        add("s06_title", text=t["s06_title"], bounds="[60,140][1020,240]")
        add("s06_hint", text=t["s06_hint"], bounds="[60,280][1020,420]")
        if not state.get("card_qr_scanned"):
            add("s06_scan", text=t["s06_scan_btn"], bounds="[140,900][940,1080]", clickable=True, action="scan_card_qr")
        else:
            add("s06_verified", text=t["s06_verified"], bounds="[60,900][1020,1040]")
            add("s06_continue", text=t["continue"], bounds="[80,1920][1000,2080]", clickable=True, action="goto:S07")

    elif scr == "S07":
        count = int(state.get("qual_photos_taken", 0))
        add("s07_title", text=t["s07_title"], bounds="[60,140][1020,240]")
        add("s07_sub", text=t["s07_sub"], bounds="[60,280][1020,420]")
        add("s07_progress", text=f"Photo {count} of 3", bounds="[60,500][1020,580]")
        add("s07_capture", text=t["s07_take_photo"], bounds="[140,1600][940,1780]", clickable=True, action="take_qual_photo")

    elif scr == "S08":
        add("s08_title", text=t["s08_title"], bounds="[60,200][1020,300]")
        add("s08_sub", text=t["s08_sub"], bounds="[60,360][1020,500]")
        add("s08_start", text=t["get_started"], bounds="[80,1920][1000,2080]", clickable=True, action="finish_onboarding")

    elif scr == "S09":
        add("s09_title", text=t["s09_title"], bounds="[60,100][700,200]")
        add(
            "s09_settings_btn",
            text="",
            desc=t["s09_settings_desc"],
            bounds="[900,80][1040,220]",
            clickable=True,
            action="goto:S23",
        )
        if state.get("build") == "screening":
            add("s09_sub", text=t["s09_sub_screening"], bounds="[60,220][1020,300]")
            add(
                "s09_new_screening",
                text=t["s09_new_screening"],
                bounds="[80,400][1000,620]",
                clickable=True,
                action="start_screening",
            )
        else:
            # Study build: triageEnabled is false, so New screening button is omitted on S09!
            add("s09_sub", text=t["s09_sub_study"], bounds="[60,220][1020,300]")
        add("s09_recent", text=t["s09_recent"], bounds="[60,720][1020,800]")

    elif scr == "S10a":
        add("s10a_title", text=t["s10a_title"], bounds="[60,140][1020,240]")
        add("s10a_q", text=t["s10a_q"], bounds="[60,300][1020,440]")
        add("s10a_note", text=t["s10a_note"], bounds="[60,480][1020,640]")
        add("s10a_yes", text=t["yes"], bounds="[80,1680][1000,1840]", clickable=True, action="goto:S10")
        add("s10a_no", text=t["no"], bounds="[80,1880][1000,2040]", clickable=True, action="goto:S09")

    elif scr == "S10":
        age_ok = bool(state.get("age_selected"))
        ga_ok = bool(state.get("ga_selected"))
        add("s10_title", text=t["s10_title"], bounds="[60,140][1020,240]")
        add("s10_age_lbl", text=t["s10_age_label"], bounds="[60,300][1020,380]")
        add(
            "s10_age_48",
            text=t["s10_age_opt_48"],
            bounds="[80,400][1000,540]",
            clickable=True,
            checked=age_ok,
            action="select_age",
        )
        add("s10_ga_lbl", text=t["s10_ga_label"], bounds="[60,620][1020,700]")
        add(
            "s10_ga_38",
            text=t["s10_ga_opt_38"],
            bounds="[80,720][1000,860]",
            clickable=True,
            checked=ga_ok,
            action="select_ga",
        )
        add(
            "s10_ga_36",
            text=t["s10_ga_opt_36"],
            bounds="[80,890][1000,1030]",
            clickable=True,
            checked=False,
            action="select_ga",
        )
        add(
            "s10_next",
            text=t["next"],
            bounds="[80,1920][1000,2080]",
            clickable=(age_ok and ga_ok),
            enabled=(age_ok and ga_ok),
            action="goto:S11",
        )

    elif scr == "S11":
        add("s11_title", text=t["s11_title"], bounds="[60,140][1020,240]")
        add("s11_q", text=t["s11_q"], bounds="[60,300][1020,460]")
        add("s11_no", text=t["no"], bounds="[80,1680][1000,1840]", clickable=True, action="goto:S12")
        add("s11_yes", text=t["yes"], bounds="[80,1880][1000,2040]", clickable=True, action="goto:S12")

    elif scr == "S12":
        add("s12_title", text=t["s12_title"], bounds="[60,140][1020,240]")
        add("s12_q", text=t["s12_q"], bounds="[60,300][1020,480]")
        add("s12_no", text=t["no"], bounds="[80,1680][1000,1840]", clickable=True, action="goto:S13")
        add("s12_yes", text=t["yes"], bounds="[80,1880][1000,2040]", clickable=True, action="goto:S13")

    elif scr == "S13":
        add("s13_title", text=t["s13_title"], bounds="[60,140][1020,240]")
        add("s13_q", text=t["s13_q"], bounds="[60,300][1020,460]")
        add("s13_no", text=t["no"], bounds="[80,1680][1000,1840]", clickable=True, action="goto:S15")
        add("s13_yes", text=t["yes"], bounds="[80,1880][1000,2040]", clickable=True, action="goto:S15")

    elif scr == "S15":
        add("s15_title", text=t["s15_title"], bounds="[60,140][1020,240]")
        add("s15_body", text=t["s15_body"], bounds="[60,300][1020,480]")
        add("s15_open_cam", text=t["open_camera"], bounds="[80,1920][1000,2080]", clickable=True, action="goto:S16")

    elif scr == "S16":
        add("s16_status", text=t["s16_status_ready"], bounds="[60,120][1020,220]")
        add("s16_shutter", text=t["s16_capture_btn"], bounds="[240,1840][840,2040]", clickable=True, action="capture_screening_photo")

    elif scr == "S17":
        att = int(state.get("photo_attempts", 1))
        add("s17_title", text=t["s17_title"], bounds="[60,140][1020,240]")
        add("s17_attempt", text=f"Attempt {att} of 3", bounds="[60,260][1020,340]")
        add("s17_reason", text=t["s17_glare"], bounds="[60,380][1020,520]")
        if att < 3:
            add("s17_retake", text=t["s17_retake"], bounds="[80,1720][1000,1880]", clickable=True, action="goto:S16")
        add(
            "s17_fallback",
            text=t["s17_use_fallback"],
            bounds="[80,1920][1000,2080]",
            clickable=True,
            action="trigger_quality_fallback",
        )

    elif scr == "S18":
        add("s18_title", text=t["s18_title"], bounds="[60,140][1020,240]")
        add("s18_sub", text=t["s18_sub"], bounds="[60,280][1020,440]")
        add("s18_add", text=t["s18_add_forehead"], bounds="[80,1680][1000,1840]", clickable=True, action="goto:S20")
        add("s18_skip", text=t["s18_skip_forehead"], bounds="[80,1880][1000,2040]", clickable=True, action="goto:S20")

    elif scr == "S20":
        is_fb = bool(state.get("result_is_fallback"))
        reg_val = state.get("register_text", "")
        add("s20_header", text=t["s20_header"], bounds="[60,120][1020,200]")
        add(
            "s20_band",
            text=t["s20_band_blood"] if is_fb else t["s20_band_recheck"],
            bounds="[60,220][1020,360]",
        )
        add("s20_authority", text=t["s20_authority"], bounds="[60,400][1020,480]")
        add("s20_confirm", text=t["s20_confirm_blood"], bounds="[60,500][1020,580]")
        if is_fb:
            add("s20_fb_note", text=t["s20_fallback_note"], bounds="[60,600][1020,740]")
        add(
            "s20_reg_input",
            text=reg_val if reg_val else t["s20_reg_label"],
            desc=t["s20_reg_label"],
            bounds="[80,1400][1000,1560]",
            clickable=True,
            focused=True,
            action="focus_register",
        )
        if state.get("register_error"):
            add("s20_reg_err", text=t["s20_reg_error"], bounds="[80,1580][1000,1660]")
        add("s20_save", text=t["s20_save"], bounds="[80,1920][1000,2080]", clickable=True, action="save_screening")

    elif scr == "S23":
        add("s23_title", text=t["s23_title"], bounds="[60,100][1020,200]", scrollable=True)
        add("s23_lang", text=t["s23_lang"], bounds="[60,260][1020,400]", clickable=True, action="goto:S01")
        add(
            "s23_lock",
            text=t["s23_lock"],
            bounds="[60,440][1020,580]",
            clickable=True,
            checked=bool(state.get("screen_lock_enabled")),
            action="toggle_screen_lock",
        )
        add("s23_card", text=t["s23_card"], bounds="[60,620][1020,760]", clickable=True, action="goto:S06")
        if state.get("settings_scrolled"):
            add("s23_about", text=t["s23_about"], bounds="[60,1200][1020,1340]", clickable=True)
            add(
                "s23_research",
                text=t["s23_research"],
                bounds="[60,1400][1020,1560]",
                clickable=True,
                action="goto:R01",
            )

    elif scr == "R01":
        add("r01_title", text=t["r01_title"], bounds="[60,120][1020,220]")
        if not state.get("research_qr_scanned"):
            add("r01_scan", text=t["r01_scan_qr"], bounds="[120,800][960,980]", clickable=True, action="scan_research_qr")
        else:
            stage = state.get("pin_stage", "create")
            lbl = (
                t["r01_pin_label"]
                if state.get("research_pin_set")
                else (t["r01_pin_create"] if stage == "create" else t["r01_pin_confirm"])
            )
            masked = "*" * len(state.get("typed_buffer", ""))
            add("r01_pin_lbl", text=lbl, bounds="[60,340][1020,440]")
            add(
                "r01_pin_box",
                text=masked if masked else "______",
                desc=lbl,
                bounds="[120,480][960,620]",
                clickable=True,
                focused=True,
            )
            add("r01_unlock", text=t["r01_unlock_btn"], bounds="[80,1880][1000,2040]", clickable=True, action="submit_r01_pin")

    elif scr == "R02":
        add("r02_title", text=t["r02_title"], bounds="[60,100][860,200]")
        add(
            "r02_lock_btn",
            text="",
            desc=t["r02_lock_desc"],
            bounds="[880,80][1040,220]",
            clickable=True,
            action="lock_research_mode",
        )
        add("r02_new_session", text=t["r02_new_session"], bounds="[80,340][1000,520]", clickable=True)
        add("r02_pending_tsb", text=t["r02_pending_tsb"], bounds="[80,580][1000,760]", clickable=True)
        add(
            "r02_export",
            text=t["r02_export"],
            bounds="[80,820][1000,1000]",
            clickable=True,
            action="goto:R07",
        )

    elif scr == "R07":
        add("r07_title", text=t["r07_title"], bounds="[60,100][1020,200]", scrollable=True)
        add("r07_usb", text=t["r07_usb"], bounds="[80,300][1000,460]", clickable=True)
        add("r07_cloud_hdr", text=t["r07_cloud_title"], bounds="[60,560][1020,640]")
        if not state.get("study_enrolled"):
            add(
                "r07_join",
                text=t["r07_join_server"],
                bounds="[80,700][1000,860]",
                clickable=True,
                action="goto:R08",
            )
        else:
            add(
                "r07_upload",
                text=t["r07_upload_now"],
                bounds="[80,700][1000,860]",
                clickable=True,
                action="upload_study_data",
            )
            if state.get("study_uploaded"):
                add("r07_uploaded_ok", text=t["r07_uploaded_ok"], bounds="[60,920][1020,1020]")

    elif scr == "R08":
        add("r08_title", text=t["r08_title"], bounds="[60,100][1020,200]")
        if not state.get("r08_qr_scanned"):
            add("r08_scan", text=t["r08_scan_qr"], bounds="[120,400][960,580]", clickable=True, action="scan_r08_qr")
        else:
            code_val = state.get("enrolment_code", "")
            add("r08_code_lbl", text=t["r08_code_label"], bounds="[60,340][1020,420]")
            add(
                "r08_code_box",
                text=code_val if code_val else "XXXX-XXXX-XXXX",
                desc=t["r08_code_label"],
                bounds="[80,460][1000,600]",
                clickable=True,
                focused=True,
            )
            add("r08_submit", text=t["r08_submit"], bounds="[80,1880][1000,2040]", clickable=True, action="submit_r08_code")
            if state.get("study_enrolled"):
                add("r08_ok", text=t["r08_enrolled_ok"], bounds="[60,700][1020,800]")

    return nodes


def apply_node_action(state: dict[str, Any], action: str) -> None:
    if not action:
        return
    if action.startswith("goto:"):
        target = action.split(":", 1)[1]
        state["history"].append(state["screen"])
        state["screen"] = target
        return

    if action == "lang_bn":
        state["lang"] = "bn"
    elif action == "lang_en":
        state["lang"] = "en"
    elif action == "scan_card_qr":
        state["card_qr_scanned"] = True
    elif action == "take_qual_photo":
        state["qual_photos_taken"] = int(state.get("qual_photos_taken", 0)) + 1
        if state["qual_photos_taken"] >= 3:
            state["history"].append(state["screen"])
            state["screen"] = "S08"
    elif action == "finish_onboarding":
        state["onboarded"] = True
        state["history"].append(state["screen"])
        state["screen"] = "S09"
    elif action == "start_screening":
        state["history"].append(state["screen"])
        state["screen"] = "S10a"
    elif action == "select_age":
        state["age_selected"] = True
    elif action == "select_ga":
        state["ga_selected"] = True
    elif action == "capture_screening_photo":
        if state.get("bench_scenario") == "bad_05_glare":
            state["photo_attempts"] = int(state.get("photo_attempts", 0)) + 1
            state["history"].append(state["screen"])
            if state["photo_attempts"] >= 3:
                state["result_is_fallback"] = True
                state["screen"] = "S20"
            else:
                state["screen"] = "S17"
        else:
            state["history"].append(state["screen"])
            state["screen"] = "S18"
    elif action == "trigger_quality_fallback":
        state["result_is_fallback"] = True
        state["history"].append(state["screen"])
        state["screen"] = "S20"
    elif action == "save_screening":
        if not state.get("register_error"):
            state["saved_to_log"] = True
            state["history"].append(state["screen"])
            state["screen"] = "S09"
    elif action == "toggle_screen_lock":
        state["screen_lock_enabled"] = not bool(state.get("screen_lock_enabled"))
    elif action == "scan_research_qr":
        state["research_qr_scanned"] = True
    elif action == "submit_r01_pin":
        buf = state.get("typed_buffer", "")
        if len(buf) >= 6 and buf.isdigit():
            if state.get("research_pin_set"):
                state["typed_buffer"] = ""
                state["history"].append(state["screen"])
                state["screen"] = "R02"
            else:
                if state.get("pin_stage") == "create":
                    state["research_pin"] = buf[:6]
                    state["typed_buffer"] = ""
                    state["pin_stage"] = "confirm"
                else:
                    state["research_pin_set"] = True
                    state["typed_buffer"] = ""
                    state["history"].append(state["screen"])
                    state["screen"] = "R02"
    elif action == "lock_research_mode":
        state["research_locked"] = True
        state["history"].append(state["screen"])
        state["screen"] = "S09"
    elif action == "upload_study_data":
        state["study_uploaded"] = True
    elif action == "scan_r08_qr":
        state["r08_qr_scanned"] = True
    elif action == "submit_r08_code":
        code = state.get("enrolment_code", "").strip()
        if len(code) >= 8:
            state["study_enrolled"] = True


def write_xml_dump(state: dict[str, Any], out_path: Path) -> None:
    hierarchy = ET.Element("hierarchy", attrib={"rotation": "0"})
    nodes = build_screen_nodes(state)
    for idx, n in enumerate(nodes):
        ET.SubElement(
            hierarchy,
            "node",
            attrib={
                "index": str(idx),
                "text": n["text"],
                "resource-id": n["resource_id"],
                "class": n["class"],
                "package": n["package"],
                "content-desc": n["desc"],
                "checkable": "true" if n["checked"] else "false",
                "checked": "true" if n["checked"] else "false",
                "clickable": "true" if n["clickable"] else "false",
                "enabled": "true" if n["enabled"] else "false",
                "focusable": "true" if n["clickable"] else "false",
                "focused": "true" if n["focused"] else "false",
                "scrollable": "true" if n["scrollable"] else "false",
                "long-clickable": "false",
                "password": "false",
                "selected": "false",
                "bounds": n["bounds"],
            },
        )
    tree = ET.ElementTree(hierarchy)
    out_path.parent.mkdir(parents=True, exist_ok=True)
    tree.write(out_path, encoding="utf-8", xml_declaration=True)


def write_png_screenshot(state: dict[str, Any], out_path: Path) -> None:
    """Render a valid PNG image representing the current screen into `out_path`."""
    out_path.parent.mkdir(parents=True, exist_ok=True)
    nodes = build_screen_nodes(state)

    try:
        from PIL import Image, ImageDraw  # type: ignore

        img = Image.new("RGB", (540, 1140), color=(248, 250, 252))
        draw = ImageDraw.Draw(img)
        # Top status bar
        draw.rectangle([0, 0, 540, 36], fill=(11, 92, 104))
        for n in nodes:
            parsed = parse_bounds(n["bounds"])
            if not parsed:
                continue
            x1, y1, x2, y2 = [c // 2 for c in parsed]
            if n["clickable"]:
                fill = (11, 92, 104) if n["enabled"] else (180, 190, 200)
                txt_color = (255, 255, 255)
                draw.rectangle([x1, y1, x2, y2], fill=fill, outline=(8, 68, 78), width=2)
            else:
                txt_color = (20, 28, 36)
                draw.rectangle([x1, y1, x2, y2], outline=(210, 218, 226), width=1)
            label = n["text"] or n["desc"]
            if label:
                draw.text((x1 + 10, y1 + 12), label[:48], fill=txt_color)
        img.save(out_path, format="PNG")
        return
    except Exception:
        pass

    # Pure-Python deterministic PNG fallback (no external dependencies required)
    width, height = 270, 570
    bg = (248, 250, 252)
    primary = (11, 92, 104)
    rows: list[bytes] = []
    boxes = []
    for n in nodes:
        parsed = parse_bounds(n["bounds"])
        if parsed:
            boxes.append(([c // 4 for c in parsed], n["clickable"]))

    for y in range(height):
        row = bytearray([0])  # filter type 0
        for x in range(width):
            color = primary if y < 18 else bg
            for (bx1, by1, bx2, by2), is_click in boxes:
                if bx1 <= x <= bx2 and by1 <= y <= by2:
                    color = primary if is_click else (226, 232, 240)
                    break
            row.extend(color)
        rows.append(bytes(row))

    raw_data = b"".join(rows)

    def chunk(tag: bytes, data: bytes) -> bytes:
        return (
            struct.pack("!I", len(data))
            + tag
            + data
            + struct.pack("!I", zlib.crc32(tag + data) & 0xFFFFFFFF)
        )

    ihdr = struct.pack("!IIBBBBB", width, height, 8, 2, 0, 0, 0)
    png_bytes = (
        b"\x89PNG\r\n\x1a\n"
        + chunk(b"IHDR", ihdr)
        + chunk(b"IDAT", zlib.compress(raw_data, 6))
        + chunk(b"IEND", b"")
    )
    out_path.write_bytes(png_bytes)


def main() -> int:
    parser = argparse.ArgumentParser(description="Deterministic ADB simulator for NeoGold UX tests.")
    parser.add_argument("--run-dir", default=".", help="Run directory storing .sim_state.json")
    sub = parser.add_subparsers(dest="cmd", required=True)

    sub.add_parser("launch")
    p_dump = sub.add_parser("dump")
    p_dump.add_argument("xml_out")

    p_shot = sub.add_parser("shot")
    p_shot.add_argument("png_out")

    p_tap = sub.add_parser("tap")
    p_tap.add_argument("x", type=int)
    p_tap.add_argument("y", type=int)

    p_type = sub.add_parser("type")
    p_type.add_argument("text")

    p_swipe = sub.add_parser("swipe")
    p_swipe.add_argument("direction", choices=["up", "down", "left", "right"])

    sub.add_parser("back")
    sub.add_parser("home")

    args = parser.parse_args()
    run_dir = Path(args.run_dir).resolve()
    state = load_state(run_dir)

    if args.cmd == "launch":
        if not state.get("screen"):
            state["screen"] = "S09" if state.get("onboarded") else "S01"
        save_state(run_dir, state)
        return 0

    if args.cmd == "dump":
        write_xml_dump(state, Path(args.xml_out))
        return 0

    if args.cmd == "shot":
        write_png_screenshot(state, Path(args.png_out))
        return 0

    if args.cmd == "tap":
        nodes = build_screen_nodes(state)
        for n in nodes:
            if not n["clickable"] or not n["enabled"]:
                continue
            parsed = parse_bounds(n["bounds"])
            if not parsed:
                continue
            x1, y1, x2, y2 = parsed
            if x1 <= args.x <= x2 and y1 <= args.y <= y2:
                apply_node_action(state, n.get("action", ""))
                break
        save_state(run_dir, state)
        return 0

    if args.cmd == "type":
        scr = state.get("screen")
        text = args.text
        if scr == "S20":
            state["register_text"] = text
            # Register no. allows only [A-Za-z0-9/-] (no spaces!)
            state["register_error"] = any(ch == " " for ch in text)
        elif scr == "R01":
            state["typed_buffer"] = text
        elif scr == "R08":
            state["enrolment_code"] = text
        save_state(run_dir, state)
        return 0

    if args.cmd == "swipe":
        scr = state.get("screen")
        if args.direction == "up":
            if scr == "S23":
                state["settings_scrolled"] = True
            elif scr == "R07":
                state["r07_scrolled"] = True
        elif args.direction == "down":
            if scr == "S23":
                state["settings_scrolled"] = False
        save_state(run_dir, state)
        return 0

    if args.cmd == "back":
        hist = state.get("history") or []
        if hist:
            state["screen"] = hist.pop()
        save_state(run_dir, state)
        return 0

    if args.cmd == "home":
        state["screen"] = "S09" if state.get("onboarded") else "S01"
        save_state(run_dir, state)
        return 0

    return 0


if __name__ == "__main__":
    sys.exit(main())

