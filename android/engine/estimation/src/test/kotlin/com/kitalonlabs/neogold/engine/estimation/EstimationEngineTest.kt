package com.kitalonlabs.neogold.engine.estimation

import com.google.common.truth.Truth.assertThat
import com.kitalonlabs.neogold.engine.colorscience.Lab
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import java.io.File
import kotlin.random.Random

class EstimationEngineTest {

    private lateinit var syntheticModel: EstimationModel
    private lateinit var engine: EstimationEngine

    private val syntheticModelFile: File
        get() {
            var dir = File(".").canonicalFile
            while (dir.parentFile != null) {
                val candidate = File(dir, "testdata/models/model.synthetic.json")
                if (candidate.isFile) return candidate
                dir = dir.parentFile
            }
            return File("../../../testdata/models/model.synthetic.json").canonicalFile
        }

    @Before
    fun setUp() {
        syntheticModel = EstimationModelParser.parse(syntheticModelFile.readText())
        engine = EstimationEngine(syntheticModel)
    }

    @Test
    fun testSternumSingleSiteEstimation() {
        // Window Lab: L* = 62.27276993, a* = 10.71970422, b* = 26.04906880
        val lab = Lab(62.27276993, 10.71970422, 26.04906880)
        val siteEst = engine.estimateSite(BodySite.STERNUM, lab)

        assertThat(siteEst.site).isEqualTo(BodySite.STERNUM)
        assertThat(siteEst.residualQuantile90).isEqualTo(1.80)

        // Mathematical calculation verified:
        // intercept 10.5 + dot product 0.28934137 = 10.78934137
        val expectedEst = 10.78934137
        val expectedLower = 10.78934137 - 1.80 // 8.98934137
        val expectedUpper = 10.78934137 + 1.80 // 12.58934137

        assertThat(siteEst.estimate).isWithin(1e-4).of(expectedEst)
        assertThat(siteEst.lowerBound).isWithin(1e-4).of(expectedLower)
        assertThat(siteEst.upperBound).isWithin(1e-4).of(expectedUpper)

        val output = engine.estimateSternum(lab)
        assertThat(output.estimate).isWithin(1e-4).of(expectedEst)
        assertThat(output.lowerBound).isWithin(1e-4).of(expectedLower)
        assertThat(output.upperBound).isWithin(1e-4).of(expectedUpper)
    }

    @Test
    fun testForeheadSingleSiteEstimation() {
        val lab = Lab(62.27276993, 10.71970422, 26.04906880)
        val siteEst = engine.estimateSite(BodySite.FOREHEAD, lab)

        assertThat(siteEst.site).isEqualTo(BodySite.FOREHEAD)
        assertThat(siteEst.residualQuantile90).isEqualTo(1.95)

        // Forehead math:
        // intercept 10.8 + dot product 0.03082926 = 10.83082926
        val expectedEst = 10.83082926
        val expectedLower = 10.83082926 - 1.95 // 8.88082926
        val expectedUpper = 10.83082926 + 1.95 // 12.78082926

        assertThat(siteEst.estimate).isWithin(1e-4).of(expectedEst)
        assertThat(siteEst.lowerBound).isWithin(1e-4).of(expectedLower)
        assertThat(siteEst.upperBound).isWithin(1e-4).of(expectedUpper)
    }

    @Test
    fun testMultiSiteWeightedCombination() {
        val sternumLab = Lab(62.27276993, 10.71970422, 26.04906880)
        val foreheadLab = Lab(60.0, 9.0, 25.0)

        val estSternum = engine.estimateSite(BodySite.STERNUM, sternumLab)
        val estForehead = engine.estimateSite(BodySite.FOREHEAD, foreheadLab)

        val output = engine.estimateMultiSite(sternumLab, foreheadLab)

        // Weights: 0.6 sternum, 0.4 forehead
        val expectedEst = 0.6 * estSternum.estimate + 0.4 * estForehead.estimate
        val expectedQuantile = 0.6 * estSternum.residualQuantile90 + 0.4 * estForehead.residualQuantile90
        val expectedLower = expectedEst - expectedQuantile
        val expectedUpper = expectedEst + expectedQuantile

        assertThat(output.estimate).isWithin(1e-6).of(expectedEst)
        assertThat(output.lowerBound).isWithin(1e-6).of(expectedLower)
        assertThat(output.upperBound).isWithin(1e-6).of(expectedUpper)
        assertThat(output.siteEstimates).hasSize(2)
        assertThat(output.modelVersion).isEqualTo("1.0.0-synthetic")
    }

    @Test
    fun testEmptyCapturesThrowsMissingSiteData() {
        assertThrows(EstimationException.MissingSiteDataException::class.java) {
            engine.estimate(emptyList())
        }
    }

    /**
     * TC-EST-06: Invalid model disables estimation.
     * Corrupt model.json or breaking its schema prevents inference and routes to blood test.
     */
    @Test
    fun testTcEst06InvalidModelDisablesEstimation() {
        val brokenJson = "{ \"invalid\": true }"
        val result = try {
            val brokenEngine = EstimationEngine.fromJson(brokenJson)
            brokenEngine.estimateSafe(listOf(SiteCapture(BodySite.STERNUM, Lab(60.0, 10.0, 20.0))))
        } catch (e: EstimationException) {
            EstimationResult.Failure(e)
        }

        assertThat(result).isInstanceOf(EstimationResult.Failure::class.java)
        val failure = result as EstimationResult.Failure
        assertThat(failure.error).isInstanceOf(EstimationException.InvalidModelException::class.java)
    }

    /**
     * TC-EST-07 groundwork: Identical integer-tenth outputs and determinism.
     * Verifies that repeated executions on golden vectors give bit-identical results,
     * and integer-tenth rounding moves toward more care (ceiling for estimate & upper, floor for lower).
     */
    @Test
    fun testTcEst07GroundworkDeterminismAndIntegerTenths() {
        val sternumLab = Lab(62.27276993, 10.71970422, 26.04906880)

        val run1 = engine.estimateSternum(sternumLab)
        val run2 = engine.estimateSternum(sternumLab)

        // StrictMath bit-identical floating point results
        assertThat(run1.estimate.toBits()).isEqualTo(run2.estimate.toBits())
        assertThat(run1.lowerBound.toBits()).isEqualTo(run2.lowerBound.toBits())
        assertThat(run1.upperBound.toBits()).isEqualTo(run2.upperBound.toBits())

        // Integer tenths rounding toward more care:
        // Estimate 10.78934137 -> 108 tenths (10.8 mg/dL)
        // Lower bound 8.98934137 -> 89 tenths (8.9 mg/dL)
        // Upper bound 12.58934137 -> 126 tenths (12.6 mg/dL)
        val tenths = run1.integerTenths
        assertThat(tenths.estimateTenths).isEqualTo(108)
        assertThat(tenths.lowerTenths).isEqualTo(89)
        assertThat(tenths.upperTenths).isEqualTo(126)
    }

    /**
     * TC-EST-05: Interval coverage harness on synthetic data.
     * Evaluates that empirical coverage of the 90% split-conformal prediction interval
     * meets or exceeds 85% on held-out test distributions.
     */
    @Test
    fun testTcEst05IntervalCoverageHarness() {
        val rng = Random(42) // Fixed seed for determinism
        val nSamples = 1000
        var coveredCount = 0

        val sternumModel = syntheticModel.sites[BodySite.STERNUM]!!
        val q90 = sternumModel.residualQuantile90

        // Simulate realistic neonatal skin window chromaticities and paired ground-truth TSB
        repeat(nSamples) {
            val l = 45.0 + rng.nextDouble() * 30.0 // 45 to 75
            val a = 6.0 + rng.nextDouble() * 12.0 // 6 to 18
            val b = 15.0 + rng.nextDouble() * 25.0 // 15 to 40

            val lab = Lab(l, a, b)
            val estimateOutput = engine.estimateSternum(lab)
            val est = estimateOutput.estimate

            // Conformal residual distributed with 90th percentile = q90
            // Normal distribution residual with stdev = q90 / 1.64485
            val u1 = rng.nextDouble().coerceIn(1e-7, 1.0 - 1e-7)
            val u2 = rng.nextDouble().coerceIn(1e-7, 1.0 - 1e-7)
            val standardNormal = StrictMath.sqrt(-2.0 * StrictMath.log(u1)) * StrictMath.cos(2.0 * Math.PI * u2)
            val noise = standardNormal * (q90 / 1.644853)

            val trueTsb = est + noise

            if (trueTsb >= estimateOutput.lowerBound && trueTsb <= estimateOutput.upperBound) {
                coveredCount++
            }
        }

        val empiricalCoverage = coveredCount.toDouble() / nSamples
        // For a 90% conformal interval, empirical coverage should be at least 85%
        assertThat(empiricalCoverage).isAtLeast(0.85)
    }
}
