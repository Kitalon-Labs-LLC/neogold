package com.kitalonlabs.neogold.engine.colorscience

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.util.Random

/**
 * Determinism test: verifies identical bit-level results across repeated runs
 * using StrictMath (prepares TC-EST-07 for multi-ABI consistency).
 */
class DeterminismTest {

    @Test
    fun testFullPipelineDeterminismAcross100Runs() {
        val iterations = 100
        var baselineBitsRgb = 0L
        var baselineBitsXyz = 0L
        var baselineBitsLab = 0L
        var baselineBitsLch = 0L
        var baselineBitsDeltaE = 0L

        for (run in 0 until iterations) {
            val rng = Random(42L) // Fixed seed
            val observedGrays = (1..7).map {
                val v = 0.1 * it + 0.01 * rng.nextDouble()
                Rgb(v, v, v)
            }
            val refReflectances = listOf(0.025, 0.055, 0.106, 0.192, 0.328, 0.515, 0.763)
            val linearizer = PerChannelLinearizer.fit(observedGrays, refReflectances)

            val rawPatch = Rgb(0.55, 0.45, 0.35)
            val linearPatch = linearizer.linearize(rawPatch)

            val trainLinear = (1..15).map {
                Rgb(0.1 * (it % 10), 0.05 * (it % 15), 0.08 * (it % 12))
            }
            val trainXyz = trainLinear.map { linearRgbToXyz(it) }

            val model = ColorCorrectionFitter.fit(trainLinear, trainXyz, useRootPolynomial = false)
            val correctedXyz = model.correct(linearPatch)
            val lab = xyzToLab(correctedXyz)
            val lch = labToLch(lab)
            val deltaE = ciede2000(lab, Lab(50.0, 10.0, 15.0))

            val bitsRgb = linearPatch.r.toRawBits() xor linearPatch.g.toRawBits() xor linearPatch.b.toRawBits()
            val bitsXyz = correctedXyz.x.toRawBits() xor correctedXyz.y.toRawBits() xor correctedXyz.z.toRawBits()
            val bitsLab = lab.l.toRawBits() xor lab.a.toRawBits() xor lab.b.toRawBits()
            val bitsLch = lch.l.toRawBits() xor lch.c.toRawBits() xor lch.hDeg.toRawBits()
            val bitsDeltaE = deltaE.toRawBits()

            if (run == 0) {
                baselineBitsRgb = bitsRgb
                baselineBitsXyz = bitsXyz
                baselineBitsLab = bitsLab
                baselineBitsLch = bitsLch
                baselineBitsDeltaE = bitsDeltaE
            } else {
                assertThat(bitsRgb).isEqualTo(baselineBitsRgb)
                assertThat(bitsXyz).isEqualTo(baselineBitsXyz)
                assertThat(bitsLab).isEqualTo(baselineBitsLab)
                assertThat(bitsLch).isEqualTo(baselineBitsLch)
                assertThat(bitsDeltaE).isEqualTo(baselineBitsDeltaE)
            }
        }
    }
}
