package com.kitalonlabs.neogold.engine.estimation

import com.google.common.truth.Truth.assertThat
import com.kitalonlabs.neogold.engine.colorscience.Lab
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.File

class FeatureExtractorTest {

    private val goldenPatchCorrectionFile: File
        get() {
            var dir = File(".").canonicalFile
            while (dir.parentFile != null) {
                val candidate = File(dir, "testdata/golden/colorscience/golden_patch_correction.json")
                if (candidate.isFile) return candidate
                dir = dir.parentFile
            }
            return File("../../../testdata/golden/colorscience/golden_patch_correction.json").canonicalFile
        }

    @Test
    fun testFeatureExtractionFromNominalLab() {
        val lab = Lab(l = 60.0, a = 12.0, b = 24.0)
        val features = FeatureExtractor.extractFeatures(lab)

        assertThat(features["l_star"]).isEqualTo(60.0)
        assertThat(features["a_star"]).isEqualTo(12.0)
        assertThat(features["b_star"]).isEqualTo(24.0)
        assertThat(features["b_over_l"]).isEqualTo(24.0 / 60.0)
        assertThat(features["a_over_b"]).isEqualTo(12.0 / 24.0)
    }

    @Test
    fun testParityWithGoldenPatchCorrectionSkinWindow() {
        // Golden skin window Lab from P04: L = 62.27276993, a = 10.71970422, b = 26.04906880
        val lab = Lab(l = 62.27276993, a = 10.71970422, b = 26.04906880)
        val features = FeatureExtractor.extractFeatures(lab)

        val expectedL = 62.27276993
        val expectedA = 10.71970422
        val expectedB = 26.04906880
        val expectedBOverL = 0.41830593
        val expectedAOverB = 0.41151967

        val tolerance = 1e-4
        assertThat(features["l_star"]!!).isWithin(tolerance).of(expectedL)
        assertThat(features["a_star"]!!).isWithin(tolerance).of(expectedA)
        assertThat(features["b_star"]!!).isWithin(tolerance).of(expectedB)
        assertThat(features["b_over_l"]!!).isWithin(tolerance).of(expectedBOverL)
        assertThat(features["a_over_b"]!!).isWithin(tolerance).of(expectedAOverB)
    }

    @Test
    fun testDivisionByZeroHandledGracefully() {
        // L* is 0
        val zeroL = Lab(l = 0.0, a = 10.0, b = 20.0)
        val featuresZeroL = FeatureExtractor.extractFeatures(zeroL)
        assertThat(featuresZeroL["b_over_l"]).isEqualTo(0.0)

        // b* is 0
        val zeroB = Lab(l = 50.0, a = 10.0, b = 0.0)
        val featuresZeroB = FeatureExtractor.extractFeatures(zeroB)
        assertThat(featuresZeroB["a_over_b"]).isEqualTo(0.0)
    }

    @Test
    fun testExtractOrderedVectorMatchesModelFeatureList() {
        val lab = Lab(l = 55.0, a = 8.0, b = 22.0)
        val featureList = listOf("b_star", "l_star", "a_star", "a_over_b", "b_over_l")
        val vector = FeatureExtractor.extractVector(lab, featureList)

        assertThat(vector).hasLength(5)
        assertThat(vector[0]).isEqualTo(22.0)
        assertThat(vector[1]).isEqualTo(55.0)
        assertThat(vector[2]).isEqualTo(8.0)
        assertThat(vector[3]).isEqualTo(8.0 / 22.0)
        assertThat(vector[4]).isEqualTo(22.0 / 55.0)
    }

    @Test
    fun testUnknownFeatureInListThrows() {
        val lab = Lab(l = 50.0, a = 10.0, b = 20.0)
        val badFeatureList = listOf("l_star", "unknown_nonexistent_feature")

        val ex = assertThrows(EstimationException.InvalidModelException::class.java) {
            FeatureExtractor.extractVector(lab, badFeatureList)
        }
        assertThat(ex.reason).isEqualTo(EstimationException.InvalidModelException.Reason.UNKNOWN_FEATURE)
    }
}
