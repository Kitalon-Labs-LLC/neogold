package com.kitalonlabs.neogold.engine.cardtrust

import com.google.crypto.tink.subtle.Ed25519Verify
import java.nio.charset.StandardCharsets
import java.security.GeneralSecurityException
import java.time.LocalDate

/**
 * Offline card trust and study profile verification engine (doc 4 Section 7, doc 8 Section 4).
 * Pure Kotlin, uses Google Tink for RFC 8032 Ed25519 verification.
 */
class CardTrustVerifier(
    val trustList: TrustList
) {

    /**
     * Verifies a Card Batch QR binary payload per qr-formats.md Section 4.2.
     *
     * @param payloadBytes Raw bytes decoded from QR code.
     * @param evaluationDate Date to evaluate validity against (defaults to today).
     * @return Typed [VerificationResult].
     */
    fun verifyCardBatch(
        payloadBytes: ByteArray,
        evaluationDate: LocalDate = LocalDate.now()
    ): VerificationResult<CardBatchPayload> {
        // Step 1: Magic byte, version, and binary minimum length check
        if (payloadBytes.size < 6 + 1 + 3 + 1 + 3 + 1 + 64) {
            return VerificationResult.Malformed
        }

        val payloadType = payloadBytes[0].toInt() and 0xFF
        val formatVersion = payloadBytes[1].toInt() and 0xFF
        if (payloadType != 0x01 || formatVersion != 0x01) {
            return VerificationResult.Malformed
        }

        var offset = 6
        val kLen = payloadBytes[offset].toInt() and 0xFF
        offset += 1
        if (kLen < 3 || kLen > 32 || offset + kLen > payloadBytes.size - 64) {
            return VerificationResult.Malformed
        }
        val issuerBytes = payloadBytes.copyOfRange(offset, offset + kLen)
        if (!PayloadParser.isAsciiPrintable(issuerBytes)) {
            return VerificationResult.Malformed
        }
        val issuerKeyId = String(issuerBytes, StandardCharsets.US_ASCII)
        offset += kLen

        val bLen = payloadBytes[offset].toInt() and 0xFF
        offset += 1
        if (bLen < 3 || bLen > 32 || offset + bLen > payloadBytes.size - 64) {
            return VerificationResult.Malformed
        }
        offset += bLen

        val nPatches = payloadBytes[offset].toInt() and 0xFF
        offset += 1
        val patchesBytesLen = nPatches * 6
        if (payloadBytes.size < offset + patchesBytesLen + 64) {
            return VerificationResult.Malformed
        }
        offset += patchesBytesLen

        // Step 2: Issuer lookup
        val key = trustList.keys.firstOrNull { it.keyId == issuerKeyId }
            ?: return VerificationResult.UnknownIssuer

        // Step 3: Purpose binding (CARD_ISSUER required)
        if (key.purpose != KeyPurpose.CARD_ISSUER) {
            return VerificationResult.WrongPurpose
        }

        // Step 4: Signature verification using Tink Ed25519Verify
        val signedBytes = payloadBytes.copyOfRange(0, offset)
        val signature = payloadBytes.copyOfRange(offset, offset + 64)

        val signatureValid = try {
            val verifier = Ed25519Verify(key.publicKeyBytes)
            verifier.verify(signature, signedBytes)
            true
        } catch (_: GeneralSecurityException) {
            false
        } catch (_: Exception) {
            false
        }

        if (!signatureValid) {
            return VerificationResult.BadSignature
        }

        // Structural check: reject trailing bytes
        if (payloadBytes.size != offset + 64) {
            return VerificationResult.Malformed
        }

        // Parse and validate full payload fields
        val payload = PayloadParser.parseCardBatch(payloadBytes)
            ?: return VerificationResult.Malformed

        // Step 5: Revocation check
        if (payload.batchId in trustList.revokedCardBatches) {
            return VerificationResult.Revoked
        }

        // Step 6: Date validity check
        // 6a: Trust list validity window
        if (evaluationDate.isBefore(trustList.validFrom) || evaluationDate.isAfter(trustList.validUntil)) {
            return VerificationResult.Expired
        }
        // 6b: Issuer key validity window
        if (evaluationDate.isBefore(key.validFrom) || evaluationDate.isAfter(key.validUntil)) {
            return VerificationResult.Expired
        }
        // 6c: Card batch expiration date
        if (evaluationDate.isAfter(payload.expiresAt)) {
            return VerificationResult.Expired
        }

        // Step 7: Success
        return VerificationResult.Verified(payload)
    }

    /**
     * Verifies a Study Profile QR binary payload per qr-formats.md Section 4.2.
     *
     * @param payloadBytes Raw bytes decoded from QR code.
     * @param evaluationDate Date to evaluate validity against (defaults to today).
     * @return Typed [VerificationResult].
     */
    fun verifyStudyProfile(
        payloadBytes: ByteArray,
        evaluationDate: LocalDate = LocalDate.now()
    ): VerificationResult<StudyProfilePayload> {
        // Step 1: Magic byte, version, and binary minimum length check
        if (payloadBytes.size < 5 + 1 + 3 + 1 + 2 + 1 + 1 + 1 + 1 + 1 + 32 + 64) {
            return VerificationResult.Malformed
        }

        val payloadType = payloadBytes[0].toInt() and 0xFF
        val formatVersion = payloadBytes[1].toInt() and 0xFF
        if (payloadType != 0x02 || formatVersion != 0x01) {
            return VerificationResult.Malformed
        }

        var offset = 5
        val kLen = payloadBytes[offset].toInt() and 0xFF
        offset += 1
        if (kLen < 3 || kLen > 32 || offset + kLen > payloadBytes.size - 64) {
            return VerificationResult.Malformed
        }
        val issuerBytes = payloadBytes.copyOfRange(offset, offset + kLen)
        if (!PayloadParser.isAsciiPrintable(issuerBytes)) {
            return VerificationResult.Malformed
        }
        val issuerKeyId = String(issuerBytes, StandardCharsets.US_ASCII)
        offset += kLen

        val sLen = payloadBytes[offset].toInt() and 0xFF
        offset += 1
        if (sLen < 2 || sLen > 32 || offset + sLen > payloadBytes.size - 64) {
            return VerificationResult.Malformed
        }
        offset += sLen

        val dLen = payloadBytes[offset].toInt() and 0xFF
        offset += 1
        if (dLen < 1 || dLen > 128 || offset + dLen > payloadBytes.size - 64) {
            return VerificationResult.Malformed
        }
        offset += dLen

        val pLen = payloadBytes[offset].toInt() and 0xFF
        offset += 1
        if (pLen < 1 || pLen > 128 || offset + pLen > payloadBytes.size - 64) {
            return VerificationResult.Malformed
        }
        offset += pLen

        val hLen = payloadBytes[offset].toInt() and 0xFF
        offset += 1
        if (hLen > 128 || offset + hLen > payloadBytes.size - 64) {
            return VerificationResult.Malformed
        }
        offset += hLen

        // export public key (32 bytes)
        if (payloadBytes.size < offset + 32 + 64) {
            return VerificationResult.Malformed
        }
        offset += 32

        // Step 2: Issuer lookup
        val key = trustList.keys.firstOrNull { it.keyId == issuerKeyId }
            ?: return VerificationResult.UnknownIssuer

        // Step 3: Purpose binding (STUDY_ISSUER required)
        if (key.purpose != KeyPurpose.STUDY_ISSUER) {
            return VerificationResult.WrongPurpose
        }

        // Step 4: Signature verification using Tink Ed25519Verify
        val signedBytes = payloadBytes.copyOfRange(0, offset)
        val signature = payloadBytes.copyOfRange(offset, offset + 64)

        val signatureValid = try {
            val verifier = Ed25519Verify(key.publicKeyBytes)
            verifier.verify(signature, signedBytes)
            true
        } catch (_: GeneralSecurityException) {
            false
        } catch (_: Exception) {
            false
        }

        if (!signatureValid) {
            return VerificationResult.BadSignature
        }

        // Structural check: reject trailing bytes
        if (payloadBytes.size != offset + 64) {
            return VerificationResult.Malformed
        }

        val payload = PayloadParser.parseStudyProfile(payloadBytes)
            ?: return VerificationResult.Malformed

        // Step 5: Revocation check
        if (payload.studyCode in trustList.revokedStudyCodes) {
            return VerificationResult.Revoked
        }

        // Step 6: Date validity check
        // 6a: Trust list validity window
        if (evaluationDate.isBefore(trustList.validFrom) || evaluationDate.isAfter(trustList.validUntil)) {
            return VerificationResult.Expired
        }
        // 6b: Issuer key validity window
        if (evaluationDate.isBefore(key.validFrom) || evaluationDate.isAfter(key.validUntil)) {
            return VerificationResult.Expired
        }
        // 6c: Study profile expiration date
        if (evaluationDate.isAfter(payload.expiresAt)) {
            return VerificationResult.Expired
        }

        // Step 7: Success
        return VerificationResult.Verified(payload)
    }

    /**
     * Generic verification examining magic payload_type byte.
     */
    fun verifyAny(
        payloadBytes: ByteArray,
        evaluationDate: LocalDate = LocalDate.now()
    ): VerificationResult<Any> {
        if (payloadBytes.isEmpty()) return VerificationResult.Malformed
        return when (payloadBytes[0].toInt() and 0xFF) {
            0x01 -> verifyCardBatch(payloadBytes, evaluationDate)
            0x02 -> verifyStudyProfile(payloadBytes, evaluationDate)
            else -> VerificationResult.Malformed
        }
    }
}
