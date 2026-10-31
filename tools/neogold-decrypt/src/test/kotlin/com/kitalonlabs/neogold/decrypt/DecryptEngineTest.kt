package com.kitalonlabs.neogold.decrypt

import com.google.common.truth.Truth.assertThat
import com.google.crypto.tink.HybridEncrypt
import com.google.crypto.tink.InsecureSecretKeyAccess
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.RegistryConfiguration
import com.google.crypto.tink.StreamingAead
import com.google.crypto.tink.TinkProtoKeysetFormat
import com.google.crypto.tink.hybrid.HpkeParameters
import com.google.crypto.tink.hybrid.HpkePublicKey
import com.google.crypto.tink.hybrid.HybridConfig
import com.google.crypto.tink.streamingaead.PredefinedStreamingAeadParameters
import com.google.crypto.tink.streamingaead.StreamingAeadConfig
import com.google.crypto.tink.subtle.X25519
import com.google.crypto.tink.util.Bytes
import org.junit.Assert.fail
import org.junit.BeforeClass
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Base64
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class DecryptEngineTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    companion object {
        @JvmStatic
        @BeforeClass
        fun setup() {
            HybridConfig.register()
            StreamingAeadConfig.register()
        }
    }

    private data class SyntheticExportBundle(
        val zipFile: File,
        val privateKeyBytes: ByteArray,
        val publicKeyBytes: ByteArray,
        val originalSessionsCsv: String,
        val originalCapturesCsv: String,
        val originalTsbCsv: String,
        val originalParticipantsCsv: String,
        val originalImageBytes: ByteArray
    )

    private fun createSyntheticExport(
        tamperCiphertext: Boolean = false,
        tamperManifest: Boolean = false
    ): SyntheticExportBundle {
        val privKey = X25519.generatePrivateKey()
        val pubKey = X25519.publicFromPrivate(privKey)

        val dataKeysetHandle = KeysetHandle.generateNew(PredefinedStreamingAeadParameters.AES256_GCM_HKDF_4KB)
        val streamingAead = dataKeysetHandle.getPrimitive(RegistryConfiguration.get(), StreamingAead::class.java)

        // 1. Plaintext CSVs
        val sessionsCsv = """
            session_id,participant_code,status,created_at,age_hours,gestational_age_weeks,rule_outcome,triage_action,estimate_mg_dl,estimate_low_mg_dl,estimate_high_mg_dl,device_model,card_batch_id
            SESS-1,SKP-0142,COMPLETE,1760000000000,48.0,38,,REFER_URGENT,15.2,14.0,16.5,Infinix X6528B,BATCH-2026-01
            SESS-2,SKP-0142,COMPLETE,1760086400000,72.0,38,,NO_FURTHER_ACTION_FROM_SCREEN,8.1,7.2,9.0,Infinix X6528B,BATCH-2026-01
            SESS-3,SKP-0143,COMPLETE,1760050000000,36.0,39,,BLOOD_TEST_TODAY,12.0,10.8,13.2,Infinix X6528B,BATCH-2026-01
        """.trimIndent() + "\n"

        val capturesCsv = """
            capture_id,session_id,attempt_number,site,captured_at,quality_status,window_l,window_a,window_b,correction_residual_de,image_file,image_sha256
            CAP-1,SESS-1,1,STERNUM,1760000010000,PASSED,60.5,12.0,24.0,0.85,images/CAP-1.jpg,hash1
            CAP-2,SESS-2,1,STERNUM,1760086410000,PASSED,61.0,11.5,23.5,0.72,images/CAP-2.jpg,hash2
        """.trimIndent() + "\n"

        val tsbCsv = """
            tsb_id,participant_code,session_id,tsb_mg_dl,draw_time,method
            TSB-1,SKP-0142,SESS-1,14.8,1760003600000,LAB
            TSB-2,SKP-0143,SESS-3,11.5,1760053600000,POINT_OF_CARE
        """.trimIndent() + "\n"

        val participantsCsv = """
            participant_id,participant_code,consent_form_id,consent_recorded_at,created_at
            PART-1,SKP-0142,SKP-CONS-001,1759990000000,1759990000000
            PART-2,SKP-0143,SKP-CONS-002,1760040000000,1760040000000
        """.trimIndent() + "\n"

        val fakeImage = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 0x00, 0x10, 0x4A, 0x46, 0x49, 0x46, 0x00)

        // 2. Encrypt CSVs and image
        fun encryptBytes(plain: ByteArray): ByteArray {
            val baos = ByteArrayOutputStream()
            streamingAead.newEncryptingStream(baos, ByteArray(0)).use {
                it.write(plain)
            }
            return baos.toByteArray()
        }

        var encSessions = encryptBytes(sessionsCsv.toByteArray(StandardCharsets.UTF_8))
        val encCaptures = encryptBytes(capturesCsv.toByteArray(StandardCharsets.UTF_8))
        val encTsb = encryptBytes(tsbCsv.toByteArray(StandardCharsets.UTF_8))
        val encParticipants = encryptBytes(participantsCsv.toByteArray(StandardCharsets.UTF_8))
        val encImage = encryptBytes(fakeImage)

        if (tamperCiphertext) {
            // Corrupt one byte of sessions ciphertext
            encSessions = encSessions.copyOf()
            encSessions[encSessions.size / 2] = (encSessions[encSessions.size / 2].toInt() xor 0x55).toByte()
        }

        fun sha256(b: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

        val manifestLines = mutableListOf(
            "${sha256(encSessions)}  data/sessions.csv.enc",
            "${sha256(encCaptures)}  data/captures.csv.enc",
            "${sha256(encTsb)}  data/tsb_measurements.csv.enc",
            "${sha256(encParticipants)}  data/participants.csv.enc",
            "${sha256(encImage)}  images/CAP-1.jpg.enc"
        )

        var manifestContent = manifestLines.joinToString("\n") + "\n"
        if (tamperManifest) {
            manifestContent += "0000000000000000000000000000000000000000000000000000000000000000  data/nonexistent.enc\n"
        }
        val manifestBytes = manifestContent.toByteArray(StandardCharsets.UTF_8)
        val manifestSha256 = sha256(manifestBytes)

        // Wrap the data keyset with Tink HPKE, written here independently of the engine under test
        val hpkeParameters = HpkeParameters.builder()
            .setKemId(HpkeParameters.KemId.DHKEM_X25519_HKDF_SHA256)
            .setKdfId(HpkeParameters.KdfId.HKDF_SHA256)
            .setAeadId(HpkeParameters.AeadId.AES_256_GCM)
            .setVariant(HpkeParameters.Variant.NO_PREFIX)
            .build()
        val hpkePublicKey = HpkePublicKey.create(hpkeParameters, Bytes.copyFrom(pubKey), null)
        val encryptor = KeysetHandle.newBuilder()
            .addEntry(KeysetHandle.importKey(hpkePublicKey).withFixedId(1).makePrimary())
            .build()
            .getPrimitive(RegistryConfiguration.get(), HybridEncrypt::class.java)

        val rawKeyset = TinkProtoKeysetFormat.serializeKeyset(dataKeysetHandle, InsecureSecretKeyAccess.get())
        val wrappedDataKeyBytes = encryptor.encrypt(rawKeyset, DecryptEngine.CONTEXT_INFO)
        val wrappedDataKeyB64 = Base64.getEncoder().encodeToString(wrappedDataKeyBytes)

        val headerJson = """
            {
              "export_format_version": 1,
              "csv_schema_version": 1,
              "study_code": "SKP",
              "exported_at": "2026-10-20T12:00:00Z",
              "total_participants": 2,
              "total_sessions": 3,
              "total_images": 1,
              "wrapped_data_key_b64": "$wrappedDataKeyB64",
              "manifest_sha256": "$manifestSha256"
            }
        """.trimIndent()
        val headerBytes = headerJson.toByteArray(StandardCharsets.UTF_8)

        // Write ZIP archive
        val zipFile = tempFolder.newFile("test_export_${System.currentTimeMillis()}.zip")
        ZipOutputStream(FileOutputStream(zipFile)).use { zos ->
            zos.putNextEntry(ZipEntry("header.json"))
            zos.write(headerBytes)
            zos.closeEntry()

            zos.putNextEntry(ZipEntry("manifest.sha256"))
            zos.write(manifestBytes)
            zos.closeEntry()

            zos.putNextEntry(ZipEntry("data/sessions.csv.enc"))
            zos.write(encSessions)
            zos.closeEntry()

            zos.putNextEntry(ZipEntry("data/captures.csv.enc"))
            zos.write(encCaptures)
            zos.closeEntry()

            zos.putNextEntry(ZipEntry("data/tsb_measurements.csv.enc"))
            zos.write(encTsb)
            zos.closeEntry()

            zos.putNextEntry(ZipEntry("data/participants.csv.enc"))
            zos.write(encParticipants)
            zos.closeEntry()

            zos.putNextEntry(ZipEntry("images/CAP-1.jpg.enc"))
            zos.write(encImage)
            zos.closeEntry()
        }

        return SyntheticExportBundle(
            zipFile = zipFile,
            privateKeyBytes = privKey,
            publicKeyBytes = pubKey,
            originalSessionsCsv = sessionsCsv,
            originalCapturesCsv = capturesCsv,
            originalTsbCsv = tsbCsv,
            originalParticipantsCsv = participantsCsv,
            originalImageBytes = fakeImage
        )
    }

    /**
     * TC-EXP-01: Encrypted USB export decryption roundtrip (FR-30, NG-036).
     * Files unreadable without key; after decryption, CSV rows and image hashes match.
     */
    @Test
    fun tcExp01_encryptedExportDecryptionRoundTrip() {
        val bundle = createSyntheticExport()
        val outDir = tempFolder.newFolder("decrypted_tc01")

        val result = DecryptEngine.decryptArchive(
            zipFile = bundle.zipFile,
            exportPrivateKeyBytes = bundle.privateKeyBytes,
            outputDir = outDir,
            applyDateShift = false // verify exact equality without shift first
        )

        assertThat(result.studyCode).isEqualTo("SKP")
        assertThat(result.participantsCount).isEqualTo(2)
        assertThat(result.sessionsCount).isEqualTo(3)
        assertThat(result.imagesCount).isEqualTo(1)

        val decryptedSessions = File(outDir, "sessions.csv").readText()
        assertThat(decryptedSessions).contains("SESS-1,SKP-0142,COMPLETE")
        assertThat(decryptedSessions).contains("SESS-3,SKP-0143,COMPLETE")

        val decryptedImage = File(outDir, "images/CAP-1.jpg").readBytes()
        assertThat(decryptedImage).isEqualTo(bundle.originalImageBytes)

        val decryptedManifest = File(outDir, "manifest.sha256")
        assertThat(decryptedManifest.exists()).isTrue()
        assertThat(decryptedManifest.readText()).contains("sessions.csv")
    }

    /**
     * TC-EXP-02: No identifiers (FR-30, NFR-08, T15).
     * Participant code is the only link; no register numbers, names, or birth dates.
     */
    @Test
    fun tcExp02_noPersonalIdentifiersInDecryptedExport() {
        val bundle = createSyntheticExport()
        val outDir = tempFolder.newFolder("decrypted_tc02")

        DecryptEngine.decryptArchive(
            zipFile = bundle.zipFile,
            exportPrivateKeyBytes = bundle.privateKeyBytes,
            outputDir = outDir,
            applyDateShift = true
        )

        for (fileName in listOf("sessions.csv", "captures.csv", "tsb_measurements.csv", "participants.csv")) {
            val content = File(outDir, fileName).readText()
            assertThat(content).doesNotContain("register_number")
            assertThat(content).doesNotContain("name")
            assertThat(content).doesNotContain("birth_date")
            assertThat(content).doesNotContain("phone")
            assertThat(content).doesNotContain("address")
            assertThat(content).doesNotContain("national_id")
        }
    }

    /**
     * TC-EXP-03: Date shift (T15).
     * One random offset per participant; intervals between a participant's sessions and TSB unchanged.
     */
    @Test
    fun tcExp03_dateShiftPreservesClinicalIntervals() {
        val bundle = createSyntheticExport()
        val outDir = tempFolder.newFolder("decrypted_tc03")

        val result = DecryptEngine.decryptArchive(
            zipFile = bundle.zipFile,
            exportPrivateKeyBytes = bundle.privateKeyBytes,
            outputDir = outDir,
            applyDateShift = true
        )

        assertThat(result.dateShiftApplied).isTrue()
        val p1OffsetDays = result.participantOffsets["SKP-0142"]
        val p2OffsetDays = result.participantOffsets["SKP-0143"]

        assertThat(p1OffsetDays).isNotNull()
        assertThat(p2OffsetDays).isNotNull()
        assertThat(p1OffsetDays).isNotEqualTo(0)
        assertThat(p1OffsetDays!!).isAtLeast(-180)
        assertThat(p1OffsetDays).isAtMost(180)

        // Parse shifted timestamps
        val sessionsLines = File(outDir, "sessions.csv").readLines()
        val sess1Cols = sessionsLines.find { it.startsWith("SESS-1") }!!.split(",")
        val sess2Cols = sessionsLines.find { it.startsWith("SESS-2") }!!.split(",")

        val sess1Time = sess1Cols[3].toLong()
        val sess2Time = sess2Cols[3].toLong()

        // Original SESS-1: 1760000000000, SESS-2: 1760086400000 (diff = 86400000 ms, exactly 24 hours)
        val shiftedSessionDiff = sess2Time - sess1Time
        assertThat(shiftedSessionDiff).isEqualTo(86400000L) // EXACTLY 24 hours preserved!

        // Original TSB-1: 1760003600000 (draw_time), SESS-1: 1760000000000 (diff = 3600000 ms, exactly 1 hour)
        val tsbLines = File(outDir, "tsb_measurements.csv").readLines()
        val tsb1Cols = tsbLines.find { it.startsWith("TSB-1") }!!.split(",")
        val tsb1DrawTime = tsb1Cols[4].toLong()

        val shiftedTsbInterval = tsb1DrawTime - sess1Time
        assertThat(shiftedTsbInterval).isEqualTo(3600000L) // EXACTLY 1 hour preserved!

        // Confirm actual calendar dates were shifted by the expected offset
        val p1OffsetMs = p1OffsetDays * 86_400_000L
        assertThat(sess1Time).isEqualTo(1760000000000L + p1OffsetMs)
    }

    /**
     * TC-EXP-04: Tampered ciphertext or wrong key fails with a clear, specific error.
     */
    @Test
    fun tcExp04_wrongKeyFailsWithClearError() {
        val bundle = createSyntheticExport()
        val outDir = tempFolder.newFolder("decrypted_wrong_key")
        val wrongPrivKey = X25519.generatePrivateKey()

        try {
            DecryptEngine.decryptArchive(
                zipFile = bundle.zipFile,
                exportPrivateKeyBytes = wrongPrivKey,
                outputDir = outDir
            )
            fail("Expected DecryptionException for wrong private key")
        } catch (e: DecryptionException) {
            assertThat(e.message).contains("wrong private key")
        }
    }

    @Test
    fun tcExp04_tamperedCiphertextFailsWithClearError() {
        val bundle = createSyntheticExport(tamperCiphertext = true)
        val outDir = tempFolder.newFolder("decrypted_tampered")

        try {
            DecryptEngine.decryptArchive(
                zipFile = bundle.zipFile,
                exportPrivateKeyBytes = bundle.privateKeyBytes,
                outputDir = outDir
            )
            fail("Expected ManifestVerificationException or TamperedCiphertextException")
        } catch (e: Exception) {
            val isExpected = e is ManifestVerificationException || e is TamperedCiphertextException
            assertThat(isExpected).isTrue()
            val msg = e.message?.lowercase() ?: ""
            assertThat(msg.contains("checksum mismatch") || msg.contains("tampered") || msg.contains("corrupted")).isTrue()
        }
    }
}
