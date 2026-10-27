"""
tools/tests/test_check_no_skipped_tests.py

Proves tools/check_no_skipped_tests.py detects all skip annotations in test code
and passes on clean test code without skip markers.
"""

from tools.check_no_skipped_tests import find_skips_in_content


def test_clean_test_content_passes():
    content = """
    class TriageTest {
        @Test
        fun testTriageCalculation() {
            assertEquals(1, 1)
        }
    }
    """
    skips = find_skips_in_content(content)
    assert skips == []


def test_ignore_annotation_fails():
    content = """
    @Ignore
    @Test
    fun testBrokenCase() {}
    """
    skips = find_skips_in_content(content)
    assert len(skips) == 1
    assert skips[0][1] == "@Ignore"


def test_disabled_annotation_fails():
    content = """
    @Disabled("temporarily turned off")
    @Test
    fun testDisabledCase() {}
    """
    skips = find_skips_in_content(content)
    assert len(skips) == 1
    assert skips[0][1] == "@Disabled"


def test_pytest_mark_skip_fails():
    content = """
    @pytest.mark.skip(reason="not ready")
    def test_feature():
        pass
    """
    skips = find_skips_in_content(content)
    assert len(skips) == 1
    assert skips[0][1] == "pytest.mark.skip"


def test_pytest_skip_call_fails():
    content = """
    def test_feature():
        pytest.skip("skipping")
    """
    skips = find_skips_in_content(content)
    assert len(skips) == 1
    assert skips[0][1] == "pytest.skip("


def test_assumetrue_false_fails():
    content = """
    @Test
    fun testSomething() {
        assumeTrue(false)
    }
    """
    skips = find_skips_in_content(content)
    assert len(skips) == 1
    assert skips[0][1] == "assumeTrue(false)"


def test_comments_containing_skip_words_pass():
    content = """
    // Do not use @Ignore here
    # We should not pytest.mark.skip anything
    /* @Disabled is forbidden */
    """
    skips = find_skips_in_content(content)
    assert skips == []
