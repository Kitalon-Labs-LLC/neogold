package com.kitalonlabs.neogold.engine.camera

import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureResult
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import com.kitalonlabs.neogold.engine.camera.model.BurstCaptureResult
import com.kitalonlabs.neogold.engine.camera.model.CameraHardwareLevel
import com.kitalonlabs.neogold.engine.camera.model.CaptureMetadata
import com.kitalonlabs.neogold.engine.camera.model.LaplacianEstimator
import com.kitalonlabs.neogold.engine.vision.ImageFrame
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.util.concurrent.Executor

/**
 * Controller executing short burst captures (3 frames, or 2 frames on low-RAM devices).
 * Enforces in-memory processing: zero image files written to disk during screening.
 * Doc 4 Section 5.1/5.6, NFR-02, NFR-04b, TC-CAP-15.
 */
class BurstCaptureController(
    private val isLowRamDevice: Boolean = false,
    private val characteristics: CameraCharacteristics? = null,
    private val acceptedControls: Map<String, Any?> = characteristics?.let {
        CameraControlHelper.inspectAvailableControls(it)
    } ?: emptyMap()
) {

    /**
     * Determines burst frame count: 2 for low-RAM devices, 3 for standard.
     */
    val burstFrameCount: Int = if (isLowRamDevice) 2 else 3

    /**
     * Executes the burst capture using CameraX [ImageCapture].
     * Frames are captured sequentially into memory, decoded downsampled, and evaluated.
     */
    suspend fun executeBurst(
        imageCapture: ImageCapture,
        executor: Executor
    ): BurstCaptureResult = withContext(Dispatchers.Default) {
        val frames = mutableListOf<ImageFrame>()
        val metadataList = mutableListOf<CaptureMetadata>()

        for (i in 0 until burstFrameCount) {
            val (frame, metadata) = captureSingleFrame(imageCapture, executor)
            frames.add(frame)
            metadataList.add(metadata)
        }

        val sharpestIndex = LaplacianEstimator.selectSharpestIndex(frames)
        BurstCaptureResult(
            frames = frames,
            metadata = metadataList,
            sharpestIndex = sharpestIndex
        )
    }

    /**
     * Captures a single frame purely in-memory using ImageCapture.takePicture.
     */
    private suspend fun captureSingleFrame(
        imageCapture: ImageCapture,
        executor: Executor
    ): Pair<ImageFrame, CaptureMetadata> {
        val deferred = CompletableDeferred<Pair<ImageFrame, CaptureMetadata>>()

        imageCapture.takePicture(
            executor,
            object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(image: ImageProxy) {
                    try {
                        val buffer: ByteBuffer = image.planes[0].buffer
                        val bytes = ByteArray(buffer.remaining())
                        buffer.get(bytes)

                        // In-memory downsampled decode to ~1600 px long edge
                        val frame = DownsampledImageDecoder.decodeDownsampled(bytes)

                        // Extract CaptureResult metadata if available
                        val captureResult: CaptureResult? = extractCaptureResult(image)
                        val metadata = if (captureResult != null && characteristics != null) {
                            CameraControlHelper.extractMetadata(
                                result = captureResult,
                                characteristics = characteristics,
                                acceptedControls = acceptedControls
                            )
                        } else {
                            createFallbackMetadata(image)
                        }

                        deferred.complete(Pair(frame, metadata))
                    } catch (e: Throwable) {
                        deferred.completeExceptionally(e)
                    } finally {
                        image.close()
                    }
                }

                override fun onError(exception: ImageCaptureException) {
                    deferred.completeExceptionally(exception)
                }
            }
        )

        return deferred.await()
    }

    private fun extractCaptureResult(image: ImageProxy): CaptureResult? {
        val info = image.imageInfo
        if (info is androidx.camera.core.internal.CameraCaptureResultImageInfo) {
            val captureResult = info.cameraCaptureResult
            if (captureResult is androidx.camera.camera2.internal.Camera2CameraCaptureResult) {
                return captureResult.captureResult
            }
        }
        return try {
            val method = info.javaClass.getMethod("getCameraCaptureResult")
            val internalResult = method.invoke(info)
            val getCaptureResultMethod = internalResult?.javaClass?.getMethod("getCaptureResult")
            getCaptureResultMethod?.invoke(internalResult) as? CaptureResult
        } catch (_: Exception) {
            null
        }
    }

    private fun createFallbackMetadata(image: ImageProxy): CaptureMetadata {
        val hwLevel = characteristics?.let { CameraControlHelper.getHardwareLevel(it) }
            ?: CameraHardwareLevel.UNKNOWN
        val aeLock = characteristics?.let { CameraControlHelper.isAeLockSupported(it) } ?: false
        val awbLock = characteristics?.let { CameraControlHelper.isAwbLockSupported(it) } ?: false

        return CaptureMetadata(
            sensorSensitivity = null,
            exposureTimeNs = null,
            aeLockSupported = aeLock,
            awbLockSupported = awbLock,
            aeLocked = aeLock,
            awbLocked = awbLock,
            aeState = null,
            awbState = null,
            colorCorrectionGains = null,
            hardwareLevel = hwLevel,
            acceptedControls = acceptedControls
        )
    }
}
