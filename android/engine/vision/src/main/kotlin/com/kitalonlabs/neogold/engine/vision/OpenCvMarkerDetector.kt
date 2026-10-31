@file:Suppress("MagicNumber") // BT.601 luma weights (299, 587, 114 per mille) and the four-corner marker layout

package com.kitalonlabs.neogold.engine.vision

import org.opencv.android.OpenCVLoader
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.objdetect.ArucoDetector
import org.opencv.objdetect.DetectorParameters
import org.opencv.objdetect.Objdetect

/**
 * ArUco 4x4 marker detector backed by OpenCV's `objdetect` module (ADR-018).
 *
 * The card carries four `DICT_4X4_50` markers, IDs 0 to 3 at TL, TR, BR, BL (card/spec/card-v1.json). Corners come
 * back in the marker's own clockwise order from its top-left, which is what [DetectedMarker] documents. The card
 * rules (IDs 0 to 3 only, an ID found twice is dropped, the centre is where the diagonals cross) live in
 * [selectCardMarkers], which the JVM tests cover.
 *
 * OpenCV's native library cannot load on the JVM, so unit tests see [isAvailable] = false and [detectMarkers]
 * returns an empty list. [DefaultMarkerDetector] decides what happens then. The first [isAvailable] call loads a
 * 25 MB library: make it off the main thread. A failed load is not retried until the process restarts.
 */
class OpenCvMarkerDetector : MarkerDetector {

    private val lock = Any()
    private val detector: ArucoDetector? = if (isAvailable()) createDetector() else null

    override fun detectMarkers(frame: ImageFrame): List<DetectedMarker> {
        val aruco = detector ?: return emptyList()
        val corners = ArrayList<Mat>()
        val ids = Mat()
        var gray: Mat? = null
        try {
            val mat = toGrayMat(frame)
            gray = mat
            synchronized(lock) { aruco.detectMarkers(mat, corners, ids) }
            return selectCardMarkers(readMarkers(corners, ids))
        } finally {
            gray?.release()
            ids.release()
            corners.forEach { it.release() }
        }
    }

    private fun readMarkers(corners: List<Mat>, ids: Mat): List<RawMarker> =
        corners.indices.map { i ->
            val xy = FloatArray(CORNER_VALUES)
            corners[i].get(0, 0, xy)
            RawMarker(
                id = ids.get(i, 0)[0].toInt(),
                corners = List(CORNERS_PER_MARKER) { Point2D(xy[2 * it].toDouble(), xy[2 * it + 1].toDouble()) }
            )
        }

    private fun toGrayMat(frame: ImageFrame): Mat {
        val n = frame.width * frame.height
        val gray = ByteArray(n)
        val px = frame.data
        for (i in 0 until n) {
            val argb = px[i]
            val r = (argb shr 16) and 0xFF
            val g = (argb shr 8) and 0xFF
            val b = argb and 0xFF
            gray[i] = ((r * 299 + g * 587 + b * 114) / 1000).toByte()
        }
        val mat = Mat(frame.height, frame.width, CvType.CV_8UC1)
        mat.put(0, 0, gray)
        return mat
    }

    private fun createDetector(): ArucoDetector {
        val params = DetectorParameters()
        params.set_cornerRefinementMethod(Objdetect.CORNER_REFINE_SUBPIX)
        return ArucoDetector(Objdetect.getPredefinedDictionary(Objdetect.DICT_4X4_50), params)
    }

    companion object {
        private const val CORNERS_PER_MARKER = 4
        private const val CORNER_VALUES = 2 * CORNERS_PER_MARKER

        // OpenCVLoader.initLocal() loads libopencv_java4.so. Off the JVM, or on an ABI the library was not built for,
        // that fails with an UnsatisfiedLinkError, which is a LinkageError and not an Exception. Anything else
        // (an out-of-memory error, say) is not a missing library and is left to propagate.
        @Suppress("TooGenericExceptionCaught")
        private val loaded: Boolean by lazy {
            try {
                OpenCVLoader.initLocal()
            } catch (_: Exception) {
                false
            } catch (_: LinkageError) {
                false
            }
        }

        /** True when OpenCV's native library loaded; false on the JVM and on an unsupported ABI. */
        fun isAvailable(): Boolean = loaded
    }
}
