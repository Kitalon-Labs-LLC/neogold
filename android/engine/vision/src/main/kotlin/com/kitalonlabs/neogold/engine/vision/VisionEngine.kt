package com.kitalonlabs.neogold.engine.vision

import com.kitalonlabs.neogold.engine.colorscience.Lab

/**
 * NeoGold Vision Engine (P08).
 * Processes a single camera frame:
 * 1. Finds 4 corner fiducial markers.
 * 2. Computes perspective homography and warps to canonical 1200x760 px image.
 * 3. Samples 23 color patches and punched skin window (trimmed means).
 * 4. Runs 10-step Quality Gate in Section 5.3 order.
 *
 * Buffer reuse guarantees one frame at a time, bounded memory footprint (NFR-04b).
 */
class VisionEngine(
    val thresholds: QualityThresholds = QualityThresholds(),
    val markerDetector: MarkerDetector = DefaultMarkerDetector()
) {
    // Single-frame reusable warper buffer (1200 * 760 ints = 3.65 MB)
    private val canonicalBuffer = IntArray(CardGeometry.CANONICAL_WIDTH * CardGeometry.CANONICAL_HEIGHT)
    private val warper = CardWarper(canonicalBuffer)

    // Memory tracking
    private var peakAllocationBytes: Long = (canonicalBuffer.size * 4).toLong()

    fun getPeakAllocationBytes(): Long = peakAllocationBytes

    /**
     * Process a single camera frame.
     *
     * @param frame Downscaled camera capture frame (long edge ~1600 px).
     * @param batchReference Optional batch spectrophotometer reference values (for residual check).
     */
    fun processFrame(
        frame: ImageFrame,
        batchReference: Map<String, Lab>? = null
    ): QualityGateResult {
        // Track memory
        val frameBytes = (frame.data.size * 4).toLong()
        val currentTotal = (canonicalBuffer.size * 4).toLong() + frameBytes
        if (currentTotal > peakAllocationBytes) {
            peakAllocationBytes = currentTotal
        }

        // 1. Detect corner markers
        val detectedList = markerDetector.detectMarkers(frame)
        val markerMap = detectedList.associateBy { it.id }

        // Check if all 4 corner markers (0=TL, 1=TR, 2=BR, 3=BL) are found
        val hasAllFour = markerMap.keys.containsAll(listOf(0, 1, 2, 3))

        if (!hasAllFour) {
            val emptyDetection = CardDetectionResult(
                markers = markerMap,
                homographyMatrix = null,
                cardAngleDegrees = 0.0
            )
            return VisionQualityGate.evaluate(
                detectionResult = emptyDetection,
                canonicalCard = null,
                thresholds = thresholds,
                batchReference = batchReference
            )
        }

        // 2. Order detected points to match canonical marker centers
        val srcCenters = listOf(
            markerMap.getValue(0).center, // TL
            markerMap.getValue(1).center, // TR
            markerMap.getValue(2).center, // BR
            markerMap.getValue(3).center // BL
        )

        val dstCenters = listOf(
            CardGeometry.MARKER_CENTERS.getValue(0), // (110, 110)
            CardGeometry.MARKER_CENTERS.getValue(1), // (1090, 110)
            CardGeometry.MARKER_CENTERS.getValue(2), // (1090, 650)
            CardGeometry.MARKER_CENTERS.getValue(3) // (110, 650)
        )

        // 3. Estimate homography & tilt angle
        val tiltAngleDeg = Homography.estimateTiltAngleDegrees(srcCenters)

        val H_canon_to_frame = try {
            Homography.findHomography(dstCenters, srcCenters)
        } catch (_: Exception) {
            val failedDetection = CardDetectionResult(
                markers = markerMap,
                homographyMatrix = null,
                cardAngleDegrees = tiltAngleDeg
            )
            return VisionQualityGate.evaluate(
                detectionResult = failedDetection,
                canonicalCard = null,
                thresholds = thresholds,
                batchReference = batchReference
            )
        }

        val detectionResult = CardDetectionResult(
            markers = markerMap,
            homographyMatrix = H_canon_to_frame.m,
            cardAngleDegrees = tiltAngleDeg
        )

        // 4. Warp frame to canonical 1200x760 px image
        // H_canon_to_frame maps canonical (u, v) -> frame (x, y) which is directly used for inverse warping
        val canonicalImage = warper.warpToCanonical(frame, H_canon_to_frame)

        // 5. Evaluate Quality Gate
        return VisionQualityGate.evaluate(
            detectionResult = detectionResult,
            canonicalCard = canonicalImage,
            thresholds = thresholds,
            batchReference = batchReference
        )
    }
}
