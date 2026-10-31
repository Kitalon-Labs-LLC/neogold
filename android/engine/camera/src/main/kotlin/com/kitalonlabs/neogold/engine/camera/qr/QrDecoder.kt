package com.kitalonlabs.neogold.engine.camera.qr

import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.NotFoundException
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.ReaderException
import com.google.zxing.ResultMetadataType
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import java.io.ByteArrayOutputStream

/**
 * Reads the signed binary QR on a calibration card or an enrollment sheet from the brightness plane of a camera frame
 * (NG-039, card/spec/qr-formats.md Section 1).
 *
 * The payloads are packed bytes in QR byte mode, not text. ZXing turns byte-mode data into a string by guessing a
 * character set, which would corrupt bytes above 0x7F, so the bytes are taken from its byte segments instead. A
 * symbol with no byte segments is not one of ours and is ignored; for a mixed-mode symbol only its byte segments are
 * returned, which cannot matter because the signature is checked over exactly those bytes.
 *
 * A camera frame is untrusted input, so no frame may raise: whatever ZXing throws on a damaged or crafted symbol is
 * "no symbol in this frame".
 *
 * Pure JVM: no Android class is used, so the tests render real QR images and read them back.
 */
object QrDecoder {

    private val hints: Map<DecodeHintType, Any> = mapOf(
        DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
        DecodeHintType.TRY_HARDER to true,
        DecodeHintType.CHARACTER_SET to "ISO-8859-1"
    )

    /**
     * @param luminance one byte per pixel, row after row; a row may be longer than the image is wide
     * @param rowStride bytes from the start of one row to the start of the next
     * @return the payload bytes of the QR symbol in the frame, or null when there is none or it is not byte mode
     */
    @Suppress("TooGenericExceptionCaught") // see the class comment: no frame may raise
    fun decode(luminance: ByteArray, width: Int, height: Int, rowStride: Int = width): ByteArray? {
        require(width > 0 && height > 0 && rowStride >= width) { "Frame size is not valid" }
        require(luminance.size >= rowStride * (height - 1) + width) { "Frame is smaller than its size says" }
        // The reader keeps state between decodes, so it is not shared between threads or frames.
        val reader = QRCodeReader()
        val source = PlanarYUVLuminanceSource(luminance, rowStride, height, 0, 0, width, height, false)
        val bitmap = BinaryBitmap(HybridBinarizer(source))
        return try {
            val result = reader.decode(bitmap, hints)
            payloadOf(result.resultMetadata?.get(ResultMetadataType.BYTE_SEGMENTS))
        } catch (_: NotFoundException) {
            null // no symbol in this frame, which is the usual case
        } catch (_: ReaderException) {
            null // a symbol that did not decode cleanly; the next frame may
        } catch (_: RuntimeException) {
            null // a crafted or damaged symbol that ZXing did not expect; it must not end the app
        }
    }

    private fun payloadOf(segments: Any?): ByteArray? {
        val parts = (segments as? Iterable<*>)?.map { it as? ByteArray ?: return null } ?: return null
        if (parts.isEmpty()) return null
        val out = ByteArrayOutputStream()
        parts.forEach { out.write(it) }
        return out.toByteArray()
    }
}
