package com.kitalonlabs.neogold.feature.screening.domain

import com.google.common.truth.Truth.assertThat
import com.kitalonlabs.neogold.engine.triage.PredictionInterval
import com.kitalonlabs.neogold.engine.triage.TriageAction
import com.kitalonlabs.neogold.engine.triage.TriageResult
import com.kitalonlabs.neogold.engine.triage.TriageUnavailableReason
import org.junit.Test

/**
 * The saved row is the shown decision (BUG-2026-021) with the versions and card that were really used (BUG-2026-022) and
 * the answers the worker really gave (BUG-2026-023): TC-LOG-01, TC-TRI-22, TC-TRI-26.
 */
class ScreeningRecordTest {

    private val now = 1_800_000_000_000L

    private val inputs = ScreeningInputs(
        gestationalAgeWeeks = 36,
        ageHours = 61.5,
        dangerSigns = false,
        jaundiceOnsetUnder24h = false,
        priorPhototherapy = false,
        guardianConsentConfirmed = true
    )

    private val versions = VersionsRecord(
        appVersion = "1.0.3",
        modelVersion = "2026.10-m1",
        configVersion = "2026.10-c7",
        cardBatchId = "BATCH-77",
        deviceModel = "Pixel 8",
        deviceManufacturer = "Google",
        osVersion = "Android 15"
    )

    private fun save(state: ScreeningState, register: String? = null, attempts: Int = 1) =
        state.toSessionEntity("s-1", now, register, "profile-1", attempts)

    private fun action(
        a: TriageAction,
        reason: String,
        interval: PredictionInterval? = null,
        threshold: Double? = null,
        recheck: Int? = null
    ) = TriageResult.Action(a, reason, threshold, interval, "cfg", recheck)

    @Test
    fun aHardRuleIsSavedWithItsRuleItsAnswersAndNoEstimateOrModel() {
        val withDanger = inputs.copy(dangerSigns = true)
        val result = action(TriageAction.REFER_URGENT, "R1")
        val noCard = versions.copy(cardBatchId = NO_CARD_SCANNED)

        val row = save(ScreeningState.UrgentReferral(withDanger, "R1", result, noCard), register = "12/345")!!

        assertThat(row.triageAction).isEqualTo("REFER_URGENT")
        assertThat(row.ruleOutcome).isEqualTo("R1")
        assertThat(row.reasonCodes).isEqualTo("[\"R1\"]")
        assertThat(row.flagDangerSigns).isEqualTo(1)
        assertThat(row.flagJaundiceUnder24h).isEqualTo(0)
        assertThat(row.flagPriorPhototherapy).isEqualTo(0)
        assertThat(row.estimateMgDl).isNull()
        assertThat(row.thresholdMgDl).isNull()
        assertThat(row.cardBatchId).isNull() // no card was scanned before the rule decided
        assertThat(row.modelVersion).isNull() // no model ran
        assertThat(row.appVersion).isEqualTo("1.0.3")
        assertThat(row.configVersion).isEqualTo("2026.10-c7")
        assertThat(row.registerNumber).isEqualTo("12/345")
        assertThat(row.ageHours).isEqualTo(61.5)
        assertThat(row.gestationalAgeWeeks).isEqualTo(36)
        assertThat(row.guardianConsentConfirmed).isEqualTo(1)
        assertThat(row.deviceProfileId).isEqualTo("profile-1")
    }

    @Test
    fun theFlagsAreTheAnswersGivenForEachRule() {
        val earlyInputs = inputs.copy(jaundiceOnsetUnder24h = true)
        val early = save(
            ScreeningState.UrgentReferral(earlyInputs, "R2", action(TriageAction.REFER_URGENT, "R2"), versions)
        )!!
        val photoInputs = inputs.copy(priorPhototherapy = true)
        val photoResult = action(TriageAction.CANNOT_SCREEN_BLOOD_TEST, "R4")
        val photo = save(ScreeningState.CannotScreen(photoInputs, "R4", photoResult, versions))!!

        assertThat(early.flagJaundiceUnder24h).isEqualTo(1)
        assertThat(early.flagPriorPhototherapy).isEqualTo(0)
        assertThat(photo.flagPriorPhototherapy).isEqualTo(1)
        assertThat(photo.triageAction).isEqualTo("CANNOT_SCREEN_BLOOD_TEST")
    }

    @Test
    fun aRecheckKeepsItsHoursAsADueTimeAndItsThreshold() {
        val interval = PredictionInterval(10.0, 8.0, 12.0)
        val recheck = action(TriageAction.RECHECK, "RECHECK_ZONE", interval, 12.5, recheck = 24)

        val row = save(ScreeningState.BloodTestFallback(inputs, recheck, versions))!!

        assertThat(row.triageAction).isEqualTo("RECHECK")
        assertThat(row.recheckDueAt).isEqualTo(now + 24 * 3_600_000L)
        assertThat(row.thresholdMgDl).isEqualTo(12.5)
        assertThat(row.estimateMgDl).isEqualTo(10.0)
        assertThat(row.estimateLowMgDl).isEqualTo(8.0)
        assertThat(row.estimateHighMgDl).isEqualTo(12.0)
    }

    @Test
    fun anUnavailableTriageIsSavedAsABloodTestWithNoEstimateOrReason() {
        val unavailable = TriageResult.Unavailable(TriageUnavailableReason.TRIAGE_DISABLED, "off", "cfg")
        val state = ScreeningState.TriageUnavailable(inputs, unavailable, versions.copy(cardBatchId = NO_CARD_SCANNED))

        val row = save(state)!!

        assertThat(row.triageAction).isEqualTo("CANNOT_SCREEN_BLOOD_TEST")
        assertThat(row.estimateMgDl).isNull()
        assertThat(row.estimateLowMgDl).isNull()
        assertThat(row.reasonCodes).isNull()
        assertThat(row.ruleOutcome).isNull()
    }

    @Test
    fun aStateThatIsNotTheEndOfASessionIsNotSaved() {
        assertThat(save(ScreeningState.Initial)).isNull()
        assertThat(save(ScreeningState.CardCheck(inputs))).isNull()
        assertThat(save(ScreeningState.Blocked(null, CardBlockReason.ASSET_INTEGRITY_COMPROMISED))).isNull()
        assertThat(save(ScreeningState.Blocked(inputs, CardBlockReason.BATCH_EXPIRED))).isNull()
    }

    @Test
    fun aRecheckIsDueItsHoursAfterTheDecisionWasShown_notAfterSaveWasTapped() {
        val interval = PredictionInterval(10.0, 8.0, 12.0)
        val recheck = action(TriageAction.RECHECK, "RECHECK_ZONE", interval, 12.5, recheck = 24)
        val decidedAt = now - 3 * 3_600_000L // the card was shown three hours before Save

        val row = ScreeningState.BloodTestFallback(inputs, recheck, versions)
            .toSessionEntity("s-1", now, null, null, 1, decidedAtMs = decidedAt)!!

        assertThat(row.recheckDueAt).isEqualTo(decidedAt + 24 * 3_600_000L)
    }
}
