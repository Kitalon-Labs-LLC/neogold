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
}
