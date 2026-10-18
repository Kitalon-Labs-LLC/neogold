package com.kitalonlabs.neogold.engine.colorscience

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Tests for monotonic linearization engine using monotone piecewise-linear interpolation
 * and Pool Adjacent Violators Algorithm (PAVA) for isotonic regression.
 */
class MonotonicLinearizerTest {

    @Test
    fun testPavaOnAlreadyMonotonicData() {
        val input = listOf(
            0.1 to 0.05,
            0.2 to 0.12,
            0.3 to 0.25,
            0.4 to 0.40,
            0.5 to 0.65
        )
        val result = MonotonicLinearizer.pava(input)
        assertThat(result).hasSize(input.size)
        for (i in input.indices) {
            assertThat(result[i].first).isEqualTo(input[i].first)
            assertThat(result[i].second).isEqualTo(input[i].second)
        }
    }

    @Test
    fun testPavaResolvesInversions() {
        // Inverted pair in the middle (0.3 -> 0.35, 0.4 -> 0.30)
        val noisy = listOf(
            0.1 to 0.10,
            0.2 to 0.20,
            0.3 to 0.35,
            0.4 to 0.30,
            0.5 to 0.50
        )
        val result = MonotonicLinearizer.pava(noisy)
        // Verify monotonicity is strictly preserved: y[i] <= y[i+1]
        for (i in 0 until result.size - 1) {
            assertThat(result[i].second).isAtMost(result[i + 1].second)
        }
        // Mean of inverted block: (0.35 + 0.30) / 2 = 0.325
        assertThat(StrictMath.abs(result[2].second - 0.325)).isAtMost(1e-12)
        assertThat(StrictMath.abs(result[3].second - 0.325)).isAtMost(1e-12)
    }

    @Test
    fun testMonotonePiecewiseLinearInterpolation() {
        val knots = listOf(
            0.0 to 0.0,
            0.2 to 0.1,
            0.5 to 0.4,
            0.8 to 0.7,
            1.0 to 1.0
        )
        val interp = MonotonePiecewiseLinear(knots)

        // Exact at knots
        assertThat(interp.interpolate(0.0)).isEqualTo(0.0)
        assertThat(interp.interpolate(0.2)).isEqualTo(0.1)
        assertThat(interp.interpolate(0.5)).isEqualTo(0.4)
        assertThat(interp.interpolate(1.0)).isEqualTo(1.0)

        // Midpoints
        // x = 0.1 -> midway 0.0 to 0.1 = 0.05
        assertThat(interp.interpolate(0.1)).isEqualTo(0.05)
        // x = 0.35 -> midway 0.2 to 0.5 = 0.1 + (0.4 - 0.1)*(0.15/0.30) = 0.25
        assertThat(interp.interpolate(0.35)).isEqualTo(0.25)

        // Below 0.0 and above 1.0
        assertThat(interp.interpolate(-0.1)).isEqualTo(0.0)
        assertThat(interp.interpolate(1.2)).isEqualTo(1.0)
    }

    @Test
    fun testStabilityWith6GrayPatches() {
        // Simulating 6 gray ramp steps from card
        val observedR = listOf(0.15, 0.25, 0.38, 0.52, 0.70, 0.88)
        val measuredReflectances = listOf(0.02, 0.06, 0.14, 0.28, 0.50, 0.80)

        val linearizer = MonotonicLinearizer.fitSingleChannel(observedR, measuredReflectances)
        // Check monotonicity at 100 points
        var prev = -1.0
        for (i in 0..100) {
            val x = i / 100.0
            val y = linearizer.interpolate(x)
            assertThat(y).isAtLeast(prev)
            prev = y
        }
    }

    @Test
    fun testStabilityWith7GrayPatchesCardV1() {
        // Card v1 G7 down to G1 nominal L* values:
        // G7: L*=18 -> Y ~ 0.025
        // G6: L*=28 -> Y ~ 0.055
        // G5: L*=39 -> Y ~ 0.106
        // G4: L*=51 -> Y ~ 0.192
        // G3: L*=64 -> Y ~ 0.328
        // G2: L*=77 -> Y ~ 0.515
        // G1: L*=90 -> Y ~ 0.763
        val measuredReflectances = listOf(0.025, 0.055, 0.106, 0.192, 0.328, 0.515, 0.763)
        // Simulated observed camera values with mild sRGB-like tone curve
        val observed = listOf(0.18, 0.27, 0.37, 0.49, 0.62, 0.77, 0.91)

        val linearizer = MonotonicLinearizer.fitSingleChannel(observed, measuredReflectances)
        var prev = 0.0
        for (i in 0..100) {
            val x = i / 100.0
            val y = linearizer.interpolate(x)
            assertThat(y).isAtLeast(prev)
            prev = y
        }
    }

    @Test
    fun testPerChannelLinearizer() {
        val observedPatches = listOf(
            Rgb(0.18, 0.19, 0.17),
            Rgb(0.28, 0.27, 0.28),
            Rgb(0.38, 0.39, 0.37),
            Rgb(0.50, 0.51, 0.49),
            Rgb(0.63, 0.62, 0.64),
            Rgb(0.77, 0.78, 0.76),
            Rgb(0.92, 0.91, 0.92)
        )
        val measuredReflectances = listOf(0.025, 0.055, 0.106, 0.192, 0.328, 0.515, 0.763)

        val channelLinearizer = PerChannelLinearizer.fit(observedPatches, measuredReflectances)
        val linearized = channelLinearizer.linearize(Rgb(0.50, 0.51, 0.49))

        assertThat(StrictMath.abs(linearized.r - 0.192)).isAtMost(0.01)
        assertThat(StrictMath.abs(linearized.g - 0.192)).isAtMost(0.01)
        assertThat(StrictMath.abs(linearized.b - 0.192)).isAtMost(0.01)
    }
}
