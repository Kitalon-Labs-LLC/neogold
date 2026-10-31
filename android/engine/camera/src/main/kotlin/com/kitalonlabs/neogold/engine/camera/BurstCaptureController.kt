package com.kitalonlabs.neogold.engine.camera

import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureResult
import androidx.camera.core.ImageCapture
import com.kitalonlabs.neogold.engine.camera.host.CaptureResultRegistry
import com.kitalonlabs.neogold.engine.camera.host.ImageCaptureStillSource
import com.kitalonlabs.neogold.engine.camera.host.Still
import com.kitalonlabs.neogold.engine.camera.host.StillSource
import com.kitalonlabs.neogold.engine.camera.model.BurstCaptureResult
import com.kitalonlabs.neogold.engine.camera.model.CameraHardwareLevel
import com.kitalonlabs.neogold.engine.camera.model.CaptureMetadata
import com.kitalonlabs.neogold.engine.camera.model.LaplacianEstimator
import com.kitalonlabs.neogold.engine.vision.ImageFrame
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.Executor

/**
 * Controller executing short burst captures (3 frames, or 2 frames on low-RAM devices).
 * Enforces in-memory processing: zero image files written to disk during screening.
 * Doc 4 Section 5.1/5.6, NFR-02, NFR-04b, TC-CAP-15.
 *
 * @param captureResults how each photo was taken, recorded by the camera session; without it (or when a result does
 * not arrive in time) the photo is kept with metadata that says what is not known
 */
class BurstCaptureController(
    private val isLowRamDevice: Boolean = false,
    private val characteristics: CameraCharacteristics? = null,
    private val acceptedControls: Map<String, Any?> = characteristics?.let {
        CameraControlHelper.inspectAvailableControls(it)
    } ?: emptyMap(),
    private val captureResults: CaptureResultRegistry<CaptureResult>? = null
) {

    /**
     * Determines burst frame count: 2 for low-RAM devices, 3 for standard.
     */
    val burstFrameCount: Int = if (isLowRamDevice) 2 else 3

    /** Takes the burst with a CameraX [ImageCapture]. */
    suspend fun executeBurst(imageCapture: ImageCapture, executor: Executor): BurstCaptureResult =
        executeBurst(ImageCaptureStillSource(imageCapture, executor))

    /**
     * Takes the burst from [source]. Frames are captured one after another into memory, decoded downsampled, and the
     * sharpest is selected.
     */
    suspend fun executeBurst(source: StillSource): BurstCaptureResult = withContext(Dispatchers.Default) {
        val frames = mutableListOf<ImageFrame>()
        val metadataList = mutableListOf<CaptureMetadata>()

        repeat(burstFrameCount) {
            val captured = captureFrame(source)
            frames.add(captured.frame)
            metadataList.add(captured.metadata)
        }

        BurstCaptureResult(
            frames = frames,
            metadata = metadataList,
            sharpestIndex = LaplacianEstimator.selectSharpestIndex(frames)
        )
    }

    /** One photo from [source] as the JPEG the camera made, with how it was taken. Nothing is decoded or kept on disk. */
    suspend fun captureStill(source: StillSource): CapturedStill {
        val still = source.takeStill()
        return CapturedStill(still.jpeg, metadataFor(still))
    }

    /** One photo from [source], decoded in memory to about 1600 px on the long edge, with how it was taken. */
    suspend fun captureFrame(source: StillSource): CapturedFrame = withContext(Dispatchers.Default) {
        val still = source.takeStill()
        CapturedFrame(DownsampledImageDecoder.decodeDownsampled(still.jpeg), metadataFor(still))
    }

    private suspend fun metadataFor(still: Still): CaptureMetadata {
        val result = captureResults?.await(still.sensorTimestampNs, RESULT_WAIT_MS)
        return if (result != null && characteristics != null) {
            CameraControlHelper.extractMetadata(
                result = result,
                characteristics = characteristics,
                acceptedControls = acceptedControls
            )
        } else {
            createFallbackMetadata()
        }
    }

    /** What is known without a capture result: the camera's abilities, and nothing about this photo. */
    private fun createFallbackMetadata(): CaptureMetadata {
        val hwLevel = characteristics?.let { CameraControlHelper.getHardwareLevel(it) }
            ?: CameraHardwareLevel.UNKNOWN
        val aeLock = characteristics?.let { CameraControlHelper.isAeLockSupported(it) } ?: false
        val awbLock = characteristics?.let { CameraControlHelper.isAwbLockSupported(it) } ?: false

        return CaptureMetadata(
            sensorSensitivity = null,
            exposureTimeNs = null,
            aeLockSupported = aeLock,
            awbLockSupported = awbLock,
            // Without the result nobody knows whether the locks were on, and an unknown is not recorded as a yes.
            aeLocked = false,
            awbLocked = false,
            aeState = null,
            awbState = null,
            colorCorrectionGains = null,
            hardwareLevel = hwLevel,
            acceptedControls = acceptedControls
        )
    }

    private companion object {
        /** How long a photo waits for its capture result before it is kept without one. */
        const val RESULT_WAIT_MS = 750L
    }
}

/** A decoded photo and the camera's record of how it was taken. */
class CapturedFrame(val frame: ImageFrame, val metadata: CaptureMetadata)

/** A photo as the camera made it, JPEG bytes in memory, and the camera's record of how it was taken. */
class CapturedStill(val jpeg: ByteArray, val metadata: CaptureMetadata)
