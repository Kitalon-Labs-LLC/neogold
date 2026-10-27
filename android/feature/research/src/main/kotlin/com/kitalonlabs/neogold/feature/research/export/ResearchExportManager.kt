package com.kitalonlabs.neogold.feature.research.export

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import com.kitalonlabs.neogold.core.data.local.NeoGoldDatabase
import com.kitalonlabs.neogold.core.data.local.entity.AuditEventEntity
import com.kitalonlabs.neogold.core.data.local.entity.CaptureEntity
import com.kitalonlabs.neogold.core.data.local.entity.ResearchParticipantEntity
import com.kitalonlabs.neogold.core.data.local.entity.ScreeningSessionEntity
import com.kitalonlabs.neogold.core.data.local.entity.TsbMeasurementEntity
import com.kitalonlabs.neogold.core.security.EncryptedFileStore
import com.kitalonlabs.neogold.core.security.export.ExportCryptoEngine
import com.kitalonlabs.neogold.core.security.export.ExportHeader
import com.kitalonlabs.neogold.core.security.export.ExportSession
import com.kitalonlabs.neogold.core.security.export.ManifestEntry
import com.kitalonlabs.neogold.core.security.export.ManifestParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

enum class ExportStage {
    PREPARING,
    ENCRYPTING_METADATA,
    ENCRYPTING_DATA,
    ENCRYPTING_IMAGES,
    PACKAGING_ZIP,
    COMPLETED,
    FAILED
}

data class ExportProgress(
    val completedFiles: Int,
    val totalFiles: Int,
    val bytesProcessed: Long,
    val stage: ExportStage,
    val message: String
)

data class ExportSummary(
    val studyCode: String,
    val totalParticipants: Int,
    val totalSessions: Int,
    val totalCaptures: Int,
    val totalImages: Int,
    val totalZipBytes: Long,
    val manifestSha256: String,
    val exportedAt: String
)

class ResearchExportManager(
    private val context: Context,
    private val database: NeoGoldDatabase,
    private val encryptedFileStore: EncryptedFileStore
) {

    @Suppress("TooGenericExceptionCaught") // report any export failure through Result and progress, never crash
    suspend fun exportToUri(
        contentResolver: ContentResolver,
        destinationUri: Uri,
        stagingDir: File = File(context.cacheDir, "export_staging"),
        onProgress: ((ExportProgress) -> Unit)? = null
    ): Result<ExportSummary> = withContext(Dispatchers.IO) {
        try {
            val outputStream = contentResolver.openOutputStream(destinationUri)
                ?: return@withContext Result.failure(IllegalStateException("Could not open destination URI for writing: $destinationUri"))

            outputStream.use { out ->
                exportToStream(out, stagingDir, onProgress)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    @Suppress(
        "LongMethod",
        "TooGenericExceptionCaught"
    ) // report any export failure through Result and progress, never crash; reworked with the export wiring in C3
    suspend fun exportToStream(
        outputStream: OutputStream,
        stagingDir: File = File(context.cacheDir, "export_staging"),
        onProgress: ((ExportProgress) -> Unit)? = null
    ): Result<ExportSummary> = withContext(Dispatchers.IO) {
        try {
            onProgress?.invoke(ExportProgress(0, 0, 0L, ExportStage.PREPARING, "Loading study data..."))

            // 1. Verify active study and export public key
            val study = database.researchStudyDao().getActiveStudy()
                ?: return@withContext Result.failure(IllegalStateException("No active research study enrolled on this device"))

            val exportPublicKey = study.exportPublicKey
                ?: return@withContext Result.failure(
                    IllegalStateException("Active study '${study.studyCode}' has no export public key configured in its profile")
                )

            // 2. Fetch all research records
            val participants = database.researchParticipantDao().getParticipantsForStudy(study.id)
            val participantMap = participants.associateBy { it.id }

            val sessions = mutableListOf<ScreeningSessionEntity>()
            for (p in participants) {
                sessions.addAll(database.screeningSessionDao().getSessionsForParticipant(p.id))
            }

            val captures = mutableListOf<CaptureEntity>()
            for (s in sessions) {
                captures.addAll(database.captureDao().getCapturesForSession(s.id))
            }
            val imageCaptures = captures.filter { it.imageFile != null }

            val tsbMeasurements = mutableListOf<TsbMeasurementEntity>()
            for (p in participants) {
                tsbMeasurements.addAll(database.tsbMeasurementDao().getTsbForParticipant(p.id))
            }

            val totalExpectedFiles = 4 + imageCaptures.size // 4 CSVs + images
            val studyStaging = File(stagingDir, study.studyCode).apply { mkdirs() }
            val checkpointFile = File(studyStaging, "checkpoint.json")

            // 3. Load or generate data keyset session
            val (exportSession, completedFilesMap) = loadOrCreateCheckpoint(checkpointFile)

            var completedCount = completedFilesMap.size
            var totalBytesProcessed = 0L

            onProgress?.invoke(
                ExportProgress(
                    completedFiles = completedCount,
                    totalFiles = totalExpectedFiles,
                    bytesProcessed = totalBytesProcessed,
                    stage = ExportStage.ENCRYPTING_DATA,
                    message = "Encrypting data tables..."
                )
            )

            // 4. Encrypt CSV tables (Sessions, Captures, TSB, Participants)
            val csvFiles = listOf(
                "data/sessions.csv.enc" to generateSessionsCsv(sessions, participantMap),
                "data/captures.csv.enc" to generateCapturesCsv(captures),
                "data/tsb_measurements.csv.enc" to generateTsbCsv(tsbMeasurements, participantMap),
                "data/participants.csv.enc" to generateParticipantsCsv(participants)
            )

            for ((relPath, csvText) in csvFiles) {
                val targetFile = File(studyStaging, relPath).apply { parentFile?.mkdirs() }
                if (!isCompletedAndValid(relPath, targetFile, completedFilesMap)) {
                    val plaintextBytes = csvText.toByteArray(StandardCharsets.UTF_8)
                    val cipherBytes = exportSession.encryptBytes(plaintextBytes)
                    targetFile.writeBytes(cipherBytes)
                    val sha = ExportCryptoEngine.sha256Hex(cipherBytes)
                    completedFilesMap[relPath] = sha
                    saveCheckpoint(checkpointFile, exportSession, completedFilesMap)
                }
                completedCount = completedFilesMap.size
                totalBytesProcessed += targetFile.length()
                onProgress?.invoke(
                    ExportProgress(
                        completedFiles = completedCount,
                        totalFiles = totalExpectedFiles,
                        bytesProcessed = totalBytesProcessed,
                        stage = ExportStage.ENCRYPTING_DATA,
                        message = "Encrypted $relPath"
                    )
                )
            }

            // 5. Encrypt research images with progress & resume
            onProgress?.invoke(
                ExportProgress(
                    completedFiles = completedCount,
                    totalFiles = totalExpectedFiles,
                    bytesProcessed = totalBytesProcessed,
                    stage = ExportStage.ENCRYPTING_IMAGES,
                    message = "Encrypting ${imageCaptures.size} research images..."
                )
            )

            for ((index, cap) in imageCaptures.withIndex()) {
                val relPath = "images/${cap.id}.jpg.enc"
                val targetFile = File(studyStaging, relPath).apply { parentFile?.mkdirs() }

                if (!isCompletedAndValid(relPath, targetFile, completedFilesMap)) {
                    val imgFile = cap.imageFile!!
                    encryptedFileStore.openDecryptedStream(imgFile).use { imageInStream ->
                        FileOutputStream(targetFile).use { fileOut ->
                            exportSession.encryptStream(imageInStream, fileOut)
                        }
                    }
                    val sha = ExportCryptoEngine.sha256Hex(targetFile)
                    completedFilesMap[relPath] = sha
                    saveCheckpoint(checkpointFile, exportSession, completedFilesMap)
                }

                completedCount = completedFilesMap.size
                totalBytesProcessed += targetFile.length()
                onProgress?.invoke(
                    ExportProgress(
                        completedFiles = completedCount,
                        totalFiles = totalExpectedFiles,
                        bytesProcessed = totalBytesProcessed,
                        stage = ExportStage.ENCRYPTING_IMAGES,
                        message = "Encrypted image ${index + 1}/${imageCaptures.size}"
                    )
                )
            }

            // 6. Generate manifest.sha256
            onProgress?.invoke(
                ExportProgress(
                    completedFiles = completedCount,
                    totalFiles = totalExpectedFiles,
                    bytesProcessed = totalBytesProcessed,
                    stage = ExportStage.PACKAGING_ZIP,
                    message = "Building manifest and archive..."
                )
            )

            val manifestEntries = completedFilesMap.entries
                .sortedBy { it.key }
                .map { ManifestEntry(it.value, it.key) }
            val manifestContent = ManifestParser.format(manifestEntries)
            val manifestBytes = manifestContent.toByteArray(StandardCharsets.UTF_8)
            val manifestSha256 = ExportCryptoEngine.sha256Hex(manifestBytes)

            // 7. Wrap data key with Tink HPKE
            val wrappedDataKeyB64 = exportSession.wrapDataKey(exportPublicKey)
            val exportedAtIso = Instant.now().toString()

            val header = ExportHeader(
                exportFormatVersion = 1,
                csvSchemaVersion = 1,
                studyCode = study.studyCode,
                exportedAt = exportedAtIso,
                totalParticipants = participants.size,
                totalSessions = sessions.size,
                totalImages = imageCaptures.size,
                wrappedDataKeyB64 = wrappedDataKeyB64,
                manifestSha256 = manifestSha256
            )
            val headerBytes = header.toJsonString().toByteArray(StandardCharsets.UTF_8)

            // 8. Stream everything into ZIP
            var totalZipBytes = 0L
            val countingOutStream = object : OutputStream() {
                override fun write(b: Int) {
                    outputStream.write(b)
                    totalZipBytes++
                }

                override fun write(b: ByteArray, off: Int, len: Int) {
                    outputStream.write(b, off, len)
                    totalZipBytes += len
                }

                override fun flush() {
                    outputStream.flush()
                }
            }

            ZipOutputStream(BufferedOutputStream(countingOutStream)).use { zipOut ->
                // Write header.json
                zipOut.putNextEntry(ZipEntry("header.json"))
                zipOut.write(headerBytes)
                zipOut.closeEntry()

                // Write manifest.sha256
                zipOut.putNextEntry(ZipEntry("manifest.sha256"))
                zipOut.write(manifestBytes)
                zipOut.closeEntry()

                // Write all ciphertext files
                val buffer = ByteArray(8192)
                for (entry in manifestEntries) {
                    val file = File(studyStaging, entry.relativePath)
                    zipOut.putNextEntry(ZipEntry(entry.relativePath))
                    FileInputStream(file).use { fis ->
                        var n: Int
                        while (fis.read(buffer).also { n = it } != -1) {
                            zipOut.write(buffer, 0, n)
                        }
                    }
                    zipOut.closeEntry()
                }
                zipOut.finish()
                zipOut.flush()
            }

            // 9. Record audit event
            val summary = ExportSummary(
                studyCode = study.studyCode,
                totalParticipants = participants.size,
                totalSessions = sessions.size,
                totalCaptures = captures.size,
                totalImages = imageCaptures.size,
                totalZipBytes = totalZipBytes,
                manifestSha256 = manifestSha256,
                exportedAt = exportedAtIso
            )

            database.auditEventDao().insert(
                AuditEventEntity(
                    id = UUID.randomUUID().toString(),
                    eventType = "EXPORT_CREATED",
                    occurredAt = System.currentTimeMillis(),
                    details = "Exported study ${study.studyCode}: ${summary.totalParticipants} participants, " +
                        "${summary.totalSessions} sessions, ${summary.totalImages} images (${summary.totalZipBytes} bytes)"
                )
            )

            // 10. Clean up staging directory on complete
            studyStaging.deleteRecursively()

            onProgress?.invoke(
                ExportProgress(
                    completedFiles = totalExpectedFiles,
                    totalFiles = totalExpectedFiles,
                    bytesProcessed = totalZipBytes,
                    stage = ExportStage.COMPLETED,
                    message = "Export complete (${summary.totalZipBytes} bytes)"
                )
            )

            Result.success(summary)
        } catch (e: Exception) {
            onProgress?.invoke(
                ExportProgress(0, 0, 0L, ExportStage.FAILED, "Export failed: ${e.message}")
            )
            Result.failure(e)
        }
    }

    private fun isCompletedAndValid(relPath: String, file: File, completedMap: Map<String, String>): Boolean {
        val expectedSha = completedMap[relPath] ?: return false
        if (!file.exists() || file.length() == 0L) return false
        val actualSha = ExportCryptoEngine.sha256Hex(file)
        return actualSha.equals(expectedSha, ignoreCase = true)
    }

    private fun loadOrCreateCheckpoint(checkpointFile: File): Pair<ExportSession, MutableMap<String, String>> {
        if (checkpointFile.exists()) {
            try {
                val jsonStr = checkpointFile.readText(StandardCharsets.UTF_8)
                val obj = JSONObject(jsonStr)
                val rawKeysetB64 = obj.getString("raw_keyset_b64")
                val session = ExportCryptoEngine.restoreSession(rawKeysetB64)

                val completedFiles = mutableMapOf<String, String>()
                val filesObj = obj.getJSONObject("files")
                for (key in filesObj.keys()) {
                    completedFiles[key] = filesObj.getString(key)
                }
                return Pair(session, completedFiles)
            } catch (_: Exception) {
                // If checkpoint corrupted, reset
            }
        }

        // New checkpoint
        val session = ExportCryptoEngine.createSession()
        val completedFiles = mutableMapOf<String, String>()
        saveCheckpoint(checkpointFile, session, completedFiles)
        return Pair(session, completedFiles)
    }

    private fun saveCheckpoint(checkpointFile: File, session: ExportSession, completedFiles: Map<String, String>) {
        val rawKeysetB64 = session.serializeKeyset()

        val filesObj = JSONObject()
        for ((path, sha) in completedFiles) {
            filesObj.put(path, sha)
        }

        val root = JSONObject().apply {
            put("raw_keyset_b64", rawKeysetB64)
            put("files", filesObj)
            put("updated_at", System.currentTimeMillis())
        }

        checkpointFile.writeText(root.toString(2), StandardCharsets.UTF_8)
    }

    // CSV Generators enforcing data minimization
    private fun generateSessionsCsv(
        sessions: List<ScreeningSessionEntity>,
        participantMap: Map<String, ResearchParticipantEntity>
    ): String {
        val sb = StringBuilder()
        sb.append(
            "session_id,participant_code,status,created_at,age_hours,gestational_age_weeks,rule_outcome,triage_action," +
                "estimate_mg_dl,estimate_low_mg_dl,estimate_high_mg_dl,device_model,card_batch_id\n"
        )

        for (s in sessions) {
            val pCode = s.participantId?.let { participantMap[it]?.participantCode } ?: ""
            sb.append(
                listOf(
                    s.id,
                    pCode,
                    s.status,
                    s.createdAt,
                    s.ageHours,
                    s.gestationalAgeWeeks,
                    s.ruleOutcome ?: "",
                    s.triageAction ?: "",
                    s.estimateMgDl ?: "",
                    s.estimateLowMgDl ?: "",
                    s.estimateHighMgDl ?: "",
                    s.deviceProfileId ?: "",
                    s.cardBatchId ?: ""
                ).joinToString(",") { escapeCsv(it.toString()) }
            ).append("\n")
        }
        return sb.toString()
    }

    private fun generateCapturesCsv(captures: List<CaptureEntity>): String {
        val sb = StringBuilder()
        sb.append(
            "capture_id,session_id,attempt_number,site,captured_at,quality_status,window_l,window_a,window_b," +
                "correction_residual_de,image_file,image_sha256\n"
        )

        for (c in captures) {
            val imgFileRel = c.imageFile?.let { "images/${c.id}.jpg" } ?: ""
            sb.append(
                listOf(
                    c.id,
                    c.sessionId,
                    c.attemptNumber,
                    c.site,
                    c.capturedAt,
                    c.qualityStatus,
                    "", // window_l parsed downstream if features extracted
                    "", // window_a
                    "", // window_b
                    c.correctionResidualDe ?: "",
                    imgFileRel,
                    c.imageSha256 ?: ""
                ).joinToString(",") { escapeCsv(it.toString()) }
            ).append("\n")
        }
        return sb.toString()
    }

    private fun generateTsbCsv(
        tsbList: List<TsbMeasurementEntity>,
        participantMap: Map<String, ResearchParticipantEntity>
    ): String {
        val sb = StringBuilder()
        sb.append("tsb_id,participant_code,session_id,tsb_mg_dl,draw_time,method\n")

        for (t in tsbList) {
            val pCode = participantMap[t.participantId]?.participantCode ?: ""
            sb.append(
                listOf(
                    t.id,
                    pCode,
                    t.sessionId ?: "",
                    t.tsbMgDl,
                    t.drawTime,
                    t.method
                ).joinToString(",") { escapeCsv(it.toString()) }
            ).append("\n")
        }
        return sb.toString()
    }

    private fun generateParticipantsCsv(participants: List<ResearchParticipantEntity>): String {
        val sb = StringBuilder()
        sb.append("participant_id,participant_code,consent_form_id,consent_recorded_at,created_at\n")

        for (p in participants) {
            sb.append(
                listOf(
                    p.id,
                    p.participantCode,
                    p.consentFormId,
                    p.consentRecordedAt,
                    p.createdAt
                ).joinToString(",") { escapeCsv(it.toString()) }
            ).append("\n")
        }
        return sb.toString()
    }

    private fun escapeCsv(value: String): String {
        return if (value.indexOfAny(charArrayOf(',', '"', '\n', '\r')) >= 0) {
            "\"${value.replace("\"", "\"\"")}\""
        } else {
            value
        }
    }
}
