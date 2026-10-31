package com.kitalonlabs.neogold.core.security.crash

import android.os.Build
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant

/**
 * Data structure representing a sanitized crash report (NG-049, FR-34, doc 8 Section 6.6).
 *
 * PRIVACY GUARANTEE:
 * Exception messages are completely dropped to prevent leakage of formatted register numbers,
 * study codes, or user-entered text. Only exception class names and stack frames are retained.
 */
data class CrashFrame(
    val className: String,
    val methodName: String,
    val fileName: String?,
    val lineNumber: Int
) {
    override fun toString(): String {
        val fileInfo = if (fileName != null && lineNumber > 0) "$fileName:$lineNumber" else "Unknown Source"
        return "$className.$methodName($fileInfo)"
    }
}

data class CrashThrowable(
    val exceptionClass: String,
    val stackFrames: List<CrashFrame>,
    val cause: CrashThrowable? = null
)

data class CrashReport(
    val timestamp: String,
    val appVersion: String,
    val androidVersion: String,
    val deviceManufacturer: String,
    val deviceModel: String,
    val threadName: String,
    val throwable: CrashThrowable
) {

    fun toPlainText(): String {
        val sb = StringBuilder()
        sb.appendLine("NeoGold Crash Report")
        sb.appendLine("====================")
        sb.appendLine("Time: $timestamp")
        sb.appendLine("App Version: $appVersion")
        sb.appendLine("Android: $androidVersion")
        sb.appendLine("Device: $deviceManufacturer $deviceModel")
        sb.appendLine("Thread: $threadName")
        sb.appendLine()
        sb.appendLine("Stack Trace (Messages omitted for privacy):")
        appendThrowable(sb, throwable, isCause = false)
        return sb.toString()
    }

    private fun appendThrowable(sb: StringBuilder, t: CrashThrowable, isCause: Boolean) {
        val prefix = if (isCause) "Caused by: " else "Exception: "
        sb.appendLine("$prefix${t.exceptionClass}")
        for (frame in t.stackFrames) {
            sb.appendLine("\tat $frame")
        }
        if (t.cause != null) {
            appendThrowable(sb, t.cause, isCause = true)
        }
    }

    fun toJsonString(): String {
        val root = JSONObject().apply {
            put("timestamp", timestamp)
            put("appVersion", appVersion)
            put("androidVersion", androidVersion)
            put("deviceManufacturer", deviceManufacturer)
            put("deviceModel", deviceModel)
            put("threadName", threadName)
            put("throwable", throwableToJson(throwable))
        }
        return root.toString(2)
    }

    private fun throwableToJson(t: CrashThrowable): JSONObject {
        return JSONObject().apply {
            put("exceptionClass", t.exceptionClass)
            val framesArr = JSONArray()
            for (f in t.stackFrames) {
                framesArr.put(
                    JSONObject().apply {
                        put("className", f.className)
                        put("methodName", f.methodName)
                        put("fileName", f.fileName ?: JSONObject.NULL)
                        put("lineNumber", f.lineNumber)
                    }
                )
            }
            put("stackFrames", framesArr)
            if (t.cause != null) {
                put("cause", throwableToJson(t.cause))
            }
        }
    }

    companion object {
        fun fromThrowable(
            throwable: Throwable,
            thread: Thread = Thread.currentThread(),
            appVersion: String = "1.0.0",
            now: Instant = Instant.now()
        ): CrashReport {
            return CrashReport(
                timestamp = now.toString(),
                appVersion = appVersion,
                androidVersion = "API ${Build.VERSION.SDK_INT} (${Build.VERSION.RELEASE})",
                deviceManufacturer = Build.MANUFACTURER ?: "Unknown",
                deviceModel = Build.MODEL ?: "Unknown",
                threadName = thread.name,
                throwable = parseThrowable(throwable)
            )
        }

        private fun parseThrowable(t: Throwable): CrashThrowable {
            val frames = t.stackTrace.map { element ->
                CrashFrame(
                    className = element.className,
                    methodName = element.methodName,
                    fileName = element.fileName,
                    lineNumber = element.lineNumber
                )
            }
            val cause = t.cause?.let { parseThrowable(it) }
            return CrashThrowable(
                exceptionClass = t.javaClass.name,
                stackFrames = frames,
                cause = cause
            )
        }

        fun fromJsonString(jsonStr: String): CrashReport {
            val root = JSONObject(jsonStr)
            return CrashReport(
                timestamp = root.getString("timestamp"),
                appVersion = root.getString("appVersion"),
                androidVersion = root.getString("androidVersion"),
                deviceManufacturer = root.getString("deviceManufacturer"),
                deviceModel = root.getString("deviceModel"),
                threadName = root.getString("threadName"),
                throwable = parseThrowableJson(root.getJSONObject("throwable"))
            )
        }

        private fun parseThrowableJson(obj: JSONObject): CrashThrowable {
            val exceptionClass = obj.getString("exceptionClass")
            val framesArr = obj.getJSONArray("stackFrames")
            val frames = mutableListOf<CrashFrame>()
            for (i in 0 until framesArr.length()) {
                val fObj = framesArr.getJSONObject(i)
                frames.add(
                    CrashFrame(
                        className = fObj.getString("className"),
                        methodName = fObj.getString("methodName"),
                        fileName = if (fObj.isNull("fileName")) null else fObj.getString("fileName"),
                        lineNumber = fObj.getInt("lineNumber")
                    )
                )
            }
            val cause = if (obj.has("cause") && !obj.isNull("cause")) {
                parseThrowableJson(obj.getJSONObject("cause"))
            } else {
                null
            }
            return CrashThrowable(
                exceptionClass = exceptionClass,
                stackFrames = frames,
                cause = cause
            )
        }
    }
}
