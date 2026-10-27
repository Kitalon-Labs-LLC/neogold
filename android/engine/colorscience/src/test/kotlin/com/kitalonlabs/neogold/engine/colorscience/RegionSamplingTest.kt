package com.kitalonlabs.neogold.engine.colorscience

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Tests for trimmed mean statistics, inner region sampling (60% patch, 70% window),
 * and TC-EST-03 (colored ring at window edge does not change skin value).
 */
class RegionSamplingTest {

    @Test
    fun testTrimmedMean1DDiscardsTopAndBottom10Percent() {
        // Array of 100 values from 1.0 to 100.0
        val values = DoubleArray(100) { (it + 1).toDouble() }
        // Adding extreme outliers to top 5 and bottom 5
        values[0] = -1000.0
        values[1] = -500.0
        values[98] = 5000.0
        values[99] = 10000.0

        // Discard 10% bottom (indices 0..9) and 10% top (indices 90..99)
        // Leaving indices 10..89: values 11 to 90 -> mean is (11 + 90) / 2 = 50.5
        val trimmed = RegionSampling.trimmedMean(values, trimFraction = 0.10)
        assertThat(trimmed).isEqualTo(50.5)
    }

    @Test
    fun testTrimmedMeanRgbDiscardsDarkestAndBrightestPixels() {
        val pixels = mutableListOf<Rgb>()
        // 80 normal skin-like pixels (0.6, 0.4, 0.3)
        repeat(80) {
            pixels.add(Rgb(0.60, 0.40, 0.30))
        }
        // 10 bright specular highlight pixels (1.0, 1.0, 1.0)
        repeat(10) {
            pixels.add(Rgb(1.0, 1.0, 1.0))
        }
        // 10 dark shadow/bleed pixels (0.05, 0.05, 0.05)
        repeat(10) {
            pixels.add(Rgb(0.05, 0.05, 0.05))
        }

        val sampled = RegionSampling.trimmedMeanRgb(pixels, trimFraction = 0.10)
        // Should exactly equal the central 80 skin pixels
        assertThat(StrictMath.abs(sampled.r - 0.60)).isAtMost(1e-10)
        assertThat(StrictMath.abs(sampled.g - 0.40)).isAtMost(1e-10)
        assertThat(StrictMath.abs(sampled.b - 0.30)).isAtMost(1e-10)
    }

    @Test
    fun testInnerRegionComputation() {
        // Window: 240x240 px, 70% inner region -> 168x168 px, margin 36 px
        val windowRect = RectPx(360, 260, 240, 240)
        val innerWindow = RegionSampling.computeInnerRegion(windowRect, 0.70)
        assertThat(innerWindow.x).isEqualTo(396)
        assertThat(innerWindow.y).isEqualTo(296)
        assertThat(innerWindow.w).isEqualTo(168)
        assertThat(innerWindow.h).isEqualTo(168)

        // Patch: 90x90 px, 60% inner region -> 54x54 px, margin 18 px
        val patchRect = RectPx(450, 30, 90, 90)
        val innerPatch = RegionSampling.computeInnerRegion(patchRect, 0.60)
        assertThat(innerPatch.x).isEqualTo(468)
        assertThat(innerPatch.y).isEqualTo(48)
        assertThat(innerPatch.w).isEqualTo(54)
        assertThat(innerPatch.h).isEqualTo(54)
    }

    @Test
    fun testTcEst03ColoredRingAtWindowEdgeDoesNotChangeSkinValue() {
        // Synthetic image canvas 1200 x 760
        // Window rect at canonical coordinates: (360, 260, 240, 240)
        // Inner 70% rect: (396, 296, 168, 168)
        // Uniform true skin value
        val trueSkinRgb = Rgb(0.72, 0.50, 0.38)
        val windowRect = RectPx(360, 260, 240, 240)

        // Clean image: entire window is true skin
        val cleanWindowRgb = RegionSampling.sampleWindowRgb(
            imageWidth = 1200,
            imageHeight = 760,
            windowRect = windowRect,
            getPixel = { _, _ -> trueSkinRgb }
        )

        // Contaminated image: a brightly colored ring (e.g. bright green card border or adhesive bleed)
        // occupying the outer 25 px of the window (which is within the 36 px outer margin)
        val ringColor = Rgb(0.0, 1.0, 0.0) // Bright green
        val contaminatedWindowRgb = RegionSampling.sampleWindowRgb(
            imageWidth = 1200,
            imageHeight = 760,
            windowRect = windowRect,
            getPixel = { x, y ->
                val distFromLeft = x - windowRect.x
                val distFromRight = (windowRect.x + windowRect.w - 1) - x
                val distFromTop = y - windowRect.y
                val distFromBottom = (windowRect.y + windowRect.h - 1) - y
                val minDist = minOf(distFromLeft, distFromRight, distFromTop, distFromBottom)
                if (minDist < 25) {
                    ringColor // Colored ring at edge
                } else {
                    trueSkinRgb
                }
            }
        )

        // Verifies TC-EST-03: ring does not change the skin value
        assertThat(contaminatedWindowRgb.r).isEqualTo(cleanWindowRgb.r)
        assertThat(contaminatedWindowRgb.g).isEqualTo(cleanWindowRgb.g)
        assertThat(contaminatedWindowRgb.b).isEqualTo(cleanWindowRgb.b)
    }
}
