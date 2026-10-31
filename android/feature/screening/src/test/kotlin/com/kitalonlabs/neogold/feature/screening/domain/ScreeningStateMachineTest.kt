package com.kitalonlabs.neogold.feature.screening.domain

import com.google.common.truth.Truth.assertThat
import com.kitalonlabs.neogold.engine.cardtrust.CardBatchPayload
import com.kitalonlabs.neogold.engine.cardtrust.PatchReference
import com.kitalonlabs.neogold.engine.cardtrust.VerificationResult
import com.kitalonlabs.neogold.engine.triage.TriageAction
import com.kitalonlabs.neogold.engine.triage.TriageConfigParser
import com.kitalonlabs.neogold.engine.triage.TriageEngine
import com.kitalonlabs.neogold.engine.triage.TriageReasonCode
import com.kitalonlabs.neogold.engine.triage.TriageResult
import com.kitalonlabs.neogold.engine.vision.QualityGateResult
import com.kitalonlabs.neogold.engine.vision.QualityMetrics
import com.kitalonlabs.neogold.engine.vision.QualityReasonCode
import org.junit.Before
import org.junit.Test
import java.io.File
import java.time.LocalDate

/**
 * Tests for the Screening State Machine specified in doc 4 Section 4.4.
 * Covers:
 * - TC-TRI-01, TC-TRI-02 (Urgent referrals: R1, R2)
 * - TC-TRI-03, TC-TRI-04, TC-TRI-05 (Cannot screen: R3, R4, R5)
 * - TC-CAP-14, TC-TRI-15 (Quality fallback after 3 failures)
 * - TC-CARD-02, TC-CARD-05 (Card verification blocked states)
 * - TC-UI-13 (Guardian consent requirement)
 */
class ScreeningStateMachineTest {

    private lateinit var triageEngine: TriageEngine
    private lateinit var testVersions: VersionsRecord
    private lateinit var verifiedCardBatch: CardBatchPayload

    private val repoRoot: File
        get() {
            var dir = File(".").canonicalFile
            while (dir.parentFile != null) {
                if (File(dir, "testdata").isDirectory && (File(dir, ".git").isDirectory || File(dir, "docs").isDirectory)) {
                    return dir
                }
                dir = dir.parentFile!!
            }
            return File("../../..").canonicalFile
        }

    @Before
    fun setUp() {
        val configFile = File(repoRoot, "testdata/triage/config.synthetic.json")
        val config = TriageConfigParser.parse(configFile.readText())
        triageEngine = TriageEngine(config)

        testVersions = VersionsRecord(
            appVersion = "1.0.0",
            modelVersion = "2026.10-synth",
            configVersion = "2026.10.0-synth",
            cardBatchId = "BATCH-2026-001",
            deviceModel = "Pixel 8",
            deviceManufacturer = "Google",
            osVersion = "Android 15"
        )

        verifiedCardBatch = CardBatchPayload(
            formatVersion = 1,
            issuedAtDays = 1000,
            expiresAtDays = 2000,
            issuedAt = LocalDate.now().minusDays(10),
            expiresAt = LocalDate.now().plusDays(365),
            issuerKeyId = "neogold-dhaka-lab1",
            batchId = "BATCH-2026-001",
            patchCount = 23,
            patches = (0 until 23).map { PatchReference(it, 50.0, 0.0, 0.0) },
            signedBytes = ByteArray(100),
            signature = ByteArray(64)
        )
    }

    @Test
    fun testStartScreening_transitionsToInputs() {
        val sm = ScreeningStateMachine(integrityChecker = { true })
        assertThat(sm.currentState).isEqualTo(ScreeningState.Initial)

        val next = sm.dispatch(ScreeningEvent.StartScreening)
        assertThat(next).isInstanceOf(ScreeningState.Inputs::class.java)
    }

    @Test
    fun testConsentNotConfirmed_staysInInputs() {
        val sm = ScreeningStateMachine(integrityChecker = { true })
        sm.dispatch(ScreeningEvent.StartScreening)

        val unconsentedInputs = ScreeningInputs(
            gestationalAgeWeeks = 38,
            ageHours = 48.0,
            dangerSigns = false,
            jaundiceOnsetUnder24h = false,
            priorPhototherapy = false,
            guardianConsentConfirmed = false // Refused consent (TC-UI-13)
        )

        val state = sm.dispatch(ScreeningEvent.SubmitInputs(unconsentedInputs))
        assertThat(state).isInstanceOf(ScreeningState.Inputs::class.java)
        val inputsState = state as ScreeningState.Inputs
        assertThat(inputsState.inputs?.guardianConsentConfirmed).isFalse()
    }

    @Test
    fun testHardRuleR1_dangerSigns_urgentReferral() {
        val sm = ScreeningStateMachine(integrityChecker = { true })
        sm.dispatch(ScreeningEvent.StartScreening)

        val inputs = ScreeningInputs(
            gestationalAgeWeeks = 38,
            ageHours = 48.0,
            dangerSigns = true, // Danger signs present!
            jaundiceOnsetUnder24h = false,
            priorPhototherapy = false,
            guardianConsentConfirmed = true
        )
        sm.dispatch(ScreeningEvent.SubmitInputs(inputs))
        val state = sm.dispatch(ScreeningEvent.EvaluateRules(triageEngine, testVersions))

        assertThat(state).isInstanceOf(ScreeningState.UrgentReferral::class.java)
        val referral = state as ScreeningState.UrgentReferral
        assertThat(referral.reasonCode).isEqualTo(TriageReasonCode.R1)
        val action = referral.triageResult as TriageResult.Action
        assertThat(action.action).isEqualTo(TriageAction.REFER_URGENT)
    }

    @Test
    fun testHardRuleR2_earlyJaundice_urgentReferral() {
        val sm = ScreeningStateMachine(integrityChecker = { true })
        sm.dispatch(ScreeningEvent.StartScreening)

        val inputs = ScreeningInputs(
            gestationalAgeWeeks = 38,
            ageHours = 12.0,
            dangerSigns = false,
            jaundiceOnsetUnder24h = true, // Jaundice < 24h
            priorPhototherapy = false,
            guardianConsentConfirmed = true
        )
        sm.dispatch(ScreeningEvent.SubmitInputs(inputs))
        val state = sm.dispatch(ScreeningEvent.EvaluateRules(triageEngine, testVersions))

        assertThat(state).isInstanceOf(ScreeningState.UrgentReferral::class.java)
        val referral = state as ScreeningState.UrgentReferral
        assertThat(referral.reasonCode).isEqualTo(TriageReasonCode.R2)
    }

    @Test
    fun testHardRuleR3_gaUnder35_cannotScreen() {
        val sm = ScreeningStateMachine(integrityChecker = { true })
        sm.dispatch(ScreeningEvent.StartScreening)

        val inputs = ScreeningInputs(
            gestationalAgeWeeks = 34, // GA < 35
            ageHours = 48.0,
            dangerSigns = false,
            jaundiceOnsetUnder24h = false,
            priorPhototherapy = false,
            guardianConsentConfirmed = true
        )
        sm.dispatch(ScreeningEvent.SubmitInputs(inputs))
        val state = sm.dispatch(ScreeningEvent.EvaluateRules(triageEngine, testVersions))

        assertThat(state).isInstanceOf(ScreeningState.CannotScreen::class.java)
        val cannot = state as ScreeningState.CannotScreen
        assertThat(cannot.reasonCode).isEqualTo(TriageReasonCode.R3)
    }

    @Test
    fun testHardRulesPass_transitionsToCardCheck() {
        val sm = ScreeningStateMachine(integrityChecker = { true })
        sm.dispatch(ScreeningEvent.StartScreening)

        val inputs = ScreeningInputs(
            gestationalAgeWeeks = 38,
            ageHours = 48.0,
            dangerSigns = false,
            jaundiceOnsetUnder24h = false,
            priorPhototherapy = false,
            guardianConsentConfirmed = true
        )
        sm.dispatch(ScreeningEvent.SubmitInputs(inputs))
        val state = sm.dispatch(ScreeningEvent.EvaluateRules(triageEngine, testVersions))

        assertThat(state).isInstanceOf(ScreeningState.CardCheck::class.java)
    }

    @Test
    fun testCardVerificationFailure_transitionsToBlocked() {
        val sm = ScreeningStateMachine(integrityChecker = { true })
        sm.dispatch(ScreeningEvent.StartScreening)

        val inputs = ScreeningInputs(
            gestationalAgeWeeks = 38,
            ageHours = 48.0,
            dangerSigns = false,
            jaundiceOnsetUnder24h = false,
            priorPhototherapy = false,
            guardianConsentConfirmed = true
        )
        sm.dispatch(ScreeningEvent.SubmitInputs(inputs))
        sm.dispatch(ScreeningEvent.EvaluateRules(triageEngine, testVersions))

        val state = sm.dispatch(ScreeningEvent.CardVerified(VerificationResult.BadSignature))
        assertThat(state).isInstanceOf(ScreeningState.Blocked::class.java)
        val blocked = state as ScreeningState.Blocked
        assertThat(blocked.reason).isEqualTo(CardBlockReason.UNVERIFIED_SIGNATURE)
    }

    @Test
    fun testCardVerificationSuccess_transitionsToCapture() {
        val sm = ScreeningStateMachine(integrityChecker = { true })
        sm.dispatch(ScreeningEvent.StartScreening)

        val inputs = ScreeningInputs(
            gestationalAgeWeeks = 38,
            ageHours = 48.0,
            dangerSigns = false,
            jaundiceOnsetUnder24h = false,
            priorPhototherapy = false,
            guardianConsentConfirmed = true
        )
        sm.dispatch(ScreeningEvent.SubmitInputs(inputs))
        sm.dispatch(ScreeningEvent.EvaluateRules(triageEngine, testVersions))

        val state = sm.dispatch(ScreeningEvent.CardVerified(VerificationResult.Verified(verifiedCardBatch)))
        assertThat(state).isInstanceOf(ScreeningState.Capture::class.java)
        val capture = state as ScreeningState.Capture
        assertThat(capture.attempt).isEqualTo(1)
        assertThat(capture.previousFailure).isNull()
    }

    @Test
    fun testQualityFailureAttempts_loopBackAndFallbackOnThree() {
        val sm = ScreeningStateMachine(integrityChecker = { true })
        sm.dispatch(ScreeningEvent.StartScreening)

        val inputs = ScreeningInputs(
            gestationalAgeWeeks = 38,
            ageHours = 48.0,
            dangerSigns = false,
            jaundiceOnsetUnder24h = false,
            priorPhototherapy = false,
            guardianConsentConfirmed = true
        )
        sm.dispatch(ScreeningEvent.SubmitInputs(inputs))
        sm.dispatch(ScreeningEvent.EvaluateRules(triageEngine, testVersions))
        sm.dispatch(ScreeningEvent.CardVerified(VerificationResult.Verified(verifiedCardBatch)))

        val dummyFrame = com.kitalonlabs.neogold.engine.vision.ImageFrame(10, 10, IntArray(100))
        val blurFailQuality = QualityGateResult(
            passed = false,
            failureReasons = listOf(QualityReasonCode.BLUR),
            metrics = QualityMetrics(4, 5.0, 0.5, 30.0, 0.0, 128.0, 0.5, 1.0, null),
            canonicalImage = null,
            sampledPatches = null,
            windowRgb = null,
            windowLab = null
        )

        // Attempt 1 fails -> loops to Capture attempt 2
        val state1 = sm.dispatch(ScreeningEvent.FrameCaptured(dummyFrame, null, blurFailQuality))
        assertThat(state1).isInstanceOf(ScreeningState.Capture::class.java)
        val capture2 = state1 as ScreeningState.Capture
        assertThat(capture2.attempt).isEqualTo(2)
        assertThat(capture2.previousFailure).isEqualTo(QualityReasonCode.BLUR)

        // Attempt 2 fails -> loops to Capture attempt 3
        val glareFailQuality = blurFailQuality.copy(failureReasons = listOf(QualityReasonCode.GLARE))
        val state2 = sm.dispatch(ScreeningEvent.FrameCaptured(dummyFrame, null, glareFailQuality))
        assertThat(state2).isInstanceOf(ScreeningState.Capture::class.java)
        val capture3 = state2 as ScreeningState.Capture
        assertThat(capture3.attempt).isEqualTo(3)
        assertThat(capture3.previousFailure).isEqualTo(QualityReasonCode.GLARE)

        // Attempt 3 fails -> transitions to BloodTestFallback (TC-CAP-14, TC-TRI-15)
        val state3 = sm.dispatch(ScreeningEvent.FrameCaptured(dummyFrame, null, blurFailQuality))
        assertThat(state3).isInstanceOf(ScreeningState.BloodTestFallback::class.java)
        val fallback = state3 as ScreeningState.BloodTestFallback
        val action = fallback.triageResult as TriageResult.Action
        assertThat(action.action).isEqualTo(TriageAction.BLOOD_TEST_TODAY)
        assertThat(action.reasonCode).isEqualTo(TriageReasonCode.QUALITY_FALLBACK)
    }

    @Test
    fun testSaveSession_recordsSummary() {
        val sm = ScreeningStateMachine(integrityChecker = { true })
        sm.dispatch(ScreeningEvent.StartScreening)

        val inputs = ScreeningInputs(
            gestationalAgeWeeks = 38,
            ageHours = 48.0,
            dangerSigns = true,
            jaundiceOnsetUnder24h = false,
            priorPhototherapy = false,
            guardianConsentConfirmed = true
        )
        sm.dispatch(ScreeningEvent.SubmitInputs(inputs))
        sm.dispatch(ScreeningEvent.EvaluateRules(triageEngine, testVersions))

        val savedState = sm.dispatch(ScreeningEvent.SaveSession("SES-12345"))
        assertThat(savedState).isInstanceOf(ScreeningState.Saved::class.java)
        val saved = savedState as ScreeningState.Saved
        assertThat(saved.summary.sessionId).isEqualTo("SES-12345")
        assertThat(saved.summary.versions.cardBatchId).isEqualTo("BATCH-2026-001")
    }

    @Test
    fun testAssetIntegrityCompromised_startScreeningBlocked_TC_SEC_09() {
        val sm = ScreeningStateMachine(
            integrityChecker = { false }
        )
        val state = sm.dispatch(ScreeningEvent.StartScreening)
        assertThat(state).isInstanceOf(ScreeningState.Blocked::class.java)
        val blocked = state as ScreeningState.Blocked
        assertThat(blocked.reason).isEqualTo(CardBlockReason.ASSET_INTEGRITY_COMPROMISED)
    }

    @Test
    fun testAssetIntegrityCompromised_submitInputsBlocked_TC_SEC_09() {
        val sm = ScreeningStateMachine(
            integrityChecker = { false }
        )
        val inputs = ScreeningInputs(
            gestationalAgeWeeks = 38,
            ageHours = 48.0,
            dangerSigns = false,
            jaundiceOnsetUnder24h = false,
            priorPhototherapy = false,
            guardianConsentConfirmed = true
        )
        val state = sm.dispatch(ScreeningEvent.SubmitInputs(inputs))
        assertThat(state).isInstanceOf(ScreeningState.Blocked::class.java)
        val blocked = state as ScreeningState.Blocked
        assertThat(blocked.reason).isEqualTo(CardBlockReason.ASSET_INTEGRITY_COMPROMISED)
    }
}
