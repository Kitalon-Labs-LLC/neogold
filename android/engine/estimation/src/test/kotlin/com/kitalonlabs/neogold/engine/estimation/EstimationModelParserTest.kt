package com.kitalonlabs.neogold.engine.estimation

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.File

class EstimationModelParserTest {

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

    @Test
    fun testParseValidSyntheticModel() {
        val json = syntheticModelFile.readText()
        val model = EstimationModelParser.parse(json)

        assertThat(model.modelVersion).isEqualTo("1.0.0-synthetic")
        assertThat(model.minAppVersion).isEqualTo("1.0.0")
        assertThat(model.units).isEqualTo("mg/dL")
        assertThat(model.algorithm).isEqualTo("ridge_regression_split_conformal")
        assertThat(model.colorSpace).isEqualTo("CIELAB_D65")
        assertThat(model.trainingDataSummary.sampleCount).isEqualTo(300)
        assertThat(model.trainingDataSummary.participantCount).isEqualTo(150)
        assertThat(model.trainingDataSummary.metrics["mae"]).isEqualTo(1.15)
        assertThat(model.featureList).containsExactly("l_star", "a_star", "b_star", "b_over_l", "a_over_b").inOrder()

        assertThat(model.sites.keys).containsExactly(BodySite.STERNUM, BodySite.FOREHEAD)
        val sternum = model.sites[BodySite.STERNUM]!!
        assertThat(sternum.standardizationMeans).hasSize(5)
        assertThat(sternum.standardizationScales).hasSize(5)
        assertThat(sternum.ridgeCoefficients).hasSize(5)
        assertThat(sternum.intercept).isEqualTo(10.5)
        assertThat(sternum.residualQuantile90).isEqualTo(1.80)

        assertThat(model.multiSiteWeights[BodySite.STERNUM]).isEqualTo(0.6)
        assertThat(model.multiSiteWeights[BodySite.FOREHEAD]).isEqualTo(0.4)
    }

    @Test
    fun testMissingRequiredFieldThrows() {
        val validJson = syntheticModelFile.readText()

        // Missing modelVersion
        val missingVersion = validJson.replace("\"modelVersion\": \"1.0.0-synthetic\",", "")
        val exVersion = assertThrows(EstimationException.InvalidModelException::class.java) {
            EstimationModelParser.parse(missingVersion)
        }
        assertThat(exVersion.reason).isEqualTo(EstimationException.InvalidModelException.Reason.MISSING_REQUIRED_FIELD)

        // Missing sternum site
        val missingSternum = validJson.replace("\"sternum\": {", "\"chest\": {")
        val exSternum = assertThrows(EstimationException.InvalidModelException::class.java) {
            EstimationModelParser.parse(missingSternum)
        }
        assertThat(exSternum.reason).isAnyOf(
            EstimationException.InvalidModelException.Reason.MISSING_REQUIRED_FIELD,
            EstimationException.InvalidModelException.Reason.SCHEMA_VIOLATION
        )
    }

    @Test
    fun testDimensionMismatchThrows() {
        val validJson = syntheticModelFile.readText()
        // Cut one coefficient from sternum
        val broken = validJson.replace("[-0.20, 0.35, 1.85, 0.50, -0.25]", "[-0.20, 0.35, 1.85, 0.50]")
        val ex = assertThrows(EstimationException.InvalidModelException::class.java) {
            EstimationModelParser.parse(broken)
        }
        assertThat(ex.reason).isEqualTo(EstimationException.InvalidModelException.Reason.DIMENSION_MISMATCH)
    }

    @Test
    fun testInvalidScaleThrows() {
        val validJson = syntheticModelFile.readText()
        // Zero scale
        val zeroScale = validJson.replace("[10.0, 5.0, 8.0, 0.15, 0.15]", "[10.0, 0.0, 8.0, 0.15, 0.15]")
        val exZero = assertThrows(EstimationException.InvalidModelException::class.java) {
            EstimationModelParser.parse(zeroScale)
        }
        assertThat(exZero.reason).isEqualTo(EstimationException.InvalidModelException.Reason.INVALID_SCALE)

        // Negative scale
        val negScale = validJson.replace("[10.0, 5.0, 8.0, 0.15, 0.15]", "[10.0, -5.0, 8.0, 0.15, 0.15]")
        val exNeg = assertThrows(EstimationException.InvalidModelException::class.java) {
            EstimationModelParser.parse(negScale)
        }
        assertThat(exNeg.reason).isEqualTo(EstimationException.InvalidModelException.Reason.INVALID_SCALE)
    }

    @Test
    fun testInvalidQuantileThrows() {
        val validJson = syntheticModelFile.readText()
        val broken = validJson.replace("\"residualQuantile90\": 1.80", "\"residualQuantile90\": -0.5")
        val ex = assertThrows(EstimationException.InvalidModelException::class.java) {
            EstimationModelParser.parse(broken)
        }
        assertThat(ex.reason).isEqualTo(EstimationException.InvalidModelException.Reason.INVALID_QUANTILE)
    }

    @Test
    fun testInvalidWeightsThrows() {
        val validJson = syntheticModelFile.readText()
        val broken = validJson.replace("\"sternum\": 0.6", "\"sternum\": -0.6")
        val ex = assertThrows(EstimationException.InvalidModelException::class.java) {
            EstimationModelParser.parse(broken)
        }
        assertThat(ex.reason).isEqualTo(EstimationException.InvalidModelException.Reason.INVALID_WEIGHTS)
    }

    @Test
    fun testMalformedJsonThrowsSyntaxError() {
        val badJson = "{ \"modelVersion\": \"1.0.0\", unquoted_bad_json: 123"
        val ex = assertThrows(EstimationException.InvalidModelException::class.java) {
            EstimationModelParser.parse(badJson)
        }
        assertThat(ex.reason).isEqualTo(EstimationException.InvalidModelException.Reason.SYNTAX_ERROR)
    }

    @Test
    fun testUnsupportedVersionThrows() {
        val validJson = syntheticModelFile.readText()
        val badVersion = validJson.replace("\"modelVersion\": \"1.0.0-synthetic\"", "\"modelVersion\": \"beta-1\"")
        val ex = assertThrows(EstimationException.InvalidModelException::class.java) {
            EstimationModelParser.parse(badVersion)
        }
        assertThat(ex.reason).isEqualTo(EstimationException.InvalidModelException.Reason.UNSUPPORTED_VERSION)
    }

    @Test
    fun testDuplicateFeaturesThrows() {
        val validJson = syntheticModelFile.readText()
        val dupFeatures = validJson.replace(
            "[\n    \"l_star\",\n    \"a_star\",\n    \"b_star\",\n    \"b_over_l\",\n    \"a_over_b\"\n  ]",
            "[\"l_star\", \"a_star\", \"b_star\", \"b_over_l\", \"l_star\"]"
        )
        val ex = assertThrows(EstimationException.InvalidModelException::class.java) {
            EstimationModelParser.parse(dupFeatures)
        }
        assertThat(ex.reason).isEqualTo(EstimationException.InvalidModelException.Reason.SCHEMA_VIOLATION)
    }

    @Test
    fun testParseCanonicalModel() {
        var dir = File(".").canonicalFile
        var found: File? = null
        while (dir.parentFile != null) {
            val candidate = File(dir, "config/model.json")
            if (candidate.isFile) {
                found = candidate
                break
            }
            dir = dir.parentFile
        }
        val file = found ?: File("../../../config/model.json").canonicalFile
        if (file.isFile) {
            val json = file.readText()
            val model = EstimationModelParser.parse(json)
            assertThat(model.modelVersion).isEqualTo("1.0.0-synthetic")
            assertThat(model.algorithm).isEqualTo("ridge_regression_split_conformal")
            assertThat(model.sites.keys).containsExactly(BodySite.STERNUM, BodySite.FOREHEAD)
            assertThat(model.sites[BodySite.STERNUM]!!.residualQuantile90).isGreaterThan(0.0)
            assertThat(model.sites[BodySite.FOREHEAD]!!.residualQuantile90).isGreaterThan(0.0)
            assertThat(model.multiSiteWeights[BodySite.STERNUM]).isEqualTo(0.6)
            assertThat(model.multiSiteWeights[BodySite.FOREHEAD]).isEqualTo(0.4)
        }
    }
}
