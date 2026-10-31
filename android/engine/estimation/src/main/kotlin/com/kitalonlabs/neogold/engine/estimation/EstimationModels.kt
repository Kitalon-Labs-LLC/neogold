package com.kitalonlabs.neogold.engine.estimation

import com.kitalonlabs.neogold.engine.colorscience.Lab
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Anatomical body sites used for skin reflectance capture.
 */
enum class BodySite {
    STERNUM,
    FOREHEAD;

    companion object {
        fun fromString(value: String): BodySite = when (value.trim().lowercase()) {
            "sternum" -> STERNUM
            "forehead" -> FOREHEAD
            else -> throw IllegalArgumentException("Unknown body site: '$value'")
        }
    }
}

/**
 * Regression model parameters for a single body site.
 */
data class SiteModel(
    val standardizationMeans: List<Double>,
    val standardizationScales: List<Double>,
    val ridgeCoefficients: List<Double>,
    val intercept: Double,
    val residualQuantile90: Double
) {
    init {
        require(standardizationMeans.size == standardizationScales.size) {
            "Dimension mismatch: standardizationMeans size (${standardizationMeans.size}) " +
                "!= standardizationScales size (${standardizationScales.size})"
        }
        require(standardizationMeans.size == ridgeCoefficients.size) {
            "Dimension mismatch: standardizationMeans size (${standardizationMeans.size}) " +
                "!= ridgeCoefficients size (${ridgeCoefficients.size})"
        }
        require(standardizationScales.all { it > 0.0 && !it.isNaN() && !it.isInfinite() }) {
            "All standardizationScales must be strictly positive finite numbers"
        }
        require(residualQuantile90 > 0.0 && !residualQuantile90.isNaN() && !residualQuantile90.isInfinite()) {
            "residualQuantile90 must be a strictly positive finite number"
        }
    }
}

/**
 * Metadata and pedigree summary of training dataset.
 */
data class TrainingDataSummary(
    val sampleCount: Int,
    val participantCount: Int,
    val description: String,
    val trainedDate: String,
    val populations: List<String>,
    val devices: List<String>,
    val metrics: Map<String, Double> = emptyMap()
)

/**
 * Complete versioned estimation model loaded from model.json.
 */
data class EstimationModel(
    val modelVersion: String,
    val minAppVersion: String,
    val units: String,
    val algorithm: String,
    val colorSpace: String,
    val trainingDataSummary: TrainingDataSummary,
    val featureList: List<String>,
    val sites: Map<BodySite, SiteModel>,
    val multiSiteWeights: Map<BodySite, Double>
) {
    init {
        require(modelVersion.isNotBlank()) { "modelVersion must not be blank" }
        require(minAppVersion.isNotBlank()) { "minAppVersion must not be blank" }
        require(units == "mg/dL") { "units must be 'mg/dL'" }
        require(featureList.isNotEmpty()) { "featureList must not be empty" }
        require(featureList.size == featureList.toSet().size) { "featureList must contain unique feature names" }
        require(sites.containsKey(BodySite.STERNUM)) { "Model must contain at least 'sternum' site parameters" }
        for ((site, siteModel) in sites) {
            require(siteModel.standardizationMeans.size == featureList.size) {
                "Site $site feature dimensions (${siteModel.standardizationMeans.size}) " +
                    "do not match featureList length (${featureList.size})"
            }
        }
    }
}

/**
 * Captured skin window CIELAB reading for a specific body site.
 */
data class SiteCapture(
    val site: BodySite,
    val windowLab: Lab
)

/**
 * Estimation result for a single body site.
 */
data class SingleSiteEstimate(
    val site: BodySite,
    val estimate: Double,
    val lowerBound: Double,
    val upperBound: Double,
    val rawFeatures: Map<String, Double>,
    val standardizedFeatures: DoubleArray,
    val residualQuantile90: Double
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is SingleSiteEstimate) return false
        if (site != other.site) return false
        if (estimate != other.estimate) return false
        if (lowerBound != other.lowerBound) return false
        if (upperBound != other.upperBound) return false
        if (rawFeatures != other.rawFeatures) return false
        if (!standardizedFeatures.contentEquals(other.standardizedFeatures)) return false
        if (residualQuantile90 != other.residualQuantile90) return false
        return true
    }

    override fun hashCode(): Int {
        var result = site.hashCode()
        result = 31 * result + estimate.hashCode()
        result = 31 * result + lowerBound.hashCode()
        result = 31 * result + upperBound.hashCode()
        result = 31 * result + rawFeatures.hashCode()
        result = 31 * result + standardizedFeatures.contentHashCode()
        result = 31 * result + residualQuantile90.hashCode()
        return result
    }
}

/**
 * Bilirubin prediction interval (in mg/dL).
 */
data class PredictionInterval(
    val estimate: Double,
    val lower: Double,
    val upper: Double
)

/**
 * Integer-tenths prediction interval according to doc 4 Section 6.2 triage arithmetic.
 * Rounding always moves toward more care:
 * - Estimate rounded up (CEILING)
 * - Upper bound rounded up (CEILING)
 * - Lower bound rounded down (FLOOR)
 */
data class IntegerTenthsInterval(
    val estimateTenths: Int,
    val lowerTenths: Int,
    val upperTenths: Int
)

/**
 * Output of the estimation engine.
 */
data class EstimationOutput(
    val estimate: Double,
    val lowerBound: Double,
    val upperBound: Double,
    val siteEstimates: Map<BodySite, SingleSiteEstimate>,
    val modelVersion: String,
    val units: String = "mg/dL"
) {
    val interval: PredictionInterval
        get() = PredictionInterval(estimate, lowerBound, upperBound)

    val integerTenths: IntegerTenthsInterval
        get() = IntegerTenthsInterval(
            estimateTenths = toTenthsCeiling(estimate),
            lowerTenths = toTenthsFloor(lowerBound),
            upperTenths = toTenthsCeiling(upperBound)
        )

    companion object {
        fun toTenthsFloor(value: Double): Int {
            return BigDecimal(value.toString())
                .multiply(BigDecimal.TEN)
                .setScale(0, RoundingMode.FLOOR)
                .intValueExact()
        }

        fun toTenthsCeiling(value: Double): Int {
            return BigDecimal(value.toString())
                .multiply(BigDecimal.TEN)
                .setScale(0, RoundingMode.CEILING)
                .intValueExact()
        }
    }
}
