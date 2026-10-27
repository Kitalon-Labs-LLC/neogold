package com.kitalonlabs.neogold.engine.colorscience

/**
 * Common interface for color correction models mapping linearized RGB to CIE XYZ (D65).
 */
interface ColorCorrectionModel {
    fun correct(linearRgb: Rgb): Xyz
}

/**
 * Standard 3x3 linear color correction matrix (CCM) fitted via least-squares.
 * Maps [R, G, B] -> [X, Y, Z].
 */
class Linear3x3CorrectionModel(
    val matrix: Matrix
) : ColorCorrectionModel {
    init {
        require(matrix.rows == 3 && matrix.cols == 3) {
            "3x3 correction model requires a 3x3 matrix (got ${matrix.rows} x ${matrix.cols})"
        }
    }

    override fun correct(linearRgb: Rgb): Xyz {
        val r = linearRgb.r
        val g = linearRgb.g
        val b = linearRgb.b

        val x = r * matrix[0, 0] + g * matrix[1, 0] + b * matrix[2, 0]
        val y = r * matrix[0, 1] + g * matrix[1, 1] + b * matrix[2, 1]
        val z = r * matrix[0, 2] + g * matrix[1, 2] + b * matrix[2, 2]

        return Xyz(
            x = StrictMath.max(0.0, x),
            y = StrictMath.max(0.0, y),
            z = StrictMath.max(0.0, z)
        )
    }
}

/**
 * Second-order root-polynomial color correction model (Finlayson et al. 2015).
 * Expands [R, G, B] to 6 exposure-invariant terms: [R, G, B, sqrt(RG), sqrt(GB), sqrt(BR)].
 * Matrix dimensions are 6 x 3.
 */
class RootPolynomialCorrectionModel(
    val matrix: Matrix
) : ColorCorrectionModel {
    init {
        require(matrix.rows == 6 && matrix.cols == 3) {
            "Root-polynomial correction model requires a 6x3 matrix (got ${matrix.rows} x ${matrix.cols})"
        }
    }

    override fun correct(linearRgb: Rgb): Xyz {
        val r = StrictMath.max(0.0, linearRgb.r)
        val g = StrictMath.max(0.0, linearRgb.g)
        val b = StrictMath.max(0.0, linearRgb.b)

        val rg = StrictMath.sqrt(r * g)
        val gb = StrictMath.sqrt(g * b)
        val br = StrictMath.sqrt(b * r)

        val f0 = r;
        val f1 = g;
        val f2 = b
        val f3 = rg;
        val f4 = gb;
        val f5 = br

        val x = f0 * matrix[0, 0] + f1 * matrix[1, 0] + f2 * matrix[2, 0] +
            f3 * matrix[3, 0] + f4 * matrix[4, 0] + f5 * matrix[5, 0]

        val y = f0 * matrix[0, 1] + f1 * matrix[1, 1] + f2 * matrix[2, 1] +
            f3 * matrix[3, 1] + f4 * matrix[4, 1] + f5 * matrix[5, 1]

        val z = f0 * matrix[0, 2] + f1 * matrix[1, 2] + f2 * matrix[2, 2] +
            f3 * matrix[3, 2] + f4 * matrix[4, 2] + f5 * matrix[5, 2]

        return Xyz(
            x = StrictMath.max(0.0, x),
            y = StrictMath.max(0.0, y),
            z = StrictMath.max(0.0, z)
        )
    }
}

/**
 * Fits 3x3 linear or 2nd-order root-polynomial color correction models using least squares.
 */
object ColorCorrectionFitter {

    fun fit(
        linearRgbPatches: List<Rgb>,
        referenceXyzPatches: List<Xyz>,
        useRootPolynomial: Boolean = false
    ): ColorCorrectionModel {
        val n = linearRgbPatches.size
        require(n == referenceXyzPatches.size) {
            "Input patches ($n) and reference patches (${referenceXyzPatches.size}) must match in count"
        }

        val targetB = Matrix(
            rows = n,
            cols = 3,
            data = DoubleArray(n * 3).also { arr ->
                for (i in 0 until n) {
                    val xyz = referenceXyzPatches[i]
                    arr[i * 3 + 0] = xyz.x
                    arr[i * 3 + 1] = xyz.y
                    arr[i * 3 + 2] = xyz.z
                }
            }
        )

        return if (!useRootPolynomial) {
            require(n >= 3) { "Need at least 3 patches for 3x3 color correction (got $n)" }
            val inputA = Matrix(
                rows = n,
                cols = 3,
                data = DoubleArray(n * 3).also { arr ->
                    for (i in 0 until n) {
                        val rgb = linearRgbPatches[i]
                        arr[i * 3 + 0] = rgb.r
                        arr[i * 3 + 1] = rgb.g
                        arr[i * 3 + 2] = rgb.b
                    }
                }
            )
            val solvedMatrix = inputA.solveLeastSquares(targetB)
            Linear3x3CorrectionModel(solvedMatrix)
        } else {
            require(n >= 6) { "Need at least 6 patches for root-polynomial correction (got $n)" }
            val inputA = Matrix(
                rows = n,
                cols = 6,
                data = DoubleArray(n * 6).also { arr ->
                    for (i in 0 until n) {
                        val rgb = linearRgbPatches[i]
                        val r = StrictMath.max(0.0, rgb.r)
                        val g = StrictMath.max(0.0, rgb.g)
                        val b = StrictMath.max(0.0, rgb.b)
                        arr[i * 6 + 0] = r
                        arr[i * 6 + 1] = g
                        arr[i * 6 + 2] = b
                        arr[i * 6 + 3] = StrictMath.sqrt(r * g)
                        arr[i * 6 + 4] = StrictMath.sqrt(g * b)
                        arr[i * 6 + 5] = StrictMath.sqrt(b * r)
                    }
                }
            )
            val solvedMatrix = inputA.solveLeastSquares(targetB)
            RootPolynomialCorrectionModel(solvedMatrix)
        }
    }
}

/**
 * Quality gate residual metrics for patch color difference evaluation.
 */
data class CorrectionResidual(
    val meanDeltaE: Double,
    val maxDeltaE: Double,
    val patchDeltaEs: List<Double>
)

object QualityResidualCalculator {

    fun computeResidual(
        model: ColorCorrectionModel,
        linearRgbPatches: List<Rgb>,
        referenceLabs: List<Lab>
    ): CorrectionResidual {
        require(linearRgbPatches.size == referenceLabs.size) {
            "Linear patches count (${linearRgbPatches.size}) must match reference Labs count (${referenceLabs.size})"
        }
        val deltaEs = mutableListOf<Double>()
        var sum = 0.0
        var maxVal = 0.0

        for (i in linearRgbPatches.indices) {
            val predXyz = model.correct(linearRgbPatches[i])
            val predLab = xyzToLab(predXyz)
            val dE = ciede2000(predLab, referenceLabs[i])
            deltaEs.add(dE)
            sum += dE
            if (dE > maxVal) {
                maxVal = dE
            }
        }

        val mean = if (deltaEs.isNotEmpty()) sum / deltaEs.size else 0.0
        return CorrectionResidual(
            meanDeltaE = mean,
            maxDeltaE = maxVal,
            patchDeltaEs = deltaEs
        )
    }
}
