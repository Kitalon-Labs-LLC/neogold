package com.kitalonlabs.neogold.engine.camera

import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Rect
import androidx.camera.core.ImageInfo
import androidx.camera.core.ImageProxy
import androidx.camera.core.impl.TagBundle
import androidx.camera.core.impl.utils.ExifData
import com.google.common.truth.Truth.assertThat
import com.kitalonlabs.neogold.engine.vision.ImageFrame
import com.kitalonlabs.neogold.engine.vision.SyntheticCardRenderer
import com.kitalonlabs.neogold.engine.vision.SyntheticRenderConfig
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.nio.ByteBuffer

/**
 * TC-CAP-01 (BUG-2026-037): the live analyzer is driven with real camera-style frames (YUV, a rendered card) and a
 * controlled clock, and auto-capture is shown to fire only when all four markers are found and the card has been steady
 * for the required time. The earlier version of this test never called the analyzer.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LiveCardAnalyzerTest {

    private val renderer = SyntheticCardRenderer()
    private val cardFrame: ImageFrame by lazy { renderer.renderFrame(SyntheticRenderConfig()) }
    private val blankFrame = ImageFrame(1600, 1200, IntArray(1600 * 1200) { 0xFF808080.toInt() })

    private class FakePlane(private val bytes: ByteArray, private val stride: Int) : ImageProxy.PlaneProxy {
        override fun getRowStride() = stride
        override fun getPixelStride() = 1
        override fun getBuffer(): ByteBuffer = ByteBuffer.wrap(bytes)
    }

    private class FakeImage(
        private val frame: ImageFrame,
        private val shiftX: Int = 0
    ) : ImageProxy {
        var closed = false
        private val luminance = ByteArray(frame.width * frame.height).also { out ->
            for (y in 0 until frame.height) {
                for (x in 0 until frame.width) {
                    val sourceX = (x - shiftX).coerceIn(0, frame.width - 1)
                    val argb = frame.data[y * frame.width + sourceX]
                    val r = (argb shr 16) and 0xFF
                    val g = (argb shr 8) and 0xFF
                    val b = argb and 0xFF
                    out[y * frame.width + x] = ((299 * r + 587 * g + 114 * b) / 1000).toByte()
                }
            }
        }

        override fun close() { closed = true }
        override fun getCropRect() = Rect(0, 0, frame.width, frame.height)
        override fun setCropRect(rect: Rect?) = Unit
        override fun getFormat() = ImageFormat.YUV_420_888
        override fun getHeight() = frame.height
        override fun getWidth() = frame.width
        override fun getPlanes(): Array<ImageProxy.PlaneProxy> = arrayOf(FakePlane(luminance, frame.width))
        override fun getImageInfo(): ImageInfo = object : ImageInfo {
            override fun getTagBundle(): TagBundle = TagBundle.emptyBundle()
            override fun getTimestamp() = 0L
            override fun getRotationDegrees() = 0
            override fun getSensorToBufferTransformMatrix() = Matrix()
            override fun populateExifData(exifBuilder: ExifData.Builder) = Unit
        }

        @androidx.annotation.OptIn(androidx.camera.core.ExperimentalGetImage::class)
        override fun getImage(): android.media.Image? = null
    }

    private class Recorder {
        var clock = 10_000L
        val states = mutableListOf<LiveCardState>()
        var captures = 0
    }

    private fun analyzer(recorder: Recorder) = LiveCardAnalyzer(
        steadyRequiredDurationMs = 1_000L,
        onStateChanged = { recorder.states.add(it) },
        onAutoCaptureTriggered = { recorder.captures++ },
        clockMs = { recorder.clock }
    )

    @Test
    fun aFrameWithoutTheCardNeverTriggersACapture_TC_CAP_01() {
        val recorder = Recorder()
        val analyzer = analyzer(recorder)

        repeat(5) {
            analyzer.analyze(FakeImage(blankFrame))
            recorder.clock += 600
        }

        assertThat(recorder.states).hasSize(5)
        assertThat(recorder.states.map { it.allFourFound }).doesNotContain(true)
        assertThat(recorder.states.last().readyForCapture).isFalse()
        assertThat(recorder.captures).isEqualTo(0)
    }

    @Test
    fun allFourMarkersAreFoundButAutoCaptureWaitsForASteadyCard_TC_CAP_01() {
        val recorder = Recorder()
        val analyzer = analyzer(recorder)

        analyzer.analyze(FakeImage(cardFrame))
        recorder.clock += 400
        analyzer.analyze(FakeImage(cardFrame))

        val state = recorder.states.last()
        assertThat(state.allFourFound).isTrue()
        assertThat(state.detectedMarkerCount).isEqualTo(4)
        assertThat(state.steadyFraction).isWithin(0.05f).of(0.4f)
        assertThat(state.readyForCapture).isFalse()
        assertThat(recorder.captures).isEqualTo(0)
    }

    @Test
    fun aCardSteadyForTheRequiredTimeTriggersExactlyOneCapture_TC_CAP_01() {
        val recorder = Recorder()
        val analyzer = analyzer(recorder)

        repeat(5) {
            analyzer.analyze(FakeImage(cardFrame))
            recorder.clock += 300
        }

        assertThat(recorder.states.last().isSteadyForOneSecond).isTrue()
        assertThat(recorder.captures).isEqualTo(1) // not once per frame after it is steady
    }

    @Test
    fun aMovingCardRestartsTheWait_TC_CAP_01() {
        val recorder = Recorder()
        val analyzer = analyzer(recorder)
        analyzer.analyze(FakeImage(cardFrame))
        recorder.clock += 800

        analyzer.analyze(FakeImage(cardFrame, shiftX = 120)) // the phone moved well past the allowed movement
        recorder.clock += 300
        analyzer.analyze(FakeImage(cardFrame, shiftX = 120))

        assertThat(recorder.states.last().steadyFraction).isLessThan(0.5f)
        assertThat(recorder.captures).isEqualTo(0)
    }

    @Test
    fun losingTheCardResetsAndItCanTriggerAgainAfterAReset_TC_CAP_01() {
        val recorder = Recorder()
        val analyzer = analyzer(recorder)
        repeat(5) {
            analyzer.analyze(FakeImage(cardFrame))
            recorder.clock += 300
        }
        assertThat(recorder.captures).isEqualTo(1)

        analyzer.analyze(FakeImage(blankFrame))
        assertThat(recorder.states.last().allFourFound).isFalse()
        analyzer.resetAutoCapture()
        repeat(5) {
            analyzer.analyze(FakeImage(cardFrame))
            recorder.clock += 300
        }

        assertThat(recorder.captures).isEqualTo(2)
    }

    @Test
    fun everyFrameIsClosedSoTheCameraKeepsDelivering() {
        val analyzer = analyzer(Recorder())
        val image = FakeImage(blankFrame)

        analyzer.analyze(image)

        assertThat(image.closed).isTrue()
    }
}
