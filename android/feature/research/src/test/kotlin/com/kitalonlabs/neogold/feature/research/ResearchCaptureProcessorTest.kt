package com.kitalonlabs.neogold.feature.research

import android.graphics.Bitmap
import android.graphics.Color
import android.hardware.camera2.CameraCharacteristics
import com.google.common.truth.Truth.assertThat
import com.kitalonlabs.neogold.engine.camera.CapturedStill
import com.kitalonlabs.neogold.engine.camera.host.CameraSession
import com.kitalonlabs.neogold.engine.camera.model.CameraHardwareLevel
import com.kitalonlabs.neogold.engine.camera.model.CaptureMetadata
import com.kitalonlabs.neogold.engine.camera.research.ResearchJpegSanitizer
import com.kitalonlabs.neogold.engine.colorscience.Lab
import com.kitalonlabs.neogold.engine.vision.DetectedMarker
import com.kitalonlabs.neogold.engine.vision.ImageFrame
import com.kitalonlabs.neogold.engine.vision.MarkerDetector
import com.kitalonlabs.neogold.engine.vision.VisionEngine
import com.kitalonlabs.neogold.feature.research.capture.CaptureTooLargeException
import com.kitalonlabs.neogold.feature.research.capture.ResearchCaptureProcessor
import kotlinx.coroutines.runBlocking
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.ByteArrayOutputStream

/**
 * What a research photo is turned into (R04, TC-RSH-10, TC-API-20, safety review of C2): the photo is sanitized, the camera's
 * own record is kept as it was, and a photo checked with no card batch is not recorded as passed.
 */
@RunWith(RobolectricTestRunner::class)
class ResearchCaptureProcessorTest {

    private val metadata = CaptureMetadata(
        sensorSensitivity = 320,
        exposureTimeNs = 6_000_000L,
        aeLockSupported = true,
        awbLockSupported = true,
        aeLocked = false,
        awbLocked = true,
        aeState = null,
        awbState = null,
        colorCorrectionGains = floatArrayOf(1.9f, 1.0f, 1.0f, 2.1f),
        hardwareLevel = CameraHardwareLevel.FULL
    )

    private fun jpeg(): ByteArray {
        val bitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.YELLOW)
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
        bitmap.recycle()
        return out.toByteArray()
    }

    private val noMarkers = object : MarkerDetector {
        override fun detectMarkers(frame: ImageFrame): List<DetectedMarker> = emptyList()
    }

    private fun cameraTaking(photo: () -> ByteArray) = object : CameraSession {
        override val characteristics: CameraCharacteristics? = null
        override suspend fun captureFrame() = error("not used by R04")
        override suspend fun captureStill(flash: Boolean) = CapturedStill(photo(), metadata)
        override suspend fun captureBurst() = error("not used by R04")
        override fun close() = Unit
    }

    private val camera = cameraTaking { jpeg() }

    /** A photo of random pixels: it does not compress, so it is far over the upload limit. */
    private fun hugeJpeg(): ByteArray {
        val side = HUGE_SIDE
        val random = java.util.Random(SEED)
        val pixels = IntArray(side * side) { random.nextInt() or ALPHA }
        val bitmap = Bitmap.createBitmap(pixels, side, side, Bitmap.Config.ARGB_8888)
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 95, out)
        bitmap.recycle()
        return out.toByteArray()
    }

    private fun processor(reference: Map<String, Lab>?) = ResearchCaptureProcessor(
        vision = VisionEngine(markerDetector = noMarkers),
        reference = reference
    )

    @Test
    fun aPhotoWithNoCardToCompareWithIsNotRecordedAsPassedAndSaysWhy() = runBlocking {
        val done = processor(reference = null).capture(camera, site = "STERNUM", attemptNumber = 1, flash = false)

        assertThat(done.input.qualityPassed).isFalse()
        assertThat(done.input.qualityFailReasons).contains(ResearchCaptureProcessor.NO_REFERENCE_CARD)
    }

    @Test
    fun aPhotoCheckedAgainstACardDoesNotCarryTheNoReferenceReason() = runBlocking {
        val done = processor(reference = emptyMap()).capture(camera, site = "STERNUM", attemptNumber = 1, flash = false)

        assertThat(done.input.qualityFailReasons).doesNotContain(ResearchCaptureProcessor.NO_REFERENCE_CARD)
    }

    @Test
    fun noResidualIsKeptWhenTheCardWasNotFound() = runBlocking {
        val done = processor(
            reference = emptyMap()
        ).capture(camera, site = "FOREHEAD", attemptNumber = 2, flash = false)

        assertThat(done.input.correctionResidualDe).isNull()
        assertThat(done.input.attemptNumber).isEqualTo(2)
        assertThat(done.input.site).isEqualTo("FOREHEAD")
    }

    @Test
    fun theCamerasOwnRecordIsKeptAsItWasAndTheJpegCarriesOnlyItsOrientation_TC_API_20() = runBlocking {
        val done = processor(reference = null).capture(camera, site = "SCLERA_FLASH", attemptNumber = 1, flash = true)

        assertThat(done.input.iso).isEqualTo(320)
        assertThat(done.input.exposureNs).isEqualTo(6_000_000L)
        assertThat(done.input.aeLocked).isEqualTo(0)
        assertThat(done.input.awbLocked).isEqualTo(1)
        assertThat(done.input.colorGains).isEqualTo("[1.9,1.0,1.0,2.1]")
        assertThat(ResearchJpegSanitizer.validateAllowList(done.input.imageBytes).isValid).isTrue()
    }

    @Test
    fun aPhotoOverTheUploadLimitIsRefusedAndNothingIsReturned() = runBlocking {
        val huge = hugeJpeg()
        assertThat(huge.size).isGreaterThan(ResearchCaptureProcessor.MAX_IMAGE_BYTES)

        try {
            processor(
                reference = null
            ).capture(cameraTaking { huge }, site = "STERNUM", attemptNumber = 1, flash = false)
            fail("a photo over the limit must be refused")
        } catch (expected: CaptureTooLargeException) {
            // R04 shows the retake message and keeps nothing
        }
    }

    @Test
    fun theUploadLimitIsTheStricterOfTheTwo() {
        // doc 7 allows a study up to 20,000,000; the app's own cap is lower
        assertThat(ResearchCaptureProcessor.MAX_IMAGE_BYTES).isEqualTo(5_000_000)
    }

    private companion object {
        const val HUGE_SIDE = 2400
        const val SEED = 7L
        const val ALPHA = 0xFF000000.toInt()
    }
}
