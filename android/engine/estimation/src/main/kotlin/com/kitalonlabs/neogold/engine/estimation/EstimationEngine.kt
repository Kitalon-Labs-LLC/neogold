package com.kitalonlabs.neogold.engine.estimation

import com.kitalonlabs.neogold.engine.colorscience.Lab

/**
 * On-device bilirubin estimation engine (docs/4_System_Architecture.md Section 5.5).
 * Pure JVM engine with zero Android dependencies.
 *
 * Infers bilirubin concentration and split-conformal 90% prediction intervals from
 * corrected skin window CIELAB values using the authoritative parameters in [model].
 */
class EstimationEngine(val model: EstimationModel) {

    /**
     * Infers bilirubin estimate and 90% prediction interval for a single body site.
     *
     * @param site Anatomical site (STERNUM or FOREHEAD).
     * @param windowLab CIELAB reading from the corrected skin window.
     * @return [SingleSiteEstimate] containing estimate, interval bounds, and extracted features.
     * @throws EstimationException on feature extraction or calculation errors.
     */
    fun estimateSite(site: BodySite, windowLab: Lab): SingleSiteEstimate {
        val siteModel = model.sites[site]
            ?: throw EstimationException.InvalidModelException(
                reason = EstimationException.InvalidModelException.Reason.MISSING_REQUIRED_FIELD,
                message = "Model has no parameters for site $site"
            )

        val rawFeatures = FeatureExtractor.extractFeatures(windowLab)
        val vector = FeatureExtractor.extractVector(windowLab, model.featureList)
        val standardized = EstimationMath.standardize(
            features = vector,
            means = siteModel.standardizationMeans,
            scales = siteModel.standardizationScales
        )
        val estimate = EstimationMath.predictLinear(
            standardized = standardized,
            coefficients = siteModel.ridgeCoefficients,
            intercept = siteModel.intercept
        )
        val q90 = siteModel.residualQuantile90
        val lower = estimate - q90
        val upper = estimate + q90

        return SingleSiteEstimate(
            site = site,
            estimate = estimate,
            lowerBound = lower,
            upperBound = upper,
            rawFeatures = rawFeatures,
            standardizedFeatures = standardized,
            residualQuantile90 = q90
        )
    }

    /**
     * Computes the estimation output for one or more captured sites.
     * When both sternum and forehead are present, a weighted multi-site combination is computed.
     *
     * @param captures Non-empty list of site captures.
     * @return [EstimationOutput] with point estimate, interval, and per-site breakdowns.
     * @throws EstimationException.MissingSiteDataException if [captures] is empty.
     */
    fun estimate(captures: List<SiteCapture>): EstimationOutput {
        if (captures.isEmpty()) {
            throw EstimationException.MissingSiteDataException("At least one site capture (e.g. sternum) is required")
        }

        val siteEstimates = mutableMapOf<BodySite, SingleSiteEstimate>()
        for (capture in captures) {
            val single = estimateSite(capture.site, capture.windowLab)
            siteEstimates[capture.site] = single
        }

        val (combinedEstimate, combinedQuantile) = EstimationMath.combineSites(
            siteEstimates = siteEstimates,
            weights = model.multiSiteWeights
        )

        return EstimationOutput(
            estimate = combinedEstimate,
            lowerBound = combinedEstimate - combinedQuantile,
            upperBound = combinedEstimate + combinedQuantile,
            siteEstimates = siteEstimates,
            modelVersion = model.modelVersion,
            units = model.units
        )
    }

    /**
     * Safe estimation returning a typed [EstimationResult].
     * Never throws; failures return [EstimationResult.Failure] so screening flow
     * can disable estimation and recommend a blood test (TC-EST-06).
     */
    @Suppress(
        "TooGenericExceptionCaught"
    ) // never throws by contract: any failure becomes EstimationResult.Failure (TC-EST-06)
    fun estimateSafe(captures: List<SiteCapture>): EstimationResult {
        return try {
            EstimationResult.Success(estimate(captures))
        } catch (e: EstimationException) {
            EstimationResult.Failure(e)
        } catch (e: Exception) {
            EstimationResult.Failure(
                EstimationException.EstimationFailedException(
                    message = "Unexpected estimation failure: ${e.message}",
                    cause = e
                )
            )
        }
    }

    /** Convenience method for primary sternum-only capture. */
    fun estimateSternum(windowLab: Lab): EstimationOutput =
        estimate(listOf(SiteCapture(BodySite.STERNUM, windowLab)))

    /** Convenience method for sternum + forehead multi-site capture. */
    fun estimateMultiSite(sternumLab: Lab, foreheadLab: Lab): EstimationOutput =
        estimate(listOf(SiteCapture(BodySite.STERNUM, sternumLab), SiteCapture(BodySite.FOREHEAD, foreheadLab)))

    companion object {
        /**
         * Creates an [EstimationEngine] by parsing the provided model JSON text.
         */
        fun fromJson(jsonString: String): EstimationEngine {
            val model = EstimationModelParser.parse(jsonString)
            return EstimationEngine(model)
        }
    }
}
