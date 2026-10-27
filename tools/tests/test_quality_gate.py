"""
tools/tests/test_quality_gate.py

Proves tools/quality_gate.py correctly classifies file paths into
quality tiers (Tier 0 to Tier 3).
"""

import subprocess
from pathlib import Path

from tools.quality_gate import load_tier_definitions, match_path_to_tier

REPO_ROOT = Path(__file__).resolve().parents[2]

MOCK_TIERS = {
    0: [
        "android/engine/triage/**",
        "android/engine/estimation/**",
        "config/**",
    ],
    1: [
        "android/core/security/**",
        "backend/app/auth.py",
    ],
    2: [
        "android/feature/**",
        "backend/**",
    ],
    3: [
        "tools/**",
        "docs/**",
    ],
}


def test_tier_0_clinical_matching():
    assert match_path_to_tier("android/engine/triage/src/main/Triage.kt", MOCK_TIERS) == 0
    assert match_path_to_tier("config/triage-config.json", MOCK_TIERS) == 0


def test_tier_1_security_matching():
    assert match_path_to_tier("android/core/security/KeyStore.kt", MOCK_TIERS) == 1
    assert match_path_to_tier("backend/app/auth.py", MOCK_TIERS) == 1


def test_tier_2_app_matching():
    assert match_path_to_tier("android/feature/settings/SettingsScreen.kt", MOCK_TIERS) == 2
    assert match_path_to_tier("backend/app/main.py", MOCK_TIERS) == 2


def test_tier_3_tooling_fallback():
    assert match_path_to_tier("tools/quality_gate.py", MOCK_TIERS) == 3
    assert match_path_to_tier("docs/14_Build_Plan.md", MOCK_TIERS) == 3
    assert match_path_to_tier("random/unknown/path.txt", MOCK_TIERS) == 3


def _tracked_files() -> list[str]:
    out = subprocess.run(["git", "ls-files"], cwd=REPO_ROOT, capture_output=True, text=True, check=True).stdout
    return out.splitlines()


def test_every_real_tier_0_pattern_matches_a_tracked_file():
    # BUG-2026-046: "QualityGate*.kt" matched no file, because the class is VisionQualityGate.kt, so the
    # vision quality gate sat in Tier 2. A pattern that matches nothing protects nothing.
    tiers = load_tier_definitions(REPO_ROOT / "quality" / "tiers.yaml")
    files = _tracked_files()
    dead = [p for p in tiers[0] if not any(match_path_to_tier(f, {0: [p]}) == 0 for f in files)]
    assert dead == []


def test_clinical_files_the_safety_review_found_outside_tier_0_are_in_it():
    tiers = load_tier_definitions(REPO_ROOT / "quality" / "tiers.yaml")
    vision = "android/engine/vision/src/main/kotlin/com/kitalonlabs/neogold/engine/vision/"
    pipeline = "android/feature/screening/src/main/kotlin/com/kitalonlabs/neogold/feature/screening/domain/"
    for path in (
        vision + "VisionQualityGate.kt",
        vision + "VisionModels.kt",
        vision + "CardSampler.kt",
        pipeline + "ScreeningPipelineUseCase.kt",
        "android/app/src/main/assets/model.json",
        "android/app/src/main/assets/triage-config.json",
        "android/app/src/main/assets/trust-list.json",
        "android/app/src/main/assets/bundle-manifest.json",
        "android/config/detekt/detekt.yml",
        "android/config/detekt/detekt-engine.yml",
    ):
        assert match_path_to_tier(path, tiers) == 0, path
