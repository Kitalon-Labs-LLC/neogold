package com.kitalonlabs.neogold.feature.log

import com.kitalonlabs.neogold.core.data.local.entity.ScreeningSessionEntity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Manager for exporting local screening logs to CSV format.
 * Implements FR-33, NG-026, TC-LOG-10.
 * Default behavior excludes facility register numbers to protect privacy.
 */
object ScreeningLogExportManager {

    /**
     * Formats screening sessions as CSV string.
     *
     * @param sessions List of screening session entities to export.
     * @param includeRegisterNumbers Whether to include register numbers (default false per TC-LOG-10).
     */
    fun exportToCsv(
        sessions: List<ScreeningSessionEntity>,
        includeRegisterNumbers: Boolean = false
    ): String {
        val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
        val sb = StringBuilder()

        // Header
        val baseHeaders = listOf(
            "session_id",
            "timestamp",
            "age_hours",
            "gestational_age_weeks",
            "triage_action",
            "reason_codes",
            "estimate_mg_dl",
            "estimate_low_mg_dl",
            "estimate_high_mg_dl",
            "threshold_mg_dl",
            "app_version",
            "model_version",
            "config_version",
            "card_batch_id"
        )
        val headers = if (includeRegisterNumbers) baseHeaders + "register_number" else baseHeaders
        sb.append(headers.joinToString(",")).append("\n")

        for (s in sessions) {
            val formattedDate = dateFormat.format(Date(s.createdAt))
            val rowValues = mutableListOf(
                escapeCsv(s.id),
                escapeCsv(formattedDate),
                s.ageHours.toString(),
                s.gestationalAgeWeeks.toString(),
                escapeCsv(s.triageAction ?: ""),
                escapeCsv(s.reasonCodes ?: ""),
                s.estimateMgDl?.toString() ?: "",
                s.estimateLowMgDl?.toString() ?: "",
                s.estimateHighMgDl?.toString() ?: "",
                s.thresholdMgDl?.toString() ?: "",
                escapeCsv(s.appVersion),
                escapeCsv(s.modelVersion ?: ""),
                escapeCsv(s.configVersion),
                escapeCsv(s.cardBatchId ?: "")
            )
            if (includeRegisterNumbers) {
                rowValues.add(escapeCsv(s.registerNumber ?: ""))
            }
            sb.append(rowValues.joinToString(",")).append("\n")
        }

        return sb.toString()
    }

    private fun escapeCsv(value: String): String {
        return if (value.contains(",") || value.contains("\"") || value.contains("\n")) {
            "\"" + value.replace("\"", "\"\"") + "\""
        } else {
            value
        }
    }
}
