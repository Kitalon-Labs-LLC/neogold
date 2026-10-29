package com.kitalonlabs.neogold.engine.vision

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class VisionEngineTest {

    private val renderer = SyntheticCardRenderer()
    private val detector = FastMarkerDetector()
    private val engine = VisionEngine()

    @Test
    fun testFaceOnCardDetection() {
        val frame = renderer.renderFrame(SyntheticRenderConfig(scale = 1.0, tiltDegrees = 0.0, rotationDegrees = 0.0))
        val markers = detector.detectMarkers(frame)
        println("Detected markers count: ${markers.size}")
        for (m in markers) {
            println("Marker ${m.id} at center (${m.center.x}, ${m.center.y})")
        }
        val result = engine.processFrame(frame)
        println("Quality gate passed: ${result.passed}, failures: ${result.failureReasons}")
        assertThat(result.metrics.markersDetected).isAtLeast(4)
        assertThat(result.passed).isTrue()
    }

    @Test
    fun aMarkerIdReportedTwiceCountsAsMissingWhicheverDetectorReportsIt() {
        // Two cards in view: a second marker 1 turns up next to the real set. The engine must not pick one of them.
        val frame = renderer.renderFrame(SyntheticRenderConfig())
        val real = detector.detectMarkers(frame)
        val strangerCorners = real.first { it.id == 1 }.corners.map { Point2D(it.x + 5.0, it.y + 5.0) }
        val strangerCenter = Point2D(strangerCorners.sumOf { it.x } / 4.0, strangerCorners.sumOf { it.y } / 4.0)
        val stranger = DetectedMarker(1, strangerCorners, strangerCenter)
        val twoCards = object : MarkerDetector {
            override fun detectMarkers(frame: ImageFrame) = real + stranger
        }

        val result = VisionEngine(markerDetector = twoCards).processFrame(frame)

        assertThat(real.map { it.id }).containsAtLeast(0, 1, 2, 3)
        assertThat(result.passed).isFalse()
        assertThat(result.failureReasons).contains(QualityReasonCode.NO_CARD)
        assertThat(result.metrics.markersDetected).isEqualTo(real.map { it.id }.toSet().size - 1)
    }
}
