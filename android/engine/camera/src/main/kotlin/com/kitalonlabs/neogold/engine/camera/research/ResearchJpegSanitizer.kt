@file:Suppress(
    "MagicNumber",
    "CyclomaticComplexMethod",
    "LongMethod",
    "NestedBlockDepth",
    "ReturnCount",
    "LoopWithTooManyJumpStatements"
) // byte-level JPEG/EXIF parser: marker codes and offsets are the format itself (ITU T.81, CIPA DC-008)

package com.kitalonlabs.neogold.engine.camera.research

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Sanitizes and re-encodes JPEGs for research mode (doc 8 Section 6.4, doc 7 Section 7).
 *
 * Strict Server Allow-List:
 * - SOI (0xFFD8)
 * - APP0 JFIF without thumbnail (0xFFE0)
 * - APP1 Exif with ONLY orientation tag (0xFFE1)
 * - DQT (0xFFDB)
 * - SOF0 (0xFFC0) or SOF2 (0xFFC2)
 * - DHT (0xFFC4)
 * - DRI (0xFFDD)
 * - SOS (0xFFDA) + scan data
 * - EOI (0xFFD9)
 *
 * Explicitly stripped:
 * - GPS tags
 * - Date-time tags
 * - Maker notes
 * - XMP packets (http://ns.adobe.com/xap/1.0/)
 * - MPF secondary images
 * - Embedded thumbnails
 */
object ResearchJpegSanitizer {

    // Standalone markers without length
    private const val MARKER_SOI = 0xD8
    private const val MARKER_EOI = 0xD9
    private const val MARKER_RST0 = 0xD0
    private const val MARKER_RST7 = 0xD7

    // Allowed markers with length
    private const val MARKER_APP0 = 0xE0
    private const val MARKER_APP1 = 0xE1
    private const val MARKER_SOF0 = 0xC0
    private const val MARKER_SOF2 = 0xC2
    private const val MARKER_DHT = 0xC4
    private const val MARKER_DQT = 0xDB
    private const val MARKER_DRI = 0xDD
    private const val MARKER_SOS = 0xDA

    /**
     * Result of verifying allow-list compliance.
     */
    data class ValidationResult(
        val isValid: Boolean,
        val errorCode: String? = null,
        val violationMessage: String? = null,
        val detectedOrientation: Int? = null,
        val markersPresent: List<String> = emptyList()
    )

    /**
     * Sanitizes a JPEG by keeping only essential image segments and an Exif block with orientation.
     *
     * @param inputJpeg Raw input JPEG byte array.
     * @param forcedOrientation Optional orientation (1..8) override; if null, original orientation is preserved.
     * @return Clean JPEG byte array matching the study intake server allow-list.
     */
    fun sanitize(inputJpeg: ByteArray, forcedOrientation: Int? = null): ByteArray {
        require(inputJpeg.size >= 4) { "Input byte array too small to be a JPEG" }
        require(inputJpeg[0] == 0xFF.toByte() && inputJpeg[1] == 0xD8.toByte()) {
            "Input does not start with JPEG SOI marker (0xFF, 0xD8)"
        }

        val originalOrientation = extractOrientation(inputJpeg) ?: 1
        val orientation = (forcedOrientation ?: originalOrientation).coerceIn(1, 8)

        val out = ByteArrayOutputStream(inputJpeg.size)

        // 1. Write SOI
        out.write(0xFF)
        out.write(MARKER_SOI)

        // 2. Write minimal standard APP0 JFIF (no thumbnail)
        writeMinimalApp0(out)

        // 3. Write minimal APP1 Exif containing ONLY orientation tag
        writeMinimalApp1OrientationOnly(out, orientation)

        // 4. Copy image segments (DQT, SOF0/SOF2, DHT, DRI, SOS + scan data, EOI)
        copyImageSegments(inputJpeg, out)

        return out.toByteArray()
    }

    /**
     * Validates whether a JPEG strictly adheres to doc 7 Section 7 allow-list rules.
     */
    fun validateAllowList(jpegBytes: ByteArray): ValidationResult {
        if (jpegBytes.size < 4) {
            return ValidationResult(false, "IMAGE_INVALID", "File too short to be JPEG")
        }
        if (jpegBytes[0] != 0xFF.toByte() || jpegBytes[1] != 0xD8.toByte()) {
            return ValidationResult(false, "IMAGE_INVALID", "Missing SOI marker")
        }

        var pos = 2
        var orientationFound: Int? = null
        val markers = mutableListOf("SOI")

        while (pos < jpegBytes.size) {
            if (jpegBytes[pos] != 0xFF.toByte()) {
                pos++
                continue
            }
            while (pos < jpegBytes.size && jpegBytes[pos] == 0xFF.toByte()) {
                pos++
            }
            if (pos >= jpegBytes.size) break

            val marker = jpegBytes[pos].toInt() and 0xFF
            pos++

            when (marker) {
                MARKER_SOI -> markers.add("SOI")
                MARKER_EOI -> {
                    markers.add("EOI")
                    break
                }
                in MARKER_RST0..MARKER_RST7 -> {
                    // Restart markers
                }
                MARKER_APP0 -> {
                    markers.add("APP0")
                    if (pos + 2 > jpegBytes.size) return ValidationResult(false, "IMAGE_INVALID", "Truncated APP0")
                    val len = readUint16(jpegBytes, pos)
                    // Verify no thumbnail in APP0 JFIF
                    if (len >= 16) {
                        val xThumb = jpegBytes[pos + 14].toInt() and 0xFF
                        val yThumb = jpegBytes[pos + 15].toInt() and 0xFF
                        if (xThumb > 0 || yThumb > 0) {
                            return ValidationResult(false, "IMAGE_METADATA_NOT_ALLOWED", "APP0 contains thumbnail")
                        }
                    }
                    pos += len
                }
                MARKER_APP1 -> {
                    markers.add("APP1")
                    if (pos + 2 > jpegBytes.size) return ValidationResult(false, "IMAGE_INVALID", "Truncated APP1")
                    val len = readUint16(jpegBytes, pos)
                    val app1Data = jpegBytes.copyOfRange(pos + 2, pos + len)

                    // Check if it's XMP
                    val headerStr = String(app1Data, 0, minOf(30, app1Data.size), Charsets.US_ASCII)
                    if (headerStr.startsWith("http://ns.adobe.com")) {
                        return ValidationResult(
                            false,
                            "IMAGE_METADATA_NOT_ALLOWED",
                            "XMP metadata packet detected in APP1"
                        )
                    }

                    // Check Exif contents: must ONLY contain orientation tag in IFD0
                    val exifCheck = inspectExifStrictly(app1Data)
                    if (!exifCheck.isValid) {
                        return exifCheck
                    }
                    orientationFound = exifCheck.detectedOrientation
                    pos += len
                }
                MARKER_DQT -> {
                    markers.add("DQT")
                    val len = readUint16(jpegBytes, pos)
                    pos += len
                }
                MARKER_SOF0 -> {
                    markers.add("SOF0")
                    val len = readUint16(jpegBytes, pos)
                    pos += len
                }
                MARKER_SOF2 -> {
                    markers.add("SOF2")
                    val len = readUint16(jpegBytes, pos)
                    pos += len
                }
                MARKER_DHT -> {
                    markers.add("DHT")
                    val len = readUint16(jpegBytes, pos)
                    pos += len
                }
                MARKER_DRI -> {
                    markers.add("DRI")
                    val len = readUint16(jpegBytes, pos)
                    pos += len
                }
                MARKER_SOS -> {
                    markers.add("SOS")
                    val len = readUint16(jpegBytes, pos)
                    pos += len

                    // Scan data until next non-stuffed marker
                    while (pos < jpegBytes.size - 1) {
                        if (jpegBytes[pos] == 0xFF.toByte()) {
                            val next = jpegBytes[pos + 1].toInt() and 0xFF
                            if (next == 0x00 || (next in MARKER_RST0..MARKER_RST7)) {
                                pos += 2
                            } else {
                                // Next marker reached
                                break
                            }
                        } else {
                            pos++
                        }
                    }
                }
                else -> {
                    // Any other marker is strictly rejected
                    val markerName = "0x" + marker.toString(16).uppercase()
                    return ValidationResult(
                        isValid = false,
                        errorCode = "IMAGE_METADATA_NOT_ALLOWED",
                        violationMessage = "Marker $markerName is not in allow-list",
                        markersPresent = markers
                    )
                }
            }
        }

        return ValidationResult(
            isValid = true,
            detectedOrientation = orientationFound,
            markersPresent = markers
        )
    }

    /**
     * Inspects an Exif payload: ensures only Tag 0x0112 (Orientation) exists in IFD0 and no SubIFD, GPS, or IFD1.
     */
    private fun inspectExifStrictly(payload: ByteArray): ValidationResult {
        if (payload.size < 14) {
            return ValidationResult(false, "IMAGE_METADATA_NOT_ALLOWED", "Exif payload too short")
        }
        val header = String(payload, 0, 6, Charsets.US_ASCII)
        if (!header.startsWith("Exif\u0000\u0000")) {
            return ValidationResult(false, "IMAGE_METADATA_NOT_ALLOWED", "Non-Exif APP1 payload")
        }

        val tiffStart = 6
        val isLittleEndian = payload[tiffStart] == 'I'.code.toByte() && payload[tiffStart + 1] == 'I'.code.toByte()
        val isBigEndian = payload[tiffStart] == 'M'.code.toByte() && payload[tiffStart + 1] == 'M'.code.toByte()

        if (!isLittleEndian && !isBigEndian) {
            return ValidationResult(false, "IMAGE_METADATA_NOT_ALLOWED", "Invalid TIFF byte order")
        }

        val order = if (isLittleEndian) ByteOrder.LITTLE_ENDIAN else ByteOrder.BIG_ENDIAN
        val buffer = ByteBuffer.wrap(payload, tiffStart, payload.size - tiffStart).slice().order(order)

        buffer.position(4)
        val ifd0Offset = buffer.int
        if (ifd0Offset < 8 || ifd0Offset >= buffer.limit()) {
            return ValidationResult(false, "IMAGE_METADATA_NOT_ALLOWED", "Invalid IFD0 offset")
        }

        buffer.position(ifd0Offset)
        val entryCount = buffer.short.toInt() and 0xFFFF
        if (entryCount != 1) {
            return ValidationResult(
                isValid = false,
                errorCode = "IMAGE_METADATA_NOT_ALLOWED",
                violationMessage = "Exif contains $entryCount tags (only 1 orientation tag permitted)"
            )
        }

        val tag = buffer.short.toInt() and 0xFFFF
        if (tag != 0x0112) {
            return ValidationResult(
                isValid = false,
                errorCode = "IMAGE_METADATA_NOT_ALLOWED",
                violationMessage = "Tag 0x${tag.toString(16)} present; only orientation (0x0112) allowed"
            )
        }

        buffer.short // field type, not inspected
        val count = buffer.int
        val valShort = buffer.short.toInt() and 0xFFFF
        buffer.short // padding

        val nextIfd = buffer.int
        if (nextIfd != 0) {
            return ValidationResult(
                isValid = false,
                errorCode = "IMAGE_METADATA_NOT_ALLOWED",
                violationMessage = "Exif contains secondary IFD (thumbnail)"
            )
        }

        return ValidationResult(isValid = true, detectedOrientation = valShort)
    }

    /**
     * Extracts existing orientation from raw JPEG bytes if present.
     */
    fun extractOrientation(jpegBytes: ByteArray): Int? {
        var pos = 2
        while (pos < jpegBytes.size - 4) {
            if (jpegBytes[pos] != 0xFF.toByte()) {
                pos++
                continue
            }
            val marker = jpegBytes[pos + 1].toInt() and 0xFF
            pos += 2
            if (marker == MARKER_SOI || marker == MARKER_EOI || (marker in MARKER_RST0..MARKER_RST7)) continue

            val len = readUint16(jpegBytes, pos)
            if (marker == MARKER_APP1 && len >= 16) {
                val header = String(jpegBytes, pos + 2, 6, Charsets.US_ASCII)
                if (header.startsWith("Exif\u0000\u0000")) {
                    val tiffStart = pos + 8
                    val isLE = jpegBytes[tiffStart] == 'I'.code.toByte()
                    val order = if (isLE) ByteOrder.LITTLE_ENDIAN else ByteOrder.BIG_ENDIAN
                    val buf = ByteBuffer.wrap(jpegBytes, tiffStart, len - 8).order(order)
                    buf.position(4)
                    val ifd0 = buf.int
                    if (ifd0 in 8 until (len - 8)) {
                        buf.position(ifd0)
                        val count = buf.short.toInt() and 0xFFFF
                        var entry = 0
                        while (entry < count && buf.remaining() >= 12) {
                            entry++
                            val tag = buf.short.toInt() and 0xFFFF
                            buf.short // field type, not inspected
                            buf.int // value count, not inspected
                            if (tag == 0x0112) {
                                return buf.short.toInt() and 0xFFFF
                            } else {
                                buf.int // skip value
                            }
                        }
                    }
                }
            }
            pos += len
        }
        return null
    }

    private fun writeMinimalApp0(out: ByteArrayOutputStream) {
        out.write(0xFF)
        out.write(MARKER_APP0)
        out.write(0x00)
        out.write(0x10) // length = 16

        out.write('J'.code)
        out.write('F'.code)
        out.write('I'.code)
        out.write('F'.code)
        out.write(0x00) // "JFIF\0"

        out.write(0x01) // Major version 1
        out.write(0x01) // Minor version 1
        out.write(0x00) // Density units (0 = no units)
        out.write(0x00)
        out.write(0x01) // Xdensity = 1
        out.write(0x00)
        out.write(0x01) // Ydensity = 1
        out.write(0x00) // Xthumbnail = 0
        out.write(0x00) // Ythumbnail = 0
    }

    private fun writeMinimalApp1OrientationOnly(out: ByteArrayOutputStream, orientation: Int) {
        val app1Payload = ByteArray(32)
        // Exif identifier
        "Exif\u0000\u0000".toByteArray(Charsets.US_ASCII).copyInto(app1Payload, 0)

        // TIFF header (Little Endian "II")
        val buf = ByteBuffer.wrap(app1Payload, 6, 26).order(ByteOrder.LITTLE_ENDIAN)
        buf.put('I'.code.toByte())
        buf.put('I'.code.toByte())
        buf.putShort(42.toShort()) // Magic
        buf.putInt(8) // IFD0 offset relative to TIFF header start

        // IFD0: 1 entry
        buf.putShort(1.toShort()) // Entry count
        buf.putShort(0x0112.toShort()) // Tag 0x0112 = Orientation
        buf.putShort(3.toShort()) // Type = SHORT
        buf.putInt(1) // Count = 1
        buf.putShort(orientation.toShort()) // Value
        buf.putShort(0.toShort()) // Padding
        buf.putInt(0) // Next IFD = 0 (no thumbnail)

        val totalLen = app1Payload.size + 2
        out.write(0xFF)
        out.write(MARKER_APP1)
        out.write((totalLen shr 8) and 0xFF)
        out.write(totalLen and 0xFF)
        out.write(app1Payload)
    }

    private fun copyImageSegments(src: ByteArray, dst: ByteArrayOutputStream) {
        var pos = 2
        while (pos < src.size) {
            if (src[pos] != 0xFF.toByte()) {
                pos++
                continue
            }
            while (pos < src.size && src[pos] == 0xFF.toByte()) {
                pos++
            }
            if (pos >= src.size) break

            val marker = src[pos].toInt() and 0xFF
            pos++

            when (marker) {
                MARKER_SOI, MARKER_APP0, MARKER_APP1 -> {
                    // Already emitted or skipped
                    val len = readUint16(src, pos)
                    pos += len
                }
                MARKER_EOI -> {
                    dst.write(0xFF)
                    dst.write(MARKER_EOI)
                    break
                }
                in MARKER_RST0..MARKER_RST7 -> {
                    // Copy restart marker
                    dst.write(0xFF)
                    dst.write(marker)
                }
                MARKER_DQT, MARKER_SOF0, MARKER_SOF2, MARKER_DHT, MARKER_DRI -> {
                    val len = readUint16(src, pos)
                    dst.write(0xFF)
                    dst.write(marker)
                    dst.write(src, pos, len)
                    pos += len
                }
                MARKER_SOS -> {
                    val len = readUint16(src, pos)
                    dst.write(0xFF)
                    dst.write(MARKER_SOS)
                    dst.write(src, pos, len)
                    pos += len

                    // Copy scan data
                    while (pos < src.size - 1) {
                        if (src[pos] == 0xFF.toByte()) {
                            val next = src[pos + 1].toInt() and 0xFF
                            if (next == 0x00 || (next in MARKER_RST0..MARKER_RST7)) {
                                dst.write(src[pos].toInt())
                                dst.write(src[pos + 1].toInt())
                                pos += 2
                            } else {
                                break
                            }
                        } else {
                            dst.write(src[pos].toInt())
                            pos++
                        }
                    }
                }
                else -> {
                    // Skip any other marker (APP2..APP15, COM, etc.)
                    val len = readUint16(src, pos)
                    pos += len
                }
            }
        }
    }

    private fun readUint16(data: ByteArray, offset: Int): Int {
        val b0 = data[offset].toInt() and 0xFF
        val b1 = data[offset + 1].toInt() and 0xFF
        return (b0 shl 8) or b1
    }
}
