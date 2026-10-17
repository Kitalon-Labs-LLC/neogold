package com.kitalonlabs.neogold.engine.triage

import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Arithmetic for the triage engine.
 *
 * Rules from docs/4_System_Architecture.md Section 6.2:
 * - No floating-point comparisons anywhere in the decision path.
 * - Values are converted to integer tenths of mg/dL before comparison.
 * - Rounding always moves toward more care:
 *   - Threshold rounded down (floor).
 *   - Estimate rounded up (ceiling).
 *   - Upper bound rounded up (ceiling).
 *   - Lower bound rounded down (floor).
 * - Fixed zone widths (3.0, 15.0, 5.0, 6.0) converted to exact integer tenths (30, 150, 50, 60).
 */
object TriageArithmetic {

    /**
     * Converts a value to integer tenths of mg/dL rounding down (FLOOR, toward more care for thresholds and lower bounds).
     */
    fun toTenthsFloor(value: Double): Int {
        return BigDecimal(value.toString())
            .multiply(BigDecimal.TEN)
            .setScale(0, RoundingMode.FLOOR)
            .intValueExact()
    }

    /**
     * Converts a value to integer tenths of mg/dL rounding up (CEILING, toward more care for estimates and upper bounds).
     */
    fun toTenthsCeiling(value: Double): Int {
        return BigDecimal(value.toString())
            .multiply(BigDecimal.TEN)
            .setScale(0, RoundingMode.CEILING)
            .intValueExact()
    }

    /**
     * Converts a fixed configuration value to integer tenths of mg/dL using standard half-up rounding.
     */
    fun toTenthsHalfUp(value: Double): Int {
        return BigDecimal(value.toString())
            .multiply(BigDecimal.TEN)
            .setScale(0, RoundingMode.HALF_UP)
            .intValueExact()
    }

    /**
     * Converts tenths back to displayable Double (e.g. 171 -> 17.1).
     */
    fun tenthsToDouble(tenths: Int): Double {
        return BigDecimal(tenths)
            .divide(BigDecimal.TEN, 1, RoundingMode.UNNECESSARY)
            .toDouble()
    }

    /**
     * Linearly interpolates the phototherapy threshold for a gestational band table at the given age in hours.
     * The resulting threshold is rounded down to integer tenths (FLOOR: toward more care).
     *
     * @param table Ordered list of (ageHours, thresholdMgDl) coordinate pairs covering 0 to 336 hours.
     * @param ageHours The baby's postnatal age in hours.
     * @return The interpolated threshold in integer tenths of mg/dL.
     * @throws IllegalArgumentException if ageHours is outside the table coverage or if table is invalid.
     */
    fun interpolateThresholdTenths(
        table: List<Pair<Double, Double>>,
        ageHours: Double
    ): Int {
        require(table.size >= 2) { "Threshold table must contain at least 2 points" }
        val ageDec = BigDecimal(ageHours.toString())
        val firstAge = BigDecimal(table.first().first.toString())
        val lastAge = BigDecimal(table.last().first.toString())

        if (ageDec < firstAge || ageDec > lastAge) {
            throw IllegalArgumentException("Age $ageHours h outside threshold table range [$firstAge, $lastAge]")
        }

        for (i in 0 until table.size - 1) {
            val p0 = table[i]
            val p1 = table[i + 1]
            val a0Dec = BigDecimal(p0.first.toString())
            val a1Dec = BigDecimal(p1.first.toString())

            if (ageDec in a0Dec..a1Dec) {
                val t0Dec = BigDecimal(p0.second.toString())
                val t1Dec = BigDecimal(p1.second.toString())
                val span = a1Dec.subtract(a0Dec)

                val t = if (span.compareTo(BigDecimal.ZERO) == 0) {
                    t0Dec
                } else {
                    val deltaT = t1Dec.subtract(t0Dec)
                    val deltaAge = ageDec.subtract(a0Dec)
                    // High precision division (20 scale) before floor rounding
                    val interpolatedDelta = deltaT.multiply(deltaAge).divide(span, 20, RoundingMode.HALF_UP)
                    t0Dec.add(interpolatedDelta)
                }

                // Threshold rounded DOWN to integer tenths: toward more care
                return t.multiply(BigDecimal.TEN).setScale(0, RoundingMode.FLOOR).intValueExact()
            }
        }

        throw IllegalArgumentException("Age $ageHours h could not be matched in threshold table")
    }
}
