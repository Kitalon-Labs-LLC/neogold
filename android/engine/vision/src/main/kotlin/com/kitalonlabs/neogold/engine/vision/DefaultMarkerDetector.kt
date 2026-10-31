package com.kitalonlabs.neogold.engine.vision

import java.util.concurrent.atomic.AtomicInteger

/**
 * The marker detector the app uses (ADR-018): OpenCV's ArUco detector when its native library loads, otherwise the
 * pure Kotlin [FastMarkerDetector].
 *
 * When OpenCV is there its answer is final. Fewer than four markers means the photo is retaken, and a frame on which
 * OpenCV fails (an exception, or a linkage error from the native code) counts as "no markers" too: the Kotlin
 * detector is looser (it accepts a one-bit error, mirrored cards and the AprilTag family, none of which the printed
 * card needs) and has only been tried on synthetic frames, so it never rescues a frame OpenCV rejected. An
 * out-of-memory error is not caught.
 *
 * The Kotlin detector runs only when OpenCV is missing altogether: on the JVM, or on an ABI the library was not
 * built for. On a supported phone that is an error state, not a mode: [usesFallback] tells the screening flow, which
 * must block the screening and record that the fallback ran (package E1).
 */
class DefaultMarkerDetector(
    private val primary: MarkerDetector? = openCvOrNull(),
    private val fallback: MarkerDetector = FastMarkerDetector()
) : MarkerDetector {

    private val failures = AtomicInteger()

    /** True when OpenCV is missing and the Kotlin detector does the work. */
    val usesFallback: Boolean get() = primary == null

    /**
     * Frames on which OpenCV failed and which were counted as "no markers". One now and then is a bad frame; a count
     * that keeps rising means OpenCV is broken on this phone and every photo will be refused (package E1 flags it).
     */
    val failedFrames: Int get() = failures.get()

    @Suppress("TooGenericExceptionCaught") // OpenCV reports a failed frame as a CvException, a RuntimeException
    override fun detectMarkers(frame: ImageFrame): List<DetectedMarker> {
        if (primary == null) return fallback.detectMarkers(frame)
        return try {
            primary.detectMarkers(frame)
        } catch (_: Exception) {
            failedFrame()
        } catch (_: LinkageError) {
            failedFrame()
        }
    }

    private fun failedFrame(): List<DetectedMarker> {
        failures.incrementAndGet()
        return emptyList()
    }

    private companion object {
        @Suppress("TooGenericExceptionCaught") // creating the native detector can fail; then there is no OpenCV
        fun openCvOrNull(): MarkerDetector? = try {
            if (OpenCvMarkerDetector.isAvailable()) OpenCvMarkerDetector() else null
        } catch (_: Exception) {
            null
        } catch (_: LinkageError) {
            null
        }
    }
}
