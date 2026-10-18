package com.kitalonlabs.neogold.engine.cardtrust

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.time.LocalDate

/**
 * Strict binary parser for QR payloads per qr-formats.md Section 2 and 3.
 * Zero-allocation wherever possible, zero external dependencies, robust against malformed/fuzzed inputs.
 */
object PayloadParser {

    val EPOCH: LocalDate = LocalDate.of(2020, 1, 1)

    private val BATCH_ID_REGEX = Regex("^[A-Z0-9][A-Z0-9-]{2,31}$")
    private val STUDY_CODE_REGEX = Regex("^[A-Za-z0-9_-]{2,32}$")

    /**
     * Parses a raw byte array as a Card Batch payload (type = 0x01).
     * Returns null if malformed, truncated, containing trailing bytes, or unsupported version.
     */
    fun parseCardBatch(payload: ByteArray): CardBatchPayload? {
        // Minimum possible card batch payload length:
        // 6 (header) + 1+3 (issuer) + 1+3 (batch) + 1 (patch_count) + 23*6 (patches) + 64 (sig) = 217 bytes
        if (payload.size < 6 + 1 + 3 + 1 + 3 + 1 + (23 * 6) + 64) {
            return null
        }

        val buf = ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN)

        val payloadType = buf.get().toInt() and 0xFF
        val formatVersion = buf.get().toInt() and 0xFF
        if (payloadType != 0x01 || formatVersion != 0x01) {
            return null
        }

        val issuedAtDays = buf.short.toInt() and 0xFFFF
        val expiresAtDays = buf.short.toInt() and 0xFFFF

        val kLen = buf.get().toInt() and 0xFF
        if (kLen < 3 || kLen > 32 || buf.remaining() < kLen + 1 + 3 + 1 + (23 * 6) + 64) {
            return null
        }
        val issuerBytes = ByteArray(kLen)
        buf.get(issuerBytes)
        if (!isAsciiPrintable(issuerBytes)) {
            return null
        }
        val issuerKeyId = String(issuerBytes, StandardCharsets.US_ASCII)

        val bLen = buf.get().toInt() and 0xFF
        if (bLen < 3 || bLen > 32 || buf.remaining() < bLen + 1 + (23 * 6) + 64) {
            return null
        }
        val batchBytes = ByteArray(bLen)
        buf.get(batchBytes)
        if (!isAsciiPrintable(batchBytes)) {
            return null
        }
        val batchId = String(batchBytes, StandardCharsets.US_ASCII)
        if (!BATCH_ID_REGEX.matches(batchId)) {
            return null
        }

        val patchCount = buf.get().toInt() and 0xFF
        if (patchCount != 23) {
            return null
        }

        val expectedRemaining = (patchCount * 6) + 64
        if (buf.remaining() != expectedRemaining) {
            return null
        }

        val patches = ArrayList<PatchReference>(patchCount)
        for (i in 0 until patchCount) {
            val rawL = buf.short.toInt() and 0xFFFF
            val rawA = buf.short.toInt() // signed int16
            val rawB = buf.short.toInt() // signed int16

            val lStar = rawL / 100.0
            val aStar = rawA / 100.0
            val bStar = rawB / 100.0

            patches.add(PatchReference(index = i, lStar = lStar, aStar = aStar, bStar = bStar))
        }

        val signedLength = payload.size - 64
        val signedBytes = payload.copyOfRange(0, signedLength)
        val signature = payload.copyOfRange(signedLength, payload.size)

        val issuedAt = EPOCH.plusDays(issuedAtDays.toLong())
        val expiresAt = EPOCH.plusDays(expiresAtDays.toLong())

        return CardBatchPayload(
            formatVersion = formatVersion,
            issuedAtDays = issuedAtDays,
            expiresAtDays = expiresAtDays,
            issuedAt = issuedAt,
            expiresAt = expiresAt,
            issuerKeyId = issuerKeyId,
            batchId = batchId,
            patchCount = patchCount,
            patches = patches,
            signedBytes = signedBytes,
            signature = signature
        )
    }

    /**
     * Parses a raw byte array as a Study Profile payload (type = 0x02).
     * Returns null if malformed, truncated, containing trailing bytes, or unsupported version.
     */
    fun parseStudyProfile(payload: ByteArray): StudyProfilePayload? {
        // Minimum possible study profile payload length:
        // 5 (header) + 1+3 (issuer) + 1+2 (code) + 1+1 (name) + 1+1 (partner) + 1+0 (host) + 32 (pub) + 64 (sig) = 113 bytes
        if (payload.size < 5 + 1 + 3 + 1 + 2 + 1 + 1 + 1 + 1 + 1 + 32 + 64) {
            return null
        }

        val buf = ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN)

        val payloadType = buf.get().toInt() and 0xFF
        val formatVersion = buf.get().toInt() and 0xFF
        if (payloadType != 0x02 || formatVersion != 0x01) {
            return null
        }

        val expiresAtDays = buf.short.toInt() and 0xFFFF
        val tpCode = buf.get().toInt() and 0xFF
        val timestampPrecision = when (tpCode) {
            0 -> TimestampPrecision.DAY_PLUS_OFFSETS
            1 -> TimestampPrecision.FULL
            else -> return null
        }

        val kLen = buf.get().toInt() and 0xFF
        if (kLen < 3 || kLen > 32 || buf.remaining() < kLen + 1 + 2 + 1 + 1 + 1 + 1 + 1 + 32 + 64) {
            return null
        }
        val issuerBytes = ByteArray(kLen)
        buf.get(issuerBytes)
        if (!isAsciiPrintable(issuerBytes)) {
            return null
        }
        val issuerKeyId = String(issuerBytes, StandardCharsets.US_ASCII)

        val sLen = buf.get().toInt() and 0xFF
        if (sLen < 2 || sLen > 32 || buf.remaining() < sLen + 1 + 1 + 1 + 1 + 1 + 32 + 64) {
            return null
        }
        val codeBytes = ByteArray(sLen)
        buf.get(codeBytes)
        if (!isAsciiPrintable(codeBytes)) {
            return null
        }
        val studyCode = String(codeBytes, StandardCharsets.US_ASCII)
        if (!STUDY_CODE_REGEX.matches(studyCode)) {
            return null
        }

        val dLen = buf.get().toInt() and 0xFF
        if (dLen < 1 || dLen > 128 || buf.remaining() < dLen + 1 + 1 + 1 + 32 + 64) {
            return null
        }
        val nameBytes = ByteArray(dLen)
        buf.get(nameBytes)
        val displayName = try {
            val decoded = String(nameBytes, StandardCharsets.UTF_8)
            // Verify UTF-8 round-trip
            if (!decoded.toByteArray(StandardCharsets.UTF_8).contentEquals(nameBytes)) return null
            decoded
        } catch (_: Exception) {
            return null
        }

        val pLen = buf.get().toInt() and 0xFF
        if (pLen < 1 || pLen > 128 || buf.remaining() < pLen + 1 + 32 + 64) {
            return null
        }
        val partnerBytes = ByteArray(pLen)
        buf.get(partnerBytes)
        val partner = try {
            val decoded = String(partnerBytes, StandardCharsets.UTF_8)
            if (!decoded.toByteArray(StandardCharsets.UTF_8).contentEquals(partnerBytes)) return null
            decoded
        } catch (_: Exception) {
            return null
        }

        val hLen = buf.get().toInt() and 0xFF
        if (hLen > 128 || buf.remaining() < hLen + 32 + 64) {
            return null
        }
        val host: String? = if (hLen > 0) {
            val hostBytes = ByteArray(hLen)
            buf.get(hostBytes)
            if (!isAsciiPrintable(hostBytes)) return null
            String(hostBytes, StandardCharsets.US_ASCII)
        } else {
            null
        }

        if (buf.remaining() != 32 + 64) {
            return null
        }

        val exportPublicKey = ByteArray(32)
        buf.get(exportPublicKey)

        val signedLength = payload.size - 64
        val signedBytes = payload.copyOfRange(0, signedLength)
        val signature = payload.copyOfRange(signedLength, payload.size)

        val expiresAt = EPOCH.plusDays(expiresAtDays.toLong())

        return StudyProfilePayload(
            formatVersion = formatVersion,
            expiresAtDays = expiresAtDays,
            expiresAt = expiresAt,
            timestampPrecision = timestampPrecision,
            issuerKeyId = issuerKeyId,
            studyCode = studyCode,
            displayName = displayName,
            partner = partner,
            host = host,
            exportPublicKey = exportPublicKey,
            signedBytes = signedBytes,
            signature = signature
        )
    }

    internal fun isAsciiPrintable(bytes: ByteArray): Boolean {
        for (b in bytes) {
            val code = b.toInt() and 0xFF
            // Printable ASCII: 0x20 (' ') through 0x7E ('~')
            if (code < 0x20 || code > 0x7E) {
                return false
            }
        }
        return true
    }
}
