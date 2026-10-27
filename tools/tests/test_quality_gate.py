"""
tools/tests/test_quality_gate.py

Proves tools/quality_gate.py correctly classifies file paths into
quality tiers (Tier 0 to Tier 3).
"""

from tools.quality_gate import match_path_to_tier

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
