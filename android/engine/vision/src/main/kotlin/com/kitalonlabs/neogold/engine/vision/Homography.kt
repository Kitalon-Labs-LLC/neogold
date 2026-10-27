package com.kitalonlabs.neogold.engine.vision

/**
 * 3x3 Projective transformation matrix (Homography) in row-major order:
 * [ h00, h01, h02 ]
 * [ h10, h11, h12 ]
 * [ h20, h21, h22 ]
 */
class Homography(val m: DoubleArray) {
    init {
        require(m.size == 9) { "Homography must have 9 elements" }
    }

    /**
     * Transform point (x, y) through homography:
     * x' = (h00*x + h01*y + h02) / (h20*x + h21*y + h22)
     * y' = (h10*x + h11*y + h12) / (h20*x + h21*y + h22)
     */
    fun transform(pt: Point2D): Point2D {
        val w = m[6] * pt.x + m[7] * pt.y + m[8]
        val invW = if (StrictMath.abs(w) > 1e-12) 1.0 / w else 1.0
        val xPrime = (m[0] * pt.x + m[1] * pt.y + m[2]) * invW
        val yPrime = (m[3] * pt.x + m[4] * pt.y + m[5]) * invW
        return Point2D(xPrime, yPrime)
    }

    /**
     * Compute analytical 3x3 matrix inverse.
     */
    fun inverse(): Homography {
        val a00 = m[0]
        val a01 = m[1]
        val a02 = m[2]
        val a10 = m[3]
        val a11 = m[4]
        val a12 = m[5]
        val a20 = m[6]
        val a21 = m[7]
        val a22 = m[8]

        val det = a00 * (a11 * a22 - a12 * a21) -
            a01 * (a10 * a22 - a12 * a20) +
            a02 * (a10 * a21 - a11 * a20)

        require(StrictMath.abs(det) > 1e-12) { "Homography is singular and non-invertible (det=$det)" }
        val invDet = 1.0 / det

        val inv = DoubleArray(9)
        inv[0] = (a11 * a22 - a12 * a21) * invDet
        inv[1] = (a02 * a21 - a01 * a22) * invDet
        inv[2] = (a01 * a12 - a02 * a11) * invDet

        inv[3] = (a12 * a20 - a10 * a22) * invDet
        inv[4] = (a00 * a22 - a02 * a20) * invDet
        inv[5] = (a02 * a10 - a00 * a12) * invDet

        inv[6] = (a10 * a21 - a11 * a20) * invDet
        inv[7] = (a01 * a20 - a00 * a21) * invDet
        inv[8] = (a00 * a11 - a01 * a10) * invDet

        return Homography(inv)
    }

    companion object {
        /**
         * Solves the 8-equation linear system for 4 point correspondences:
         * srcPoints[i] -> dstPoints[i] for i = 0..3.
         * Uses Gaussian elimination with partial pivoting.
         */
        fun findHomography(src: List<Point2D>, dst: List<Point2D>): Homography {
            require(src.size == 4 && dst.size == 4) { "Exactly 4 point correspondences required" }

            // Matrix A of size 8x8, vector B of size 8
            // [ xi,  yi,  1,   0,   0,   0,  -ui*xi,  -ui*yi ] [h00..h21] = ui
            // [  0,   0,  0,  xi,  yi,   1,  -vi*xi,  -vi*yi ]             = vi
            val a = Array(8) { DoubleArray(8) }
            val b = DoubleArray(8)

            for (i in 0 until 4) {
                val x = src[i].x
                val y = src[i].y
                val u = dst[i].x
                val v = dst[i].y

                // Equation 2*i: u coordinate
                a[2 * i][0] = x
                a[2 * i][1] = y
                a[2 * i][2] = 1.0
                a[2 * i][3] = 0.0
                a[2 * i][4] = 0.0
                a[2 * i][5] = 0.0
                a[2 * i][6] = -u * x
                a[2 * i][7] = -u * y
                b[2 * i] = u

                // Equation 2*i + 1: v coordinate
                a[2 * i + 1][0] = 0.0
                a[2 * i + 1][1] = 0.0
                a[2 * i + 1][2] = 0.0
                a[2 * i + 1][3] = x
                a[2 * i + 1][4] = y
                a[2 * i + 1][5] = 1.0
                a[2 * i + 1][6] = -v * x
                a[2 * i + 1][7] = -v * y
                b[2 * i + 1] = v
            }

            // Gaussian elimination with partial pivoting
            for (p in 0 until 8) {
                var maxRow = p
                var maxVal = StrictMath.abs(a[p][p])
                for (r in p + 1 until 8) {
                    val v = StrictMath.abs(a[r][p])
                    if (v > maxVal) {
                        maxVal = v
                        maxRow = r
                    }
                }
                require(maxVal > 1e-12) { "Matrix singular in homography solver at pivot $p" }

                // Swap rows
                if (maxRow != p) {
                    val tempA = a[p]
                    a[p] = a[maxRow]
                    a[maxRow] = tempA

                    val tempB = b[p]
                    b[p] = b[maxRow]
                    b[maxRow] = tempB
                }

                // Eliminate below
                val pivot = a[p][p]
                for (r in p + 1 until 8) {
                    val factor = a[r][p] / pivot
                    a[r][p] = 0.0
                    for (c in p + 1 until 8) {
                        a[r][c] -= factor * a[p][c]
                    }
                    b[r] -= factor * b[p]
                }
            }

            // Back substitution
            val h = DoubleArray(9)
            h[8] = 1.0 // Normalization h22 = 1.0
            for (r in 7 downTo 0) {
                var sum = b[r]
                for (c in r + 1 until 8) {
                    sum -= a[r][c] * h[c]
                }
                h[r] = sum / a[r][r]
            }

            return Homography(h)
        }

        /**
         * Estimates the card tilt angle (degrees) relative to face-on viewing.
         * Evaluates trapezoidal convergence and aspect ratio foreshortening.
         */
        fun estimateTiltAngleDegrees(srcCorners: List<Point2D>): Double {
            require(srcCorners.size == 4) { "4 corners required" }
            val p0 = srcCorners[0] // TL
            val p1 = srcCorners[1] // TR
            val p2 = srcCorners[2] // BR
            val p3 = srcCorners[3] // BL

            val topLen = p0.distanceTo(p1)
            val botLen = p3.distanceTo(p2)
            val leftLen = p0.distanceTo(p3)
            val rightLen = p1.distanceTo(p2)

            // Ratio of opposite edges (< 1.0 under perspective tilt)
            val ratioHorizontal = if (StrictMath.max(topLen, botLen) > 0.0) {
                StrictMath.min(topLen, botLen) / StrictMath.max(topLen, botLen)
            } else {
                1.0
            }

            val ratioVertical = if (StrictMath.max(leftLen, rightLen) > 0.0) {
                StrictMath.min(leftLen, rightLen) / StrictMath.max(leftLen, rightLen)
            } else {
                1.0
            }

            // Foreshortening aspect ratio check against nominal marker centers aspect ratio 980 / 540
            val nominalAspect = (CardGeometry.MARKER_CENTERS.getValue(1).x - CardGeometry.MARKER_CENTERS.getValue(0).x) /
                (CardGeometry.MARKER_CENTERS.getValue(3).y - CardGeometry.MARKER_CENTERS.getValue(0).y)
            val avgWidth = (topLen + botLen) * 0.5
            val avgHeight = (leftLen + rightLen) * 0.5
            val observedAspect = if (avgHeight > 0.0) avgWidth / avgHeight else nominalAspect
            val aspectCompression = if (observedAspect < nominalAspect) {
                observedAspect / nominalAspect
            } else {
                nominalAspect / observedAspect
            }

            val clampedRatio = StrictMath.max(0.0, StrictMath.min(1.0, aspectCompression))
            val angleRad = StrictMath.acos(clampedRatio)
            return angleRad * 180.0 / StrictMath.PI
        }
    }
}
