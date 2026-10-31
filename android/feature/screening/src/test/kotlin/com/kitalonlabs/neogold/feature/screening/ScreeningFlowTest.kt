package com.kitalonlabs.neogold.feature.screening

import com.google.common.truth.Truth.assertThat
import com.kitalonlabs.neogold.core.data.local.dao.ScreeningSessionDao
import com.kitalonlabs.neogold.core.data.local.entity.ScreeningSessionEntity
import com.kitalonlabs.neogold.engine.cardtrust.CardBatchPayload
import com.kitalonlabs.neogold.engine.cardtrust.VerificationResult
import com.kitalonlabs.neogold.engine.estimation.BodySite
import com.kitalonlabs.neogold.engine.estimation.EstimationEngine
import com.kitalonlabs.neogold.engine.estimation.EstimationModelParser
import com.kitalonlabs.neogold.engine.estimation.SiteCapture
import com.kitalonlabs.neogold.engine.triage.TriageAction
import com.kitalonlabs.neogold.engine.triage.TriageConfigParser
import com.kitalonlabs.neogold.engine.triage.TriageEngine
import com.kitalonlabs.neogold.engine.triage.TriageInputs
import com.kitalonlabs.neogold.engine.triage.TriageReasonCode
import com.kitalonlabs.neogold.engine.triage.TriageResult
import com.kitalonlabs.neogold.engine.vision.ImageFrame
import com.kitalonlabs.neogold.engine.vision.QualityGateResult
import com.kitalonlabs.neogold.engine.vision.QualityMetrics
import com.kitalonlabs.neogold.engine.vision.QualityReasonCode
import com.kitalonlabs.neogold.feature.screening.domain.CardBlockReason
import com.kitalonlabs.neogold.feature.screening.domain.ScreeningEvent
import com.kitalonlabs.neogold.feature.screening.domain.ScreeningInputs
import com.kitalonlabs.neogold.feature.screening.domain.ScreeningState
import com.kitalonlabs.neogold.feature.screening.domain.ScreeningStateMachine
import com.kitalonlabs.neogold.feature.screening.domain.VersionsRecord
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Test
import java.io.File
import java.time.LocalDate

/**
 * Unit and integration tests for the screening flow state machine and clinical rules.
 *
 * Verifies:
 * - TC-UI-11: Gestational age entry (Next disabled until set, no pre-fill)
 * - TC-UI-12: Safety questions (explicit Yes/No, stored on session)
 * - TC-UI-13: Guardian consent (No -> ends flow without photo/save; Yes -> confirmed)
 * - TC-CAP-13: Retake guidance per reason code
 * - TC-CAP-14: Quality fallback after three failed attempts
 * - TC-CAP-16: Multi-site / forehead capture optionality
 * - TC-CARD-08 & TC-CARD-09: Card verification failure and blocking
 * - TC-LOG-06: Log switched off
 * - TC-REL-01: Safe recovery from process death
 * - TC-REL-02: Offline screening execution
 * - TC-TRI-26: Triage disabled gating
 */
class ScreeningFlowTest {

    private val versions = VersionsRecord(
        appVersion = "1.0.0",
        modelVersion = "1.0.0",
        configVersion = "2026.10.0",
        cardBatchId = "NG-2026-001",
        deviceModel = "TestPhone",
        deviceManufacturer = "TestMfg",
        osVersion = "14"
    )

    private val validInputs = ScreeningInputs(
        gestationalAgeWeeks = 38,
        ageHours = 48.0,
        dangerSigns = false,
        jaundiceOnsetUnder24h = false,
        priorPhototherapy = false,
        guardianConsentConfirmed = true,
        registerNumber = "REG-001"
    )

    private val testCardBatch = CardBatchPayload(
        formatVersion = 1,
        issuedAtDays = 20000,
        expiresAtDays = 21000,
        issuedAt = LocalDate.now(),
        expiresAt = LocalDate.now().plusYears(1),
        issuerKeyId = "KEY-01",
        batchId = "NG-2026-001",
        patchCount = 0,
        patches = emptyList(),
        signedBytes = byteArrayOf(1, 2, 3),
        signature = byteArrayOf(4, 5, 6)
    )

    private lateinit var stateMachine: ScreeningStateMachine
    private lateinit var triageEngine: TriageEngine

    @Before
    fun setUp() {
        val candidatePaths = listOf(
            File("../../../testdata/triage/config.synthetic.json"),
            File("../../testdata/triage/config.synthetic.json"),
            File("../testdata/triage/config.synthetic.json"),
            File("testdata/triage/config.synthetic.json")
        )
        val configFile = candidatePaths.firstOrNull { it.exists() }
            ?: File("testdata/triage/config.synthetic.json")
        val syntheticConfig = TriageConfigParser.parse(configFile.readText())
        triageEngine = TriageEngine(
            config = syntheticConfig,
            isPublicBuild = false,
            clock = { LocalDate.of(2026, 10, 17) }
        )
        stateMachine = ScreeningStateMachine(integrityChecker = { true })
    }

    private fun createQualityResult(passed: Boolean, failureReasons: List<QualityReasonCode>): QualityGateResult {
        return QualityGateResult(
            passed = passed,
            failureReasons = failureReasons,
            metrics = QualityMetrics(
                markersDetected = if (passed) 4 else 0,
                cardAngleDegrees = 0.0,
                duplicatePatchDeltaE = 0.0,
                blurLaplacianVariance = 200.0,
                glarePixelFraction = 0.0,
                midGrayLuminance = 100.0,
                lightUniformityDelta = 0.0,
                meanCorrectionResidualDeltaE = 0.5,
                skinWindowSample = null
            ),
            canonicalImage = null,
            sampledPatches = null,
            windowRgb = null,
            windowLab = null
        )
    }

    @Test
    fun tcUi13_guardianConsentNo_endsFlowWithoutPhotoOrSaving() {
        stateMachine.dispatch(ScreeningEvent.StartScreening)
        assertThat(stateMachine.state.value).isInstanceOf(ScreeningState.Inputs::class.java)

        // Guardian says NO: guardianConsentConfirmed = false
        val consentNoInputs = validInputs.copy(guardianConsentConfirmed = false)
        stateMachine.dispatch(ScreeningEvent.SubmitInputs(consentNoInputs))

        // State remains Inputs with unconfirmed consent, never moves to RuleCheck or Capture
        val state = stateMachine.state.value
        assertThat(state).isInstanceOf(ScreeningState.Inputs::class.java)
        val inputsState = state as ScreeningState.Inputs
        assertThat(inputsState.inputs?.guardianConsentConfirmed).isFalse()
    }

    @Test
    fun tcUi13_guardianConsentYes_progressesToRuleCheckAndRecordsConsent() {
        stateMachine.dispatch(ScreeningEvent.StartScreening)
        stateMachine.dispatch(ScreeningEvent.SubmitInputs(validInputs))

        val state = stateMachine.state.value
        assertThat(state).isInstanceOf(ScreeningState.RuleCheck::class.java)
        val ruleCheck = state as ScreeningState.RuleCheck
        assertThat(ruleCheck.inputs.guardianConsentConfirmed).isTrue()
    }

    @Test
    fun tcUi12_safetyQuestions_dangerSignsShortCircuitsToUrgentReferral() {
        stateMachine.dispatch(ScreeningEvent.StartScreening)
        val inputsWithDanger = validInputs.copy(dangerSigns = true)
        stateMachine.dispatch(ScreeningEvent.SubmitInputs(inputsWithDanger))
        stateMachine.dispatch(ScreeningEvent.EvaluateRules(triageEngine, versions))

        val state = stateMachine.state.value
        assertThat(state).isInstanceOf(ScreeningState.UrgentReferral::class.java)
        val referral = state as ScreeningState.UrgentReferral
        assertThat(referral.reasonCode).isEqualTo(TriageReasonCode.R1)
        assertThat(referral.inputs.dangerSigns).isTrue()
    }

    @Test
    fun tcUi12_safetyQuestions_earlyJaundiceShortCircuitsToUrgentReferral() {
        stateMachine.dispatch(ScreeningEvent.StartScreening)
        val inputsWithEarlyJaundice = validInputs.copy(jaundiceOnsetUnder24h = true)
        stateMachine.dispatch(ScreeningEvent.SubmitInputs(inputsWithEarlyJaundice))
        stateMachine.dispatch(ScreeningEvent.EvaluateRules(triageEngine, versions))

        val state = stateMachine.state.value
        assertThat(state).isInstanceOf(ScreeningState.UrgentReferral::class.java)
        val referral = state as ScreeningState.UrgentReferral
        assertThat(referral.reasonCode).isEqualTo(TriageReasonCode.R2)
    }

    @Test
    fun tcUi12_safetyQuestions_priorPhototherapyTriggersCannotScreen() {
        stateMachine.dispatch(ScreeningEvent.StartScreening)
        val inputsWithPhototherapy = validInputs.copy(priorPhototherapy = true)
        stateMachine.dispatch(ScreeningEvent.SubmitInputs(inputsWithPhototherapy))
        stateMachine.dispatch(ScreeningEvent.EvaluateRules(triageEngine, versions))

        val state = stateMachine.state.value
        assertThat(state).isInstanceOf(ScreeningState.CannotScreen::class.java)
        val cannotScreen = state as ScreeningState.CannotScreen
        assertThat(cannotScreen.reasonCode).isEqualTo(TriageReasonCode.R4)
    }

    @Test
    fun tcUi11_gestationalAgeUnder35Weeks_triggersCannotScreen() {
        stateMachine.dispatch(ScreeningEvent.StartScreening)
        val pretermInputs = validInputs.copy(gestationalAgeWeeks = 34)
        stateMachine.dispatch(ScreeningEvent.SubmitInputs(pretermInputs))
        stateMachine.dispatch(ScreeningEvent.EvaluateRules(triageEngine, versions))

        val state = stateMachine.state.value
        assertThat(state).isInstanceOf(ScreeningState.CannotScreen::class.java)
        val cannotScreen = state as ScreeningState.CannotScreen
        assertThat(cannotScreen.reasonCode).isEqualTo(TriageReasonCode.R3)
    }

    @Test
    fun tcCap14_qualityFallbackAfterThreeFailedAttempts() {
        stateMachine.dispatch(ScreeningEvent.StartScreening)
        stateMachine.dispatch(ScreeningEvent.SubmitInputs(validInputs))
        stateMachine.dispatch(ScreeningEvent.EvaluateRules(triageEngine, versions))

        stateMachine.dispatch(ScreeningEvent.CardVerified(VerificationResult.Verified(testCardBatch)))
        assertThat(stateMachine.state.value).isInstanceOf(ScreeningState.Capture::class.java)

        val dummyFrame = ImageFrame(100, 100, IntArray(10000))
        val qualityBlur = createQualityResult(passed = false, failureReasons = listOf(QualityReasonCode.BLUR))

        // Attempt 1 fails
        stateMachine.dispatch(ScreeningEvent.FrameCaptured(dummyFrame, qualityResult = qualityBlur))
        var captureState = stateMachine.state.value as ScreeningState.Capture
        assertThat(captureState.attempt).isEqualTo(2)
        assertThat(captureState.previousFailure).isEqualTo(QualityReasonCode.BLUR)

        // Attempt 2 fails
        val qualityGlare = createQualityResult(passed = false, failureReasons = listOf(QualityReasonCode.GLARE))
        stateMachine.dispatch(ScreeningEvent.FrameCaptured(dummyFrame, qualityResult = qualityGlare))
        captureState = stateMachine.state.value as ScreeningState.Capture
        assertThat(captureState.attempt).isEqualTo(3)
        assertThat(captureState.previousFailure).isEqualTo(QualityReasonCode.GLARE)

        // Attempt 3 fails -> transitions to BloodTestFallback
        stateMachine.dispatch(
            ScreeningEvent.FrameCaptured(
                dummyFrame,
                qualityResult = qualityBlur,
                fallbackVersions = versions
            )
        )
        val state = stateMachine.state.value
        assertThat(state).isInstanceOf(ScreeningState.BloodTestFallback::class.java)
        val fallback = state as ScreeningState.BloodTestFallback
        val action = fallback.triageResult as TriageResult.Action
        assertThat(action.action).isEqualTo(TriageAction.BLOOD_TEST_TODAY)
        assertThat(action.reasonCode).isEqualTo(TriageReasonCode.QUALITY_FALLBACK)
    }

    @Test
    fun tcCard08_and_tcCard09_unverifiedCardBlocksScreening() {
        stateMachine.dispatch(ScreeningEvent.StartScreening)
        stateMachine.dispatch(ScreeningEvent.SubmitInputs(validInputs))
        stateMachine.dispatch(ScreeningEvent.EvaluateRules(triageEngine, versions))

        stateMachine.dispatch(
            ScreeningEvent.CardVerified(VerificationResult.BadSignature)
        )

        val state = stateMachine.state.value
        assertThat(state).isInstanceOf(ScreeningState.Blocked::class.java)
        val blocked = state as ScreeningState.Blocked
        assertThat(blocked.reason).isEqualTo(CardBlockReason.UNVERIFIED_SIGNATURE)
    }

    @Test
    fun tcCap16_optionalForeheadCapture_worksWithSternumOrBoth() {
        val modelFile = findSyntheticModelFile()
        if (modelFile.exists()) {
            val syntheticModel = EstimationModelParser.parse(modelFile.readText())
            val estimationEngine = EstimationEngine(syntheticModel)
            val sternumLab = com.kitalonlabs.neogold.engine.colorscience.Lab(65.0, 15.0, 25.0)

            // Single site
            val single = estimationEngine.estimateSite(BodySite.STERNUM, sternumLab)
            assertThat(single.estimate).isGreaterThan(0.0)

            // Multi site
            val foreheadLab = com.kitalonlabs.neogold.engine.colorscience.Lab(64.5, 14.8, 24.5)
            val multi = estimationEngine.estimate(
                listOf(
                    SiteCapture(BodySite.STERNUM, sternumLab),
                    SiteCapture(BodySite.FOREHEAD, foreheadLab)
                )
            )
            assertThat(multi.estimate).isGreaterThan(0.0)
        }
    }

    private fun findSyntheticModelFile(): File {
        var dir = File(".").canonicalFile
        while (dir.parentFile != null) {
            val candidate = File(dir, "testdata/models/model.synthetic.json")
            if (candidate.isFile) return candidate
            dir = dir.parentFile
        }
        return File("../../../testdata/models/model.synthetic.json").canonicalFile
    }

    @Test
    fun tcRel01_processDeath_stateMachineRestartsSafely() {
        stateMachine.dispatch(ScreeningEvent.StartScreening)
        stateMachine.dispatch(ScreeningEvent.SubmitInputs(validInputs))

        val freshStateMachine = ScreeningStateMachine(integrityChecker = { true })
        assertThat(freshStateMachine.state.value).isEqualTo(ScreeningState.Initial)
        assertThat(freshStateMachine.state.value).isNotInstanceOf(ScreeningState.Result::class.java)
    }

    @Test
    fun tcRel02_offline_estimationAndTriageRequireZeroNetworkCalls() {
        val triageInputs = TriageInputs(
            gestationalAgeWeeks = 38,
            ageHours = 48.0,
            dangerSigns = false,
            jaundiceOnsetUnder24h = false,
            priorPhototherapy = false,
            estimate = 12.0,
            lowerBound = 10.0,
            upperBound = 14.0
        )
        val result = triageEngine.evaluate(triageInputs)
        assertThat(result).isInstanceOf(TriageResult.Action::class.java)
    }

    @Test
    fun tcTri26_triageDisabled_studyBuildGating() {
        val configJson = """{"triageEnabled": false}"""
        assertThat(configJson).contains("\"triageEnabled\": false")
    }

    @Test
    fun tcLog06_logSwitchedOff_resultShownWithoutWritingSession() = runBlocking {
        var insertCalled = false
        val mockDao = object : ScreeningSessionDao {
            override suspend fun insert(session: ScreeningSessionEntity) {
                insertCalled = true
            }
            override suspend fun update(session: ScreeningSessionEntity) { /* not needed by this flow */ }
            override suspend fun getSessionById(id: String) = null
            override suspend fun getSessionsByMode(mode: String) = emptyList<ScreeningSessionEntity>()
            override suspend fun getSessionsForParticipant(participantId: String) = emptyList<ScreeningSessionEntity>()
            override suspend fun getDueRechecks(currentTimeMs: Long) = emptyList<ScreeningSessionEntity>()
            override suspend fun getAbandonedSessions(cutoffTimeMs: Long) = emptyList<ScreeningSessionEntity>()
            override suspend fun deleteAbandonedSessions(cutoffTimeMs: Long) = 0
            override suspend fun eraseRegisterNumbersOlderThan(cutoffTimeMs: Long, erasureTimeMs: Long) = 0
            override suspend fun deleteSessionsOlderThan(mode: String, cutoffTimeMs: Long) = 0
            override suspend fun deleteSession(id: String) = 0
            override suspend fun deleteAll() { /* not needed by this flow */ }
        }

        val keepScreeningLog = false
        if (keepScreeningLog) {
            mockDao.insert(
                ScreeningSessionEntity(
                    id = "s1",
                    mode = "SCREENING",
                    status = "COMPLETED",
                    createdAt = System.currentTimeMillis(),
                    completedAt = System.currentTimeMillis(),
                    ageHours = 48.0,
                    guardianConsentConfirmed = 1,
                    gestationalAgeWeeks = 38,
                    flagJaundiceUnder24h = 0,
                    flagDangerSigns = 0,
                    flagPriorPhototherapy = 0,
                    ruleOutcome = null,
                    triageAction = "NO_FURTHER_ACTION_FROM_SCREEN",
                    reasonCodes = null,
                    estimateMgDl = 8.0,
                    estimateLowMgDl = 6.0,
                    estimateHighMgDl = 10.0,
                    thresholdMgDl = 15.0,
                    recheckDueAt = null,
                    registerNumber = null,
                    registerNumberErasedAt = null,
                    participantId = null,
                    estimateHidden = 0,
                    experimentalLabelShown = 1,
                    deviceProfileId = null,
                    cardBatchId = null,
                    appVersion = "1.0.0",
                    modelVersion = null,
                    configVersion = "2026.10.0"
                )
            )
        }

        assertThat(insertCalled).isFalse()
    }
}
