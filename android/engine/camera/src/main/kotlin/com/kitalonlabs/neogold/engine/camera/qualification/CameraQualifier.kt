package com.kitalonlabs.neogold.engine.camera.qualification

import com.kitalonlabs.neogold.engine.camera.device.DeviceCapabilities
import com.kitalonlabs.neogold.engine.colorscience.ColorConversions
import com.kitalonlabs.neogold.engine.colorscience.ColorCorrectionFitter
import com.kitalonlabs.neogold.engine.colorscience.Lab
import com.kitalonlabs.neogold.engine.colorscience.QualityResidualCalculator
import com.kitalonlabs.neogold.engine.colorscience.Rgb
import com.kitalonlabs.neogold.engine.colorscience.ciede2000
import com.kitalonlabs.neogold.engine.vision.CardGeometry
import com.kitalonlabs.neogold.engine.vision.ImageFrame
import com.kitalonlabs.neogold.engine.vision.VisionEngine
import java.util.UUID

/**
 * Camera Qualification and Color Calibration engine (doc 4 Section 5.1, ADR-012).
 *
 * Runs the empirical qualification test on 3 photos of the card:
 * 1. Repeatability: Mean Delta E 2000 between corrected patches across photos < 1.5
 * 2. Uniformity: Difference between duplicate corner patches < 2.0 (catches local tone mapping / HDR)
 * 3. Accuracy: Mean Delta E 2000 vs batch reference < 3.0
 *
 * Result:
 * - QUALIFIED: eligible for screening
 * - RESEARCH_ONLY: limited to research mode (TC-DEV-05)
 *
 * Missing AE/AWB lock does NOT fail a phone (ADR-012, TC-DEV-06).
 * Re-qualification required after 30 days or OS build change (NG-044, TC-DEV-07).
 */
class CameraQualifier(
    val thresholds: QualificationThresholds = QualificationThresholds(),
    private val visionEngine: VisionEngine = VisionEngine()
) {

    companion object {
        const val REQUALIFICATION_INTERVAL_MS = 30L * 24L * 60L * 60L * 1000L // 30 days
    }

    /**
     * Checks if a device profile requires re-qualification (NG-044, TC-DEV-07).
     *
     * @param profile Stored device profile.
     * @param currentTimeMs Current system epoch ms.
     * @param currentFingerprint Current OS build fingerprint.
     */
    fun checkRequalification(
        profile: DeviceProfile,
        currentTimeMs: Long = System.currentTimeMillis(),
        currentFingerprint: String
    ): RequalificationReason? {
        if (currentTimeMs - profile.calibratedAt >= REQUALIFICATION_INTERVAL_MS) {
            return RequalificationReason.EXPIRED_30_DAYS
        }
        if (profile.buildFingerprint != currentFingerprint) {
            return RequalificationReason.OS_BUILD_CHANGED
        }
        return null
    }

    /**
     * Qualifies a camera using 3 photos of the card.
     *
     * @param photos Three photos taken of the card.
     * @param batchReference Measured spectrophotometer reference targets for the card batch.
     * @param capabilities Hardware capabilities reported by DeviceChecker.
     * @param cardBatchId ID of the card batch used.
     * @param appVersion Version string of NeoGold.
     * @param currentTimeMs Timestamp of qualification.
     */
    fun qualifyCamera(
        photos: List<ImageFrame>,
        batchReference: Map<String, Lab> = CardGeometry.PATCHES.mapValues { it.value.nominalLab },
        capabilities: DeviceCapabilities,
        cardBatchId: String,
        appVersion: String = "1.0.0",
        currentTimeMs: Long = System.currentTimeMillis()
    ): QualificationResult {
        require(photos.size == 3) { "Qualification requires exactly 3 photos (got ${photos.size})" }

        val failureReasons = mutableListOf<String>()

        // 1. Process each frame through VisionEngine
        val patchSets = mutableListOf<Map<String, Lab>>()
        val uniformityDeltas = mutableListOf<Double>()
        val accuracyDeltas = mutableListOf<Double>()

        for (i in 0 until 3) {
            val qgResult = visionEngine.processFrame(photos[i], batchReference)
            val sampled = qgResult.sampledPatches
            if (!qgResult.passed || sampled == null) {
                failureReasons.add("Photo ${i + 1} failed quality gate: ${qgResult.failureReasons}")
            }

            // Convert sampled RGB to Lab, with linear matrix fit to batch reference
            val photoPatches = mutableMapOf<String, Lab>()
            if (sampled != null) {
                val observedLinearRgbList = mutableListOf<Rgb>()
                val targetXyzList = mutableListOf<com.kitalonlabs.neogold.engine.colorscience.Xyz>()
                val referenceLabs = mutableListOf<Lab>()

                for ((id, rgb) in sampled) {
                    val targetLab = batchReference[id] ?: continue
                    val linearRgb = ColorConversions.sRgbToLinear(rgb)
                    observedLinearRgbList.add(linearRgb)
                    targetXyzList.add(ColorConversions.labToXyz(targetLab))
                    referenceLabs.add(targetLab)
                }

                val model = try {
                    if (observedLinearRgbList.size >= 3) {
                        ColorCorrectionFitter.fit(observedLinearRgbList, targetXyzList, useRootPolynomial = false)
                    } else {
                        null
                    }
                } catch (_: Exception) {
                    null
                }

                for ((id, rgb) in sampled) {
                    val linearRgb = ColorConversions.sRgbToLinear(rgb)
                    val correctedLab = if (model != null) {
                        val correctedXyz = model.correct(linearRgb)
                        ColorConversions.xyzToLab(correctedXyz)
                    } else {
                        val xyz = ColorConversions.sRgbToXyz(rgb)
                        ColorConversions.xyzToLab(xyz)
                    }
                    photoPatches[id] = correctedLab
                }

                // Compute duplicate patch uniformity for this photo
                var maxDupDeltaE = 0.0
                for ((p1, p2) in CardGeometry.DUPLICATE_PAIRS) {
                    val lab1 = photoPatches[p1]
                    val lab2 = photoPatches[p2]
                    if (lab1 != null && lab2 != null) {
                        val dE = ciede2000(lab1, lab2)
                        if (dE > maxDupDeltaE) maxDupDeltaE = dE
                    }
                }
                uniformityDeltas.add(maxDupDeltaE)

                // Compute accuracy for this photo vs batch reference
                val meanAcc = if (model != null && referenceLabs.isNotEmpty()) {
                    val residual = QualityResidualCalculator.computeResidual(
                        model,
                        observedLinearRgbList,
                        referenceLabs
                    )
                    residual.meanDeltaE
                } else {
                    var sumAccDeltaE = 0.0
                    var accCount = 0
                    for ((id, refLab) in batchReference) {
                        val patchLab = photoPatches[id] ?: continue
                        sumAccDeltaE += ciede2000(patchLab, refLab)
                        accCount++
                    }
                    if (accCount > 0) sumAccDeltaE / accCount else 99.0
                }
                accuracyDeltas.add(meanAcc)
            } else {
                uniformityDeltas.add(99.0)
                accuracyDeltas.add(99.0)
            }
            patchSets.add(photoPatches)
        }

        // 2. Compute Repeatability across the 3 photos
        // Mean Delta E 2000 between corrected patches across photos (0-1, 1-2, 0-2)
        var sumRepDeltaE = 0.0
        var repCount = 0

        val photoPairs = listOf(Pair(0, 1), Pair(1, 2), Pair(0, 2))
        for ((idxA, idxB) in photoPairs) {
            val setA = patchSets[idxA]
            val setB = patchSets[idxB]
            for (patchId in CardGeometry.PATCHES.keys) {
                val labA = setA[patchId]
                val labB = setB[patchId]
                if (labA != null && labB != null) {
                    sumRepDeltaE += ciede2000(labA, labB)
                    repCount++
                }
            }
        }
        val repeatabilityDe = if (repCount > 0) sumRepDeltaE / repCount else 99.0

        // 3. Overall Uniformity (max or mean across the 3 photos)
        val uniformityDe = if (uniformityDeltas.isNotEmpty()) uniformityDeltas.maxOrNull() ?: 99.0 else 99.0

        // 4. Overall Accuracy (mean across 3 photos)
        val accuracyDe = if (accuracyDeltas.isNotEmpty()) accuracyDeltas.average() else 99.0

        // 5. Evaluate thresholds
        val repPassed = repeatabilityDe < thresholds.maxRepeatabilityDeltaE
        val uniPassed = uniformityDe < thresholds.maxUniformityDeltaE
        val accPassed = accuracyDe < thresholds.maxAccuracyDeltaE

        if (!repPassed) {
            failureReasons.add(
                "Repeatability Delta E ($repeatabilityDe) exceeds threshold ${thresholds.maxRepeatabilityDeltaE}"
            )
        }
        if (!uniPassed) {
            failureReasons.add(
                "Duplicate patch uniformity Delta E ($uniformityDe) exceeds threshold ${thresholds.maxUniformityDeltaE}"
            )
        }
        if (!accPassed) {
            failureReasons.add(
                "Calibration accuracy Delta E ($accuracyDe) exceeds threshold ${thresholds.maxAccuracyDeltaE}"
            )
        }

        val status = if (repPassed && uniPassed && accPassed && failureReasons.isEmpty()) {
            QualificationStatus.QUALIFIED
        } else {
            QualificationStatus.RESEARCH_ONLY
        }

        val profile = DeviceProfile(
            id = UUID.randomUUID().toString(),
            manufacturer = capabilities.manufacturer,
            deviceModel = capabilities.deviceModel,
            androidApi = capabilities.androidApi,
            buildFingerprint = capabilities.buildFingerprint,
            cameraId = capabilities.cameraId,
            ramMb = capabilities.ramMb,
            isLowRamDevice = capabilities.isLowRamDevice,
            cameraHardwareLevel = capabilities.cameraHardwareLevel,
            supportsAeLock = capabilities.supportsAeLock,
            supportsAwbLock = capabilities.supportsAwbLock,
            supportsManualSensor = capabilities.supportsManualSensor,
            supportsRaw = capabilities.supportsRaw,
            residualCorrectionJson = null,
            repeatabilityDe = repeatabilityDe,
            uniformityDe = uniformityDe,
            calibrationResidualDe = accuracyDe,
            qualification = status,
            cardBatchId = cardBatchId,
            calibratedAt = currentTimeMs,
            appVersion = appVersion,
            status = DeviceProfileStatus.ACTIVE
        )

        val auditEvent = QualificationAuditEvent(
            eventType = if (status == QualificationStatus.QUALIFIED) "DEVICE_QUALIFIED" else "DEVICE_RESEARCH_ONLY",
            timestampMs = currentTimeMs,
            deviceModel = capabilities.deviceModel,
            hardwareLevel = capabilities.cameraHardwareLevel.name,
            repeatabilityDe = repeatabilityDe,
            uniformityDe = uniformityDe,
            calibrationResidualDe = accuracyDe,
            qualification = status
        )

        return QualificationResult(
            status = status,
            profile = profile,
            repeatabilityDe = repeatabilityDe,
            uniformityDe = uniformityDe,
            accuracyDe = accuracyDe,
            repeatabilityPassed = repPassed,
            uniformityPassed = uniPassed,
            accuracyPassed = accPassed,
            auditEvent = auditEvent,
            failureReasons = failureReasons
        )
    }
}
