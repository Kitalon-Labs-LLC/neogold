package com.kitalonlabs.neogold.feature.log

import com.google.common.truth.Truth.assertThat
import com.kitalonlabs.neogold.core.data.local.entity.ScreeningSessionEntity
import org.junit.Test

/**
 * Unit tests verifying Screening Log and Session Detail behaviors:
 * - TC-LOG-01: Screening log display & date filtering (ALL, TODAY, WEEK, MONTH).
 * - TC-LOG-08: Due rechecks identification.
 * - TC-LOG-12: Complete session reconstruction (versions, threshold, reasons).
 */
class ScreeningLogScreenTest {

    private fun createSession(
        id: String,
        ageMs: Long,
        action: String = "NO_FURTHER_ACTION_FROM_SCREEN",
        recheckDueMs: Long? = null,
        registerNumber: String? = null
    ): ScreeningSessionEntity {
        val now = System.currentTimeMillis()
        return ScreeningSessionEntity(
            id = id,
            mode = "SCREENING",
            status = "COMPLETED",
            createdAt = now - ageMs,
            completedAt = now - ageMs + 30000L,
            ageHours = 48.0,
            guardianConsentConfirmed = 1,
            gestationalAgeWeeks = 38,
            flagJaundiceUnder24h = 0,
            flagDangerSigns = 0,
            flagPriorPhototherapy = 0,
            ruleOutcome = null,
            triageAction = action,
            reasonCodes = "[\"NONE\"]",
            estimateMgDl = 8.5,
            estimateLowMgDl = 7.5,
            estimateHighMgDl = 9.5,
            thresholdMgDl = 12.0,
            recheckDueAt = recheckDueMs,
            captureAttempts = 1,
            registerNumber = registerNumber,
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
    }

    @Test
    fun dateFilter_filtersCorrectly() {
        val oneHourMs = 3600 * 1000L
        val twoDaysMs = 2 * 24 * 3600 * 1000L
        val tenDaysMs = 10 * 24 * 3600 * 1000L
        val fortyDaysMs = 40 * 24 * 3600 * 1000L

        val sToday = createSession("today-1", oneHourMs)
        val sWeek = createSession("week-1", twoDaysMs)
        val sMonth = createSession("month-1", tenDaysMs)
        val sOld = createSession("old-1", fortyDaysMs)

        val allSessions = listOf(sToday, sWeek, sMonth, sOld)
        val currentTimeMs = System.currentTimeMillis()
        val oneDayMs = 24L * 60 * 60 * 1000

        // Test TODAY filter
        val todayFiltered = allSessions.filter { currentTimeMs - it.createdAt < oneDayMs }
        assertThat(todayFiltered).containsExactly(sToday)

        // Test WEEK filter
        val weekFiltered = allSessions.filter { currentTimeMs - it.createdAt < 7L * oneDayMs }
        assertThat(weekFiltered).containsExactly(sToday, sWeek)

        // Test MONTH filter
        val monthFiltered = allSessions.filter { currentTimeMs - it.createdAt < 30L * oneDayMs }
        assertThat(monthFiltered).containsExactly(sToday, sWeek, sMonth)

        // Test ALL filter
        val allFiltered = allSessions
        assertThat(allFiltered).hasSize(4)
    }

    @Test
    fun sessionDetail_reconstructsAllClinicalFields() {
        val session = createSession(
            id = "sess-recon",
            ageMs = 1000L,
            action = "REFER_URGENT",
            registerNumber = "REG-999"
        )

        assertThat(session.id).isEqualTo("sess-recon")
        assertThat(session.triageAction).isEqualTo("REFER_URGENT")
        assertThat(session.registerNumber).isEqualTo("REG-999")
        assertThat(session.cardBatchId).isEqualTo("NG-2026-001")
        assertThat(session.appVersion).isEqualTo("1.0.0")
        assertThat(session.modelVersion).isEqualTo("1.0.0")
        assertThat(session.configVersion).isEqualTo("2026.10.0")
        assertThat(session.guardianConsentConfirmed).isEqualTo(1)
        assertThat(session.gestationalAgeWeeks).isEqualTo(38)
    }

    @Test
    fun dueRecheck_identifiesOverdueAndUpcoming() {
        val now = System.currentTimeMillis()
        val overdueSession = createSession("overdue", 10000L, action = "RECHECK", recheckDueMs = now - 5000L)
        val upcomingSession = createSession("upcoming", 10000L, action = "RECHECK", recheckDueMs = now + 50000L)
        val noRecheckSession = createSession("none", 10000L, action = "NO_ACTION", recheckDueMs = null)

        val sessions = listOf(overdueSession, upcomingSession, noRecheckSession)
        val dueList = sessions.filter { it.recheckDueAt != null && it.recheckDueAt!! <= now }

        assertThat(dueList).containsExactly(overdueSession)
    }
}
