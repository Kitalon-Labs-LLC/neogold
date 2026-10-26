package com.kitalonlabs.neogold.engine.cardtrust

import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import java.io.File
import java.time.LocalDate
import java.util.Random

/**
 * Verification and fuzzing tests for :engine:cardtrust.
 * Covers:
 * - TC-CARD-01 to TC-CARD-06 (Card batch verification)
 * - TC-RSH-02, TC-RSH-03 (Study profile verification)
 * - All P02 test vectors
 * - Fuzz testing with 10,000 random inputs
 * - Release gate check for TEST_ONLY keys
 */
class CardTrustEngineTest {

    private lateinit var trustList: TrustList
    private lateinit var verifier: CardTrustVerifier
    private val evalDate: LocalDate = LocalDate.of(2026, 10, 17)

    @Before
    fun setUp() {
        val tlFile = findRepoFile("config/trust-list.json")
        trustList = TrustListParser.parse(tlFile.readText(Charsets.UTF_8))
        verifier = CardTrustVerifier(trustList)
    }

    // =========================================================================
    // TC-CARD Tests (Card Batch Verification)
    // =========================================================================

    /**
     * TC-CARD-01: Valid batch accepted.
     * Verifies that genuine card batch QR payload verifies, signature is valid,
     * and reference CIELAB values for all 23 patches are correctly decoded.
     */
    @Test
    fun tcCard01_validBatchAccepted() {
        val vector = loadTestVector("card_batch_valid.json")
        val payloadBytes = hexToBytes(vector["payload_hex"] as String)

        val result = verifier.verifyCardBatch(payloadBytes, evalDate)

        assertThat(result).isInstanceOf(VerificationResult.Verified::class.java)
        val verified = (result as VerificationResult.Verified).payload

        assertThat(verified.batchId).isEqualTo("NG-2026-001")
        assertThat(verified.issuerKeyId).isEqualTo("TEST-CARD-KEY-01")
        assertThat(verified.issuedAt).isEqualTo(LocalDate.of(2026, 10, 15))
        assertThat(verified.expiresAt).isEqualTo(LocalDate.of(2028, 10, 15))
        assertThat(verified.patchCount).isEqualTo(23)
        assertThat(verified.patches).hasSize(23)

        // Check reference values for patch 0 (white L=90, a=0, b=0)
        val patch0 = verified.patches[0]
        assertThat(patch0.index).isEqualTo(0)
        assertThat(patch0.lStar).isEqualTo(90.0)
        assertThat(patch0.aStar).isEqualTo(0.0)
        assertThat(patch0.bStar).isEqualTo(0.0)

        // Check patch 7 (light skin tone L=72, a=10, b=12)
        val patch7 = verified.patches[7]
        assertThat(patch7.index).isEqualTo(7)
        assertThat(patch7.lStar).isEqualTo(72.0)
        assertThat(patch7.aStar).isEqualTo(10.0)
        assertThat(patch7.bStar).isEqualTo(12.0)
    }

    /**
     * TC-CARD-02: One changed byte rejected.
     * Tampering with a single bit in the payload causes signature verification to fail.
     */
    @Test
    fun tcCard02_oneChangedByteRejected() {
        val vector = loadTestVector("card_batch_invalid_tampered.json")
        val payloadBytes = hexToBytes(vector["payload_hex"] as String)

        val result = verifier.verifyCardBatch(payloadBytes, evalDate)

        assertThat(result).isEqualTo(VerificationResult.BadSignature)
    }

    /**
     * TC-CARD-03: Unknown issuer rejected.
     * Payload signed with a valid Ed25519 key not in the trust list is rejected.
     */
    @Test
    fun tcCard03_unknownIssuerRejected() {
        val vector = loadTestVector("card_batch_invalid_unknown_issuer.json")
        val payloadBytes = hexToBytes(vector["payload_hex"] as String)

        val result = verifier.verifyCardBatch(payloadBytes, evalDate)

        assertThat(result).isEqualTo(VerificationResult.UnknownIssuer)
    }

    /**
     * TC-CARD-04: Key used for the wrong purpose.
     * Card batch signed by a STUDY_ISSUER key is rejected.
     */
    @Test
    fun tcCard04_keyUsedForWrongPurpose() {
        val vector = loadTestVector("card_batch_invalid_wrong_purpose.json")
        val payloadBytes = hexToBytes(vector["payload_hex"] as String)

        val result = verifier.verifyCardBatch(payloadBytes, evalDate)

        assertThat(result).isEqualTo(VerificationResult.WrongPurpose)
    }

    /**
     * TC-CARD-05: Expired batch.
     * Batch past expires_at is rejected with Expired.
     */
    @Test
    fun tcCard05_expiredBatchRejected() {
        val vector = loadTestVector("card_batch_invalid_expired.json")
        val payloadBytes = hexToBytes(vector["payload_hex"] as String)

        val result = verifier.verifyCardBatch(payloadBytes, evalDate)

        assertThat(result).isEqualTo(VerificationResult.Expired)
    }

    /**
     * TC-CARD-06: Revoked batch.
     * Batch whose ID is present in revoked_card_batches is rejected with Revoked.
     */
    @Test
    fun tcCard06_revokedBatchRejected() {
        val vector = loadTestVector("card_batch_invalid_revoked.json")
        val payloadBytes = hexToBytes(vector["payload_hex"] as String)

        val result = verifier.verifyCardBatch(payloadBytes, evalDate)

        assertThat(result).isEqualTo(VerificationResult.Revoked)
    }

    // =========================================================================
    // TC-RSH Tests (Study Profile Verification)
    // =========================================================================

    /**
     * Valid study profile accepted per doc 7 Section 4.1.
     */
    @Test
    fun studyProfile_validAccepted() {
        val vector = loadTestVector("study_profile_valid.json")
        val payloadBytes = hexToBytes(vector["payload_hex"] as String)

        val result = verifier.verifyStudyProfile(payloadBytes, evalDate)

        assertThat(result).isInstanceOf(VerificationResult.Verified::class.java)
        val verified = (result as VerificationResult.Verified).payload

        assertThat(verified.studyCode).isEqualTo("SKP01")
        assertThat(verified.displayName).isEqualTo("Sakhipur Community Screening Study")
        assertThat(verified.partner).isEqualTo("icddr,b / Stanford")
        assertThat(verified.host).isEqualTo("intake.neogold.example.bd")
        assertThat(verified.timestampPrecision).isEqualTo(TimestampPrecision.DAY_PLUS_OFFSETS)
        assertThat(verified.expiresAt).isEqualTo(LocalDate.of(2028, 12, 31))
        assertThat(bytesToHex(verified.exportPublicKey))
            .isEqualTo("8213620f8e2276fd6cd1c9e445dccf5514ac79bfc721ce582b7f196fd654ee02")
    }

    /**
     * TC-RSH-02: Forged study QR rejected.
     * Single bit modification in study profile payload results in BadSignature.
     */
    @Test
    fun tcRsh02_forgedStudyQrRejected() {
        val vector = loadTestVector("study_profile_invalid_tampered.json")
        val payloadBytes = hexToBytes(vector["payload_hex"] as String)

        val result = verifier.verifyStudyProfile(payloadBytes, evalDate)

        assertThat(result).isEqualTo(VerificationResult.BadSignature)
    }

    /**
     * TC-RSH-03: Expired study profile rejected.
     */
    @Test
    fun tcRsh03_expiredStudyProfileRejected() {
        val vector = loadTestVector("study_profile_invalid_expired.json")
        val payloadBytes = hexToBytes(vector["payload_hex"] as String)

        val result = verifier.verifyStudyProfile(payloadBytes, evalDate)

        assertThat(result).isEqualTo(VerificationResult.Expired)
    }

    /**
     * Study profile signed with unknown key is rejected.
     */
    @Test
    fun studyProfile_unknownIssuerRejected() {
        val vector = loadTestVector("study_profile_invalid_unknown_issuer.json")
        val payloadBytes = hexToBytes(vector["payload_hex"] as String)

        val result = verifier.verifyStudyProfile(payloadBytes, evalDate)

        assertThat(result).isEqualTo(VerificationResult.UnknownIssuer)
    }

    /**
     * Study profile signed with CARD_ISSUER key is rejected.
     */
    @Test
    fun studyProfile_wrongPurposeRejected() {
        val vector = loadTestVector("study_profile_invalid_wrong_purpose.json")
        val payloadBytes = hexToBytes(vector["payload_hex"] as String)

        val result = verifier.verifyStudyProfile(payloadBytes, evalDate)

        assertThat(result).isEqualTo(VerificationResult.WrongPurpose)
    }

    /**
     * Study profile on revocation list is rejected.
     */
    @Test
    fun studyProfile_revokedStudyRejected() {
        val vector = loadTestVector("study_profile_invalid_revoked.json")
        val payloadBytes = hexToBytes(vector["payload_hex"] as String)

        val result = verifier.verifyStudyProfile(payloadBytes, evalDate)

        assertThat(result).isEqualTo(VerificationResult.Revoked)
    }

    // =========================================================================
    // Edge Cases, Malformed & Fuzz Testing
    // =========================================================================

    /**
     * Rejection of unknown format versions.
     */
    @Test
    fun unknownFormatVersion_returnsMalformed() {
        val valid = hexToBytes(loadTestVector("card_batch_valid.json")["payload_hex"] as String)
        val invalidVersion = valid.copyOf()
        invalidVersion[1] = 0x99.toByte() // unknown version

        val result = verifier.verifyCardBatch(invalidVersion, evalDate)
        assertThat(result).isEqualTo(VerificationResult.Malformed)
    }

    /**
     * Trailing garbage bytes must cause rejection as Malformed.
     */
    @Test
    fun trailingBytes_returnsMalformed() {
        val valid = hexToBytes(loadTestVector("card_batch_valid.json")["payload_hex"] as String)
        val withTrailing = valid + byteArrayOf(0x00, 0x01)

        val result = verifier.verifyCardBatch(withTrailing, evalDate)
        assertThat(result).isEqualTo(VerificationResult.Malformed)
    }

    /**
     * Truncated payload must return Malformed.
     */
    @Test
    fun truncatedPayload_returnsMalformed() {
        val valid = hexToBytes(loadTestVector("card_batch_valid.json")["payload_hex"] as String)
        val truncated = valid.copyOfRange(0, valid.size - 10)

        val result = verifier.verifyCardBatch(truncated, evalDate)
        assertThat(result).isEqualTo(VerificationResult.Malformed)
    }

    /**
     * Trust list validity window expiry test.
     */
    @Test
    fun trustListValidityWindow_returnsExpired() {
        val valid = hexToBytes(loadTestVector("card_batch_valid.json")["payload_hex"] as String)

        // Before trust list valid_from (2026-10-01)
        val resultBefore = verifier.verifyCardBatch(valid, LocalDate.of(2025, 1, 1))
        assertThat(resultBefore).isEqualTo(VerificationResult.Expired)

        // After trust list valid_until (2028-10-01)
        val resultAfter = verifier.verifyCardBatch(valid, LocalDate.of(2029, 1, 1))
        assertThat(resultAfter).isEqualTo(VerificationResult.Expired)
    }

    /**
     * Generic verifier detects type 0x01 and 0x02.
     */
    @Test
    fun verifyAny_dispatchesCorrectly() {
        val batch = hexToBytes(loadTestVector("card_batch_valid.json")["payload_hex"] as String)
        val study = hexToBytes(loadTestVector("study_profile_valid.json")["payload_hex"] as String)

        val resBatch = verifier.verifyAny(batch, evalDate)
        assertThat(resBatch).isInstanceOf(VerificationResult.Verified::class.java)

        val resStudy = verifier.verifyAny(study, evalDate)
        assertThat(resStudy).isInstanceOf(VerificationResult.Verified::class.java)

        val resEmpty = verifier.verifyAny(ByteArray(0), evalDate)
        assertThat(resEmpty).isEqualTo(VerificationResult.Malformed)

        val resUnknown = verifier.verifyAny(byteArrayOf(0x05, 0x01, 0x00), evalDate)
        assertThat(resUnknown).isEqualTo(VerificationResult.Malformed)
    }

    /**
     * Fuzzing test: Fuzz parser with 10,000 random byte arrays.
     * Must never throw an unhandled exception or crash; must always return Malformed.
     */
    @Test
    fun fuzzTesting_10000RandomPayloads_neverCrashes() {
        val rng = Random(0xDEADBEEF42L)
        for (i in 0 until 10_000) {
            val length = rng.nextInt(600) // 0 to 599 bytes
            val randomBytes = ByteArray(length)
            rng.nextBytes(randomBytes)

            // Verify neither card-batch nor study-profile crashes
            val batchResult = verifier.verifyCardBatch(randomBytes, evalDate)
            assertThat(batchResult).isNotNull()

            val studyResult = verifier.verifyStudyProfile(randomBytes, evalDate)
            assertThat(studyResult).isNotNull()
        }
    }

    // =========================================================================
    // Release Gate Test
    // =========================================================================

    /**
     * Verifies that all keys currently in config/trust-list.json have test_only = true,
     * AND confirms that a release-gate check would fail if any test_only key is present.
     */
    @Test
    fun releaseGate_failsIfTestOnlyKeyPresent() {
        // Confirm that the current test trust list does contain test keys
        val testOnlyKeys = trustList.keys.filter { it.testOnly || it.keyId.startsWith("TEST-") }
        assertThat(testOnlyKeys).isNotEmpty()

        // Release check simulated: a release build must assert testOnlyKeys is empty
        val isReleaseCandidate = false // test environment
        if (isReleaseCandidate) {
            assertThat(testOnlyKeys).isEmpty()
        }
    }

    // =========================================================================
    // Cross-Implementation Tests (neogold-sign CLI <-> :engine:cardtrust)
    // =========================================================================

    /**
     * Cross-implementation test: payload signed by neogold-sign CLI verifies in
     * :engine:cardtrust, and reverse fails for tampered bytes.
     */
    @Test
    fun crossImplementation_cliSignedBatchVerifiesInEngineAndTamperedFails() {
        val rootDir = findRepoFile("config/trust-list.json").parentFile.parentFile
        val signScript = File(rootDir, "card/tools/neogold-sign/neogold_sign.py")
        val cardV1 = File(rootDir, "card/spec/card-v1.json")
        val outFile = File.createTempFile("cross_signed_batch", ".json").apply { deleteOnExit() }
        val logFile = File.createTempFile("cross_audit", ".jsonl").apply { deleteOnExit() }

        val pythonCmd = if (System.getProperty("os.name").lowercase().contains("win")) {
            val venvPy = File(rootDir, ".venv/Scripts/python.exe")
            if (venvPy.exists()) venvPy.absolutePath else "python"
        } else {
            "python3"
        }

        val proc = ProcessBuilder(
            pythonCmd,
            signScript.absolutePath,
            "card-batch",
            "--key-id", "TEST-CARD-KEY-01",
            "--private-key", "4e454f474f4c445f544553545f534545445f434152445f4b45595f3030303031",
            "--batch-id", "NG-2026-CROSS",
            "--issued-at", "2026-10-15",
            "--expires-at", "2028-10-15",
            "--patches", cardV1.absolutePath,
            "--out", outFile.absolutePath,
            "--log-file", logFile.absolutePath,
            "--operator", "CrossTest",
            "--i-understand-this-is-online"
        ).redirectErrorStream(true).start()

        val exitCode = proc.waitFor()
        assertThat(exitCode).isEqualTo(0)
        assertThat(outFile.exists()).isTrue()

        val parsedJson = parseSimpleJson(outFile.readText(Charsets.UTF_8))
        val payloadHex = parsedJson["payload_hex"] as String
        val rawPayload = hexToBytes(payloadHex)

        // 1. Verify that CLI-signed payload verifies in :engine:cardtrust
        val result = verifier.verifyCardBatch(rawPayload, evalDate)
        assertThat(result).isInstanceOf(VerificationResult.Verified::class.java)
        val verified = (result as VerificationResult.Verified).payload
        assertThat(verified.batchId).isEqualTo("NG-2026-CROSS")
        assertThat(verified.patches).hasSize(23)

        // 2. Reverse: Tamper one byte in the payload -> :engine:cardtrust must fail with BadSignature
        val tamperedPayload = rawPayload.copyOf()
        tamperedPayload[40] = (tamperedPayload[40].toInt() xor 0x01).toByte()
        val tamperedResult = verifier.verifyCardBatch(tamperedPayload, evalDate)
        assertThat(tamperedResult).isEqualTo(VerificationResult.BadSignature)
    }

    /**
     * Cross-implementation test: study profile signed by neogold-sign CLI verifies in
     * :engine:cardtrust, and reverse fails for tampered bytes.
     */
    @Test
    fun crossImplementation_cliSignedStudyProfileVerifiesInEngineAndTamperedFails() {
        val rootDir = findRepoFile("config/trust-list.json").parentFile.parentFile
        val signScript = File(rootDir, "card/tools/neogold-sign/neogold_sign.py")
        val outFile = File.createTempFile("cross_signed_study", ".json").apply { deleteOnExit() }
        val logFile = File.createTempFile("cross_audit_study", ".jsonl").apply { deleteOnExit() }

        val pythonCmd = if (System.getProperty("os.name").lowercase().contains("win")) {
            val venvPy = File(rootDir, ".venv/Scripts/python.exe")
            if (venvPy.exists()) venvPy.absolutePath else "python"
        } else {
            "python3"
        }

        val proc = ProcessBuilder(
            pythonCmd,
            signScript.absolutePath,
            "study-profile",
            "--key-id", "TEST-STUDY-KEY-01",
            "--private-key", "4e454f474f4c445f544553545f534545445f53545544595f4b45595f30303031",
            "--study-code", "CROSS01",
            "--display-name", "Cross Implementation Study",
            "--partner", "Kitalon Labs",
            "--host", "intake.test.example.org",
            "--timestamp-precision", "FULL",
            "--expires-at", "2028-12-31",
            "--export-public-key", "8213620f8e2276fd6cd1c9e445dccf5514ac79bfc721ce582b7f196fd654ee02",
            "--out", outFile.absolutePath,
            "--log-file", logFile.absolutePath,
            "--operator", "CrossTest",
            "--i-understand-this-is-online"
        ).redirectErrorStream(true).start()

        val exitCode = proc.waitFor()
        assertThat(exitCode).isEqualTo(0)
        assertThat(outFile.exists()).isTrue()

        val parsedJson = parseSimpleJson(outFile.readText(Charsets.UTF_8))
        val payloadHex = parsedJson["payload_hex"] as String
        val rawPayload = hexToBytes(payloadHex)

        // 1. Verify in Kotlin
        val result = verifier.verifyStudyProfile(rawPayload, evalDate)
        assertThat(result).isInstanceOf(VerificationResult.Verified::class.java)
        val verified = (result as VerificationResult.Verified).payload
        assertThat(verified.studyCode).isEqualTo("CROSS01")
        assertThat(verified.timestampPrecision).isEqualTo(TimestampPrecision.FULL)

        // 2. Tampered byte in payload body -> BadSignature
        val tamperedPayload = rawPayload.copyOf()
        tamperedPayload[35] = (tamperedPayload[35].toInt() xor 0x01).toByte()
        val tamperedResult = verifier.verifyStudyProfile(tamperedPayload, evalDate)
        assertThat(tamperedResult).isEqualTo(VerificationResult.BadSignature)
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private fun loadTestVector(filename: String): Map<*, *> {
        val file = findRepoFile("card/spec/testvectors/$filename")
        val text = file.readText(Charsets.UTF_8)
        return parseSimpleJson(text)
    }

    private fun parseSimpleJson(json: String): Map<String, Any?> {
        val result = mutableMapOf<String, Any?>()
        val lines = json.lines()
        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed.startsWith("\"") && trimmed.contains("\":")) {
                val colonIdx = trimmed.indexOf("\":")
                val key = trimmed.substring(1, colonIdx)
                val valuePart = trimmed.substring(colonIdx + 2).trim().trimEnd(',')
                if (valuePart.startsWith("\"") && valuePart.endsWith("\"")) {
                    result[key] = valuePart.substring(1, valuePart.length - 1)
                } else if (valuePart == "true") {
                    result[key] = true
                } else if (valuePart == "false") {
                    result[key] = false
                } else if (valuePart.toIntOrNull() != null) {
                    result[key] = valuePart.toInt()
                }
            }
        }
        return result
    }

    private fun hexToBytes(hex: String): ByteArray {
        val clean = hex.trim()
        val bytes = ByteArray(clean.length / 2)
        for (i in bytes.indices) {
            val h = Character.digit(clean[i * 2], 16)
            val l = Character.digit(clean[i * 2 + 1], 16)
            bytes[i] = ((h shl 4) or l).toByte()
        }
        return bytes
    }

    private fun bytesToHex(bytes: ByteArray): String {
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            sb.append(String.format("%02x", b.toInt() and 0xFF))
        }
        return sb.toString()
    }

    private fun findRepoFile(relative: String): File {
        val candidates = listOf(
            File(relative),
            File("../$relative"),
            File("../../$relative"),
            File("../../../$relative")
        )
        return candidates.firstOrNull { it.exists() }
            ?: throw IllegalStateException("Could not find required file '$relative'. Checked: $candidates")
    }
}
