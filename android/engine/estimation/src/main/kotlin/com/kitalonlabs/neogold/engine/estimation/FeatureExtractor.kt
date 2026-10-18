package com.kitalonlabs.neogold.engine.estimation

import com.kitalonlabs.neogold.engine.colorscience.Lab

/**
 * Feature extraction from corrected skin window CIELAB values (docs/4_System_Architecture.md Section 5.5).
 * Features extracted per site:
 * - mean L-star
 * - mean a-star
 * - mean b-star
 * - b-star / L-star ratio
 * - a-star / b-star ratio
 *
 * The feature list in model.json is authoritative for feature ordering and inclusion.
 */
object FeatureExtractor {

    const val FEATURE_L_STAR = "l_star"
    const val FEATURE_A_STAR = "a_star"
    const val FEATURE_B_STAR = "b_star"
    const val FEATURE_B_OVER_L = "b_over_l"
    const val FEATURE_A_OVER_B = "a_over_b"

    /**
     * Extracts named features from the corrected skin window [Lab].
     */
    fun extractFeatures(lab: Lab): Map<String, Double> {
        val bOverL = if (lab.l != 0.0) lab.b / lab.l else 0.0
        val aOverB = if (lab.b != 0.0) lab.a / lab.b else 0.0

        return mapOf(
            FEATURE_L_STAR to lab.l,
            FEATURE_A_STAR to lab.a,
            FEATURE_B_STAR to lab.b,
            FEATURE_B_OVER_L to bOverL,
            FEATURE_A_OVER_B to aOverB,
            // Aliases to support varying naming conventions in training/reference
            "mean_l" to lab.l,
            "mean_a" to lab.a,
            "mean_b" to lab.b,
            "b/L*" to bOverL,
            "a*/b*" to aOverB,
            "b_star/l_star" to bOverL,
            "a_star/b_star" to aOverB,
            "L*" to lab.l,
            "a*" to lab.a,
            "b*" to lab.b
        )
    }

    /**
     * Extracts an ordered double array vector corresponding to the model's authoritative [featureList].
     *
     * @param lab The corrected skin window CIELAB reading.
     * @param featureList Ordered feature names defined by the model.
     * @return Ordered double array of extracted feature values.
     * @throws EstimationException.InvalidModelException if a feature requested by the model cannot be extracted.
     */
    fun extractVector(lab: Lab, featureList: List<String>): DoubleArray {
        val features = extractFeatures(lab)
        val vector = DoubleArray(featureList.size)
        for (i in featureList.indices) {
            val name = featureList[i]
            val value = features[name]
                ?: throw EstimationException.InvalidModelException(
                    reason = EstimationException.InvalidModelException.Reason.UNKNOWN_FEATURE,
                    message = "Model requires unknown or unsupported feature: '$name'"
                )
            vector[i] = value
        }
        return vector
    }
}
