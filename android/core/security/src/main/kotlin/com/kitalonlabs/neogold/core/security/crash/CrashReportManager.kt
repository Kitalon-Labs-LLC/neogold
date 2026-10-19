package com.kitalonlabs.neogold.core.security.crash

import android.content.Context
import android.content.Intent
import com.kitalonlabs.neogold.core.security.MasterKeyProvider
import java.io.File
import java.nio.charset.StandardCharsets

/**
 * Manages encrypted crash reports stored locally on device (NG-049, FR-34, doc 8 Section 6.6).
 *
 * Requirements:
 * - Stored in an encrypted local file.
 * - Exception messages dropped.
 * - Share crash report through Android share sheet on next launch.
 * - Nothing sent automatically.
 */
class CrashReportManager(
    private val masterKeyProvider: MasterKeyProvider,
    private val reportsDir: File
) {

    constructor(context: Context, masterKeyProvider: MasterKeyProvider) : this(
        masterKeyProvider = masterKeyProvider,
        reportsDir = File(context.filesDir, "crash_reports")
    )

    constructor(context: Context) : this(
        context = context,
        masterKeyProvider = com.kitalonlabs.neogold.core.security.AndroidMasterKeyProvider(context)
    )

    private val crashReportFile = File(reportsDir, "last_crash.enc")

    init {
        reportsDir.mkdirs()
    }

    /**
     * Checks if a crash report from a previous launch exists.
     */
    fun hasPendingReport(): Boolean {
        return crashReportFile.exists() && crashReportFile.length() > 0
    }

    /**
     * Writes an encrypted crash report to disk atomically.
     */
    fun saveCrashReport(report: CrashReport) {
        val jsonText = report.toJsonString()
        val plainBytes = jsonText.toByteArray(StandardCharsets.UTF_8)
        val encryptedBytes = masterKeyProvider.wrap(
            MasterKeyProvider.ALIAS_CRASH_REPORT,
            plainBytes
        )

        val tempFile = File(reportsDir, "last_crash.enc.tmp")
        tempFile.outputStream().use { fos ->
            fos.write(encryptedBytes)
            fos.flush()
            try {
                fos.fd.sync()
            } catch (_: Exception) {}
        }
        if (!tempFile.renameTo(crashReportFile)) {
            tempFile.copyTo(crashReportFile, overwrite = true)
            tempFile.delete()
        }
    }

    /**
     * Reads and decrypts the pending crash report.
     * Returns null if no report exists or decryption fails.
     */
    fun readPendingReport(): CrashReport? {
        if (!hasPendingReport()) return null
        return try {
            val encryptedBytes = crashReportFile.readBytes()
            val decryptedBytes = masterKeyProvider.unwrap(
                MasterKeyProvider.ALIAS_CRASH_REPORT,
                encryptedBytes
            )
            val jsonText = String(decryptedBytes, StandardCharsets.UTF_8)
            CrashReport.fromJsonString(jsonText)
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Deletes the pending crash report after it has been shared or dismissed.
     */
    fun clearPendingReport(): Boolean {
        return if (crashReportFile.exists()) {
            crashReportFile.delete()
        } else {
            true
        }
    }

    /**
     * Shares the pending crash report using the Android share sheet.
     */
    fun shareReport(context: Context): Boolean {
        val report = readPendingReport() ?: return false
        val intent = createShareIntent(report)
        context.startActivity(intent)
        return true
    }

    /**
     * Creates an Android share sheet Intent for the crash report.
     */
    fun createShareIntent(report: CrashReport): Intent {
        val plainText = report.toPlainText()
        val sendIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "NeoGold Crash Report (${report.timestamp})")
            putExtra(Intent.EXTRA_TEXT, plainText)
        }
        return Intent.createChooser(sendIntent, "Share crash report")
    }

    companion object {
        fun hasPendingReport(context: Context): Boolean {
            return CrashReportManager(context).hasPendingReport()
        }

        fun shareReport(context: Context): Boolean {
            return CrashReportManager(context).shareReport(context)
        }

        fun clearReport(context: Context): Boolean {
            return CrashReportManager(context).clearPendingReport()
        }
    }
}
