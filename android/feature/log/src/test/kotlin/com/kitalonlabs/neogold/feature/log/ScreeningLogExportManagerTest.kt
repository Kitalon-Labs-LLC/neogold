package com.kitalonlabs.neogold.feature.log

import com.google.common.truth.Truth.assertThat
import com.kitalonlabs.neogold.core.data.local.entity.ScreeningSessionEntity
import org.junit.Test

/**
 * Tests for [ScreeningLogExportManager] verifying TC-LOG-10 (FR-33, NG-026).
 */
class ScreeningLogExportManagerTest {

    private val sampleSession = ScreeningSessionEntity(
        id = "session-123",
        mode = "SCREENING",
        status = "COMPLETED",
        createdAt = 1700000000000L,
        completedAt = 1700000030000L,
        ageHours = 48.0,
        guardianConsentConfirmed = 1,
        gestationalAgeWeeks = 38,
        flagJaundiceUnder24h = 0,
        flagDangerSigns = 0,
        flagPriorPhototherapy = 0,
        ruleOutcome = null,
        triageAction = "BLOOD_TEST_TODAY",
        reasonCodes = "[\"ZONE_WITHIN3\"]",
        estimateMgDl = 14.5,
        estimateLowMgDl = 12.5,
        estimateHighMgDl = 16.5,
        thresholdMgDl = 15.0,
        recheckDueAt = null,
        captureAttempts = 1,
        registerNumber = "REG-456",
        registerNumberErasedAt = null,
        participantId = null,
        estimateHidden = 0,
        experimentalLabelShown = 1,
        deviceProfileId = null,
        cardBatchId = "NG-2026-001",
        appVersion = "1.0.0",
        modelVersion = "1.0.0",
        configVersion = "2026.10.0"
    )

    @Test
    fun exportToCsv_defaultExcludesRegisterNumbers() {
        val csv = ScreeningLogExportManager.exportToCsv(
            sessions = listOf(sampleSession),
            includeRegisterNumbers = false
        )

        // Verify header does NOT contain register_number
        val header = csv.lines().first()
        assertThat(header).doesNotContain("register_number")

        // Verify row does NOT contain the actual register number
        val rows = csv.lines().filter { it.isNotBlank() }
        assertThat(rows).hasSize(2) // header + 1 row
        assertThat(rows[1]).doesNotContain("REG-456")
        assertThat(rows[1]).contains("session-123")
        assertThat(rows[1]).contains("BLOOD_TEST_TODAY")
    }

    @Test
    fun exportToCsv_explicitOptInIncludesRegisterNumbers() {
        val csv = ScreeningLogExportManager.exportToCsv(
            sessions = listOf(sampleSession),
            includeRegisterNumbers = true
        )

        val header = csv.lines().first()
        assertThat(header).contains("register_number")

        val rows = csv.lines().filter { it.isNotBlank() }
        assertThat(rows).hasSize(2)
        assertThat(rows[1]).contains("REG-456")
    }

    @Test
    fun exportToCsv_emptyListReturnsHeaderOnly() {
        val csv = ScreeningLogExportManager.exportToCsv(emptyList())
        val lines = csv.lines().filter { it.isNotBlank() }
        assertThat(lines).hasSize(1)
        assertThat(lines[0]).startsWith("session_id,timestamp")
    }
}
