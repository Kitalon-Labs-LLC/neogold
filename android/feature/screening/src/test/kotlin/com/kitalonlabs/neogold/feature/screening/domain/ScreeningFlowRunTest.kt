package com.kitalonlabs.neogold.feature.screening.domain

import com.google.common.truth.Truth.assertThat
import com.kitalonlabs.neogold.engine.cardtrust.CardBatchPayload
import com.kitalonlabs.neogold.engine.cardtrust.PatchReference
import com.kitalonlabs.neogold.engine.cardtrust.VerificationResult
import com.kitalonlabs.neogold.engine.estimation.EstimationEngine
import com.kitalonlabs.neogold.engine.triage.TriageAction
import com.kitalonlabs.neogold.engine.triage.TriageConfigParser
import com.kitalonlabs.neogold.engine.triage.TriageEngine
import com.kitalonlabs.neogold.engine.triage.TriageReasonCode
import com.kitalonlabs.neogold.engine.triage.TriageResult
import com.kitalonlabs.neogold.engine.vision.CardGeometry
import com.kitalonlabs.neogold.engine.vision.ImageFrame
import com.kitalonlabs.neogold.engine.vision.QualityReasonCode
import com.kitalonlabs.neogold.engine.vision.SyntheticCardRenderer
import com.kitalonlabs.neogold.engine.vision.SyntheticRenderConfig
import com.kitalonlabs.neogold.engine.vision.SyntheticWindowContent
import com.kitalonlabs.neogold.engine.vision.VisionEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Test
import java.io.File
import java.time.LocalDate

/**
 * A whole screening through [ScreeningFlow] on a synthetic card (package E1): the answers, the engine's decision before the
 * camera, the photo, the retakes, the 10-second limit and the saved row. TC-CAP-13, TC-CAP-14, TC-TRI-15, TC-TRI-22,
 * TC-TRI-26, TC-EST-04.
 */
class ScreeningFlowRunTest {

    private lateinit var triageEngine: TriageEngine
    private lateinit var estimationEngine: EstimationEngine
    private lateinit var batch: CardBatchPayload

    private val baseVersions = VersionsRecord(
        appVersion = "1.0.0",
        modelVersion = "test-model",
        configVersion = "test-config",
        cardBatchId = NO_CARD_SCANNED,
        deviceModel = "Pixel 8",
        deviceManufacturer = "Google",
        osVersion = "Android 15"
    )

    private val repoRoot: File
        get() {
            var dir = File(".").canonicalFile
            while (dir.parentFile != null) {
                if (File(dir, "testdata").isDirectory && (File(dir, ".git").isDirectory || File(dir, "docs").isDirectory)) {
                    return dir
                }
                dir = dir.parentFile!!
            }
            error("repository root not found")
        }

    @Before
    fun setUp() {
        estimationEngine = EstimationEngine.fromJson(File(repoRoot, "testdata/models/model.synthetic.json").readText())
        val config = TriageConfigParser.parse(File(repoRoot, "testdata/triage/config.synthetic.json").readText())
        triageEngine = TriageEngine(config, clock = { LocalDate.of(2026, 10, 17) })
        val refs = CardGeometry.PATCHES.values.mapIndexed { i, p ->
            PatchReference(i, p.nominalLab.l, p.nominalLab.a, p.nominalLab.b)
        }
        batch = CardBatchPayload(
            formatVersion = 1,
            issuedAtDays = 1000,
            expiresAtDays = 3000,
            issuedAt = LocalDate.of(2026, 9, 1),
            expiresAt = LocalDate.of(2027, 9, 1),
            issuerKeyId = "neogold-issuer-root",
            batchId = "BATCH-2026-TEST",
            patchCount = 23,
            patches = refs,
            signedBytes = ByteArray(100),
            signature = ByteArray(64)
        )
    }

    private fun flow(
        engine: TriageEngine? = triageEngine,
        timeoutMs: Long = ScreeningFlow.ANALYSIS_TIMEOUT_MS,
        run: ((ScreeningFlow.Analysis) -> PipelineExecutionResult)? = null
    ): ScreeningFlow {
        val pipeline = ScreeningPipelineUseCase(integrityChecker = { true }, visionEngine = VisionEngine())
        return if (run == null) {
            ScreeningFlow(
                { true },
                engine,
                estimationEngine,
                baseVersions,
                pipeline,
                Dispatchers.Default,
                timeoutMs
            )
        } else {
            ScreeningFlow(
                { true },
                engine,
                estimationEngine,
                baseVersions,
                pipeline,
                Dispatchers.Default,
                timeoutMs,
                run
            )
        }
    }

    private fun answerAllWell(f: ScreeningFlow, ageHours: Double = 48.0, weeks: Int = 38) {
        f.begin(guardianConsentConfirmed = true)
        f.answerAgeAndGestation(ageHours, weeks)
        f.answerEarlyJaundice(false)
        f.answerDangerSigns(false)
        f.answerPriorPhototherapy(false)
    }

    private val goodFrame: ImageFrame
        get() = SyntheticCardRenderer().renderFrame(
            SyntheticRenderConfig(windowContent = SyntheticWindowContent.SKIN_JAUNDICE)
        )

    @Test
    fun nothingIsDecidedUntilEveryAnswerIsGiven() {
        val f = flow()
        f.begin(guardianConsentConfirmed = true)
        f.answerAgeAndGestation(48.0, 38)
        f.answerEarlyJaundice(false)
        f.answerDangerSigns(false)
        // the phototherapy answer is missing: no default is filled in

        assertThat(f.isComplete).isFalse()
        assertThat(f.decide()).isInstanceOf(ScreeningState.Inputs::class.java)

        f.answerPriorPhototherapy(false)
        assertThat(f.isComplete).isTrue()
        assertThat(f.decide()).isInstanceOf(ScreeningState.CardCheck::class.java)
    }

    @Test
    fun withoutGuardianConsentTheCameraNeverOpens() {
        val f = flow()
        f.begin(guardianConsentConfirmed = false)
        f.answerAgeAndGestation(48.0, 38)
        f.answerEarlyJaundice(false)
        f.answerDangerSigns(false)
        f.answerPriorPhototherapy(false)

        assertThat(f.decide()).isInstanceOf(ScreeningState.Inputs::class.java)
    }

    @Test
    fun aDangerSignSendsTheWorkerToAReferralBeforeAnyCard_andTheRowSaysSo() {
        val f = flow()
        f.begin(guardianConsentConfirmed = true)
        f.answerAgeAndGestation(30.0, 39)
        f.answerEarlyJaundice(false)
        f.answerDangerSigns(true)
        f.answerPriorPhototherapy(false)

        val state = f.decide() as ScreeningState.UrgentReferral
        val row = f.record("s", 1L, null, null, captureAttempts = 0)!!

        assertThat((state.triageResult as TriageResult.Action).reasonCode).isEqualTo(TriageReasonCode.R1)
        assertThat(row.triageAction).isEqualTo("REFER_URGENT")
        assertThat(row.flagDangerSigns).isEqualTo(1)
        assertThat(row.cardBatchId).isNull()
    }

    @Test
    fun aGoodPhotoGivesAResultWithTheScannedCardAndTheModelInTheRow() = runBlocking<Unit> {
        val f = flow()
        answerAllWell(f)
        f.decide()
        f.cardChecked(VerificationResult.Verified(batch))
        assertThat(f.attempt).isEqualTo(1)

        val state = f.analyze(goodFrame, null)

        val result = state as ScreeningState.Result
        val row = f.record("s", 1L, "12/345", "profile-1", captureAttempts = 1)!!
        assertThat(result.versions.cardBatchId).isEqualTo("BATCH-2026-TEST")
        assertThat(row.cardBatchId).isEqualTo("BATCH-2026-TEST")
        assertThat(row.modelVersion).isEqualTo("test-model")
        assertThat(row.estimateMgDl).isNotNull()
        assertThat(row.estimateLowMgDl!!).isAtMost(row.estimateMgDl!!)
        assertThat(row.estimateHighMgDl!!).isAtLeast(row.estimateMgDl!!)
        assertThat(row.thresholdMgDl).isNotNull()
        // the row is exactly what the card on screen is made from
        val shown = result.triageResult.toPresentation()
        assertThat(row.triageAction).isEqualTo(shown.storedAction)
        assertThat(row.registerNumber).isEqualTo("12/345")
    }

    @Test
    fun threeBlurryPhotosSendTheWorkerToABloodTest() = runBlocking<Unit> {
        val f = flow()
        answerAllWell(f)
        f.decide()
        f.cardChecked(VerificationResult.Verified(batch))
        val blank = ImageFrame(64, 64, IntArray(64 * 64) { 0xFF808080.toInt() }) // no card in view

        val first = f.analyze(blank, null)
        assertThat((first as ScreeningState.Capture).attempt).isEqualTo(2)
        assertThat(first.previousFailure).isEqualTo(QualityReasonCode.NO_CARD)
        val second = f.analyze(blank, null)
        assertThat((second as ScreeningState.Capture).attempt).isEqualTo(3)
        val third = f.analyze(blank, null)

        val fallback = third as ScreeningState.BloodTestFallback
        val action = fallback.triageResult as TriageResult.Action
        assertThat(action.action).isEqualTo(TriageAction.BLOOD_TEST_TODAY)
        assertThat(action.reasonCode).isEqualTo(TriageReasonCode.QUALITY_FALLBACK)
        assertThat(f.record("s", 1L, null, null, captureAttempts = 3)!!.estimateMgDl).isNull()
    }

    @Test
    fun aGoodPhotoAfterARetakeStillGivesAResult() = runBlocking<Unit> {
        val f = flow()
        answerAllWell(f)
        f.decide()
        f.cardChecked(VerificationResult.Verified(batch))
        f.analyze(ImageFrame(64, 64, IntArray(64 * 64) { 0xFF808080.toInt() }), null)

        val state = f.analyze(goodFrame, null)

        assertThat(state).isInstanceOf(ScreeningState.Result::class.java)
    }

    @Test
    fun anAnalysisThatTakesTooLongIsABloodTest_notAWait() = runBlocking<Unit> {
        val f = flow(timeoutMs = 200) { _ ->
            Thread.sleep(2_000)
            PipelineExecutionResult.Error(IllegalStateException("never reached"))
        }
        answerAllWell(f)
        f.decide()
        f.cardChecked(VerificationResult.Verified(batch))

        val state = f.analyze(goodFrame, null)

        val fallback = state as ScreeningState.BloodTestFallback
        assertThat(
            (fallback.triageResult as TriageResult.Action).reasonCode
        ).isEqualTo(TriageReasonCode.QUALITY_FALLBACK)
    }

    @Test
    fun anAnalysisThatFailsIsABloodTest_notACrash() = runBlocking<Unit> {
        val f = flow { _ -> PipelineExecutionResult.Error(IllegalStateException("model could not run")) }
        answerAllWell(f)
        f.decide()
        f.cardChecked(VerificationResult.Verified(batch))

        val state = f.analyze(goodFrame, null)

        assertThat(state).isInstanceOf(ScreeningState.BloodTestFallback::class.java)
    }

    @Test
    fun aThrowingAnalysisIsABloodTest_notACrash() = runBlocking<Unit> {
        val f = flow { _ -> throw IllegalStateException("boom") }
        answerAllWell(f)
        f.decide()
        f.cardChecked(VerificationResult.Verified(batch))

        assertThat(f.analyze(goodFrame, null)).isInstanceOf(ScreeningState.BloodTestFallback::class.java)
    }

    @Test
    fun withNoTriageEngineNothingStartsEvenWithAllAnswers() {
        val f = flow(engine = null)
        answerAllWell(f)

        assertThat(f.decide()).isInstanceOf(ScreeningState.Inputs::class.java)
        runBlocking { assertThat(f.analyze(goodFrame, null)).isInstanceOf(ScreeningState.Inputs::class.java) }
    }

    @Test
    fun aCardThatDoesNotVerifyBlocksTheScreening() {
        val f = flow()
        answerAllWell(f)
        f.decide()

        val state = f.cardChecked(VerificationResult.Expired)

        assertThat((state as ScreeningState.Blocked).reason).isEqualTo(CardBlockReason.BATCH_EXPIRED)
        assertThat(f.record("s", 1L, null, null, 0)).isNull()
    }
}
