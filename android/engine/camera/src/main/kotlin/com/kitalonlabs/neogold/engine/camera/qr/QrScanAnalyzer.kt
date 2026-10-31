package com.kitalonlabs.neogold.engine.camera.qr

import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy

/**
 * Looks for the signed QR in the live preview frames of S06 (card) and R08 (study sheet).
 *
 * Reading is rate-limited: a frame is tried at most every [minIntervalMs], because a QR reader costs more than the
 * other per-frame checks and a steady card is read on the first try or not at all. The payload is handed over as raw
 * bytes; checking its signature is the card trust engine's job, not this class's.
 *
 * @param onPayload called with the bytes of each QR symbol read, on the analysis thread
 * @param clockMs a monotonic clock in milliseconds; tests pass their own
 */
class QrScanAnalyzer(
    private val onPayload: (ByteArray) -> Unit,
    private val minIntervalMs: Long = DEFAULT_INTERVAL_MS,
    private val clockMs: () -> Long = { System.nanoTime() / NANOS_PER_MILLI }
) : ImageAnalysis.Analyzer {

    private var lastTriedAtMs: Long = Long.MIN_VALUE

    override fun analyze(image: ImageProxy) {
        try {
            val now = clockMs()
            if (lastTriedAtMs != Long.MIN_VALUE && now - lastTriedAtMs < minIntervalMs) return
            lastTriedAtMs = now

            val plane = image.planes.firstOrNull() ?: return // the first plane of a YUV frame is the brightness
            val buffer = plane.buffer.duplicate().apply { rewind() }
            val bytes = ByteArray(buffer.remaining())
            buffer.get(bytes)
            if (!holdsFrame(bytes.size, image.width, image.height, plane.rowStride)) return
            QrDecoder.decode(bytes, image.width, image.height, plane.rowStride)?.let(onPayload)
        } finally {
            image.close()
        }
    }

    /** A frame whose plane is smaller than its size says is not read, rather than raising on the analysis thread. */
    private fun holdsFrame(bytes: Int, width: Int, height: Int, rowStride: Int): Boolean {
        if (width <= 0 || height <= 0 || rowStride < width) return false
        return bytes >= rowStride.toLong() * (height - 1) + width
    }

    private companion object {
        const val DEFAULT_INTERVAL_MS = 200L
        const val NANOS_PER_MILLI = 1_000_000L
    }
}
