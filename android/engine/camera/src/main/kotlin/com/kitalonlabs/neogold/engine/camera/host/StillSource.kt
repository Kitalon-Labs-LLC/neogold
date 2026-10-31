package com.kitalonlabs.neogold.engine.camera.host

import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import kotlinx.coroutines.CompletableDeferred
import java.util.concurrent.Executor

/** One photo as the camera delivered it: JPEG bytes in memory, and the sensor timestamp that names the frame. */
class Still(val jpeg: ByteArray, val sensorTimestampNs: Long)

/**
 * Where a burst gets its photos. The camera session provides the CameraX one; tests provide a scripted one, which is
 * how the burst logic is tested without a camera.
 */
fun interface StillSource {
    /** Takes one photo. Nothing is written to disk: the bytes stay in memory (NFR-02, TC-PRIV-01). */
    suspend fun takeStill(): Still
}

/** Takes photos with a CameraX [ImageCapture], in memory. */
class ImageCaptureStillSource(
    private val imageCapture: ImageCapture,
    private val executor: Executor
) : StillSource {

    override suspend fun takeStill(): Still {
        val deferred = CompletableDeferred<Still>()
        imageCapture.takePicture(
            executor,
            object : ImageCapture.OnImageCapturedCallback() {
                @Suppress("TooGenericExceptionCaught") // pass any failure to the awaiting caller, then close the image
                override fun onCaptureSuccess(image: ImageProxy) {
                    try {
                        val buffer = image.planes[0].buffer
                        val bytes = ByteArray(buffer.remaining())
                        buffer.get(bytes)
                        deferred.complete(Still(bytes, image.imageInfo.timestamp))
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
}
