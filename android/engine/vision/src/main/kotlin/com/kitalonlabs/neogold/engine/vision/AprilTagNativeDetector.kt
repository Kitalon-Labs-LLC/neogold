package com.kitalonlabs.neogold.engine.vision

/**
 * JNI wrapper for the native AprilTag 36h11 detector in C.
 * Provides maximum speed and minimal memory footprint, with 16 KB ELF alignment.
 */
class AprilTagNativeDetector : MarkerDetector {

    companion object {
        private var isLoaded = false

        init {
            try {
                System.loadLibrary("apriltag_jni")
                isLoaded = true
            } catch (_: Throwable) {
                isLoaded = false
            }
        }

        fun isAvailable(): Boolean = isLoaded
    }

    private external fun nativeDetect(
        pixels: IntArray,
        width: Int,
        height: Int
    ): DoubleArray?

    override fun detectMarkers(frame: ImageFrame): List<DetectedMarker> {
        if (!isLoaded) return emptyList()

        val raw = try {
            nativeDetect(frame.data, frame.width, frame.height)
        } catch (_: Throwable) {
            null
        } ?: return emptyList()

        if (raw.isEmpty()) return emptyList()

        val count = raw[0].toInt()
        val result = mutableListOf<DetectedMarker>()
        var offset = 1

        for (i in 0 until count) {
            if (offset + 10 >= raw.size) break
            val id = raw[offset].toInt()
            val corners = listOf(
                Point2D(raw[offset + 1], raw[offset + 2]),
                Point2D(raw[offset + 3], raw[offset + 4]),
                Point2D(raw[offset + 5], raw[offset + 6]),
                Point2D(raw[offset + 7], raw[offset + 8])
            )
            val center = Point2D(raw[offset + 9], raw[offset + 10])
            result.add(DetectedMarker(id, corners, center))
            offset += 11
        }

        return result
    }
}

/**
 * Default marker detector that prefers native AprilTag when available,
 * and falls back to pure Kotlin FastMarkerDetector.
 */
class DefaultMarkerDetector : MarkerDetector {
    private val nativeDetector = if (AprilTagNativeDetector.isAvailable()) AprilTagNativeDetector() else null
    private val kotlinDetector = FastMarkerDetector()

    override fun detectMarkers(frame: ImageFrame): List<DetectedMarker> {
        if (nativeDetector != null) {
            val detected = nativeDetector.detectMarkers(frame)
            if (detected.size >= 4) {
                return detected
            }
        }
        return kotlinDetector.detectMarkers(frame)
    }
}
