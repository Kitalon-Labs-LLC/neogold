package com.kitalonlabs.neogold.engine.colorscience

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.util.Random

/**
 * Tests for color correction fitting (3x3 linear matrix and 2nd-order root polynomial),
 * quality gate residual calculation, and synthetic distortion recovery (< 0.5 Delta E 2000 on held-out patches).
 * Verifies TC-EST-02.
 */
class ColorCorrectionTest {

    @Test
    fun testCorrectionRecoversSyntheticDistortionUnderHalfDeltaE() {
        // Ground truth: 24 ColorChecker / card patches in linear RGB
        // We simulate a camera distortion:
        // 1. A random but well-conditioned 3x3 color cross-talk matrix C
        // 2. A non-linear tone curve (gamma = 2.1)
        val rng = Random(20261018L)
        val numTrain = 18
        val numTest = 6
        val total = numTrain + numTest

        // Generate synthetic reference XYZs from linear RGBs via standard sRGB-to-XYZ
        val trueLinearRgbs = mutableListOf<Rgb>()
        val trueRefXyzs = mutableListOf<Xyz>()
        val trueRefLabs = mutableListOf<Lab>()

        for (i in 0 until total) {
            val r = 0.05 + 0.90 * rng.nextDouble()
            val g = 0.05 + 0.90 * rng.nextDouble()
            val b = 0.05 + 0.90 * rng.nextDouble()
            val rgb = Rgb(r, g, b)
            trueLinearRgbs.add(rgb)
            val xyz = linearRgbToXyz(rgb)
            trueRefXyzs.add(xyz)
            trueRefLabs.add(xyzToLab(xyz))
        }

        // Camera distortion: well-conditioned 3x3 mixing matrix M
        // Close to identity with moderate cross-talk
        val m00 = 1.05;
        val m01 = 0.08;
        val m02 = 0.03
        val m10 = 0.05;
        val m11 = 0.98;
        val m12 = 0.06
        val m20 = 0.02;
        val m21 = 0.04;
        val m22 = 1.02

        // Distorted camera observed RGBs (pre-linearization) with gamma = 2.1
        val gamma = 2.1
        val invGamma = 1.0 / gamma
        val cameraObservedRgbs = trueLinearRgbs.map { rgb ->
            val cr = m00 * rgb.r + m01 * rgb.g + m02 * rgb.b
            val cg = m10 * rgb.r + m11 * rgb.g + m12 * rgb.b
            val cb = m20 * rgb.r + m21 * rgb.g + m22 * rgb.b
            Rgb(
                StrictMath.pow(StrictMath.max(0.0, cr), invGamma),
                StrictMath.pow(StrictMath.max(0.0, cg), invGamma),
                StrictMath.pow(StrictMath.max(0.0, cb), invGamma)
            )
        }

        // Linearize using gray ramp calibration
        val grayLinearReflectances = listOf(0.05, 0.15, 0.30, 0.50, 0.70, 0.90)
        val grayObserved = grayLinearReflectances.map { y ->
            // Distort neutral gray (cr = cg = cb ~ (m00+m01+m02)*y etc)
            val cr = (m00 + m01 + m02) * y
            val cg = (m10 + m11 + m12) * y
            val cb = (m20 + m21 + m22) * y
            Rgb(
                StrictMath.pow(cr, invGamma),
                StrictMath.pow(cg, invGamma),
                StrictMath.pow(cb, invGamma)
            )
        }
        val linearizer = PerChannelLinearizer.fit(grayObserved, grayLinearReflectances)

        // Linearize all patches
        val cameraLinearRgbs = cameraObservedRgbs.map { linearizer.linearize(it) }

        // Partition into train (numTrain) and held-out test (numTest)
        val trainRgbs = cameraLinearRgbs.subList(0, numTrain)
        val trainXyzs = trueRefXyzs.subList(0, numTrain)
        val trainLabs = trueRefLabs.subList(0, numTrain)

        val testRgbs = cameraLinearRgbs.subList(numTrain, total)
        val testLabs = trueRefLabs.subList(numTrain, total)

        // Fit 3x3 color correction
        val ccm = ColorCorrectionFitter.fit(trainRgbs, trainXyzs, useRootPolynomial = false)

        // Evaluate on training patches
        val trainResidual = QualityResidualCalculator.computeResidual(ccm, trainRgbs, trainLabs)
        assertThat(trainResidual.meanDeltaE).isAtMost(0.4)

        // Evaluate on held-out test patches
        val testResidual = QualityResidualCalculator.computeResidual(ccm, testRgbs, testLabs)
        assertThat(testResidual.meanDeltaE).isLessThan(0.5)
    }

    @Test
    fun testRootPolynomialCorrectionFitAndResidual() {
        val rng = Random(42L)
        val patchesLinear = mutableListOf<Rgb>()
        val patchesXyz = mutableListOf<Xyz>()
        val patchesLab = mutableListOf<Lab>()

        for (i in 0 until 20) {
            val r = 0.1 + 0.8 * rng.nextDouble()
            val g = 0.1 + 0.8 * rng.nextDouble()
            val b = 0.1 + 0.8 * rng.nextDouble()
            val rgb = Rgb(r, g, b)
            patchesLinear.add(rgb)
            val xyz = linearRgbToXyz(rgb)
            patchesXyz.add(xyz)
            patchesLab.add(xyzToLab(xyz))
        }

        val rootPolyModel = ColorCorrectionFitter.fit(patchesLinear, patchesXyz, useRootPolynomial = true)
        val residual = QualityResidualCalculator.computeResidual(rootPolyModel, patchesLinear, patchesLab)

        // Perfect linear conversion should fit with near-zero error (< 0.05 Delta E 2000)
        assertThat(residual.meanDeltaE).isAtMost(0.05)
    }

    @Test
    fun testTcEst02CorrectionResidualCalculation() {
        // TC-EST-02: verify residual calculation produces consistent meanDeltaE and maxDeltaE
        val labs = listOf(
            Lab(50.0, 10.0, 10.0),
            Lab(60.0, -10.0, 20.0),
            Lab(70.0, 5.0, -15.0)
        )
        val xyzs = labs.map { labToXyz(it) }
        val rgbs = xyzs.map { xyzToLinearRgb(it) }

        val model = ColorCorrectionFitter.fit(rgbs, xyzs, useRootPolynomial = false)
        val residual = QualityResidualCalculator.computeResidual(model, rgbs, labs)

        assertThat(residual.patchDeltaEs).hasSize(3)
        assertThat(residual.meanDeltaE).isAtMost(1e-6)
        assertThat(residual.maxDeltaE).isAtMost(1e-6)
    }
}
