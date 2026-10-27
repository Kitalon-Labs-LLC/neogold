package com.kitalonlabs.neogold.engine.vision

/**
 * Perspective warper mapping the card from camera image coordinates
 * to the canonical 1200x760 px plane via inverse homography and bilinear interpolation.
 * Supports buffer reuse to strictly honor memory allocation limits.
 */
class CardWarper(
    // Optional reusable buffer (1200 * 760 ints)
    private val reusableBuffer: IntArray = IntArray(CardGeometry.CANONICAL_WIDTH * CardGeometry.CANONICAL_HEIGHT)
) {
    init {
        require(reusableBuffer.size == CardGeometry.CANONICAL_WIDTH * CardGeometry.CANONICAL_HEIGHT) {
            "Reusable buffer must be exactly 1200*760 ints"
        }
    }

    /**
     * Warps [frame] to the canonical card plane using [invHomography] (which maps canonical -> source).
     * Reuses [reusableBuffer] to eliminate heap allocation.
     */
    fun warpToCanonical(frame: ImageFrame, invHomography: Homography): CanonicalCardImage {
        val targetW = CardGeometry.CANONICAL_WIDTH
        val targetH = CardGeometry.CANONICAL_HEIGHT
        val frameW = frame.width
        val frameH = frame.height
        val framePixels = frame.data

        val h = invHomography.m
        val h00 = h[0]
        val h01 = h[1]
        val h02 = h[2]
        val h10 = h[3]
        val h11 = h[4]
        val h12 = h[5]
        val h20 = h[6]
        val h21 = h[7]
        val h22 = h[8]

        var outIdx = 0
        for (v in 0 until targetH) {
            for (u in 0 until targetW) {
                // Point mapping (u, v) -> (x, y)
                val w = h20 * u + h21 * v + h22
                val invW = if (StrictMath.abs(w) > 1e-12) 1.0 / w else 1.0
                val srcX = (h00 * u + h01 * v + h02) * invW
                val srcY = (h10 * u + h11 * v + h12) * invW

                val x0 = srcX.toInt()
                val y0 = srcY.toInt()

                if (x0 >= 0 && x0 < frameW - 1 && y0 >= 0 && y0 < frameH - 1) {
                    val fx = srcX - x0
                    val fy = srcY - y0
                    val fx1 = 1.0 - fx
                    val fy1 = 1.0 - fy

                    val idx00 = y0 * frameW + x0
                    val idx01 = idx00 + 1
                    val idx10 = idx00 + frameW
                    val idx11 = idx10 + 1

                    val p00 = framePixels[idx00]
                    val p01 = framePixels[idx01]
                    val p10 = framePixels[idx10]
                    val p11 = framePixels[idx11]

                    // Bilinear interpolation for each channel
                    val r00 = (p00 shr 16) and 0xFF
                    val g00 = (p00 shr 8) and 0xFF
                    val b00 = p00 and 0xFF
                    val r01 = (p01 shr 16) and 0xFF
                    val g01 = (p01 shr 8) and 0xFF
                    val b01 = p01 and 0xFF
                    val r10 = (p10 shr 16) and 0xFF
                    val g10 = (p10 shr 8) and 0xFF
                    val b10 = p10 and 0xFF
                    val r11 = (p11 shr 16) and 0xFF
                    val g11 = (p11 shr 8) and 0xFF
                    val b11 = p11 and 0xFF

                    val rTop = r00 * fx1 + r01 * fx
                    val rBot = r10 * fx1 + r11 * fx
                    val r = (rTop * fy1 + rBot * fy).toInt().coerceIn(0, 255)

                    val gTop = g00 * fx1 + g01 * fx
                    val gBot = g10 * fx1 + g11 * fx
                    val g = (gTop * fy1 + gBot * fy).toInt().coerceIn(0, 255)

                    val bTop = b00 * fx1 + b01 * fx
                    val bBot = b10 * fx1 + b11 * fx
                    val b = (bTop * fy1 + bBot * fy).toInt().coerceIn(0, 255)

                    reusableBuffer[outIdx] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                } else if (x0 >= 0 && x0 < frameW && y0 >= 0 && y0 < frameH) {
                    // Border nearest neighbor
                    reusableBuffer[outIdx] = framePixels[y0 * frameW + x0]
                } else {
                    // Out-of-bounds fill
                    reusableBuffer[outIdx] = (0xFF shl 24) or (0xFF shl 16) or (0xFF shl 8) or 0xFF
                }
                outIdx++
            }
        }

        return CanonicalCardImage(pixels = reusableBuffer)
    }
}
