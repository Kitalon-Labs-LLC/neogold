package com.kitalonlabs.neogold.engine.camera.host

import android.hardware.camera2.CameraCharacteristics
import com.kitalonlabs.neogold.engine.camera.CapturedFrame
import com.kitalonlabs.neogold.engine.camera.LiveCardState
import com.kitalonlabs.neogold.engine.camera.model.BurstCaptureResult

/**
 * What the camera is used for. The host builds the matching frame analyzer, so the screens never see a CameraX type.
 */
sealed interface CameraMode {
    /** S06 and R08: read the signed QR on the card or the study sheet; [onPayload] gets its bytes. */
    class ReadQr(val onPayload: (ByteArray) -> Unit) : CameraMode

    /** S07: photos of the card, one at a time, with no live analysis. */
    data object Stills : CameraMode

    /** S16 and R04: live card detection, and the burst once the card is steady. */
    class LiveCard(
        val onState: (LiveCardState) -> Unit,
        val onAutoCapture: () -> Unit
    ) : CameraMode
}

/** A camera that is open and showing a preview. Closing it releases the camera. */
interface CameraSession {
    /** The abilities of the lens in use, when the platform will say. */
    val characteristics: CameraCharacteristics?

    /** One photo in memory, with how it was taken. Nothing is written to disk. */
    suspend fun captureFrame(): CapturedFrame

    /** The 2 or 3 photos of one screening, with the sharpest marked. */
    suspend fun captureBurst(): BurstCaptureResult

    fun close()
}
