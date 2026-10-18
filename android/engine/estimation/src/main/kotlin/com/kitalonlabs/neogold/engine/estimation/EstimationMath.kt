package com.kitalonlabs.neogold.engine.estimation

/**
 * Pure Kotlin mathematical routines for feature standardization, linear inference,
 * and multi-site combination. Uses StrictMath for deterministic cross-ABI precision.
 */
object EstimationMath {

    /**
     * Standardizes raw features: z_i = (x_i - mean_i) / scale_i.
     */
    fun standardize(
        features: DoubleArray,
        means: List<Double>,
        scales: List<Double>
    ): DoubleArray {
        require(features.size == means.size) {
            "Dimension mismatch: features count (${features.size}) != means count (${means.size})"
        }
        require(features.size == scales.size) {
            "Dimension mismatch: features count (${features.size}) != scales count (${scales.size})"
        }

        val z = DoubleArray(features.size)
        for (i in features.indices) {
            val scale = scales[i]
            if (scale <= 0.0 || scale.isNaN() || scale.isInfinite()) {
                throw EstimationException.InvalidModelException(
                    reason = EstimationException.InvalidModelException.Reason.INVALID_SCALE,
                    message = "Scale factor at index $i must be strictly positive and finite (found $scale)"
                )
            }
            z[i] = (features[i] - means[i]) / scale
        }
        return z
    }

    /**
     * Computes linear ridge regression prediction: y = intercept + sum_i (z_i * w_i).
     */
    fun predictLinear(
        standardized: DoubleArray,
        coefficients: List<Double>,
        intercept: Double
    ): Double {
        require(standardized.size == coefficients.size) {
            "Dimension mismatch: standardized features count (${standardized.size}) != coefficients count (${coefficients.size})"
        }

        var sum = 0.0
        for (i in standardized.indices) {
            sum += standardized[i] * coefficients[i]
        }
        return intercept + sum
    }

    /**
     * Combines multiple site predictions using normalized weights.
     *
     * If a single site is provided, returns that site's (estimate, quantile).
     * If both sternum and forehead are provided, returns the weighted combination:
     *   estimate = sum(w_i * y_i)
     *   quantile = sum(w_i * q_i)
     *
     * @return Pair of (combinedEstimate, combinedResidualQuantile90).
     */
    fun combineSites(
        siteEstimates: Map<BodySite, SingleSiteEstimate>,
        weights: Map<BodySite, Double>
    ): Pair<Double, Double> {
        if (siteEstimates.isEmpty()) {
            throw EstimationException.MissingSiteDataException("Cannot combine empty site estimates")
        }

        if (siteEstimates.size == 1) {
            val single = siteEstimates.values.first()
            return Pair(single.estimate, single.residualQuantile90)
        }

        var totalWeight = 0.0
        for ((site, _) in siteEstimates) {
            val w = weights[site]
                ?: throw EstimationException.InvalidModelException(
                    reason = EstimationException.InvalidModelException.Reason.INVALID_WEIGHTS,
                    message = "Model missing weight configuration for site $site"
                )
            if (w < 0.0 || w.isNaN() || w.isInfinite()) {
                throw EstimationException.InvalidModelException(
                    reason = EstimationException.InvalidModelException.Reason.INVALID_WEIGHTS,
                    message = "Site $site weight must be a non-negative finite number (found $w)"
                )
            }
            totalWeight += w
        }

        if (totalWeight <= 0.0) {
            throw EstimationException.InvalidModelException(
                reason = EstimationException.InvalidModelException.Reason.INVALID_WEIGHTS,
                message = "Sum of multi-site weights for active sites must be strictly positive (found $totalWeight)"
            )
        }

        var weightedEstimate = 0.0
        var weightedQuantile = 0.0
        for ((site, est) in siteEstimates) {
            val normalizedWeight = (weights[site] ?: 0.0) / totalWeight
            weightedEstimate += normalizedWeight * est.estimate
            weightedQuantile += normalizedWeight * est.residualQuantile90
        }

        return Pair(weightedEstimate, weightedQuantile)
    }
}
