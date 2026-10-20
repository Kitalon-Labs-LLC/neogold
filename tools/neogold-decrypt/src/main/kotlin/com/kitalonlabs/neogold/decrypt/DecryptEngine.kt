package com.kitalonlabs.neogold.decrypt

import com.google.crypto.tink.BinaryKeysetReader
import com.google.crypto.tink.CleartextKeysetHandle
import com.google.crypto.tink.HybridDecrypt
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.StreamingAead
import com.google.crypto.tink.hybrid.HybridConfig
import com.google.crypto.tink.proto.HpkeAead
import com.google.crypto.tink.proto.HpkeKdf
import com.google.crypto.tink.proto.HpkeKem
import com.google.crypto.tink.proto.HpkeParams
import com.google.crypto.tink.proto.HpkePrivateKey
import com.google.crypto.tink.proto.HpkePublicKey
import com.google.crypto.tink.proto.KeyData
import com.google.crypto.tink.proto.KeyStatusType
import com.google.crypto.tink.proto.Keyset
import com.google.crypto.tink.proto.OutputPrefixType
import com.google.crypto.tink.streamingaead.StreamingAeadConfig
import com.google.crypto.tink.subtle.X25519
import com.google.protobuf.ByteString
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.security.GeneralSecurityException
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.zip.ZipFile

class DecryptionException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
class ManifestVerificationException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
class TamperedCiphertextException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

data class DecryptResult(
    val studyCode: String,
    val outputDir: File,
    val participantsCount: Int,
    val sessionsCount: Int,
    val imagesCount: Int,
    val participantOffsets: Map<String, Int>,
    val dateShiftApplied: Boolean
)

object DecryptEngine {

    val CONTEXT_INFO: ByteArray = "neogold-export-v1".toByteArray(StandardCharsets.UTF_8)
    private const val BUFFER_SIZE = 8192

    init {
        try {
            HybridConfig.register()
            StreamingAeadConfig.register()
        } catch (_: Exception) {}
    }

    fun parseHeaderJson(json: String): Map<String, String> {
        val map = mutableMapOf<String, String>()
        val regex = Regex("\"([^\"]+)\"\\s*:\\s*(?:\"((?:\\\\\"|[^\"])*)\"|([0-9]+))")
        regex.findAll(json).forEach { m ->
            val k = m.groupValues[1]
            val sVal = m.groupValues[2]
            val nVal = m.groupValues[3]
            val unescaped = unescapeJsonString(sVal)
            map[k] = if (sVal.isNotEmpty() || json.contains("\"$k\"\\s*:\\s*\"\"".toRegex())) unescaped else nVal
        }
        return map
    }

    private fun unescapeJsonString(s: String): String {
        val sb = StringBuilder()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length) {
                when (val next = s[i + 1]) {
                    '/' -> sb.append('/')
                    '"' -> sb.append('"')
                    '\\' -> sb.append('\\')
                    'n' -> sb.append('\n')
                    'r' -> sb.append('\r')
                    't' -> sb.append('\t')
                    'b' -> sb.append('\b')
                    'f' -> sb.append('\u000c')
                    'u' -> {
                        if (i + 5 < s.length) {
                            val hex = s.substring(i + 2, i + 6)
                            sb.append(hex.toInt(16).toChar())
                            i += 4
                        } else {
                            sb.append(next)
                        }
                    }
                    else -> sb.append(next)
                }
                i += 2
            } else {
                sb.append(c)
                i++
            }
        }
        return sb.toString()
    }

    /**
     * Decrypts an export ZIP archive using the offline X25519 private key.
     */
    fun decryptArchive(
        zipFile: File,
        exportPrivateKeyBytes: ByteArray,
        outputDir: File,
        applyDateShift: Boolean = true
    ): DecryptResult {
        require(zipFile.exists() && zipFile.isFile) { "Export archive not found: ${zipFile.absolutePath}" }
        require(exportPrivateKeyBytes.size == 32) {
            "Export private key must be exactly 32 bytes, got ${exportPrivateKeyBytes.size}"
        }

        outputDir.mkdirs()
        val imagesOutDir = File(outputDir, "images").apply { mkdirs() }

        val zip = try {
            ZipFile(zipFile)
        } catch (e: Exception) {
            throw DecryptionException("Failed to open ZIP archive: ${e.message}", e)
        }

        zip.use { archive ->
            // 1. Read and parse header.json
            val headerEntry = archive.getEntry("header.json")
                ?: throw DecryptionException("Corrupted export: 'header.json' missing from ZIP archive")
            val headerJson = archive.getInputStream(headerEntry).use { it.readBytes().toString(StandardCharsets.UTF_8) }
            val headerMap = try {
                parseHeaderJson(headerJson)
            } catch (e: Exception) {
                throw DecryptionException("Corrupted export: failed to parse 'header.json': ${e.message}", e)
            }

            val studyCode = headerMap["study_code"]
                ?: throw DecryptionException("Corrupted export: 'study_code' missing from header.json")
            val wrappedDataKeyB64 = headerMap["wrapped_data_key_b64"]
                ?: throw DecryptionException("Corrupted export: 'wrapped_data_key_b64' missing from header.json")
            val expectedManifestSha256 = headerMap["manifest_sha256"]
                ?: throw DecryptionException("Corrupted export: 'manifest_sha256' missing from header.json")

            // 2. Read and verify manifest.sha256
            val manifestEntry = archive.getEntry("manifest.sha256")
                ?: throw DecryptionException("Corrupted export: 'manifest.sha256' missing from ZIP archive")
            val manifestBytes = archive.getInputStream(manifestEntry).use { it.readBytes() }
            val actualManifestSha = sha256Hex(manifestBytes)

            if (!actualManifestSha.equals(expectedManifestSha256, ignoreCase = true)) {
                throw ManifestVerificationException(
                    "Integrity check failed: manifest.sha256 digest ($actualManifestSha) does not match header declaration ($expectedManifestSha256)"
                )
            }

            val manifestContent = manifestBytes.toString(StandardCharsets.UTF_8)
            val manifestEntries = parseManifest(manifestContent)

            // 3. Verify SHA-256 for every ciphertext entry in the archive
            for ((relPath, expectedSha) in manifestEntries) {
                val entry = archive.getEntry(relPath)
                    ?: throw ManifestVerificationException("Integrity check failed: Manifest entry missing from ZIP archive: $relPath")
                val entryBytes = archive.getInputStream(entry).use { it.readBytes() }
                val actualSha = sha256Hex(entryBytes)
                if (!actualSha.equals(expectedSha, ignoreCase = true)) {
                    throw ManifestVerificationException(
                        "Integrity check failed: Ciphertext file '$relPath' checksum mismatch! Expected: $expectedSha, Got: $actualSha. Archive has been tampered with or corrupted."
                    )
                }
            }

            // 4. Unwrap data key using Tink HPKE
            val dataKeysetHandle = unwrapDataKey(wrappedDataKeyB64, exportPrivateKeyBytes)
            val streamingAead = dataKeysetHandle.getPrimitive(StreamingAead::class.java)

            // 5. Decrypt CSV tables
            val sessionsRaw = decryptEntryBytes(archive, "data/sessions.csv.enc", streamingAead)
            val capturesRaw = decryptEntryBytes(archive, "data/captures.csv.enc", streamingAead)
            val tsbRaw = decryptEntryBytes(archive, "data/tsb_measurements.csv.enc", streamingAead)
            val participantsRaw = decryptEntryBytes(archive, "data/participants.csv.enc", streamingAead)

            // 6. Decrypt images to outputDir/images/<capture_id>.jpg
            val imageManifestEntries = manifestEntries.filter { it.key.startsWith("images/") }
            for ((relPath, _) in imageManifestEntries) {
                val fileName = relPath.substringAfterLast("/").removeSuffix(".enc")
                val imgOutFile = File(imagesOutDir, fileName)
                archive.getInputStream(archive.getEntry(relPath)).use { cipherIn ->
                    FileOutputStream(imgOutFile).use { imgOut ->
                        decryptStream(streamingAead, cipherIn, imgOut)
                    }
                }
            }

            // 7. Parse CSVs and apply per-participant date shifting
            val (shiftedParticipants, shiftedSessions, shiftedCaptures, shiftedTsb, participantOffsets) =
                processDateShifting(
                    participantsRaw = participantsRaw.toString(StandardCharsets.UTF_8),
                    sessionsRaw = sessionsRaw.toString(StandardCharsets.UTF_8),
                    capturesRaw = capturesRaw.toString(StandardCharsets.UTF_8),
                    tsbRaw = tsbRaw.toString(StandardCharsets.UTF_8),
                    applyShift = applyDateShift
                )

            // 8. Write decrypted CSVs to output directory
            val sessionsFile = File(outputDir, "sessions.csv").apply { writeText(shiftedSessions, StandardCharsets.UTF_8) }
            val capturesFile = File(outputDir, "captures.csv").apply { writeText(shiftedCaptures, StandardCharsets.UTF_8) }
            val tsbFile = File(outputDir, "tsb_measurements.csv").apply { writeText(shiftedTsb, StandardCharsets.UTF_8) }
            val participantsFile = File(outputDir, "participants.csv").apply { writeText(shiftedParticipants, StandardCharsets.UTF_8) }

            // 9. Write decrypted manifest.sha256 for downstream loaders (e.g. PartnerExportLoader)
            val decryptedManifestEntries = mutableListOf<Pair<String, String>>()
            decryptedManifestEntries.add("sessions.csv" to sha256Hex(sessionsFile))
            decryptedManifestEntries.add("captures.csv" to sha256Hex(capturesFile))
            decryptedManifestEntries.add("tsb_measurements.csv" to sha256Hex(tsbFile))
            decryptedManifestEntries.add("participants.csv" to sha256Hex(participantsFile))

            imagesOutDir.listFiles()?.sortedBy { it.name }?.forEach { img ->
                if (img.isFile) {
                    decryptedManifestEntries.add("images/${img.name}" to sha256Hex(img))
                }
            }

            val decryptedManifestContent = decryptedManifestEntries.joinToString("\n") { (name, sha) ->
                "$sha  $name"
            } + "\n"
            File(outputDir, "manifest.sha256").writeText(decryptedManifestContent, StandardCharsets.UTF_8)

            val sessionsCount = shiftedSessions.lines().count { it.isNotBlank() } - 1
            val participantsCount = shiftedParticipants.lines().count { it.isNotBlank() } - 1

            return DecryptResult(
                studyCode = studyCode,
                outputDir = outputDir,
                participantsCount = maxOf(0, participantsCount),
                sessionsCount = maxOf(0, sessionsCount),
                imagesCount = imageManifestEntries.size,
                participantOffsets = participantOffsets,
                dateShiftApplied = applyDateShift
            )
        }
    }

    private fun unwrapDataKey(wrappedDataKeyB64: String, exportPrivateKeyBytes: ByteArray): KeysetHandle {
        val ciphertext = try {
            Base64.getDecoder().decode(wrappedDataKeyB64.trim())
        } catch (e: Exception) {
            throw DecryptionException("Malformed base64 in wrapped data key header: ${e.message}", e)
        }

        val derivedPublicBytes = try {
            X25519.publicFromPrivate(exportPrivateKeyBytes)
        } catch (e: Exception) {
            throw DecryptionException("Invalid X25519 private key: ${e.message}", e)
        }

        val hpkePubKeyProto = HpkePublicKey.newBuilder()
            .setVersion(0)
            .setParams(
                HpkeParams.newBuilder()
                    .setKem(HpkeKem.DHKEM_X25519_HKDF_SHA256)
                    .setKdf(HpkeKdf.HKDF_SHA256)
                    .setAead(HpkeAead.AES_256_GCM)
                    .build()
            )
            .setPublicKey(ByteString.copyFrom(derivedPublicBytes))
            .build()

        val hpkePrivKeyProto = HpkePrivateKey.newBuilder()
            .setVersion(0)
            .setPublicKey(hpkePubKeyProto)
            .setPrivateKey(ByteString.copyFrom(exportPrivateKeyBytes))
            .build()

        val privKeyData = KeyData.newBuilder()
            .setTypeUrl("type.googleapis.com/google.crypto.tink.HpkePrivateKey")
            .setValue(hpkePrivKeyProto.toByteString())
            .setKeyMaterialType(KeyData.KeyMaterialType.ASYMMETRIC_PRIVATE)
            .build()

        val privKeyset = Keyset.newBuilder()
            .setPrimaryKeyId(1)
            .addKey(
                Keyset.Key.newBuilder()
                    .setKeyData(privKeyData)
                    .setStatus(KeyStatusType.ENABLED)
                    .setKeyId(1)
                    .setOutputPrefixType(OutputPrefixType.RAW)
                    .build()
            )
            .build()

        val privHandle = CleartextKeysetHandle.fromKeyset(privKeyset)
        val decryptor = privHandle.getPrimitive(HybridDecrypt::class.java)

        val cleartextBytes = try {
            decryptor.decrypt(ciphertext, CONTEXT_INFO)
        } catch (e: GeneralSecurityException) {
            throw DecryptionException("Failed to unwrap export data key: wrong private key or corrupted export header", e)
        }

        return try {
            CleartextKeysetHandle.read(BinaryKeysetReader.withBytes(cleartextBytes))
        } catch (e: Exception) {
            throw DecryptionException("Failed to parse unwrapped Tink keyset handle: ${e.message}", e)
        }
    }

    private fun decryptEntryBytes(archive: ZipFile, relPath: String, streamingAead: StreamingAead): ByteArray {
        val entry = archive.getEntry(relPath)
            ?: throw DecryptionException("Required file missing from archive: $relPath")
        val baos = ByteArrayOutputStream()
        archive.getInputStream(entry).use { cipherIn ->
            decryptStream(streamingAead, cipherIn, baos)
        }
        return baos.toByteArray()
    }

    private fun decryptStream(streamingAead: StreamingAead, input: InputStream, output: OutputStream) {
        try {
            streamingAead.newDecryptingStream(input, ByteArray(0)).use { decryptingStream ->
                val buffer = ByteArray(BUFFER_SIZE)
                var bytesRead: Int
                while (decryptingStream.read(buffer).also { bytesRead = it } != -1) {
                    output.write(buffer, 0, bytesRead)
                }
                output.flush()
            }
        } catch (e: GeneralSecurityException) {
            throw TamperedCiphertextException("Authentication failed while decrypting ciphertext stream: data has been tampered with or corrupted", e)
        } catch (e: Exception) {
            throw TamperedCiphertextException("Authentication failed while decrypting ciphertext stream: data has been tampered with or corrupted: ${e.message}", e)
        }
    }

    private fun parseManifest(content: String): Map<String, String> {
        val map = mutableMapOf<String, String>()
        content.lineSequence().forEach { line ->
            val trimmed = line.trim()
            if (trimmed.isNotEmpty() && !trimmed.startsWith("#")) {
                val parts = trimmed.split(Regex("\\s+"), limit = 2)
                if (parts.size == 2) {
                    map[parts[1].trim().replace('\\', '/')] = parts[0].lowercase()
                }
            }
        }
        return map
    }

    /**
     * Applies one random date offset per participant within plus or minus 180 days.
     * All intervals for the same participant are strictly invariant.
     */
    fun processDateShifting(
        participantsRaw: String,
        sessionsRaw: String,
        capturesRaw: String,
        tsbRaw: String,
        applyShift: Boolean
    ): DateShiftedResult {
        // Collect all distinct participant codes
        val participantCodes = mutableSetOf<String>()

        fun extractCodes(csvText: String, codeColIndex: Int) {
            csvText.lineSequence().drop(1).forEach { line ->
                val cols = parseCsvLine(line)
                if (cols.size > codeColIndex) {
                    val code = cols[codeColIndex].trim()
                    if (code.isNotEmpty()) participantCodes.add(code)
                }
            }
        }

        extractCodes(participantsRaw, 1) // participant_code is index 1
        extractCodes(sessionsRaw, 1)     // participant_code is index 1
        extractCodes(tsbRaw, 1)          // participant_code is index 1

        val rng = SecureRandom()
        val offsetsDays = mutableMapOf<String, Int>()

        for (code in participantCodes) {
            if (applyShift) {
                // Random offset in [-180, 180] excluding 0 to guarantee shifting
                var offset = 0
                while (offset == 0) {
                    offset = rng.nextInt(361) - 180
                }
                offsetsDays[code] = offset
            } else {
                offsetsDays[code] = 0
            }
        }

        val shiftedParticipants = shiftCsv(participantsRaw, offsetsDays, participantCol = 1, timestampCols = listOf(3, 4))
        val shiftedSessions = shiftCsv(sessionsRaw, offsetsDays, participantCol = 1, timestampCols = listOf(3))

        // Captures reference session_id (col 1), so map session_id -> participant_code
        val sessionToParticipant = mutableMapOf<String, String>()
        sessionsRaw.lineSequence().drop(1).forEach { line ->
            val cols = parseCsvLine(line)
            if (cols.size >= 2) {
                val sessId = cols[0].trim()
                val pCode = cols[1].trim()
                if (sessId.isNotEmpty() && pCode.isNotEmpty()) {
                    sessionToParticipant[sessId] = pCode
                }
            }
        }

        val shiftedCaptures = shiftCapturesCsv(capturesRaw, sessionToParticipant, offsetsDays)
        val shiftedTsb = shiftCsv(tsbRaw, offsetsDays, participantCol = 1, timestampCols = listOf(4))

        return DateShiftedResult(
            participantsCsv = shiftedParticipants,
            sessionsCsv = shiftedSessions,
            capturesCsv = shiftedCaptures,
            tsbCsv = shiftedTsb,
            participantOffsets = offsetsDays
        )
    }

    private fun shiftCsv(
        csvText: String,
        offsetsDays: Map<String, Int>,
        participantCol: Int,
        timestampCols: List<Int>
    ): String {
        val lines = csvText.lines()
        if (lines.isEmpty()) return ""
        val header = lines.first()
        val outLines = mutableListOf(header)

        for (line in lines.drop(1)) {
            if (line.isBlank()) continue
            val cols = parseCsvLine(line).toMutableList()
            if (cols.size > participantCol) {
                val pCode = cols[participantCol].trim()
                val offsetDays = offsetsDays[pCode] ?: 0
                val offsetMs = offsetDays * 86_400_000L

                for (colIdx in timestampCols) {
                    if (cols.size > colIdx && cols[colIdx].isNotBlank()) {
                        try {
                            val origTime = cols[colIdx].trim().toLong()
                            cols[colIdx] = (origTime + offsetMs).toString()
                        } catch (_: NumberFormatException) {}
                    }
                }
            }
            outLines.add(cols.joinToString(",") { escapeCsv(it) })
        }
        return outLines.joinToString("\n") + "\n"
    }

    private fun shiftCapturesCsv(
        capturesCsv: String,
        sessionToParticipant: Map<String, String>,
        offsetsDays: Map<String, Int>
    ): String {
        val lines = capturesCsv.lines()
        if (lines.isEmpty()) return ""
        val header = lines.first()
        val outLines = mutableListOf(header)

        for (line in lines.drop(1)) {
            if (line.isBlank()) continue
            val cols = parseCsvLine(line).toMutableList()
            if (cols.size > 4) {
                val sessId = cols[1].trim()
                val pCode = sessionToParticipant[sessId] ?: ""
                val offsetDays = offsetsDays[pCode] ?: 0
                val offsetMs = offsetDays * 86_400_000L

                if (cols[4].isNotBlank()) {
                    try {
                        val origTime = cols[4].trim().toLong()
                        cols[4] = (origTime + offsetMs).toString()
                    } catch (_: NumberFormatException) {}
                }
            }
            outLines.add(cols.joinToString(",") { escapeCsv(it) })
        }
        return outLines.joinToString("\n") + "\n"
    }

    private fun parseCsvLine(line: String): List<String> {
        val result = mutableListOf<String>()
        val current = StringBuilder()
        var inQuotes = false
        var i = 0
        while (i < line.length) {
            val c = line[i]
            if (inQuotes) {
                if (c == '"') {
                    if (i + 1 < line.length && line[i + 1] == '"') {
                        current.append('"')
                        i++
                    } else {
                        inQuotes = false
                    }
                } else {
                    current.append(c)
                }
            } else {
                if (c == '"') {
                    inQuotes = true
                } else if (c == ',') {
                    result.add(current.toString())
                    current.setLength(0)
                } else {
                    current.append(c)
                }
            }
            i++
        }
        result.add(current.toString())
        return result
    }

    private fun escapeCsv(value: String): String {
        return if (value.contains(',') || value.contains('"') || value.contains('\n') || value.contains('\r')) {
            "\"${value.replace("\"", "\"\"")}\""
        } else {
            value
        }
    }

    fun sha256Hex(data: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(data)
        return digest.joinToString("") { "%02x".format(it) }
    }

    fun sha256Hex(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { fis ->
            val buf = ByteArray(BUFFER_SIZE)
            var n: Int
            while (fis.read(buf).also { n = it } != -1) {
                digest.update(buf, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}

data class DateShiftedResult(
    val participantsCsv: String,
    val sessionsCsv: String,
    val capturesCsv: String,
    val tsbCsv: String,
    val participantOffsets: Map<String, Int>
)
