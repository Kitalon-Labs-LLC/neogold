package com.kitalonlabs.neogold.engine.colorscience

/**
 * CIEDE2000 color-difference formula implementation following:
 * Gaurav Sharma, Wencheng Wu, and Edul N. Dalal,
 * "The CIEDE2000 color-difference formula: Implementation notes, supplementary test data,
 * and mathematical observations," Color Research & Application, vol. 30, no. 1, pp. 21-30, 2005.
 *
 * Uses StrictMath for all transcendentals for bit-identical multi-ABI consistency.
 */
object Ciede2000 {

    private const val POW25_TO_7 = 6103515625.0 // 25.0^7

    /**
     * Computes the CIEDE2000 color difference between two CIELAB colors.
     *
     * @param lab1 Reference color
     * @param lab2 Sample color
     * @param kL Lightness weighting factor (default 1.0)
     * @param kC Chroma weighting factor (default 1.0)
     * @param kH Hue weighting factor (default 1.0)
     * @return Delta E 2000 color difference
     */
    fun calculate(
        lab1: Lab,
        lab2: Lab,
        kL: Double = 1.0,
        kC: Double = 1.0,
        kH: Double = 1.0
    ): Double {
        val l1 = lab1.l
        val a1 = lab1.a
        val b1 = lab1.b

        val l2 = lab2.l
        val a2 = lab2.a
        val b2 = lab2.b

        if (l1 == l2 && a1 == a2 && b1 == b2) {
            return 0.0
        }

        val c1 = StrictMath.hypot(a1, b1)
        val c2 = StrictMath.hypot(a2, b2)
        val cBar = (c1 + c2) / 2.0

        val cBar7 = StrictMath.pow(cBar, 7.0)
        val g = 0.5 * (1.0 - StrictMath.sqrt(cBar7 / (cBar7 + POW25_TO_7)))

        val a1Prime = (1.0 + g) * a1
        val a2Prime = (1.0 + g) * a2

        val c1Prime = StrictMath.hypot(a1Prime, b1)
        val c2Prime = StrictMath.hypot(a2Prime, b2)

        var h1Prime = StrictMath.toDegrees(StrictMath.atan2(b1, a1Prime))
        if (h1Prime < 0.0) {
            h1Prime += 360.0
        }

        var h2Prime = StrictMath.toDegrees(StrictMath.atan2(b2, a2Prime))
        if (h2Prime < 0.0) {
            h2Prime += 360.0
        }

        val deltaLPrime = l2 - l1
        val deltaCPrime = c2Prime - c1Prime

        val deltaHPrimeDeg: Double = if (c1Prime * c2Prime == 0.0) {
            0.0
        } else {
            val diff = h2Prime - h1Prime
            when {
                StrictMath.abs(diff) <= 180.0 -> diff
                diff > 180.0 -> diff - 360.0
                else -> diff + 360.0
            }
        }

        val deltaHPrime = 2.0 * StrictMath.sqrt(c1Prime * c2Prime) *
                StrictMath.sin(StrictMath.toRadians(deltaHPrimeDeg / 2.0))

        val lBarPrime = (l1 + l2) / 2.0
        val cBarPrime = (c1Prime + c2Prime) / 2.0

        val hBarPrime: Double = if (c1Prime * c2Prime == 0.0) {
            h1Prime + h2Prime
        } else {
            val diff = StrictMath.abs(h1Prime - h2Prime)
            when {
                diff <= 180.0 -> (h1Prime + h2Prime) / 2.0
                (h1Prime + h2Prime) < 360.0 -> (h1Prime + h2Prime + 360.0) / 2.0
                else -> (h1Prime + h2Prime - 360.0) / 2.0
            }
        }

        val t = 1.0 - 0.17 * StrictMath.cos(StrictMath.toRadians(hBarPrime - 30.0)) +
                0.24 * StrictMath.cos(StrictMath.toRadians(2.0 * hBarPrime)) +
                0.32 * StrictMath.cos(StrictMath.toRadians(3.0 * hBarPrime + 6.0)) -
                0.20 * StrictMath.cos(StrictMath.toRadians(4.0 * hBarPrime - 63.0))

        val deltaTheta = 30.0 * StrictMath.exp(
            -StrictMath.pow((hBarPrime - 275.0) / 25.0, 2.0)
        )

        val cBarPrime7 = StrictMath.pow(cBarPrime, 7.0)
        val rc = 2.0 * StrictMath.sqrt(cBarPrime7 / (cBarPrime7 + POW25_TO_7))

        val lBarMinus50Sq = StrictMath.pow(lBarPrime - 50.0, 2.0)
        val sl = 1.0 + (0.015 * lBarMinus50Sq) / StrictMath.sqrt(20.0 + lBarMinus50Sq)
        val sc = 1.0 + 0.045 * cBarPrime
        val sh = 1.0 + 0.015 * cBarPrime * t

        val rt = -StrictMath.sin(StrictMath.toRadians(2.0 * deltaTheta)) * rc

        val vl = deltaLPrime / (kL * sl)
        val vc = deltaCPrime / (kC * sc)
        val vh = deltaHPrime / (kH * sh)

        return StrictMath.sqrt(vl * vl + vc * vc + vh * vh + rt * vc * vh)
    }
}

/** Package-level convenience function for CIEDE2000 color difference. */
fun ciede2000(
    lab1: Lab,
    lab2: Lab,
    kL: Double = 1.0,
    kC: Double = 1.0,
    kH: Double = 1.0
): Double = Ciede2000.calculate(lab1, lab2, kL, kC, kH)
