package com.kitalonlabs.neogold.feature.research.capture

import com.kitalonlabs.neogold.engine.camera.DownsampledImageDecoder
import com.kitalonlabs.neogold.engine.camera.host.CameraSession
import com.kitalonlabs.neogold.engine.camera.model.CaptureMetadata
import com.kitalonlabs.neogold.engine.camera.research.ResearchJpegSanitizer
import com.kitalonlabs.neogold.engine.colorscience.Lab
import com.kitalonlabs.neogold.engine.vision.QualityReasonCode
import com.kitalonlabs.neogold.engine.vision.VisionEngine
import com.kitalonlabs.neogold.feature.research.data.ResearchCaptureInput
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The photo, after it was taken: what to keep, and how the quality gate saw it. */
class ProcessedCapture(
    val input: ResearchCaptureInput,
    /** Why the quality gate would refuse the photo, for the screen to offer a retake. Empty when it passed. */
    val qualityProblems: List<QualityReasonCode>
)

/** The photo is larger than the study server accepts, even after the metadata was removed. */
class CaptureTooLargeException : Exception("The photo is larger than the upload limit")

/**
 * Takes one research photo with an open camera and turns it into the record that is kept (R04, FR-27, NG-030).
 *
 * The photo is taken as a JPEG and goes through three steps, all in memory: the quality gate runs on a downsampled copy
 * so the record says whether the photo passed and why not; the JPEG is rewritten so that it carries nothing but its
 * orientation (no location, no time, no device name: doc 8 Section 6.4); and its size is checked against the upload
 * limit. No estimate is made or kept: research is blinded (FR-27).
 *
 * @param reference the colors of the card batch in use, for the quality gate's residual check; null when none is known
 */
class ResearchCaptureProcessor(
    private val vision: VisionEngine,
    private val reference: Map<String, Lab>?,
    private val cpu: CoroutineDispatcher = Dispatchers.Default
) {

    @Throws(CaptureTooLargeException::class)
    suspend fun capture(session: CameraSession, site: String, attemptNumber: Int, flash: Boolean): ProcessedCapture {
        val shot = session.captureStill(flash)
        return withContext(cpu) {
            val gate = vision.processFrame(DownsampledImageDecoder.decodeDownsampled(shot.jpeg), reference)
            val clean = ResearchJpegSanitizer.sanitize(shot.jpeg)
            if (clean.size > MAX_IMAGE_BYTES) throw CaptureTooLargeException()
            ProcessedCapture(
                input = ResearchCaptureInput(
                    site = site,
                    attemptNumber = attemptNumber,
                    imageBytes = clean,
                    qualityPassed = gate.passed,
                    qualityFailReasons = gate.failureReasons.map { it.name },
                    // the residual is measured only when the card was found and there was a batch to compare it with
                    correctionResidualDe = gate.metrics.meanCorrectionResidualDeltaE
                        .takeIf { reference != null && gate.metrics.markersDetected >= CARD_MARKERS },
                    iso = shot.metadata.sensorSensitivity,
                    exposureNs = shot.metadata.exposureTimeNs,
                    aeLocked = shot.metadata.aeLocked.toInt(),
                    awbLocked = shot.metadata.awbLocked.toInt(),
                    colorGains = shot.metadata.gainsJson()
                ),
                qualityProblems = gate.failureReasons
            )
        }
    }

    private fun Boolean.toInt() = if (this) 1 else 0

    private fun CaptureMetadata.gainsJson(): String? =
        colorCorrectionGains?.joinToString(prefix = "[", postfix = "]", separator = ",")

    companion object {
        /** The study server's `max_image_bytes` (doc 7 Section 5.1). */
        const val MAX_IMAGE_BYTES = 5_000_000
        private const val CARD_MARKERS = 4
    }
}
