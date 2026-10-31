package com.kitalonlabs.neogold.engine.camera

import android.graphics.ImageFormat
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.kitalonlabs.neogold.engine.vision.DefaultMarkerDetector
import com.kitalonlabs.neogold.engine.vision.DetectedMarker
import com.kitalonlabs.neogold.engine.vision.Homography
import com.kitalonlabs.neogold.engine.vision.ImageFrame
import com.kitalonlabs.neogold.engine.vision.MarkerDetector
import com.kitalonlabs.neogold.engine.vision.Point2D
import java.nio.ByteBuffer

/**
 * Live preview card detection state.
 * Document 6 Section 7.3 (S16), Section 8, TC-CAP-01.
 */
data class LiveCardState(
    val detectedMarkerCount: Int,
    val detectedMarkers: Map<Int, DetectedMarker>,
    val allFourFound: Boolean,
    val cardAngleDegrees: Double,
    val steadyFraction: Float, // 0.0f to 1.0f (reaches 1.0f at 1000 ms)
    val isSteadyForOneSecond: Boolean,
    val readyForCapture: Boolean
) {
    companion object {
        val IDLE = LiveCardState(
            detectedMarkerCount = 0,
            detectedMarkers = emptyMap(),
            allFourFound = false,
            cardAngleDegrees = 0.0,
            steadyFraction = 0.0f,
            isSteadyForOneSecond = false,
            readyForCapture = false
        )
    }
}

/**
 * CameraX ImageAnalysis.Analyzer for live card detection on preview frames (640x480).
 * Fires auto-capture when all four markers are detected and the phone is steady for 1 second.
 * Doc 4 Section 5 capture pipeline, doc 6 S16, TC-CAP-01.
 */
class LiveCardAnalyzer(
    private val markerDetector: MarkerDetector = DefaultMarkerDetector(),
    private val maxMovementPx: Double = 18.0,
    private val steadyRequiredDurationMs: Long = 1000L,
    private val onStateChanged: (LiveCardState) -> Unit,
    private val onAutoCaptureTriggered: () -> Unit,
    private val clockMs: () -> Long = System::currentTimeMillis
) : ImageAnalysis.Analyzer {

    private var steadyStartTimeMs: Long = 0L
    private var lastCenter: Point2D? = null
    private var autoCaptureFired: Boolean = false

    override fun analyze(image: ImageProxy) {
        try {
            val frame = imageProxyToImageFrame(image)
            val markers = markerDetector.detectMarkers(frame)
            val markerMap = markers.associateBy { it.id }
            val hasAllFour = markerMap.keys.containsAll(listOf(0, 1, 2, 3))

            val now = clockMs()

            if (!hasAllFour) {
                steadyStartTimeMs = 0L
                lastCenter = null
                val state = LiveCardState(
                    detectedMarkerCount = markerMap.size,
                    detectedMarkers = markerMap,
                    allFourFound = false,
                    cardAngleDegrees = 0.0,
                    steadyFraction = 0.0f,
                    isSteadyForOneSecond = false,
                    readyForCapture = false
                )
                onStateChanged(state)
                return
            }

            // All 4 markers detected
            val tl = markerMap.getValue(0).center
            val tr = markerMap.getValue(1).center
            val br = markerMap.getValue(2).center
            val bl = markerMap.getValue(3).center

            val currentCenter = Point2D(
                (tl.x + tr.x + br.x + bl.x) / 4.0,
                (tl.y + tr.y + br.y + bl.y) / 4.0
            )

            val tiltAngle = Homography.estimateTiltAngleDegrees(listOf(tl, tr, br, bl))

            // Check stability relative to previous center
            val prevCenter = lastCenter
            val isSteadyPosition = if (prevCenter == null) {
                true
            } else {
                currentCenter.distanceTo(prevCenter) <= maxMovementPx
            }
            lastCenter = currentCenter

            if (isSteadyPosition) {
                if (steadyStartTimeMs == 0L) {
                    steadyStartTimeMs = now
                }
            } else {
                steadyStartTimeMs = now
            }

            val elapsedMs = now - steadyStartTimeMs
            val fraction = (elapsedMs.toFloat() / steadyRequiredDurationMs.toFloat()).coerceIn(0.0f, 1.0f)
            val isSteadyOneSec = fraction >= 1.0f

            val state = LiveCardState(
                detectedMarkerCount = 4,
                detectedMarkers = markerMap,
                allFourFound = true,
                cardAngleDegrees = tiltAngle,
                steadyFraction = fraction,
                isSteadyForOneSecond = isSteadyOneSec,
                readyForCapture = isSteadyOneSec
            )
            onStateChanged(state)

            if (isSteadyOneSec && !autoCaptureFired) {
                autoCaptureFired = true
                onAutoCaptureTriggered()
            }
        } finally {
            image.close()
        }
    }

    fun resetAutoCapture() {
        autoCaptureFired = false
        steadyStartTimeMs = 0L
        lastCenter = null
    }

    /**
     * Converts CameraX ImageProxy (YUV_420_888 or RGBA_8888) to an in-memory ImageFrame.
     */
    private fun imageProxyToImageFrame(image: ImageProxy): ImageFrame {
        val w = image.width
        val h = image.height

        if (image.format == ImageFormat.YUV_420_888) {
            // Fast Y-plane extraction for luminance + gray-to-ARGB
            val yBuffer: ByteBuffer = image.planes[0].buffer
            val yRowStride = image.planes[0].rowStride
            val pixels = IntArray(w * h)

            for (y in 0 until h) {
                val rowOffset = y * yRowStride
                for (x in 0 until w) {
                    val lum = yBuffer.get(rowOffset + x).toInt() and 0xFF
                    // ARGB with r=g=b=lum
                    pixels[y * w + x] = (0xFF shl 24) or (lum shl 16) or (lum shl 8) or lum
                }
            }
            return ImageFrame(w, h, pixels)
        } else {
            // Fallback for RGBA
            val buffer = image.planes[0].buffer
            val pixels = IntArray(w * h)
            val intBuffer = buffer.asIntBuffer()
            if (intBuffer.remaining() >= pixels.size) {
                intBuffer.get(pixels)
            }
            return ImageFrame(w, h, pixels)
        }
    }
}
