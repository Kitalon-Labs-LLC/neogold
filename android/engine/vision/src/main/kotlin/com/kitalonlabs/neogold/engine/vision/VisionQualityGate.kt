package com.kitalonlabs.neogold.engine.vision

import com.kitalonlabs.neogold.engine.colorscience.ColorConversions
import com.kitalonlabs.neogold.engine.colorscience.ColorCorrectionFitter
import com.kitalonlabs.neogold.engine.colorscience.Lab
import com.kitalonlabs.neogold.engine.colorscience.QualityResidualCalculator
import com.kitalonlabs.neogold.engine.colorscience.Rgb
import com.kitalonlabs.neogold.engine.colorscience.ciede2000

/**
 * Quality Gate evaluating capture quality in exact order specified in doc 4 Section 5.3:
 * 1. NO_CARD
 * 2. CARD_ANGLE
 * 3. NONUNIFORM_PATCHES
 * 4. BLUR
 * 5. GLARE
 * 6. UNDEREXPOSED
 * 7. OVEREXPOSED
 * 8. UNEVEN_LIGHT
 * 9. HIGH_RESIDUAL
 * 10. WINDOW_NOT_SKIN
 *
 * All thresholds injected from QualityThresholds, never hardcoded constants.
 */
object VisionQualityGate {

    /**
     * Computes the discrete Laplacian variance of the canonical card luminance image.
     */
    fun computeLaplacianVariance(card: CanonicalCardImage): Double {
        val w = card.width
        val h = card.height
        var sumLap = 0.0
        var sumLapSq = 0.0
        var count = 0

        // Subsample step 2 for speed
        val step = 2
        for (y in 1 until (h - 1) step step) {
            for (x in 1 until (w - 1) step step) {
                val center = card.getLuminance(x, y)
                val left = card.getLuminance(x - 1, y)
                val right = card.getLuminance(x + 1, y)
                val top = card.getLuminance(x, y - 1)
                val bottom = card.getLuminance(x, y + 1)

                // Laplacian operator: left + right + top + bottom - 4*center
                val lap = left + right + top + bottom - 4.0 * center
                sumLap += lap
                sumLapSq += lap * lap
                count++
            }
        }

        if (count == 0) return 0.0
        val mean = sumLap / count
        return StrictMath.max(0.0, (sumLapSq / count) - (mean * mean))
    }

    /**
     * Evaluates all quality checks in exact doc 4 Section 5.3 order.
     *
     * @param detectionResult Result of 4-corner marker detection.
     * @param canonicalCard The warped 1200x760 px image (null if detection failed).
     * @param thresholds Config-driven quality thresholds.
     * @param batchReference Optional batch measured spectrophotometer Lab targets (default: nominal).
     */
    @Suppress(
        "CyclomaticComplexMethod",
        "LongMethod"
    ) // one ordered rule list (doc 4 Section 5.2), each check is a branch
    fun evaluate(
        detectionResult: CardDetectionResult,
        canonicalCard: CanonicalCardImage?,
        thresholds: QualityThresholds,
        batchReference: Map<String, Lab>? = null
    ): QualityGateResult {
        val failureReasons = mutableListOf<QualityReasonCode>()

        // 1. NO_CARD (Fewer than 4 corner markers detected)
        if (!detectionResult.allFourFound || canonicalCard == null) {
            failureReasons.add(QualityReasonCode.NO_CARD)
            val metrics = QualityMetrics(
                markersDetected = detectionResult.markers.size,
                cardAngleDegrees = detectionResult.cardAngleDegrees,
                duplicatePatchDeltaE = 0.0,
                blurLaplacianVariance = 0.0,
                glarePixelFraction = 0.0,
                midGrayLuminance = 0.0,
                lightUniformityDelta = 0.0,
                meanCorrectionResidualDeltaE = 0.0,
                skinWindowSample = null
            )
            return QualityGateResult(
                passed = false,
                failureReasons = failureReasons,
                metrics = metrics,
                canonicalImage = null,
                sampledPatches = null,
                windowRgb = null,
                windowLab = null
            )
        }

        // 2. CARD_ANGLE (Card tilted beyond maxCardAngleDegrees, e.g. 20°)
        if (detectionResult.cardAngleDegrees > thresholds.maxCardAngleDegrees) {
            failureReasons.add(QualityReasonCode.CARD_ANGLE)
        }

        // Sample patches & skin window
        val sampledPatches = CardSampler.samplePatches(canonicalCard)
        val skinSample = CardSampler.sampleSkinWindow(canonicalCard, thresholds)

        // 3. NONUNIFORM_PATCHES (Duplicate patches in opposite corners disagree)
        var maxDupDeltaE = 0.0
        for ((p1Id, p2Id) in CardGeometry.DUPLICATE_PAIRS) {
            val p1 = sampledPatches[p1Id]
            val p2 = sampledPatches[p2Id]
            if (p1 != null && p2 != null) {
                val dE = ciede2000(p1.lab, p2.lab)
                if (dE > maxDupDeltaE) maxDupDeltaE = dE
            }
        }
        if (maxDupDeltaE > thresholds.maxPatchNonuniformityDelta) {
            failureReasons.add(QualityReasonCode.NONUNIFORM_PATCHES)
        }

        // 4. BLUR (Laplacian variance below threshold)
        val blurVariance = computeLaplacianVariance(canonicalCard)
        if (blurVariance < thresholds.minBlurLaplacianVariance) {
            failureReasons.add(QualityReasonCode.BLUR)
        }

        // 5. GLARE (Fraction of saturated pixels on any patch or skin window > threshold)
        var maxGlareFraction = skinSample.saturatedPixelFraction
        for (patch in sampledPatches.values) {
            if (patch.saturatedPixelFraction > maxGlareFraction) {
                maxGlareFraction = patch.saturatedPixelFraction
            }
        }
        if (maxGlareFraction > thresholds.maxGlarePixelFraction) {
            failureReasons.add(QualityReasonCode.GLARE)
        }

        // 6 & 7. EXPOSURE (Mid-gray patch G4 luminance outside usable range)
        val g4Patch = sampledPatches["G4"]
        val midGrayLum = (g4Patch?.trimmedMeanRgb?.luminance() ?: 0.0) * 255.0
        if (midGrayLum < thresholds.minExposureMidGray) {
            failureReasons.add(QualityReasonCode.UNDEREXPOSED)
        } else if (midGrayLum > thresholds.maxExposureMidGray) {
            failureReasons.add(QualityReasonCode.OVEREXPOSED)
        }

        // 8. UNEVEN_LIGHT (Difference between gray patches at opposite corners G2 vs G2D, G4 vs G4D)
        val g2 = (sampledPatches["G2"]?.trimmedMeanRgb?.luminance() ?: 0.0) * 255.0
        val g2d = (sampledPatches["G2D"]?.trimmedMeanRgb?.luminance() ?: 0.0) * 255.0
        val g4 = (sampledPatches["G4"]?.trimmedMeanRgb?.luminance() ?: 0.0) * 255.0
        val g4d = (sampledPatches["G4D"]?.trimmedMeanRgb?.luminance() ?: 0.0) * 255.0

        val deltaG2 = StrictMath.abs(g2 - g2d)
        val deltaG4 = StrictMath.abs(g4 - g4d)
        // Normalized delta in Delta L* units (~2.55 lum units per L*)
        val maxLightDeltaL = StrictMath.max(deltaG2, deltaG4) / PERCENT_TO_BYTE_SCALE
        if (maxLightDeltaL > thresholds.maxLightUniformityDelta) {
            failureReasons.add(QualityReasonCode.UNEVEN_LIGHT)
        }

        // 9. HIGH_RESIDUAL (Mean Delta E 2000 of corrected patches vs reference > threshold)
        val nominalRef = CardGeometry.PATCHES.mapValues { it.value.nominalLab }
        val referenceMap = batchReference ?: nominalRef

        var meanResidualDeltaE = 0.0
        try {
            // Fit 3x3 color correction matrix from sampled patches to batch reference
            val observedLinearRgbList = mutableListOf<Rgb>()
            val targetXyzList = mutableListOf<com.kitalonlabs.neogold.engine.colorscience.Xyz>()
            val referenceLabs = mutableListOf<Lab>()

            for ((id, patch) in sampledPatches) {
                val refLab = referenceMap[id] ?: continue
                observedLinearRgbList.add(ColorConversions.sRgbToLinear(patch.trimmedMeanRgb))
                targetXyzList.add(ColorConversions.labToXyz(refLab))
                referenceLabs.add(refLab)
            }

            if (observedLinearRgbList.size >= 3) {
                val correctionModel = ColorCorrectionFitter.fit(
                    observedLinearRgbList,
                    targetXyzList,
                    useRootPolynomial = false
                )
                val residual = QualityResidualCalculator.computeResidual(
                    correctionModel,
                    observedLinearRgbList,
                    referenceLabs
                )
                meanResidualDeltaE = residual.meanDeltaE
            }
        } catch (_: Exception) {
            meanResidualDeltaE = NO_RESIDUAL_DELTA_E
        }

        if (meanResidualDeltaE > thresholds.maxCorrectionResidualDeltaE) {
            failureReasons.add(QualityReasonCode.HIGH_RESIDUAL)
        }

        // 10. WINDOW_NOT_SKIN (Skin plausibility check fails)
        if (!skinSample.isPlausibleSkin) {
            failureReasons.add(QualityReasonCode.WINDOW_NOT_SKIN)
        }

        val passed = failureReasons.isEmpty()
        val metrics = QualityMetrics(
            markersDetected = detectionResult.markers.size,
            cardAngleDegrees = detectionResult.cardAngleDegrees,
            duplicatePatchDeltaE = maxDupDeltaE,
            blurLaplacianVariance = blurVariance,
            glarePixelFraction = maxGlareFraction,
            midGrayLuminance = midGrayLum,
            lightUniformityDelta = maxLightDeltaL,
            meanCorrectionResidualDeltaE = meanResidualDeltaE,
            skinWindowSample = skinSample
        )

        return QualityGateResult(
            passed = passed,
            failureReasons = failureReasons,
            metrics = metrics,
            canonicalImage = canonicalCard,
            sampledPatches = sampledPatches.mapValues { it.value.trimmedMeanRgb },
            windowRgb = skinSample.trimmedMeanRgb,
            windowLab = skinSample.meanLab
        )
    }
}

private const val PERCENT_TO_BYTE_SCALE = 2.55

/**
 * Residual reported when fitting the correction throws, so HIGH_RESIDUAL always fires. With fewer than three
 * matched reference patches no residual is computed at all and the check does not fire (BUG-2026-048).
 */
private const val NO_RESIDUAL_DELTA_E = 999.0
