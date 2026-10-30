package com.kitalonlabs.neogold.feature.research.export

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import com.kitalonlabs.neogold.core.data.local.NeoGoldDatabase
import com.kitalonlabs.neogold.core.data.local.entity.AuditEventEntity
import com.kitalonlabs.neogold.core.data.local.entity.CaptureEntity
import com.kitalonlabs.neogold.core.data.local.entity.ResearchParticipantEntity
import com.kitalonlabs.neogold.core.data.local.entity.ScreeningSessionEntity
import com.kitalonlabs.neogold.core.data.local.entity.TsbMeasurementEntity
import com.kitalonlabs.neogold.core.security.EncryptedFileStore
import com.kitalonlabs.neogold.core.security.export.ExportCryptoEngine
import com.kitalonlabs.neogold.core.security.export.ExportHeader
import com.kitalonlabs.neogold.core.security.export.ManifestEntry
import com.kitalonlabs.neogold.core.security.export.ManifestParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
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
import kotlin.coroutines.cancellation.CancellationException

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

    /** Writes the export to a document chosen with the Storage Access Framework (see [exportToDestination]). */
    suspend fun exportToUri(
        contentResolver: ContentResolver,
        destinationUri: Uri,
        stagingDir: File = File(context.cacheDir, "export_staging"),
        onProgress: ((ExportProgress) -> Unit)? = null
    ): Result<ExportSummary> =
        exportToDestination(ExportDestination.forUri(contentResolver, destinationUri), stagingDir, onProgress)

    /**
     * Writes the export to [destination]. If the export fails or is cancelled, the destination is discarded: a half-written
     * archive that looks like an export is worse than none.
     */
    @Suppress("TooGenericExceptionCaught") // any failure goes back through Result, never a crash
    suspend fun exportToDestination(
        destination: ExportDestination,
        stagingDir: File = File(context.cacheDir, "export_staging"),
        onProgress: ((ExportProgress) -> Unit)? = null
    ): Result<ExportSummary> = withContext(Dispatchers.IO) {
        val result = try {
            val out = destination.open()
            if (out == null) {
                Result.failure(IllegalStateException("The export destination could not be opened"))
            } else {
                out.use { exportToStream(it, stagingDir, onProgress) }
            }
        } catch (e: CancellationException) {
            withContext(NonCancellable) { destination.discard() }
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
        if (result.isFailure) withContext(NonCancellable) { destination.discard() }
        result
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
            // Always start clean. Nothing is resumed, so nothing left by an earlier run is trusted or reused.
            stagingDir.deleteRecursively()
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

            // 3. A new data key for this export, in memory only (BUG-2026-038): it is never written to the staging
            // directory, and leaves the phone only inside the HPKE wrap addressed to the offline study key.
            val exportSession = ExportCryptoEngine.createSession()
            val completedFilesMap = linkedMapOf<String, String>()

            var completedCount = 0
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
                currentCoroutineContext().ensureActive() // leaving the screen cancels the export at the next file
                val targetFile = File(studyStaging, relPath).apply { parentFile?.mkdirs() }
                val cipherBytes = exportSession.encryptBytes(csvText.toByteArray(StandardCharsets.UTF_8))
                targetFile.writeBytes(cipherBytes)
                completedFilesMap[relPath] = ExportCryptoEngine.sha256Hex(cipherBytes)
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
                currentCoroutineContext().ensureActive()
                val relPath = "images/${cap.id}.jpg.enc"
                val targetFile = File(studyStaging, relPath).apply { parentFile?.mkdirs() }

                val imgFile = cap.imageFile!!
                encryptedFileStore.openDecryptedStream(imgFile).use { imageInStream ->
                    FileOutputStream(targetFile).use { fileOut ->
                        exportSession.encryptStream(imageInStream, fileOut)
                    }
                }
                completedFilesMap[relPath] = ExportCryptoEngine.sha256Hex(targetFile)

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

            // 10. The staging directory is removed in `finally`, on success, failure and cancel alike.

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
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // The message of an exception can carry a path or other values, so the screen is never given it.
            onProgress?.invoke(ExportProgress(0, 0, 0L, ExportStage.FAILED, "Export failed"))
            Result.failure(e)
        } finally {
            withContext(NonCancellable) { stagingDir.deleteRecursively() }
        }
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

/** Where an export is written: the stream to write to, and how to remove what was written when the export fails. */
class ExportDestination(
    val open: () -> OutputStream?,
    val discard: () -> Unit
) {
    companion object {
        /** A document chosen with the Storage Access Framework; a failed export deletes the partly written document. */
        fun forUri(contentResolver: ContentResolver, uri: Uri) = ExportDestination(
            open = { contentResolver.openOutputStream(uri) },
            discard = {
                try {
                    DocumentsContract.deleteDocument(contentResolver, uri)
                } catch (_: Exception) {
                    // the document may already be gone, or the provider may not allow deletion: nothing more to do
                }
            }
        )
    }
}

/**
 * The name of an export archive: `neogold-export-<STUDY_CODE>-<TIMESTAMP>.zip` (docs/specs/export-format.md Section 3). The
 * timestamp is UTC, so two exports never share a name and the name sorts by time. Anything in the study code that is not a
 * letter, a digit, an underscore or a dash is dropped, so the name is safe on every file system.
 */
fun exportFileName(studyCode: String, at: Instant): String {
    val code = studyCode
        .filter { (it.isLetterOrDigit() && it.code < ASCII_LIMIT) || it == '_' || it == '-' }
        .ifEmpty { "STUDY" }
    val stamp = java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'")
        .withZone(java.time.ZoneOffset.UTC)
        .format(at)
    return "neogold-export-$code-$stamp.zip"
}

private const val ASCII_LIMIT = 128
