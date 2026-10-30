package com.kitalonlabs.neogold.engine.camera.host

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.content.Context
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.util.Size
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.Preview
import androidx.camera.core.UseCaseGroup
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.google.common.util.concurrent.ListenableFuture
import com.kitalonlabs.neogold.engine.camera.BurstCaptureController
import com.kitalonlabs.neogold.engine.camera.CameraControlHelper
import com.kitalonlabs.neogold.engine.camera.CapturedFrame
import com.kitalonlabs.neogold.engine.camera.LiveCardAnalyzer
import com.kitalonlabs.neogold.engine.camera.model.BurstCaptureResult
import com.kitalonlabs.neogold.engine.camera.qr.QrScanAnalyzer
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Opens the back camera for one of the screens and returns a [CameraSession] (doc 4 Section 5.1).
 *
 * It binds a preview, the frame analyzer that [CameraMode] asks for, and a still capture, with the controls NeoGold
 * wants in every request (no scene modes, effects, stabilization, flash or heavy noise reduction). The camera's record
 * of each still capture reaches the burst controller through a Camera2 interop callback, which is the supported way to
 * get it (BUG-2026-032). Only NeoGold types leave this class.
 */
class CameraHost(private val context: Context) {

    /** Binds the camera to [lifecycleOwner] without drawing a preview, as a test or a headless capture does. */
    suspend fun open(lifecycleOwner: LifecycleOwner, mode: CameraMode): CameraSession =
        open(lifecycleOwner, surfaceProvider = null, mode = mode)

    /**
     * Binds the camera to [lifecycleOwner] and starts the preview on [surfaceProvider]. Internal: the preview view
     * that owns the provider lives in this module, so no CameraX type crosses the module boundary.
     */
    internal suspend fun open(
        lifecycleOwner: LifecycleOwner,
        surfaceProvider: Preview.SurfaceProvider?,
        mode: CameraMode
    ): CameraSession {
        val mainExecutor = ContextCompat.getMainExecutor(context)
        val provider = ProcessCameraProvider.getInstance(context).awaitOn(mainExecutor)
        // Frames are analyzed and photos copied off the main thread, on one thread that goes with the session.
        val workExecutor = Executors.newSingleThreadExecutor()
        val selector = CameraSelector.DEFAULT_BACK_CAMERA
        val characteristics = characteristicsOf(provider, selector)

        val results = CaptureResultRegistry<CaptureResult>()
        val preview = Preview.Builder().also { builder ->
            characteristics?.let { CameraControlHelper.applyPreviewControls(builder, it) }
        }.build()
        if (surfaceProvider != null) preview.setSurfaceProvider(surfaceProvider)
        val imageCapture = buildImageCapture(characteristics, results)
        val analysis = buildAnalysis(mode)

        provider.unbindAll()
        val useCases = UseCaseGroup.Builder().apply {
            addUseCase(preview)
            analysis?.let { addUseCase(it.first) }
            addUseCase(imageCapture)
        }.build()
        try {
            provider.bindToLifecycle(lifecycleOwner, selector, useCases)
        } catch (e: IllegalArgumentException) {
            workExecutor.shutdown() // a failed open must not leave a thread behind for every retry
            throw e
        } catch (e: IllegalStateException) {
            workExecutor.shutdown()
            throw e
        }
        analysis?.let { (useCase, analyzer) -> useCase.setAnalyzer(workExecutor, analyzer) }

        val controller = BurstCaptureController(
            isLowRamDevice = (context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).isLowRamDevice,
            characteristics = characteristics,
            captureResults = results
        )
        return Session(
            provider,
            controller,
            ImageCaptureStillSource(imageCapture, workExecutor),
            characteristics,
            workExecutor
        )
    }

    @SuppressLint("UnsafeOptInUsageError") // Camera2 interop is the supported way to read the camera's capture result
    private fun buildImageCapture(
        characteristics: CameraCharacteristics?,
        results: CaptureResultRegistry<CaptureResult>
    ): ImageCapture {
        val builder = ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
            .setFlashMode(ImageCapture.FLASH_MODE_OFF)
        characteristics?.let { CameraControlHelper.applyCaptureControls(builder, it) }
        Camera2Interop.Extender(builder).setSessionCaptureCallback(
            object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(
                    session: CameraCaptureSession,
                    request: CaptureRequest,
                    result: TotalCaptureResult
                ) {
                    val intent = request.get(CaptureRequest.CONTROL_CAPTURE_INTENT)
                    val timestamp = result.get(CaptureResult.SENSOR_TIMESTAMP)
                    if (intent == CaptureRequest.CONTROL_CAPTURE_INTENT_STILL_CAPTURE && timestamp != null) {
                        results.record(timestamp, result)
                    }
                }
            }
        )
        return builder.build()
    }

    private fun buildAnalysis(mode: CameraMode): Pair<ImageAnalysis, ImageAnalysis.Analyzer>? {
        val (size, analyzer) = when (mode) {
            CameraMode.Stills -> return null
            is CameraMode.ReadQr -> QR_ANALYSIS_SIZE to QrScanAnalyzer(onPayload = mode.onPayload)
            is CameraMode.LiveCard -> LIVE_ANALYSIS_SIZE to LiveCardAnalyzer(
                onStateChanged = mode.onState,
                onAutoCaptureTriggered = mode.onAutoCapture
            )
        }
        val selector = ResolutionSelector.Builder()
            .setResolutionStrategy(ResolutionStrategy(size, ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER))
            .build()
        val useCase = ImageAnalysis.Builder()
            .setResolutionSelector(selector)
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()
        return useCase to analyzer
    }

    @SuppressLint("UnsafeOptInUsageError") // reading the lens's id through Camera2 interop
    private fun characteristicsOf(provider: ProcessCameraProvider, selector: CameraSelector): CameraCharacteristics? {
        val info = selector.filter(provider.availableCameraInfos).firstOrNull() ?: return null
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        return runCatching { manager.getCameraCharacteristics(Camera2CameraInfo.from(info).cameraId) }.getOrNull()
    }

    private class Session(
        private val provider: ProcessCameraProvider,
        private val controller: BurstCaptureController,
        private val stills: StillSource,
        override val characteristics: CameraCharacteristics?,
        private val workExecutor: ExecutorService
    ) : CameraSession {
        override suspend fun captureFrame(): CapturedFrame = controller.captureFrame(stills)

        override suspend fun captureBurst(): BurstCaptureResult = controller.executeBurst(stills)

        override fun close() {
            provider.unbindAll()
            workExecutor.shutdown()
        }
    }

    private companion object {
        val QR_ANALYSIS_SIZE = Size(1280, 720) // a 36 mm symbol on a card held at hand distance needs more than VGA
        val LIVE_ANALYSIS_SIZE = Size(640, 480)
    }
}

/** Awaits a CameraX future without blocking: the listener runs on [executor] and resumes the coroutine. */
private suspend fun <T : Any> ListenableFuture<T>.awaitOn(executor: Executor): T =
    suspendCancellableCoroutine { continuation ->
        addListener(
            {
                try {
                    continuation.resume(get())
                } catch (e: ExecutionException) {
                    continuation.resumeWithException(e.cause ?: e)
                }
            },
            executor
        )
        continuation.invokeOnCancellation { cancel(false) }
    }
