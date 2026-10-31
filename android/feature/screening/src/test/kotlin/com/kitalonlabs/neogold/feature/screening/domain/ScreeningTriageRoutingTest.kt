package com.kitalonlabs.neogold.feature.screening.domain

import com.google.common.truth.Truth.assertThat
import com.kitalonlabs.neogold.core.designsystem.components.organisms.TriageUiAction
import com.kitalonlabs.neogold.engine.cardtrust.CardBatchPayload
import com.kitalonlabs.neogold.engine.cardtrust.PatchReference
import com.kitalonlabs.neogold.engine.cardtrust.VerificationResult
import com.kitalonlabs.neogold.engine.triage.ClinicalSignoff
import com.kitalonlabs.neogold.engine.triage.PredictionInterval
import com.kitalonlabs.neogold.engine.triage.SignoffStatus
import com.kitalonlabs.neogold.engine.triage.TriageAction
import com.kitalonlabs.neogold.engine.triage.TriageConfig
import com.kitalonlabs.neogold.engine.triage.TriageConfigParser
import com.kitalonlabs.neogold.engine.triage.TriageEngine
import com.kitalonlabs.neogold.engine.triage.TriageReasonCode
import com.kitalonlabs.neogold.engine.triage.TriageResult
import com.kitalonlabs.neogold.engine.triage.TriageUnavailableReason
import com.kitalonlabs.neogold.engine.vision.ImageFrame
import com.kitalonlabs.neogold.engine.vision.QualityGateResult
import com.kitalonlabs.neogold.engine.vision.QualityMetrics
import com.kitalonlabs.neogold.engine.vision.QualityReasonCode
import org.junit.Before
import org.junit.Test
import java.io.File
import java.time.LocalDate

/**
 * Package E1: every clinical decision in the screening flow, hard rules and quality fallback included, goes through
 * `TriageEngine`, so the engine's first step (triage on, signed for a public build, not expired) applies on every path
 * (BUG-2026-019, BUG-2026-020, TC-TRI-26); and the state machine invents no input and no version (BUG-2026-022, 023).
 */
class ScreeningTriageRoutingTest {

    private lateinit var config: TriageConfig
    private val today = LocalDate.of(2026, 10, 17)

    private val versions = VersionsRecord(
        appVersion = "1.0.0",
        modelVersion = "2026.10-synth",
        configVersion = "2026.10.0-synth",
        cardBatchId = "pending-scan",
        deviceModel = "Pixel 8",
        deviceManufacturer = "Google",
        osVersion = "Android 15"
    )

    private val batch = CardBatchPayload(
        formatVersion = 1,
        issuedAtDays = 1000,
        expiresAtDays = 2000,
        issuedAt = LocalDate.of(2026, 9, 1),
        expiresAt = LocalDate.of(2027, 9, 1),
        issuerKeyId = "neogold-dhaka-lab1",
        batchId = "BATCH-2026-001",
        patchCount = 23,
        patches = (0 until 23).map { PatchReference(it, 50.0, 0.0, 0.0) },
        signedBytes = ByteArray(100),
        signature = ByteArray(64)
    )

    private val wellInputs = ScreeningInputs(
        gestationalAgeWeeks = 38,
        ageHours = 48.0,
        dangerSigns = false,
        jaundiceOnsetUnder24h = false,
        priorPhototherapy = false,
        guardianConsentConfirmed = true
    )

    private val failedQuality = QualityGateResult(
        passed = false,
        failureReasons = listOf(QualityReasonCode.BLUR),
        metrics = QualityMetrics(4, 5.0, 0.5, 30.0, 0.0, 128.0, 0.5, 1.0, null),
        canonicalImage = null,
        sampledPatches = null,
        windowRgb = null,
        windowLab = null
    )

    @Before
    fun setUp() {
        val file = listOf(
            File("../../../testdata/triage/config.synthetic.json"),
            File("../../testdata/triage/config.synthetic.json"),
            File("../testdata/triage/config.synthetic.json"),
            File("testdata/triage/config.synthetic.json")
        ).firstOrNull { it.exists() } ?: error("Could not find config.synthetic.json")
        config = TriageConfigParser.parse(file.readText())
    }

    private fun engine(cfg: TriageConfig = config, publicBuild: Boolean = false) =
        TriageEngine(cfg, isPublicBuild = publicBuild, clock = { today })

    private fun machineAt(inputs: ScreeningInputs, eng: TriageEngine): Pair<ScreeningStateMachine, ScreeningState> {
        val sm = ScreeningStateMachine(integrityChecker = { true })
        sm.dispatch(ScreeningEvent.StartScreening)
        sm.dispatch(ScreeningEvent.SubmitInputs(inputs))
        return sm to sm.dispatch(ScreeningEvent.EvaluateRules(eng, versions))
    }

    private val frame = ImageFrame(10, 10, IntArray(100))

    // ---- TC-TRI-26: triage not available -------------------------------------------------------------------------

    @Test
    fun tcTri26_aSwitchedOffConfigNeverReachesAHardRuleOrTheCamera() {
        val off = config.copy(triageEnabled = false)

        // even with a danger sign: the screen does not pretend to triage; it says to use a blood test
        val (_, state) = machineAt(wellInputs.copy(dangerSigns = true), engine(off))

        val unavailable = state as ScreeningState.TriageUnavailable
        assertThat(unavailable.triageResult.reason).isEqualTo(TriageUnavailableReason.TRIAGE_DISABLED)
        assertThat(unavailable.versions).isEqualTo(versions)
    }

    @Test
    fun tcTri26_anExpiredConfigStopsTheFlowBeforeTheCamera() {
        val expired = config.copy(validUntil = today.minusDays(1))

        val (_, state) = machineAt(wellInputs, engine(expired))

        assertThat((state as ScreeningState.TriageUnavailable).triageResult.reason)
            .isEqualTo(TriageUnavailableReason.CONFIG_EXPIRED)
    }

    @Test
    fun tcTri26_anUnsignedConfigInAPublicBuildStopsTheFlow_butNotInADevelopmentBuild() {
        val unsigned = config.copy(clinicalSignoff = ClinicalSignoff(SignoffStatus.DRAFT, null))

        val (_, publicState) = machineAt(wellInputs, engine(unsigned, publicBuild = true))
        val (_, devState) = machineAt(wellInputs, engine(unsigned, publicBuild = false))

        assertThat((publicState as ScreeningState.TriageUnavailable).triageResult.reason)
            .isEqualTo(TriageUnavailableReason.CONFIG_UNSIGNED)
        assertThat(devState).isInstanceOf(ScreeningState.CardCheck::class.java)
    }

    @Test
    fun tcTri26_aSessionWithUnavailableTriageCanBeSaved_andIsPresentedAsABloodTestWithNoEstimate() {
        val off = config.copy(triageEnabled = false)
        val (sm, _) = machineAt(wellInputs, engine(off))

        val saved = sm.dispatch(ScreeningEvent.SaveSession("session-1")) as ScreeningState.Saved
        val shown = saved.summary.triageResult.toPresentation()

        assertThat(shown.uiAction).isEqualTo(TriageUiAction.CANNOT_SCREEN)
        assertThat(shown.nextStep).isEqualTo(NextStep.USE_BLOOD_TEST)
        assertThat(shown.interval).isNull()
        assertThat(shown.thresholdMgDl).isNull()
        assertThat(shown.storedAction).isEqualTo(TriageAction.CANNOT_SCREEN_BLOOD_TEST.name)
    }

    // ---- BUG-2026-020: the hard rules come from the engine ------------------------------------------------------

    @Test
    fun bug020_theHardRulesAreWhateverTheEngineSays() {
        val cases = mapOf(
            wellInputs.copy(dangerSigns = true) to TriageReasonCode.R1,
            wellInputs.copy(jaundiceOnsetUnder24h = true) to TriageReasonCode.R2,
            wellInputs.copy(gestationalAgeWeeks = 34) to TriageReasonCode.R3,
            wellInputs.copy(priorPhototherapy = true) to TriageReasonCode.R4,
            wellInputs.copy(ageHours = 400.0) to TriageReasonCode.R5
        )
        for ((inputs, code) in cases) {
            val (_, state) = machineAt(inputs, engine())
            val result = when (state) {
                is ScreeningState.UrgentReferral -> state.triageResult
                is ScreeningState.CannotScreen -> state.triageResult
                else -> error("expected a hard rule for $code, got $state")
            } as TriageResult.Action
            assertThat(result.reasonCode).isEqualTo(code)
            assertThat(result).isEqualTo(engine().evaluateBeforeCapture(inputs.toTriage()))
        }
    }

    private fun ScreeningInputs.toTriage() = com.kitalonlabs.neogold.engine.triage.TriageInputs(
        dangerSigns = dangerSigns,
        jaundiceOnsetUnder24h = jaundiceOnsetUnder24h,
        gestationalAgeWeeks = gestationalAgeWeeks,
        priorPhototherapy = priorPhototherapy,
        ageHours = ageHours
    )

    // ---- quality fallback ------------------------------------------------------------------------------------------

    @Test
    fun theQualityFallbackIsTheEnginesAnswer_withTheCardBatchThatWasScanned() {
        val (sm, _) = machineAt(wellInputs, engine())
        sm.dispatch(ScreeningEvent.CardVerified(VerificationResult.Verified(batch)))
        sm.dispatch(ScreeningEvent.FrameCaptured(frame, null, failedQuality))
        sm.dispatch(ScreeningEvent.FrameCaptured(frame, null, failedQuality))

        val state = sm.dispatch(
            ScreeningEvent.FrameCaptured(frame, null, failedQuality)
        ) as ScreeningState.BloodTestFallback

        val action = state.triageResult as TriageResult.Action
        assertThat(action.action).isEqualTo(TriageAction.BLOOD_TEST_TODAY)
        assertThat(action.reasonCode).isEqualTo(TriageReasonCode.QUALITY_FALLBACK)
        // the record names the card that was scanned, not the placeholder it was started with
        assertThat(state.versions.cardBatchId).isEqualTo("BATCH-2026-001")
        assertThat(state.versions.configVersion).isEqualTo(versions.configVersion)
    }

    @Test
    fun noVersionsAreMadeUp_aThirdFailedCaptureWithNothingKnownLeavesTheStateAsItIs() {
        // a machine that never saw EvaluateRules has no engine and no versions to record
        val capture = ScreeningState.Capture(wellInputs, batch, attempt = 3, previousFailure = QualityReasonCode.BLUR)
        val sm = ScreeningStateMachine(integrityChecker = { true }, initialState = capture)

        val state = sm.dispatch(ScreeningEvent.FrameCaptured(frame, null, failedQuality))

        assertThat(state).isEqualTo(capture)
    }

    @Test
    fun aConfigThatExpiresDuringTheSession_makesTheThirdFailureUnavailable_notAFallbackAdvice() {
        var now = today
        val eng = TriageEngine(config.copy(validUntil = today), clock = { now })
        val (sm, started) = machineAt(wellInputs, eng)
        assertThat(started).isInstanceOf(ScreeningState.CardCheck::class.java)
        sm.dispatch(ScreeningEvent.CardVerified(VerificationResult.Verified(batch)))
        sm.dispatch(ScreeningEvent.FrameCaptured(frame, null, failedQuality))
        sm.dispatch(ScreeningEvent.FrameCaptured(frame, null, failedQuality))

        now = today.plusDays(1) // midnight passes while the worker is taking photos

        val state = sm.dispatch(ScreeningEvent.FrameCaptured(frame, null, failedQuality))

        assertThat((state as ScreeningState.TriageUnavailable).triageResult.reason)
            .isEqualTo(TriageUnavailableReason.CONFIG_EXPIRED)
    }

    // ---- nothing invented ------------------------------------------------------------------------------------------

    @Test
    fun bug023_aBlockedStartInventsNoInputs() {
        val sm = ScreeningStateMachine(integrityChecker = { false })

        val blocked = sm.dispatch(ScreeningEvent.StartScreening) as ScreeningState.Blocked

        assertThat(blocked.inputs).isNull()
        assertThat(blocked.reason).isEqualTo(CardBlockReason.ASSET_INTEGRITY_COMPROMISED)
    }

    // ---- the result mapper (BUG-2026-006, BUG-2026-021) ------------------------------------------------------------

    private fun action(
        a: TriageAction,
        reason: String,
        interval: PredictionInterval? = null,
        threshold: Double? = null,
        recheck: Int? = null
    ) = TriageResult.Action(a, reason, threshold, interval, "cfg", recheck)

    @Test
    fun bug006_021_eachEngineActionIsShownAndStoredAsTheSameThing() {
        val interval = PredictionInterval(10.0, 8.5, 11.5)
        val rows = listOf(
            Triple(action(TriageAction.REFER_URGENT, "R1"), TriageUiAction.REFER_URGENT, NextStep.ARRANGE_TRANSPORT),
            Triple(
                action(TriageAction.BLOOD_TEST_TODAY, "ABOVE_THRESHOLD", interval, 12.0),
                TriageUiAction.BLOOD_TEST,
                NextStep.SEND_BLOOD_TEST
            ),
            Triple(
                action(TriageAction.RECHECK, "RECHECK_ZONE", interval, 12.0, recheck = 24),
                TriageUiAction.RECHECK,
                NextStep.CHECK_AGAIN
            ),
            Triple(
                action(TriageAction.NO_FURTHER_ACTION_FROM_SCREEN, "-", interval, 15.0),
                TriageUiAction.NO_FURTHER_ACTION,
                NextStep.CONTINUE_ROUTINE
            ),
            Triple(
                action(TriageAction.CANNOT_SCREEN_BLOOD_TEST, "R3"),
                TriageUiAction.CANNOT_SCREEN,
                NextStep.USE_BLOOD_TEST
            ),
            Triple(
                action(TriageAction.CANNOT_SCREEN_CLINICAL_REVIEW, "R5"),
                TriageUiAction.CANNOT_SCREEN,
                NextStep.NEEDS_DOCTOR_REVIEW
            )
        )
        for ((result, ui, next) in rows) {
            val shown = result.toPresentation()
            assertThat(shown.uiAction).isEqualTo(ui)
            assertThat(shown.nextStep).isEqualTo(next)
            // what is stored is the engine's own action name, so the log and the card cannot differ (doc 5 Section 2.2)
            assertThat(shown.storedAction).isEqualTo(result.action.name)
            assertThat(shown.interval).isEqualTo(result.interval)
        }
    }

    @Test
    fun aCannotScreenCardNamesTheRuleThatFired() {
        assertThat(action(TriageAction.CANNOT_SCREEN_BLOOD_TEST, "R3").toPresentation().cannotScreenReason)
            .isEqualTo(CannotScreenReason.UNDER_35_WEEKS)
        assertThat(action(TriageAction.CANNOT_SCREEN_BLOOD_TEST, "R4").toPresentation().cannotScreenReason)
            .isEqualTo(CannotScreenReason.PRIOR_PHOTOTHERAPY)
        assertThat(action(TriageAction.CANNOT_SCREEN_CLINICAL_REVIEW, "R5").toPresentation().cannotScreenReason)
            .isEqualTo(CannotScreenReason.OVER_14_DAYS)
    }

    @Test
    fun aRecheckThatSaysNoHoursIsShownAsABloodTest_neverAsSomethingMilder() {
        val shown = action(TriageAction.RECHECK, "RECHECK_ZONE", recheck = null).toPresentation()

        assertThat(shown.uiAction).isEqualTo(TriageUiAction.BLOOD_TEST)
        assertThat(shown.storedAction).isEqualTo(TriageAction.BLOOD_TEST_TODAY.name)
        assertThat(shown.recheckHours).isNull()
    }

    @Test
    fun aRecheckKeepsTheHoursTheEngineGave_andNothingElseCarriesHours() {
        val shown = action(TriageAction.RECHECK, "RECHECK_ZONE", recheck = 12).toPresentation()
        assertThat(shown.recheckHours).isEqualTo(12)
        assertThat(action(TriageAction.REFER_URGENT, "R1").toPresentation().recheckHours).isNull()
    }

    @Test
    fun theNoReasonMarkerIsNotShownAsAReason() {
        assertThat(action(TriageAction.NO_FURTHER_ACTION_FROM_SCREEN, "-").toPresentation().reasonCode).isNull()
        assertThat(action(TriageAction.REFER_URGENT, "R2").toPresentation().reasonCode).isEqualTo("R2")
    }
}
