package com.kitalonlabs.neogold.engine.colorscience

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Tests for pure Kotlin linear algebra Matrix and least-squares solver.
 * Verifies arithmetic, Householder QR least squares, and ill-conditioned input detection.
 */
class MatrixTest {

    @Test
    fun testMatrixMultiplication() {
        // [1 2] * [5 6] = [1*5+2*7  1*6+2*8] = [19 22]
        // [3 4]   [7 8]   [3*5+4*7  3*6+4*8]   [43 50]
        val a = Matrix(2, 2, doubleArrayOf(1.0, 2.0, 3.0, 4.0))
        val b = Matrix(2, 2, doubleArrayOf(5.0, 6.0, 7.0, 8.0))
        val c = a * b
        assertThat(c.rows).isEqualTo(2)
        assertThat(c.cols).isEqualTo(2)
        assertThat(c[0, 0]).isEqualTo(19.0)
        assertThat(c[0, 1]).isEqualTo(22.0)
        assertThat(c[1, 0]).isEqualTo(43.0)
        assertThat(c[1, 1]).isEqualTo(50.0)
    }

    @Test
    fun testMatrixTranspose() {
        val a = Matrix(2, 3, doubleArrayOf(1.0, 2.0, 3.0, 4.0, 5.0, 6.0))
        val at = a.transpose()
        assertThat(at.rows).isEqualTo(3)
        assertThat(at.cols).isEqualTo(2)
        assertThat(at[0, 0]).isEqualTo(1.0)
        assertThat(at[1, 0]).isEqualTo(2.0)
        assertThat(at[2, 0]).isEqualTo(3.0)
        assertThat(at[0, 1]).isEqualTo(4.0)
        assertThat(at[1, 1]).isEqualTo(5.0)
        assertThat(at[2, 1]).isEqualTo(6.0)
    }

    @Test
    fun testLeastSquaresExactSquareSystem() {
        // Solve A X = B where A is 3x3 invertible
        // 2x + y - z = 8
        // -3x - y + 2z = -11
        // -2x + y + 2z = -3
        // Solution: x = 2, y = 3, z = -1
        val a = Matrix(
            3,
            3,
            doubleArrayOf(
                2.0, 1.0, -1.0,
                -3.0, -1.0, 2.0,
                -2.0, 1.0, 2.0
            )
        )
        val b = Matrix(3, 1, doubleArrayOf(8.0, -11.0, -3.0))
        val x = a.solveLeastSquares(b)
        assertThat(x.rows).isEqualTo(3)
        assertThat(x.cols).isEqualTo(1)
        assertThat(StrictMath.abs(x[0, 0] - 2.0)).isAtMost(1e-10)
        assertThat(StrictMath.abs(x[1, 0] - 3.0)).isAtMost(1e-10)
        assertThat(StrictMath.abs(x[2, 0] - (-1.0))).isAtMost(1e-10)
    }

    @Test
    fun testLeastSquaresOverdeterminedSystem() {
        // Line fitting y = m*x + c
        // Points: (1, 2), (2, 3.9), (3, 6.1), (4, 8.0) -> slope ~2.0, intercept ~0.0
        val a = Matrix(
            4,
            2,
            doubleArrayOf(
                1.0,
                1.0,
                2.0,
                1.0,
                3.0,
                1.0,
                4.0,
                1.0
            )
        )
        val b = Matrix(4, 1, doubleArrayOf(2.0, 3.9, 6.1, 8.0))
        val x = a.solveLeastSquares(b)
        // Analytical:
        // x_mean = 2.5, y_mean = 5.0
        // sum((x-xm)(y-ym)) = (-1.5)*(-3.0) + (-0.5)*(-1.1) + (0.5)*(1.1) + (1.5)*(3.0) = 4.5 + 0.55 + 0.55 + 4.5 = 10.1
        // sum((x-xm)^2) = 2.25 + 0.25 + 0.25 + 2.25 = 5.0
        // m = 10.1 / 5.0 = 2.02
        // c = 5.0 - 2.02 * 2.5 = -0.05
        assertThat(StrictMath.abs(x[0, 0] - 2.02)).isAtMost(1e-10)
        assertThat(StrictMath.abs(x[1, 0] - (-0.05))).isAtMost(1e-10)
    }

    @Test
    fun testIllConditionedZeroMatrixThrows() {
        val a = Matrix(4, 3, DoubleArray(12)) // All zeros
        val b = Matrix(4, 1, doubleArrayOf(1.0, 2.0, 3.0, 4.0))
        assertThrows(IllConditionedMatrixException::class.java) {
            a.solveLeastSquares(b)
        }
    }

    @Test
    fun testIllConditionedCollinearColumnsThrows() {
        // Column 2 is exactly 2 * Column 1
        val a = Matrix(
            4,
            3,
            doubleArrayOf(
                1.0, 2.0, 5.0,
                2.0, 4.0, 3.0,
                3.0, 6.0, 2.0,
                4.0, 8.0, 7.0
            )
        )
        val b = Matrix(4, 1, doubleArrayOf(1.0, 1.0, 1.0, 1.0))
        assertThrows(IllConditionedMatrixException::class.java) {
            a.solveLeastSquares(b)
        }
    }

    @Test
    fun testIllConditionedNearSingularInputThrows() {
        // Collinear with 1e-15 perturbation
        val a = Matrix(
            3,
            3,
            doubleArrayOf(
                1.0, 2.0, 3.0,
                2.0, 4.0, 6.0,
                3.0, 6.0, 9.000000000000001
            )
        )
        val b = Matrix(3, 1, doubleArrayOf(1.0, 2.0, 3.0))
        assertThrows(IllConditionedMatrixException::class.java) {
            a.solveLeastSquares(b)
        }
    }

    @Test
    fun testMultipleRightHandSidesLeastSquares() {
        // A (5 x 3), B (5 x 3) - fitting CCM mapping
        val a = Matrix(
            5,
            3,
            doubleArrayOf(
                0.1, 0.2, 0.3,
                0.4, 0.5, 0.6,
                0.7, 0.2, 0.1,
                0.2, 0.8, 0.4,
                0.9, 0.9, 0.9
            )
        )
        // Known ground truth M (3 x 3)
        val m = Matrix(
            3,
            3,
            doubleArrayOf(
                1.2, 0.1, 0.0,
                0.0, 1.1, 0.2,
                0.1, 0.0, 0.9
            )
        )
        val b = a * m
        val recoveredM = a.solveLeastSquares(b)
        assertThat(recoveredM.rows).isEqualTo(3)
        assertThat(recoveredM.cols).isEqualTo(3)
        for (r in 0 until 3) {
            for (c in 0 until 3) {
                assertThat(StrictMath.abs(recoveredM[r, c] - m[r, c])).isAtMost(1e-10)
            }
        }
    }
}
