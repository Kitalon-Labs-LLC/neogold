package com.kitalonlabs.neogold.engine.vision

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * The detector choice of ADR-018. OpenCV cannot load on the JVM, so these tests use stand-ins for it and check the
 * rule: OpenCV's answer is final when OpenCV is there, a failed frame counts as "no markers", and the Kotlin detector
 * covers only the absence of OpenCV, which [DefaultMarkerDetector.usesFallback] reports. OpenCV itself is exercised
 * on a device or emulator by OpenCvMarkerDetectorInstrumentedTest.
 */
class DefaultMarkerDetectorTest {

    private val frame = ImageFrame(4, 4, IntArray(16))

    private fun marker(id: Int) = DetectedMarker(
        id = id,
        corners = List(4) { Point2D(it.toDouble(), id.toDouble()) },
        center = Point2D(1.5, id.toDouble())
    )

    private class StubDetector(private val answer: () -> List<DetectedMarker>) : MarkerDetector {
        var calls = 0

        override fun detectMarkers(frame: ImageFrame): List<DetectedMarker> {
            calls++
            return answer()
        }
    }

    @Test
    fun usesTheKotlinDetectorWhenOpenCvIsNotThere() {
        val fallback = StubDetector { listOf(marker(0), marker(1)) }
        val detector = DefaultMarkerDetector(primary = null, fallback = fallback)

        val found = detector.detectMarkers(frame)

        assertThat(found.map { it.id }).containsExactly(0, 1).inOrder()
        assertThat(fallback.calls).isEqualTo(1)
        assertThat(detector.usesFallback).isTrue()
    }

    @Test
    fun trustsOpenCvEvenWhenItFindsFewerThanFourMarkers() {
        val primary = StubDetector { listOf(marker(0), marker(1), marker(2)) }
        val fallback = StubDetector { listOf(marker(0), marker(1), marker(2), marker(3)) }
        val detector = DefaultMarkerDetector(primary, fallback)

        val found = detector.detectMarkers(frame)

        assertThat(found.map { it.id }).containsExactly(0, 1, 2).inOrder()
        assertThat(fallback.calls).isEqualTo(0)
        assertThat(detector.usesFallback).isFalse()
    }

    @Test
    fun trustsOpenCvWhenItFindsNothing() {
        val primary = StubDetector { emptyList() }
        val fallback = StubDetector { listOf(marker(0), marker(1), marker(2), marker(3)) }

        assertThat(DefaultMarkerDetector(primary, fallback).detectMarkers(frame)).isEmpty()
        assertThat(fallback.calls).isEqualTo(0)
    }

    @Test
    fun aFrameOnWhichOpenCvThrowsCountsAsNoMarkersAndIsCounted() {
        val primary = StubDetector { throw IllegalStateException("OpenCV failed on this frame") }
        val fallback = StubDetector { listOf(marker(3)) }
        val detector = DefaultMarkerDetector(primary, fallback)

        assertThat(detector.detectMarkers(frame)).isEmpty()
        assertThat(detector.detectMarkers(frame)).isEmpty()

        assertThat(fallback.calls).isEqualTo(0)
        assertThat(detector.failedFrames).isEqualTo(2)
    }

    @Test
    fun aLinkageErrorFromOpenCvCountsAsNoMarkersAndIsCounted() {
        val primary = StubDetector { throw NoSuchMethodError("org.opencv.objdetect.ArucoDetector.detectMarkers") }
        val fallback = StubDetector { listOf(marker(2)) }
        val detector = DefaultMarkerDetector(primary, fallback)

        assertThat(detector.detectMarkers(frame)).isEmpty()

        assertThat(fallback.calls).isEqualTo(0)
        assertThat(detector.failedFrames).isEqualTo(1)
    }

    @Test
    fun aHealthyOpenCvLeavesTheFailureCountAtZero() {
        val detector = DefaultMarkerDetector(StubDetector { listOf(marker(0)) }, StubDetector { emptyList() })

        detector.detectMarkers(frame)

        assertThat(detector.failedFrames).isEqualTo(0)
    }

    @Test
    fun anOutOfMemoryErrorIsNotSwallowed() {
        val primary = StubDetector { throw OutOfMemoryError("frame too large") }
        val fallback = StubDetector { listOf(marker(0)) }
        val detector = DefaultMarkerDetector(primary, fallback)

        assertThrows(OutOfMemoryError::class.java) { detector.detectMarkers(frame) }
        assertThat(fallback.calls).isEqualTo(0)
    }

    @Test
    fun openCvIsNotAvailableOnTheJvmAndFindsNothing() {
        assertThat(OpenCvMarkerDetector.isAvailable()).isFalse()
        assertThat(OpenCvMarkerDetector().detectMarkers(frame)).isEmpty()
    }

    @Test
    fun theDefaultDetectorOnTheJvmIsTheKotlinOneAndFindsTheCardMarkers() {
        val cardFrame = SyntheticCardRenderer().renderFrame(SyntheticRenderConfig(markerFamily = "ARUCO_4X4"))
        val detector = DefaultMarkerDetector()

        assertThat(detector.usesFallback).isTrue()
        assertThat(detector.detectMarkers(cardFrame).map { it.id }).containsAtLeast(0, 1, 2, 3)
    }
}
