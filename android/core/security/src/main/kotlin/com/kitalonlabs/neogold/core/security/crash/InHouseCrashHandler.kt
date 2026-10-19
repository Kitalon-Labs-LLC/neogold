package com.kitalonlabs.neogold.core.security.crash

/**
 * In-house UncaughtExceptionHandler that sanitizes crash stack traces, drops exception messages,
 * writes an encrypted local file, and delegates to the default exception handler.
 */
class InHouseCrashHandler(
    private val crashReportManager: CrashReportManager,
    private val appVersion: String = "1.0.0",
    private val defaultHandler: Thread.UncaughtExceptionHandler? = Thread.getDefaultUncaughtExceptionHandler()
) : Thread.UncaughtExceptionHandler {

    override fun uncaughtException(thread: Thread, throwable: Throwable) {
        try {
            val report = CrashReport.fromThrowable(
                throwable = throwable,
                thread = thread,
                appVersion = appVersion
            )
            crashReportManager.saveCrashReport(report)
        } catch (_: Throwable) {
            // Never let crash recording crash the process
        } finally {
            defaultHandler?.uncaughtException(thread, throwable)
        }
    }

    companion object {
        fun install(
            crashReportManager: CrashReportManager,
            appVersion: String = "1.0.0"
        ): InHouseCrashHandler {
            val original = Thread.getDefaultUncaughtExceptionHandler()
            val handler = InHouseCrashHandler(crashReportManager, appVersion, original)
            Thread.setDefaultUncaughtExceptionHandler(handler)
            return handler
        }

        fun install(
            context: android.content.Context,
            appVersion: String = "1.0.0"
        ): InHouseCrashHandler {
            val manager = CrashReportManager(context)
            return install(manager, appVersion)
        }
    }
}
