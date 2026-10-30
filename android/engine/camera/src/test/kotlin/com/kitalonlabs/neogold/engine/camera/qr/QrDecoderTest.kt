package com.kitalonlabs.neogold.engine.camera.qr

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import org.junit.Test
import java.io.File

/**
 * The scan half of TC-CARD-01 and TC-CARD-02 (NG-039): every payload in card/spec/testvectors is drawn as a real QR
 * image, as the printed card and the enrollment sheet carry it (byte mode, level M), and read back byte for byte. The
 * signature check on what comes out is the card trust engine's job and has its own tests.
 */
class QrDecoderTest {

    private class Image(val pixels: ByteArray, val width: Int, val height: Int, val rowStride: Int)

    /** Draws [payload] as a QR in byte mode with a four-module quiet zone, [scale] pixels per module. */
    private fun render(payload: ByteArray, scale: Int = 6, extraRowBytes: Int = 0): Image {
        val text = String(payload, Charsets.ISO_8859_1)
        val matrix = QRCodeWriter().encode(
            text,
            BarcodeFormat.QR_CODE,
            0,
            0,
            mapOf(
                EncodeHintType.CHARACTER_SET to "ISO-8859-1",
                EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
                EncodeHintType.MARGIN to QUIET_ZONE_MODULES
            )
        )
        val width = matrix.width * scale
        val height = matrix.height * scale
        val stride = width + extraRowBytes
        val pixels = ByteArray(stride * height) { WHITE }
        for (y in 0 until height) {
            for (x in 0 until width) {
                if (matrix.get(x / scale, y / scale)) pixels[y * stride + x] = BLACK
            }
        }
        return Image(pixels, width, height, stride)
    }

    private fun rotated90(image: Image): Image {
        val rotated = ByteArray(image.height * image.width) { WHITE }
        for (y in 0 until image.height) {
            for (x in 0 until image.width) {
                rotated[x * image.height + (image.height - 1 - y)] = image.pixels[y * image.rowStride + x]
            }
        }
        return Image(rotated, image.height, image.width, image.height)
    }

    private fun decode(image: Image) = QrDecoder.decode(image.pixels, image.width, image.height, image.rowStride)

    private fun vectorPayloads(): List<Pair<String, ByteArray>> {
        val dir = listOf(
            "card/spec/testvectors", "../card/spec/testvectors", "../../card/spec/testvectors",
            "../../../card/spec/testvectors"
        ).map(::File).firstOrNull { it.isDirectory }
            ?: error("card/spec/testvectors not found from ${File(".").absolutePath}")
        val pattern = Regex("\"payload_hex\"\\s*:\\s*\"([0-9a-fA-F]+)\"")
        return dir.listFiles { f -> f.name.endsWith(".json") }!!.sortedBy { it.name }.mapNotNull { file ->
            pattern.find(
                file.readText()
            )?.let { file.name to it.groupValues[1].chunked(2).map { b -> b.toInt(16).toByte() }.toByteArray() }
        }
    }

    @Test
    fun everyCardAndStudyVectorIsReadBackByteForByte_TC_CARD_01_TC_CARD_02() {
        val vectors = vectorPayloads()

        assertThat(vectors.map { it.first }).containsAtLeast("card_batch_valid.json", "study_profile_valid.json")
        assertThat(vectors.size).isAtLeast(12)
        for ((name, payload) in vectors) {
            assertWithMessage(name).that(decode(render(payload))).isEqualTo(payload)
        }
    }

    @Test
    fun aSmallerSymbolAndAFrameWithPaddedRowsAreStillRead() {
        val payload = vectorPayloads().first { it.first == "card_batch_valid.json" }.second

        assertThat(decode(render(payload, scale = 3))).isEqualTo(payload)
        assertThat(decode(render(payload, scale = 5, extraRowBytes = 37))).isEqualTo(payload)
    }

    @Test
    fun aCardHeldSidewaysIsStillRead() {
        val payload = vectorPayloads().first { it.first == "study_profile_valid.json" }.second

        assertThat(decode(rotated90(render(payload)))).isEqualTo(payload)
    }

    @Test
    fun bytesAbove0x7fAreNotChangedByCharacterSetGuessing() {
        val payload = ByteArray(BYTE_VALUES) { it.toByte() } // 0x00 to 0xFF, every value once

        assertThat(decode(render(payload))).isEqualTo(payload)
    }

    @Test
    fun aFrameWithoutASymbolGivesNothing() {
        val blank = Image(ByteArray(FRAME * FRAME) { WHITE }, FRAME, FRAME, FRAME)
        val noise = Image(ByteArray(FRAME * FRAME) { ((it * 7919) % 251).toByte() }, FRAME, FRAME, FRAME)

        assertThat(decode(blank)).isNull()
        assertThat(decode(noise)).isNull()
    }

    @Test
    fun aQrThatIsNotByteModeIsNotOneOfOurs() {
        // Digits only: the encoder picks numeric mode, so there are no byte segments and the symbol is ignored.
        val numeric = render("0123456789012345678901234567890123456789".toByteArray(Charsets.US_ASCII))

        assertThat(decode(numeric)).isNull()
    }

    @Test(expected = IllegalArgumentException::class)
    fun aFrameSmallerThanItsSizeSaysIsRefused() {
        QrDecoder.decode(ByteArray(10), 100, 100)
    }

    private companion object {
        const val WHITE: Byte = -1 // 0xFF
        const val BLACK: Byte = 0
        const val QUIET_ZONE_MODULES = 4
        const val BYTE_VALUES = 256
        const val FRAME = 200
    }
}
