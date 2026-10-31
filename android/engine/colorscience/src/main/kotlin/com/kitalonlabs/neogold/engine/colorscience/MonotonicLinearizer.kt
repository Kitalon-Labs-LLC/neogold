package com.kitalonlabs.neogold.engine.colorscience

/**
 * Monotone piecewise-linear interpolator with linear clamping at endpoints.
 */
class MonotonePiecewiseLinear(
    val knots: List<Pair<Double, Double>>
) {
    init {
        require(knots.size >= 2) { "MonotonePiecewiseLinear requires at least 2 knots (got ${knots.size})" }
        for (i in 0 until knots.size - 1) {
            require(knots[i].first < knots[i + 1].first) {
                "Knots x coordinates must be strictly increasing: knots[$i].x=${knots[i].first} >= knots[${i + 1}].x=${knots[i + 1].first}"
            }
            require(knots[i].second <= knots[i + 1].second) {
                "Knots y coordinates must be monotonically non-decreasing: " +
                    "knots[$i].y=${knots[i].second} > knots[${i + 1}].y=${knots[i + 1].second}"
            }
        }
    }

    /**
     * Interpolates the value y for a given x.
     * Values below the first knot are clamped to knots.first().y (or linearly extended if x >= 0).
     * Values above the last knot are clamped to knots.last().y.
     */
    fun interpolate(x: Double): Double {
        if (x <= knots.first().first) {
            return knots.first().second
        }
        if (x >= knots.last().first) {
            return knots.last().second
        }

        // Binary search for interval
        var low = 0
        var high = knots.size - 1
        while (low <= high) {
            val mid = (low + high) ushr 1
            if (knots[mid].first <= x) {
                if (mid == knots.size - 1 || knots[mid + 1].first > x) {
                    val x0 = knots[mid].first
                    val y0 = knots[mid].second
                    val x1 = knots[mid + 1].first
                    val y1 = knots[mid + 1].second
                    val t = (x - x0) / (x1 - x0)
                    return y0 + t * (y1 - y0)
                }
                low = mid + 1
            } else {
                high = mid - 1
            }
        }
        return knots.last().second
    }
}

/**
 * Fits monotonic tone curves using Pool Adjacent Violators Algorithm (PAVA) and piecewise linear interpolation.
 */
object MonotonicLinearizer {

    /**
     * Pool Adjacent Violators Algorithm (PAVA) for isotonic regression.
     * Takes a list of (x, y) pairs sorted by x, and returns (x, y') where y' is monotonically non-decreasing.
     */
    fun pava(points: List<Pair<Double, Double>>): List<Pair<Double, Double>> {
        if (points.isEmpty()) return emptyList()
        val sorted = points.sortedBy { it.first }

        // Block structure for PAVA
        data class Block(
            var weight: Double,
            var sum: Double,
            val indices: MutableList<Int>
        ) {
            val value: Double get() = sum / weight
        }

        val blocks = mutableListOf<Block>()
        for ((idx, pt) in sorted.withIndex()) {
            val block = Block(weight = 1.0, sum = pt.second, indices = mutableListOf(idx))
            while (blocks.isNotEmpty() && blocks.last().value > block.value) {
                val prev = blocks.removeAt(blocks.size - 1)
                block.weight += prev.weight
                block.sum += prev.sum
                block.indices.addAll(0, prev.indices)
            }
            blocks.add(block)
        }

        val outY = DoubleArray(sorted.size)
        for (b in blocks) {
            val v = b.value
            for (i in b.indices) {
                outY[i] = v
            }
        }

        return sorted.indices.map { sorted[it].first to outY[it] }
    }

    /**
     * Fits a single channel monotonic curve from observed values to reference reflectances.
     */
    fun fitSingleChannel(
        observedValues: List<Double>,
        referenceReflectances: List<Double>
    ): MonotonePiecewiseLinear {
        require(observedValues.size == referenceReflectances.size) {
            "Observed values (${observedValues.size}) and reference reflectances (${referenceReflectances.size}) must match"
        }
        require(observedValues.size >= 2) { "Need at least 2 points to fit a tone curve" }

        val paired = observedValues.indices.map { observedValues[it] to referenceReflectances[it] }
        val smoothed = pava(paired)

        // Ensure endpoints (0, 0) and (1, 1) are present for robust coverage
        val knots = mutableListOf<Pair<Double, Double>>()
        if (smoothed.first().first > 0.0) {
            knots.add(0.0 to 0.0)
        }
        for (pt in smoothed) {
            if (knots.isNotEmpty() && pt.first == knots.last().first) {
                // If duplicate x, keep higher y to maintain monotonicity
                knots[knots.size - 1] = pt.first to StrictMath.max(knots.last().second, pt.second)
            } else {
                knots.add(pt)
            }
        }
        if (knots.last().first < 1.0) {
            knots.add(1.0 to 1.0)
        }

        // Secondary pass to ensure strict x increasing and non-decreasing y
        val finalKnots = mutableListOf<Pair<Double, Double>>()
        for (k in knots) {
            if (finalKnots.isEmpty()) {
                finalKnots.add(k)
            } else {
                val prev = finalKnots.last()
                val x = if (k.first <= prev.first) prev.first + KNOT_X_NUDGE else k.first
                val y = if (k.second < prev.second) prev.second else k.second
                finalKnots.add(x to y)
            }
        }

        return MonotonePiecewiseLinear(finalKnots)
    }
}

/**
 * Multi-channel linearizer applying monotonic curves per R, G, B channel.
 */
class PerChannelLinearizer(
    val redCurve: MonotonePiecewiseLinear,
    val greenCurve: MonotonePiecewiseLinear,
    val blueCurve: MonotonePiecewiseLinear
) {
    fun linearize(rgb: Rgb): Rgb = Rgb(
        r = redCurve.interpolate(rgb.r),
        g = greenCurve.interpolate(rgb.g),
        b = blueCurve.interpolate(rgb.b)
    )

    companion object {
        fun fit(
            observedGrayPatches: List<Rgb>,
            referenceReflectances: List<Double>
        ): PerChannelLinearizer {
            val rVals = observedGrayPatches.map { it.r }
            val gVals = observedGrayPatches.map { it.g }
            val bVals = observedGrayPatches.map { it.b }

            return PerChannelLinearizer(
                redCurve = MonotonicLinearizer.fitSingleChannel(rVals, referenceReflectances),
                greenCurve = MonotonicLinearizer.fitSingleChannel(gVals, referenceReflectances),
                blueCurve = MonotonicLinearizer.fitSingleChannel(bVals, referenceReflectances)
            )
        }
    }
}

/** Smallest x step used to keep knot x coordinates strictly increasing. */
private const val KNOT_X_NUDGE = 1e-6
