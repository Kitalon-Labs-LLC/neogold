"""
tools/tests/test_check_strictmath.py

Proves tools/check_strictmath.py correctly detects forbidden Math imports
and method calls, and passes on clean engine files using StrictMath.
"""

from pathlib import Path
from tools.check_strictmath import check_engine_file


def test_clean_engine_file_passes(tmp_path: Path):
    clean_kt = tmp_path / "CleanEngine.kt"
    clean_kt.write_text(
        """package com.kitalonlabs.neogold.engine
import java.lang.StrictMath
class CleanEngine {
    fun calc(x: Double): Double = StrictMath.pow(x, 2.0) + StrictMath.sqrt(x)
}
""",
        encoding="utf-8",
    )
    violations = check_engine_file(clean_kt)
    assert violations == []


def test_forbidden_math_import_fails(tmp_path: Path):
    bad_kt = tmp_path / "BadImport.kt"
    bad_kt.write_text(
        """package com.kitalonlabs.neogold.engine
import java.lang.Math
class BadEngine {
    fun calc(x: Double): Double = Math.pow(x, 2.0)
}
""",
        encoding="utf-8",
    )
    violations = check_engine_file(bad_kt)
    assert len(violations) >= 1
    assert any("import java.lang.Math is forbidden" in v for v in violations)


def test_forbidden_kotlin_math_pow_fails(tmp_path: Path):
    bad_kt = tmp_path / "BadKotlinMath.kt"
    bad_kt.write_text(
        """package com.kitalonlabs.neogold.engine
import kotlin.math.pow
class BadEngine {
    fun calc(x: Double): Double = x.pow(2.0)
}
""",
        encoding="utf-8",
    )
    violations = check_engine_file(bad_kt)
    assert len(violations) >= 1
    assert any("import kotlin.math for powers, roots, or exponentials is forbidden" in v for v in violations)


def test_forbidden_math_call_without_import_fails(tmp_path: Path):
    bad_kt = tmp_path / "BadCall.kt"
    bad_kt.write_text(
        """package com.kitalonlabs.neogold.engine
class BadEngine {
    fun calc(x: Double): Double = Math.sqrt(x)
}
""",
        encoding="utf-8",
    )
    violations = check_engine_file(bad_kt)
    assert len(violations) >= 1
    assert any("Math.<func> is forbidden" in v for v in violations)
