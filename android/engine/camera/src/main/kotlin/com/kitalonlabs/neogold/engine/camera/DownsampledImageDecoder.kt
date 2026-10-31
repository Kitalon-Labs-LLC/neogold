package com.kitalonlabs.neogold.engine.camera

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.kitalonlabs.neogold.engine.vision.ImageFrame
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * In-memory downsampled JPEG decoder (doc 4 Section 5.6, NFR-04b).
 * Decodes full-resolution image buffers downsampled to ~1600 px on the long edge.
 * Crucially: zero writes to disk; bitmaps are recycled immediately into ImageFrames.
 */
object DownsampledImageDecoder {

    const val TARGET_LONG_EDGE_PX = 1600

    /**
     * Decodes a byte array (JPEG) into an in-memory [ImageFrame] with long edge ~1600 px.
     */
    fun decodeDownsampled(
        jpegBytes: ByteArray,
        targetLongEdge: Int = TARGET_LONG_EDGE_PX
    ): ImageFrame {
        val boundsOptions = BitmapFactory.Options().apply {
            inJustDecodeBounds = true
        }
        BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size, boundsOptions)

        val origW = boundsOptions.outWidth
        val origH = boundsOptions.outHeight
        require(origW > 0 && origH > 0) { "Invalid JPEG dimensions: $origW x $origH" }

        val origLongEdge = max(origW, origH)
        var sampleSize = 1
        if (origLongEdge > targetLongEdge) {
            val ratio = (origLongEdge.toFloat() / targetLongEdge.toFloat()).roundToInt()
            // inSampleSize should ideally be a power of 2 for JPEG decoder efficiency
            sampleSize = Integer.highestOneBit(ratio)
            if (sampleSize < 1) sampleSize = 1
        }

        val decodeOptions = BitmapFactory.Options().apply {
            inSampleSize = sampleSize
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }

        val bitmap = BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size, decodeOptions)
            ?: error("Failed to decode JPEG byte array into Bitmap")

        try {
            val w = bitmap.width
            val h = bitmap.height
            val pixels = IntArray(w * h)
            bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
            return ImageFrame(width = w, height = h, data = pixels)
        } finally {
            bitmap.recycle()
        }
    }
}
