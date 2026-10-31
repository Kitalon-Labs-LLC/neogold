package com.kitalonlabs.neogold.engine.camera.research

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream

/**
 * Tests JPEG sanitizer and allow-list validator for research mode.
 * Document 8 Section 6.4, Document 7 Section 7.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ResearchJpegSanitizerTest {

    private fun createSyntheticJpeg(width: Int = 100, height: Int = 100): ByteArray {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bitmap)
        canvas.drawColor(Color.YELLOW)
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
        bitmap.recycle()
        return out.toByteArray()
    }

    @Test
    fun testSanitizeRemovesDisallowedMetadataAndKeepsAllowList() {
        val rawJpeg = createSyntheticJpeg(80, 80)
        assertThat(rawJpeg.size).isGreaterThan(100)

        // Inject a simulated unauthorized marker (COM marker 0xFFFE with text "GPS: 23.8103, 90.4125")
        val injectedStream = ByteArrayOutputStream()
        injectedStream.write(rawJpeg, 0, 2) // SOI

        // Inject COM marker
        val comText = "GPS: 23.8103, 90.4125, Date: 2026-10-18T10:00:00Z".toByteArray(Charsets.UTF_8)
        val comLen = comText.size + 2
        injectedStream.write(0xFF)
        injectedStream.write(0xFE) // COM
        injectedStream.write((comLen shr 8) and 0xFF)
        injectedStream.write(comLen and 0xFF)
        injectedStream.write(comText)

        // Append rest of JPEG
        injectedStream.write(rawJpeg, 2, rawJpeg.size - 2)
        val rawWithGpsComment = injectedStream.toByteArray()

        // Before sanitization, validator MUST reject it
        val preValidation = ResearchJpegSanitizer.validateAllowList(rawWithGpsComment)
        assertThat(preValidation.isValid).isFalse()
        assertThat(preValidation.errorCode).isEqualTo("IMAGE_METADATA_NOT_ALLOWED")

        // Run sanitizer
        val sanitized = ResearchJpegSanitizer.sanitize(rawWithGpsComment, forcedOrientation = 6)

        // After sanitization, validator MUST pass
        val postValidation = ResearchJpegSanitizer.validateAllowList(sanitized)
        assertThat(postValidation.isValid).isTrue()
        assertThat(postValidation.detectedOrientation).isEqualTo(6)
        assertThat(
            postValidation.markersPresent.distinct()
        ).containsExactly("SOI", "APP0", "APP1", "DQT", "SOF0", "DHT", "SOS", "EOI")

        // Decodable by standard BitmapFactory
        val decoded = BitmapFactory.decodeByteArray(sanitized, 0, sanitized.size)
        assertThat(decoded).isNotNull()
        assertThat(decoded.width).isEqualTo(80)
        assertThat(decoded.height).isEqualTo(80)
        decoded.recycle()
    }

    @Test
    fun testOrientationPreserved() {
        val rawJpeg = createSyntheticJpeg(60, 60)
        val sanitized = ResearchJpegSanitizer.sanitize(rawJpeg, forcedOrientation = 3)

        val report = ResearchJpegSanitizer.validateAllowList(sanitized)
        assertThat(report.isValid).isTrue()
        assertThat(report.detectedOrientation).isEqualTo(3)
    }

    @Test
    fun testRejectsXmpInApp1() {
        val rawJpeg = createSyntheticJpeg(50, 50)
        // Construct JPEG with an XMP packet in APP1
        val stream = ByteArrayOutputStream()
        stream.write(rawJpeg, 0, 2) // SOI

        val xmpBytes = "http://ns.adobe.com/xap/1.0/\u0000<x:xmpmeta>Location</x:xmpmeta>".toByteArray(Charsets.UTF_8)
        val app1Len = xmpBytes.size + 2
        stream.write(0xFF)
        stream.write(0xE1) // APP1
        stream.write((app1Len shr 8) and 0xFF)
        stream.write(app1Len and 0xFF)
        stream.write(xmpBytes)
        stream.write(rawJpeg, 2, rawJpeg.size - 2)

        val report = ResearchJpegSanitizer.validateAllowList(stream.toByteArray())
        assertThat(report.isValid).isFalse()
        assertThat(report.errorCode).isEqualTo("IMAGE_METADATA_NOT_ALLOWED")
        assertThat(report.violationMessage).contains("XMP")
    }

    /** A JPEG with one EXIF tag, orientation [orientation], in the byte order [littleEndian] (as a phone writes it). */
    private fun jpegWithOrientation(orientation: Int, littleEndian: Boolean): ByteArray {
        val raw = createSyntheticJpeg(40, 40)
        fun u16(
            v: Int
        ) = if (littleEndian) {
            byteArrayOf(
                v.toByte(),
                (v shr 8).toByte()
            )
        } else {
            byteArrayOf((v shr 8).toByte(), v.toByte())
        }
        fun u32(v: Int) = if (littleEndian) {
            byteArrayOf(v.toByte(), (v shr 8).toByte(), (v shr 16).toByte(), (v shr 24).toByte())
        } else {
            byteArrayOf((v shr 24).toByte(), (v shr 16).toByte(), (v shr 8).toByte(), v.toByte())
        }
        val tiff = ByteArrayOutputStream().apply {
            write(if (littleEndian) "II".toByteArray() else "MM".toByteArray())
            write(u16(42))
            write(u32(8)) // IFD0 starts right after this 8-byte header
            write(u16(1)) // one entry
            write(u16(0x0112)) // orientation
            write(u16(3)) // SHORT
            write(u32(1))
            write(u16(orientation))
            write(u16(0)) // padding
            write(u32(0)) // no next IFD
        }.toByteArray()
        val payload = "Exif\u0000\u0000".toByteArray(Charsets.US_ASCII) + tiff
        val out = ByteArrayOutputStream()
        out.write(raw, 0, 2) // SOI
        out.write(0xFF)
        out.write(0xE1) // APP1
        out.write(((payload.size + 2) shr 8) and 0xFF)
        out.write((payload.size + 2) and 0xFF)
        out.write(payload)
        out.write(raw, 2, raw.size - 2)
        return out.toByteArray()
    }

    @Test
    fun orientationIsReadFromTheExifOfARealPhoto_BUG_2026_039() {
        for (orientation in 1..8) {
            assertThat(ResearchJpegSanitizer.extractOrientation(jpegWithOrientation(orientation, littleEndian = true)))
                .isEqualTo(orientation)
            assertThat(ResearchJpegSanitizer.extractOrientation(jpegWithOrientation(orientation, littleEndian = false)))
                .isEqualTo(orientation)
        }
    }

    @Test
    fun sanitizingKeepsTheOriginalOrientationWhenNoneIsForced_BUG_2026_039() {
        val sanitized = ResearchJpegSanitizer.sanitize(jpegWithOrientation(6, littleEndian = true))

        val report = ResearchJpegSanitizer.validateAllowList(sanitized)
        assertThat(report.isValid).isTrue()
        assertThat(report.detectedOrientation).isEqualTo(6)
    }

    @Test
    fun aJpegWithoutExifOrWithATruncatedBlockGivesNoOrientation() {
        assertThat(ResearchJpegSanitizer.extractOrientation(createSyntheticJpeg())).isNull()
        val whole = jpegWithOrientation(6, littleEndian = true)
        assertThat(ResearchJpegSanitizer.extractOrientation(whole.copyOf(20))).isNull()
    }
}
