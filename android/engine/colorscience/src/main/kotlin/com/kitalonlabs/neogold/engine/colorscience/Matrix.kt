package com.kitalonlabs.neogold.engine.colorscience

/**
 * Exception thrown when a matrix inversion or least-squares fit encounters
 * a singular, rank-deficient, or ill-conditioned matrix.
 */
class IllConditionedMatrixException(message: String) : RuntimeException(message)

/**
 * Self-contained 2D double-precision Matrix for linear algebra and least-squares regression.
 * Pure Kotlin with zero external library dependencies.
 * All math operations use StrictMath.
 */
class Matrix(
    val rows: Int,
    val cols: Int,
    val data: DoubleArray
) {
    init {
        require(rows > 0 && cols > 0) { "Matrix dimensions must be positive (got $rows x $cols)" }
        require(data.size == rows * cols) {
            "Data array size (${data.size}) does not match dimensions ($rows x $cols = ${rows * cols})"
        }
    }

    operator fun get(r: Int, c: Int): Double {
        checkBounds(r, c)
        return data[r * cols + c]
    }

    operator fun set(r: Int, c: Int, value: Double) {
        checkBounds(r, c)
        data[r * cols + c] = value
    }

    private fun checkBounds(r: Int, c: Int) {
        require(r in 0 until rows && c in 0 until cols) {
            "Index ($r, $c) out of bounds for matrix of size $rows x $cols"
        }
    }

    operator fun plus(other: Matrix): Matrix {
        require(rows == other.rows && cols == other.cols) {
            "Cannot add matrices of different dimensions ($rows x $cols vs ${other.rows} x ${other.cols})"
        }
        val out = DoubleArray(data.size)
        for (i in data.indices) {
            out[i] = data[i] + other.data[i]
        }
        return Matrix(rows, cols, out)
    }

    operator fun minus(other: Matrix): Matrix {
        require(rows == other.rows && cols == other.cols) {
            "Cannot subtract matrices of different dimensions ($rows x $cols vs ${other.rows} x ${other.cols})"
        }
        val out = DoubleArray(data.size)
        for (i in data.indices) {
            out[i] = data[i] - other.data[i]
        }
        return Matrix(rows, cols, out)
    }

    operator fun times(scalar: Double): Matrix {
        val out = DoubleArray(data.size)
        for (i in data.indices) {
            out[i] = data[i] * scalar
        }
        return Matrix(rows, cols, out)
    }

    operator fun times(other: Matrix): Matrix {
        require(cols == other.rows) {
            "Cannot multiply matrices: inner dimensions mismatch ($rows x $cols) * (${other.rows} x ${other.cols})"
        }
        val out = DoubleArray(rows * other.cols)
        for (i in 0 until rows) {
            val iOffset = i * cols
            val outOffset = i * other.cols
            for (k in 0 until cols) {
                val aVal = data[iOffset + k]
                if (aVal != 0.0) {
                    val otherOffset = k * other.cols
                    for (j in 0 until other.cols) {
                        out[outOffset + j] += aVal * other.data[otherOffset + j]
                    }
                }
            }
        }
        return Matrix(rows, other.cols, out)
    }

    fun transpose(): Matrix {
        val out = DoubleArray(rows * cols)
        for (r in 0 until rows) {
            for (c in 0 until cols) {
                out[c * rows + r] = data[r * cols + c]
            }
        }
        return Matrix(cols, rows, out)
    }

    fun frobeniusNorm(): Double {
        var sumSq = 0.0
        for (v in data) {
            sumSq += v * v
        }
        return StrictMath.sqrt(sumSq)
    }

    /**
     * Solves the linear system A * X = B in the least-squares sense using Householder QR decomposition.
     * Throws [IllConditionedMatrixException] if A is rank-deficient, collinear, or ill-conditioned.
     */
    fun solveLeastSquares(b: Matrix): Matrix {
        require(rows >= cols) { "Least squares requires rows >= cols (got $rows x $cols)" }
        require(rows == b.rows) { "Dimension mismatch: A rows ($rows) must match B rows (${b.rows})" }

        val m = rows
        val n = cols
        val p = b.cols

        // Working copies
        val aWork = data.clone()
        val bWork = b.data.clone()

        // Householder QR decomposition
        for (k in 0 until n) {
            // Compute norm of column k below diagonal
            var normSq = 0.0
            for (i in k until m) {
                val v = aWork[i * n + k]
                normSq += v * v
            }
            val norm = StrictMath.sqrt(normSq)
            if (norm < SINGULAR_TOLERANCE) {
                throw IllConditionedMatrixException(
                    "Column $k has near-zero norm ($norm < 1e-12); matrix is singular or rank-deficient"
                )
            }

            val ak = aWork[k * n + k]
            val alpha = if (ak >= 0.0) -norm else norm

            // Householder vector v stored temporarily
            // v[k] = ak - alpha
            val vk = ak - alpha
            val vNormSq = normSq - ak * ak + vk * vk
            val beta = 2.0 / vNormSq

            aWork[k * n + k] = alpha

            // Apply Householder reflection to remaining columns of A: A[k..m, j] -= beta * (v^T A) * v
            for (j in (k + 1) until n) {
                var dot = vk * aWork[k * n + j]
                for (i in (k + 1) until m) {
                    dot += aWork[i * n + j] * aWork[i * n + k]
                }
                val tau = beta * dot
                aWork[k * n + j] -= tau * vk
                for (i in (k + 1) until m) {
                    aWork[i * n + j] -= tau * aWork[i * n + k]
                }
            }

            // Apply Householder reflection to B: B[k..m, j] -= beta * (v^T B) * v
            for (j in 0 until p) {
                var dot = vk * bWork[k * p + j]
                for (i in (k + 1) until m) {
                    dot += bWork[i * p + j] * aWork[i * n + k]
                }
                val tau = beta * dot
                bWork[k * p + j] -= tau * vk
                for (i in (k + 1) until m) {
                    bWork[i * p + j] -= tau * aWork[i * n + k]
                }
            }

            // Store reflector components for lower elements
            for (i in (k + 1) until m) {
                aWork[i * n + k] = aWork[i * n + k] // already stored
            }
        }

        // Check condition of R
        var maxDiag = 0.0
        var minDiag = Double.MAX_VALUE
        for (i in 0 until n) {
            val diag = StrictMath.abs(aWork[i * n + i])
            if (diag < SINGULAR_TOLERANCE) {
                throw IllConditionedMatrixException(
                    "Diagonal element R[$i, $i] is near-zero ($diag < 1e-12); matrix is rank-deficient"
                )
            }
            if (diag > maxDiag) maxDiag = diag
            if (diag < minDiag) minDiag = diag
        }

        val conditionRatio = maxDiag / minDiag
        if (conditionRatio > MAX_CONDITION_RATIO) {
            throw IllConditionedMatrixException(
                "Matrix is ill-conditioned: condition ratio max|Rii| / min|Rii| = $conditionRatio > 1e12"
            )
        }

        // Back-substitution to solve R * X = (Q^T * B)[0..n-1]
        val xData = DoubleArray(n * p)
        for (i in (n - 1) downTo 0) {
            val rDiagonal = aWork[i * n + i]
            for (j in 0 until p) {
                var sum = bWork[i * p + j]
                for (k in (i + 1) until n) {
                    sum -= aWork[i * n + k] * xData[k * p + j]
                }
                xData[i * p + j] = sum / rDiagonal
            }
        }

        return Matrix(n, p, xData)
    }

    companion object {
        fun zeros(rows: Int, cols: Int): Matrix = Matrix(rows, cols, DoubleArray(rows * cols))

        fun identity(n: Int): Matrix {
            val mat = zeros(n, n)
            for (i in 0 until n) {
                mat[i, i] = 1.0
            }
            return mat
        }
    }
}

/** A column or diagonal element below this is treated as zero (rank deficiency). */
private const val SINGULAR_TOLERANCE = 1e-12

/** Largest accepted ratio between the biggest and smallest diagonal element of R. */
private const val MAX_CONDITION_RATIO = 1e12
