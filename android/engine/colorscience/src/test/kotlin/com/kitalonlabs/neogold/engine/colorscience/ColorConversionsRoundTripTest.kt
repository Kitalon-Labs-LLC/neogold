package com.kitalonlabs.neogold.engine.colorscience

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test

/**
 * Validates exact round trips across sRGB, Linear RGB, CIE XYZ (D65), CIELAB, and CIELCh.
 * Required: Round trips sRGB to XYZ to Lab and back within 1e-9 on a grid.
 */
class ColorConversionsRoundTripTest {

    @Test
    fun testSrgbLinearRoundTrip() {
        val steps = 20
        for (i in 0..steps) {
            val v = i.toDouble() / steps.toDouble()
            val rgb = Rgb(v, v, v)
            val linear = sRgbToLinear(rgb)
            val recovered = linearToSRgb(linear)
            val diffR = StrictMath.abs(rgb.r - recovered.r)
            val diffG = StrictMath.abs(rgb.g - recovered.g)
            val diffB = StrictMath.abs(rgb.b - recovered.b)
            assertWithMessage("Linear round trip failed for v=%s", v).that(diffR).isAtMost(1e-9)
            assertWithMessage("Linear round trip failed for v=%s", v).that(diffG).isAtMost(1e-9)
            assertWithMessage("Linear round trip failed for v=%s", v).that(diffB).isAtMost(1e-9)
        }
    }

    @Test
    fun testXyzLabRoundTrip() {
        // Grid of Lab values spanning extreme and normal gamuts
        for (l in listOf(0.0, 0.5, 5.0, 18.0, 50.0, 75.0, 99.0, 100.0)) {
            for (a in listOf(-80.0, -30.0, -0.01, 0.0, 0.01, 30.0, 80.0)) {
                for (b in listOf(-80.0, -30.0, -0.01, 0.0, 0.01, 30.0, 80.0)) {
                    val lab = Lab(l, a, b)
                    val xyz = labToXyz(lab)
                    val recovered = xyzToLab(xyz)
                    val diffL = StrictMath.abs(lab.l - recovered.l)
                    val diffA = StrictMath.abs(lab.a - recovered.a)
                    val diffB = StrictMath.abs(lab.b - recovered.b)
                    assertWithMessage("XYZ-Lab round trip failed for Lab=%s", lab).that(diffL).isAtMost(1e-9)
                    assertWithMessage("XYZ-Lab round trip failed for Lab=%s", lab).that(diffA).isAtMost(1e-9)
                    assertWithMessage("XYZ-Lab round trip failed for Lab=%s", lab).that(diffB).isAtMost(1e-9)
                }
            }
        }
    }

    @Test
    fun testLabLchRoundTrip() {
        for (l in listOf(10.0, 50.0, 90.0)) {
            for (a in listOf(-50.0, -10.0, 0.0, 10.0, 50.0)) {
                for (b in listOf(-50.0, -10.0, 0.0, 10.0, 50.0)) {
                    val lab = Lab(l, a, b)
                    val lch = labToLch(lab)
                    val recovered = lchToLab(lch)
                    val diffL = StrictMath.abs(lab.l - recovered.l)
                    val diffA = StrictMath.abs(lab.a - recovered.a)
                    val diffB = StrictMath.abs(lab.b - recovered.b)
                    assertWithMessage("Lab-LCh round trip failed for Lab=%s", lab).that(diffL).isAtMost(1e-9)
                    assertWithMessage("Lab-LCh round trip failed for Lab=%s", lab).that(diffA).isAtMost(1e-9)
                    assertWithMessage("Lab-LCh round trip failed for Lab=%s", lab).that(diffB).isAtMost(1e-9)
                }
            }
        }
    }

    @Test
    fun testFullSrgbXyzLabRoundTripOnGrid() {
        // Grid covering [0, 1]^3 in steps of 0.1
        val values = listOf(0.0, 0.1, 0.25, 0.5, 0.75, 0.9, 1.0)
        for (r in values) {
            for (g in values) {
                for (b in values) {
                    val initialRgb = Rgb(r, g, b)
                    val xyz = sRgbToXyz(initialRgb)
                    val lab = xyzToLab(xyz)
                    val recoveredXyz = labToXyz(lab)
                    val recoveredRgb = xyzToSRgb(recoveredXyz)

                    val diffR = StrictMath.abs(initialRgb.r - recoveredRgb.r)
                    val diffG = StrictMath.abs(initialRgb.g - recoveredRgb.g)
                    val diffB = StrictMath.abs(initialRgb.b - recoveredRgb.b)

                    assertWithMessage(
                        "Full sRGB -> XYZ -> Lab -> XYZ -> sRGB round trip failed for %s. Recovered: %s",
                        initialRgb, recoveredRgb
                    ).that(diffR).isAtMost(1e-9)
                    assertWithMessage(
                        "Full sRGB -> XYZ -> Lab -> XYZ -> sRGB round trip failed for %s. Recovered: %s",
                        initialRgb, recoveredRgb
                    ).that(diffG).isAtMost(1e-9)
                    assertWithMessage(
                        "Full sRGB -> XYZ -> Lab -> XYZ -> sRGB round trip failed for %s. Recovered: %s",
                        initialRgb, recoveredRgb
                    ).that(diffB).isAtMost(1e-9)
                }
            }
        }
    }

    @Test
    fun testD65WhitePointValues() {
        // sRGB white (1, 1, 1) should map to D65 white point (Xn, 1.0, Zn) and Lab (100, 0, 0)
        val whiteRgb = Rgb(1.0, 1.0, 1.0)
        val whiteXyz = sRgbToXyz(whiteRgb)
        val whiteLab = xyzToLab(whiteXyz)

        assertThat(StrictMath.abs(whiteXyz.x - IlluminantD65.D65_NORMALIZED.x)).isAtMost(1e-5)
        assertThat(StrictMath.abs(whiteXyz.y - 1.0)).isAtMost(1e-5)
        assertThat(StrictMath.abs(whiteXyz.z - IlluminantD65.D65_NORMALIZED.z)).isAtMost(1e-5)

        assertThat(StrictMath.abs(whiteLab.l - 100.0)).isAtMost(1e-4)
        assertThat(StrictMath.abs(whiteLab.a - 0.0)).isAtMost(1e-4)
        assertThat(StrictMath.abs(whiteLab.b - 0.0)).isAtMost(1e-4)
    }

    @Test
    fun testBlackPointValues() {
        // sRGB black (0, 0, 0) should map to (0, 0, 0) in XYZ and Lab (0, 0, 0)
        val blackRgb = Rgb(0.0, 0.0, 0.0)
        val blackXyz = sRgbToXyz(blackRgb)
        val blackLab = xyzToLab(blackXyz)

        assertThat(blackXyz.x).isEqualTo(0.0)
        assertThat(blackXyz.y).isEqualTo(0.0)
        assertThat(blackXyz.z).isEqualTo(0.0)

        assertThat(blackLab.l).isEqualTo(0.0)
        assertThat(blackLab.a).isEqualTo(0.0)
        assertThat(blackLab.b).isEqualTo(0.0)
    }
}
