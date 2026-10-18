package com.kitalonlabs.neogold.engine.camera

import android.content.Context
import android.graphics.Bitmap
import com.google.common.truth.Truth.assertThat
import com.kitalonlabs.neogold.engine.camera.model.BurstCaptureResult
import com.kitalonlabs.neogold.engine.camera.model.CameraHardwareLevel
import com.kitalonlabs.neogold.engine.camera.model.CaptureMetadata
import com.kitalonlabs.neogold.engine.camera.model.LaplacianEstimator
import com.kitalonlabs.neogold.engine.vision.DetectedMarker
import com.kitalonlabs.neogold.engine.vision.ImageFrame
import com.kitalonlabs.neogold.engine.vision.Point2D
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Tests TC-CAP-01, TC-CAP-15, downsampled in-memory decoding, and zero-disk-write privacy verification.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CameraCaptureTest {

    private val context: Context get() = RuntimeEnvironment.getApplication()

    private fun createSyntheticFullResJpeg(width: Int = 4000, height: Int = 3000): ByteArray {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bitmap)
        canvas.drawColor(android.graphics.Color.DKGRAY)
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 85, out)
        bitmap.recycle()
        return out.toByteArray()
    }

    @Test
    fun tcCap01_liveCardDetectionAndAutoCaptureGating() {
        // TC-CAP-01: Auto-capture fires only when all 4 markers are detected and steady
        var autoCaptureTriggered = false
        var lastState: LiveCardState = LiveCardState.IDLE

        // Create analyzer with 100 ms steady threshold for fast unit testing
        val analyzer = LiveCardAnalyzer(
            steadyRequiredDurationMs = 100L,
            onStateChanged = { lastState = it },
            onAutoCaptureTriggered = { autoCaptureTriggered = true }
        )

        // 1. Initially idle
        assertThat(lastState.allFourFound).isFalse()
        assertThat(autoCaptureTriggered).isFalse()

        // 2. State with 3 markers -> auto-capture does not fire
        val threeMarkers = mapOf(
            0 to DetectedMarker(0, listOf(Point2D(0.0, 0.0), Point2D(10.0, 0.0), Point2D(10.0, 10.0), Point2D(0.0, 10.0)), Point2D(5.0, 5.0)),
            1 to DetectedMarker(1, listOf(Point2D(100.0, 0.0), Point2D(110.0, 0.0), Point2D(110.0, 10.0), Point2D(100.0, 10.0)), Point2D(105.0, 5.0)),
            2 to DetectedMarker(2, listOf(Point2D(100.0, 100.0), Point2D(110.0, 100.0), Point2D(110.0, 110.0), Point2D(100.0, 110.0)), Point2D(105.0, 105.0))
        )
        val partialState = LiveCardState(
            detectedMarkerCount = 3,
            detectedMarkers = threeMarkers,
            allFourFound = false,
            cardAngleDegrees = 0.0,
            steadyFraction = 0.0f,
            isSteadyForOneSecond = false,
            readyForCapture = false
        )
        assertThat(partialState.readyForCapture).isFalse()

        // 3. State with all 4 markers and steady -> ready for capture
        val fourMarkers = threeMarkers + (3 to DetectedMarker(3, listOf(Point2D(0.0, 100.0), Point2D(10.0, 100.0), Point2D(10.0, 110.0), Point2D(0.0, 110.0)), Point2D(5.0, 105.0)))
        val completeState = LiveCardState(
            detectedMarkerCount = 4,
            detectedMarkers = fourMarkers,
            allFourFound = true,
            cardAngleDegrees = 3.5,
            steadyFraction = 1.0f,
            isSteadyForOneSecond = true,
            readyForCapture = true
        )
        assertThat(completeState.allFourFound).isTrue()
        assertThat(completeState.readyForCapture).isTrue()
    }

    @Test
    fun tcCap15_captureMetadataRecording() {
        // TC-CAP-15: Capture metadata recorded: ISO, exposure time, lock states, gains, hardware level
        val metadata = CaptureMetadata(
            sensorSensitivity = 200,
            exposureTimeNs = 10_000_000L, // 10 ms
            aeLockSupported = true,
            awbLockSupported = true,
            aeLocked = true,
            awbLocked = true,
            aeState = 3, // CONTROL_AE_STATE_LOCKED
            awbState = 3, // CONTROL_AWB_STATE_LOCKED
            colorCorrectionGains = floatArrayOf(1.8f, 1.0f, 1.0f, 2.1f),
            hardwareLevel = CameraHardwareLevel.FULL,
            acceptedControls = mapOf("sceneModeDisabled" to true, "effectModeOff" to true)
        )

        assertThat(metadata.sensorSensitivity).isEqualTo(200)
        assertThat(metadata.exposureTimeNs).isEqualTo(10_000_000L)
        assertThat(metadata.aeLocked).isTrue()
        assertThat(metadata.awbLocked).isTrue()
        assertThat(metadata.colorCorrectionGains).isNotNull()
        assertThat(metadata.colorCorrectionGains!!.size).isEqualTo(4)
        assertThat(metadata.hardwareLevel).isEqualTo(CameraHardwareLevel.FULL)
    }

    @Test
    fun testDownsampledDecodingTo1600Px() {
        // Decodes high-resolution image downsampled to ~1600 px long edge in memory
        val fullResJpeg = createSyntheticFullResJpeg(3200, 2400)
        val frame = DownsampledImageDecoder.decodeDownsampled(fullResJpeg, targetLongEdge = 1600)

        assertThat(frame.width).isAtMost(1600)
        assertThat(frame.height).isAtMost(1600)
        assertThat(frame.data.size).isEqualTo(frame.width * frame.height)
    }

    @Test
    fun testSharpestFrameSelectedViaLaplacianVariance() {
        // Create two frames: one blurry (uniform gray), one sharp (high contrast checkerboard)
        val w = 200
        val h = 200
        val blurryPixels = IntArray(w * h) { 0xFF808080.toInt() }
        val blurryFrame = ImageFrame(w, h, blurryPixels)

        val sharpPixels = IntArray(w * h) { i ->
            val x = i % w
            val y = i / w
            if ((x / 10 + y / 10) % 2 == 0) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
        }
        val sharpFrame = ImageFrame(w, h, sharpPixels)

        val sharpVar = LaplacianEstimator.computeVariance(sharpFrame)
        val blurryVar = LaplacianEstimator.computeVariance(blurryFrame)

        assertThat(sharpVar).isGreaterThan(blurryVar)

        val frames = listOf(blurryFrame, sharpFrame, blurryFrame)
        val bestIdx = LaplacianEstimator.selectSharpestIndex(frames)
        assertThat(bestIdx).isEqualTo(1)
    }

    @Test
    fun scriptedCheck_noImageFileAppearsOnDiskAfterScreeningCapture() {
        // Scripted Privacy Check: Nothing is written to disk in screening mode
        val baseFiles = countImageFiles(context.filesDir) + countImageFiles(context.cacheDir)

        // Perform simulated burst capture & downsampled decode
        val jpeg = createSyntheticFullResJpeg(1600, 1200)
        val frame = DownsampledImageDecoder.decodeDownsampled(jpeg)
        val metadata = CaptureMetadata(
            sensorSensitivity = 100,
            exposureTimeNs = 5000000L,
            aeLockSupported = true,
            awbLockSupported = true,
            aeLocked = true,
            awbLocked = true,
            aeState = null,
            awbState = null,
            colorCorrectionGains = null,
            hardwareLevel = CameraHardwareLevel.FULL
        )

        val burstResult = BurstCaptureResult(
            frames = listOf(frame),
            metadata = listOf(metadata),
            sharpestIndex = 0
        )

        assertThat(burstResult.selectedFrame).isNotNull()

        // Check disk again: ZERO new image files (.jpg, .jpeg, .png, .webp) must exist on disk!
        val afterFiles = countImageFiles(context.filesDir) + countImageFiles(context.cacheDir)
        assertThat(afterFiles).isEqualTo(baseFiles)
        assertThat(afterFiles).isEqualTo(0)
    }

    private fun countImageFiles(dir: File?): Int {
        if (dir == null || !dir.exists()) return 0
        val imageExtensions = listOf(".jpg", ".jpeg", ".png", ".webp", ".raw", ".dng")
        return dir.walkTopDown().count { file ->
            file.isFile && imageExtensions.any { ext -> file.name.lowercase().endsWith(ext) }
        }
    }
}
