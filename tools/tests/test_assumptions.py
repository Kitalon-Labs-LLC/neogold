"""Tooling tests for Assumptions Scanner and Spike Record Infrastructure."""

from pathlib import Path
import pytest

from tools.assumptions import (
    scan_docs,
    generate_report,
    SECTION_4_CANDIDATES,
    IMPACT_WEIGHTS,
)


@pytest.fixture
def repo_root():
    return Path(__file__).resolve().parent.parent.parent


def test_scan_docs_extracts_items(repo_root):
    items = scan_docs(repo_root)
    assert len(items) > 20, "Should discover tagged assumptions across docs 2 to 16"
    
    tags = {item.tag for item in items}
    assert "Likely" in tags or "Guessing" in tags
    
    # Ensure unproven items exist
    unproven = [i for i in items if not i.proven]
    assert len(unproven) >= 10, "Should have at least 10 unproven items"


def test_top_10_claims_have_valid_spike_commands(repo_root):
    items = scan_docs(repo_root)
    unproven = [i for i in items if not i.proven]
    top_10 = unproven[:10]
    
    assert len(top_10) == 10
    for idx, item in enumerate(top_10, start=1):
        assert item.spike_command.startswith('/ng-spike "'), f"Item {idx} command must start with /ng-spike \""
        assert item.spike_command.endswith('"'), f"Item {idx} command must close with quotes"
        assert item.file.startswith("docs/"), f"Item {idx} file must be in docs/"
        assert item.impact in IMPACT_WEIGHTS, f"Item {idx} impact must be mapped in IMPACT_WEIGHTS"


def test_section_4_candidates_structure():
    assert len(SECTION_4_CANDIDATES) == 5
    for cand in SECTION_4_CANDIDATES:
        assert "id" in cand
        assert "claim" in cand
        assert "source" in cand
        assert "impact" in cand
        assert cand["spike_cmd"].startswith('/ng-spike "')


def test_report_generation(repo_root, tmp_path):
    items = scan_docs(repo_root)
    test_date = "2026-10-26"
    report_file = generate_report(repo_root, items, test_date)
    
    assert report_file.exists()
    content = report_file.read_text(encoding="utf-8")
    
    # Check headers and mandatory sections
    assert "# NeoGold Unverified Assumptions Audit (2026-10-26)" in content
    assert "## 1. Top 10 Priority Unproven Claims" in content
    assert "## 2. Docs 16 Section 4 Candidate Spikes" in content
    assert "## 3. Comprehensive Inventory of Tagged Assumptions" in content
    
    for cand in SECTION_4_CANDIDATES:
        assert cand["id"] in content
        assert cand["claim"] in content


def test_spike_template_structure(repo_root):
    template_path = repo_root / "docs" / "spikes" / "TEMPLATE.md"
    assert template_path.exists(), "docs/spikes/TEMPLATE.md must exist"
    
    content = template_path.read_text(encoding="utf-8")
    assert "Claim and Source" in content
    assert "Question" in content
    assert "Method and Exact Commands" in content
    assert "Raw Results" in content
    assert "Verdict" in content
    assert "Confirmed" in content and "Refuted" in content and "Inconclusive" in content
    assert "What the Verdict Changes" in content
    assert "Affected Doc Lines" in content


def test_spike_readme_states_no_merge_rule(repo_root):
    readme_path = repo_root / "docs" / "spikes" / "README.md"
    assert readme_path.exists(), "docs/spikes/README.md must exist"
    
    content = readme_path.read_text(encoding="utf-8")
    assert "Spike code NEVER merges into production branches" in content
    assert "android/" in content
    assert "backend/" in content
    assert "training/" in content
    assert "config/" in content
