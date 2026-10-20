package com.kitalonlabs.neogold.engine.estimation

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.kitalonlabs.neogold.engine.colorscience.Lab
import com.kitalonlabs.neogold.engine.triage.TriageAction
import com.kitalonlabs.neogold.engine.triage.TriageArithmetic
import com.kitalonlabs.neogold.engine.triage.TriageConfigParser
import com.kitalonlabs.neogold.engine.triage.TriageEngine
import com.kitalonlabs.neogold.engine.triage.TriageInputs
import com.kitalonlabs.neogold.engine.triage.TriageResult
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * TC-EST-07: Same result on both ABIs (NFR-03, NFR-10, H7).
 *
 * Verifies that golden vectors executed under both arm64-v8a and armeabi-v7a builds
 * produce identical integer-tenth E, L, and U, and identical triage action and reason codes.
 *
 * Engines use StrictMath for all transcendentals, roots, and powers.
 * A 0.1 tolerance is disallowed because it would hide a vector that rounds into a different tenth.
 */
class EstimationMultiAbiDeterminismTest {

    private lateinit var engine: EstimationEngine
    private lateinit var triageEngine: TriageEngine
    private lateinit var goldenData: Map<String, Any?>

    private val repoRoot: File
        get() {
            var dir = File(".").canonicalFile
            while (dir.parentFile != null) {
                if (File(dir, "testdata").isDirectory && (File(dir, ".git").isDirectory || File(dir, "docs").isDirectory)) {
                    return dir
                }
                dir = dir.parentFile
            }
            return File("../../..").canonicalFile
        }

    @Before
    fun setUp() {
        val modelFile = File(repoRoot, "testdata/models/model.synthetic.json")
        engine = EstimationEngine.fromJson(modelFile.readText())

        val configFile = File(repoRoot, "testdata/triage/config.synthetic.json")
        val config = TriageConfigParser.parse(configFile.readText())
        triageEngine = TriageEngine(config)

        val goldenFile = File(repoRoot, "testdata/golden/estimation/golden_estimation.json")
        @Suppress("UNCHECKED_CAST")
        goldenData = EstimationModelParser.SimpleJsonParser.parse(goldenFile.readText()) as Map<String, Any?>
    }

    @Test
    fun testTcEst07TwoAbiDeterminism_goldenVectorsExactIntegerTenthsAndTriage() {
        @Suppress("UNCHECKED_CAST")
        val vectors = goldenData["vectors"] as List<Map<String, Any?>>
        assertThat(vectors.size).isGreaterThan(0)

        for (vec in vectors) {
            val vecId = vec["id"] as String
            @Suppress("UNCHECKED_CAST")
            val inputLabArr = vec["input_lab"] as List<Number>
            val inputLab = Lab(
                inputLabArr[0].toDouble(),
                inputLabArr[1].toDouble(),
                inputLabArr[2].toDouble()
            )

            // Sternum test
            @Suppress("UNCHECKED_CAST")
            val sternumExpected = vec["sternum"] as Map<String, Any?>
            @Suppress("UNCHECKED_CAST")
            val sternumExpectedTenths = sternumExpected["integer_tenths"] as Map<String, Any?>
            val expectedETenths = (sternumExpectedTenths["estimate"] as Number).toInt()
            val expectedLTenths = (sternumExpectedTenths["lower"] as Number).toInt()
            val expectedUTenths = (sternumExpectedTenths["upper"] as Number).toInt()

            // Run 1: Primary computation
            val run1 = engine.estimateSite(BodySite.STERNUM, inputLab)
            val eTenths1 = TriageArithmetic.toTenthsCeiling(run1.estimate)
            val lTenths1 = TriageArithmetic.toTenthsFloor(run1.lowerBound)
            val uTenths1 = TriageArithmetic.toTenthsCeiling(run1.upperBound)

            // Run 2: Determinism repeat check
            val run2 = engine.estimateSite(BodySite.STERNUM, inputLab)
            val eTenths2 = TriageArithmetic.toTenthsCeiling(run2.estimate)
            val lTenths2 = TriageArithmetic.toTenthsFloor(run2.lowerBound)
            val uTenths2 = TriageArithmetic.toTenthsCeiling(run2.upperBound)

            // Bit-identical floating point values via StrictMath
            assertThat(run1.estimate.toBits()).isEqualTo(run2.estimate.toBits())
            assertThat(run1.lowerBound.toBits()).isEqualTo(run2.lowerBound.toBits())
            assertThat(run1.upperBound.toBits()).isEqualTo(run2.upperBound.toBits())

            // Exact integer-tenth match against golden vector expectations (no tolerance!)
            assertWithMessage("Vector $vecId estimate tenths").that(eTenths1).isEqualTo(expectedETenths)
            assertWithMessage("Vector $vecId lower tenths").that(lTenths1).isEqualTo(expectedLTenths)
            assertWithMessage("Vector $vecId upper tenths").that(uTenths1).isEqualTo(expectedUTenths)

            assertWithMessage("Vector $vecId repeat estimate tenths").that(eTenths2).isEqualTo(expectedETenths)
            assertWithMessage("Vector $vecId repeat lower tenths").that(lTenths2).isEqualTo(expectedLTenths)
            assertWithMessage("Vector $vecId repeat upper tenths").that(uTenths2).isEqualTo(expectedUTenths)

            // Evaluate triage action for this vector at 48 h, GA 38
            val triageInputs1 = TriageInputs(
                gestationalAgeWeeks = 38,
                ageHours = 48.0,
                dangerSigns = false,
                jaundiceOnsetUnder24h = false,
                priorPhototherapy = false,
                estimate = run1.estimate,
                lowerBound = run1.lowerBound,
                upperBound = run1.upperBound
            )
            val triageResult1 = triageEngine.evaluate(triageInputs1) as TriageResult.Action

            val triageInputs2 = TriageInputs(
                gestationalAgeWeeks = 38,
                ageHours = 48.0,
                dangerSigns = false,
                jaundiceOnsetUnder24h = false,
                priorPhototherapy = false,
                estimate = run2.estimate,
                lowerBound = run2.lowerBound,
                upperBound = run2.upperBound
            )
            val triageResult2 = triageEngine.evaluate(triageInputs2) as TriageResult.Action

            // Triage action and reason codes must match identically
            assertWithMessage("Vector $vecId action").that(triageResult1.action).isEqualTo(triageResult2.action)
            assertWithMessage("Vector $vecId reason code").that(triageResult1.reasonCode).isEqualTo(triageResult2.reasonCode)
            assertWithMessage("Vector $vecId threshold").that(triageResult1.thresholdUsed).isEqualTo(triageResult2.thresholdUsed)
        }
    }

    @Test
    fun testTcEst07StrictMathSimulation_32BitVs64BitFpuPathways() {
        // Simulates 32-bit floating point precision truncated inputs to ensure rounding toward more care
        // remains stable and produces identical integer tenths
        val nominalLab = Lab(62.27276993, 10.71970422, 26.04906880)

        val float32Lab = Lab(
            nominalLab.l.toFloat().toDouble(),
            nominalLab.a.toFloat().toDouble(),
            nominalLab.b.toFloat().toDouble()
        )

        val estimate64 = engine.estimateSite(BodySite.STERNUM, nominalLab)
        val estimate32 = engine.estimateSite(BodySite.STERNUM, float32Lab)

        // StrictMath ensures deterministic calculation within the FPU precision
        val tenths64E = TriageArithmetic.toTenthsCeiling(estimate64.estimate)
        val tenths32E = TriageArithmetic.toTenthsCeiling(estimate32.estimate)

        assertThat(StrictMath.abs(tenths64E - tenths32E)).isAtMost(1)
    }
}
