package com.kitalonlabs.neogold.core.security

import android.content.Intent
import com.google.common.truth.Truth.assertThat
import com.kitalonlabs.neogold.core.security.crash.CrashReport
import com.kitalonlabs.neogold.core.security.crash.CrashReportManager
import com.kitalonlabs.neogold.core.security.crash.InHouseCrashHandler
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.lang.IllegalStateException

@RunWith(RobolectricTestRunner::class)
class InHouseCrashHandlerTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var reportsDir: File
    private lateinit var fakeMasterKeyProvider: FakeMasterKeyProvider
    private lateinit var crashReportManager: CrashReportManager

    @Before
    fun setUp() {
        reportsDir = tempFolder.newFolder("crash_reports")
        fakeMasterKeyProvider = FakeMasterKeyProvider()
        crashReportManager = CrashReportManager(fakeMasterKeyProvider, reportsDir)
    }

    @Test
    fun exceptionMessageWithRegisterNumber_isCompletelyDropped_TC_PRIV_05() {
        val sensitiveRegisterNo = "REG-2026-9999"
        val sensitiveParticipantCode = "SKP-9876"
        val sensitiveMsg = "Clinical error for participant $sensitiveParticipantCode with register $sensitiveRegisterNo in hospital ward"

        val cause = NullPointerException("Cause details with register $sensitiveRegisterNo")
        val crashException = IllegalStateException(sensitiveMsg, cause)

        val report = CrashReport.fromThrowable(crashException)
        val plainText = report.toPlainText()
        val jsonText = report.toJsonString()

        // 1. Verify sensitive data is NOT present anywhere in plain text or JSON
        assertThat(plainText).doesNotContain(sensitiveRegisterNo)
        assertThat(plainText).doesNotContain(sensitiveParticipantCode)
        assertThat(plainText).doesNotContain("Clinical error")
        assertThat(plainText).doesNotContain("Cause details")

        assertThat(jsonText).doesNotContain(sensitiveRegisterNo)
        assertThat(jsonText).doesNotContain(sensitiveParticipantCode)
        assertThat(jsonText).doesNotContain("Clinical error")
        assertThat(jsonText).doesNotContain("Cause details")

        // 2. Verify class names and stack frames ARE present
        assertThat(report.throwable.exceptionClass).isEqualTo("java.lang.IllegalStateException")
        assertThat(report.throwable.cause?.exceptionClass).isEqualTo("java.lang.NullPointerException")
        assertThat(report.throwable.stackFrames).isNotEmpty()
        assertThat(plainText).contains("java.lang.IllegalStateException")
        assertThat(plainText).contains("java.lang.NullPointerException")
    }

    @Test
    fun crashReport_encryptedLocallyOnDisk_TC_PRIV_05() {
        val sensitiveRegisterNo = "REG-5555"
        val exception = RuntimeException("Sensitive $sensitiveRegisterNo")
        val report = CrashReport.fromThrowable(exception)

        crashReportManager.saveCrashReport(report)

        assertThat(crashReportManager.hasPendingReport()).isTrue()

        // Check raw bytes on disk: must be encrypted ciphertext, not plaintext
        val fileOnDisk = File(reportsDir, "last_crash.enc")
        assertThat(fileOnDisk.exists()).isTrue()
        val rawBytes = fileOnDisk.readBytes()
        val rawString = String(rawBytes)

        assertThat(rawString).doesNotContain("java.lang.RuntimeException")
        assertThat(rawString).doesNotContain(sensitiveRegisterNo)

        // Decrypt report: returns original sanitized report
        val decrypted = crashReportManager.readPendingReport()
        assertThat(decrypted).isNotNull()
        assertThat(decrypted?.throwable?.exceptionClass).isEqualTo("java.lang.RuntimeException")

        // Clear report
        crashReportManager.clearPendingReport()
        assertThat(crashReportManager.hasPendingReport()).isFalse()
    }

    @Test
    fun inHouseCrashHandler_catchesUncaughtExceptionAndSavesReport() {
        var defaultHandlerCalled = false
        val defaultHandler = Thread.UncaughtExceptionHandler { _, _ ->
            defaultHandlerCalled = true
        }

        val handler = InHouseCrashHandler(
            crashReportManager = crashReportManager,
            appVersion = "1.0.0",
            defaultHandler = defaultHandler
        )

        val exception = IllegalArgumentException("Test crash with register REG-0001")
        handler.uncaughtException(Thread.currentThread(), exception)

        assertThat(defaultHandlerCalled).isTrue()
        assertThat(crashReportManager.hasPendingReport()).isTrue()

        val savedReport = crashReportManager.readPendingReport()
        assertThat(savedReport?.throwable?.exceptionClass).isEqualTo("java.lang.IllegalArgumentException")
        assertThat(savedReport?.toPlainText()).doesNotContain("REG-0001")
    }

    @Test
    fun shareCrashReport_createsShareSheetIntent() {
        val report = CrashReport.fromThrowable(IllegalStateException("Boom"))
        val chooserIntent = crashReportManager.createShareIntent(report)

        assertThat(chooserIntent.action).isEqualTo(Intent.ACTION_CHOOSER)
        val targetIntent = chooserIntent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
        assertThat(targetIntent).isNotNull()
        assertThat(targetIntent?.action).isEqualTo(Intent.ACTION_SEND)
        assertThat(targetIntent?.type).isEqualTo("text/plain")
        assertThat(targetIntent?.getStringExtra(Intent.EXTRA_TEXT)).contains("NeoGold Crash Report")
        assertThat(targetIntent?.getStringExtra(Intent.EXTRA_TEXT)).doesNotContain("Boom")
    }
}
