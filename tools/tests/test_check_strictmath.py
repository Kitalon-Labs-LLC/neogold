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
    assert any("import kotlin.math for powers, roots, exponentials, logarithms, or trigonometry" in v for v in violations)


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


def test_cube_root_and_trigonometric_calls_fail(tmp_path: Path):
    # BUG-2026-047: the engines call StrictMath.cbrt, sin, cos and atan2, so the platform versions
    # must be rejected too.
    for call in ("Math.cbrt(x)", "Math.sin(x)", "Math.cos(x)", "Math.atan2(x, 1.0)", "java.lang.Math.tan(x)"):
        bad_kt = tmp_path / "BadTrig.kt"
        bad_kt.write_text(
            f"""package com.kitalonlabs.neogold.engine
class BadEngine {{
    fun calc(x: Double): Double = {call}
}}
""",
            encoding="utf-8",
        )
        violations = check_engine_file(bad_kt)
        assert any("Math.<func> is forbidden" in v for v in violations), call


def test_kotlin_math_trigonometry_and_cube_root_imports_fail(tmp_path: Path):
    for name in ("sin", "cos", "atan2", "cbrt", "expm1", "ln1p"):
        bad_kt = tmp_path / "BadImport.kt"
        bad_kt.write_text(
            f"""package com.kitalonlabs.neogold.engine
import kotlin.math.{name}
""",
            encoding="utf-8",
        )
        violations = check_engine_file(bad_kt)
        assert any("import kotlin.math for powers" in v for v in violations), name


def test_kotlin_math_wildcard_import_fails(tmp_path: Path):
    bad_kt = tmp_path / "BadWildcard.kt"
    bad_kt.write_text(
        """package com.kitalonlabs.neogold.engine
import kotlin.math.*
class BadEngine {
    fun calc(x: Double): Double = sqrt(x)
}
""",
        encoding="utf-8",
    )
    violations = check_engine_file(bad_kt)
    assert any("import kotlin.math.* is forbidden" in v for v in violations)


def test_strictmath_cube_root_and_trigonometry_pass(tmp_path: Path):
    clean_kt = tmp_path / "CleanTrig.kt"
    clean_kt.write_text(
        """package com.kitalonlabs.neogold.engine
import kotlin.math.abs
class CleanEngine {
    fun calc(x: Double): Double =
        StrictMath.cbrt(x) + StrictMath.sin(x) + StrictMath.cos(x) + StrictMath.atan2(x, 1.0) + abs(x)
}
""",
        encoding="utf-8",
    )
    assert check_engine_file(clean_kt) == []
