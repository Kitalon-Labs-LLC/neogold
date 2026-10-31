package com.kitalonlabs.neogold.feature.screening.domain

import com.google.common.truth.Truth.assertThat
import com.kitalonlabs.neogold.engine.cardtrust.CardBatchPayload
import com.kitalonlabs.neogold.engine.cardtrust.PatchReference
import com.kitalonlabs.neogold.engine.cardtrust.VerificationResult
import com.kitalonlabs.neogold.engine.estimation.BodySite
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
 * The optional forehead photo (FR-15, NG-040), TC-CAP-16: "weighted multi-site estimate; skipping forehead still gives a
 * result". Run on synthetic cards through [ScreeningFlow] and the real pipeline. The forehead photo can only add to a result
 * from the chest photo; a refused, slow or failed one never makes that result worse (BUG-2026-090).
 */
class ScreeningForeheadTest {

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

    private val pipeline get() = ScreeningPipelineUseCase(integrityChecker = { true }, visionEngine = VisionEngine())

    private fun flow(
        engine: EstimationEngine = estimationEngine,
        timeoutMs: Long = ScreeningFlow.ANALYSIS_TIMEOUT_MS,
        run: ((ScreeningFlow.Analysis) -> PipelineExecutionResult)? = null,
        runForehead: ((ScreeningFlow.ForeheadAnalysis) -> PipelineExecutionResult)? = null
    ): ScreeningFlow {
        val real = pipeline
        val defaultRun: (ScreeningFlow.Analysis) -> PipelineExecutionResult = { a ->
            real.execute(
                a.frame,
                a.cardBatch,
                a.inputs,
                a.attempt,
                a.estimationEngine,
                a.triageEngine,
                a.versions,
                a.metadata
            )
        }
        val defaultForehead: (ScreeningFlow.ForeheadAnalysis) -> PipelineExecutionResult = { a ->
            real.addForehead(a.sternum, a.frame, a.attempt, a.inputs, a.estimationEngine, a.triageEngine)
        }
        return ScreeningFlow(
            { true },
            triageEngine,
            engine,
            baseVersions,
            real,
            Dispatchers.Default,
            timeoutMs,
            runPipeline = run ?: defaultRun,
            runForehead = runForehead ?: defaultForehead
        )
    }

    /** A screening that has a result from the chest photo. */
    private fun withChestResult(f: ScreeningFlow): ScreeningState.Result = runBlocking {
        f.begin(guardianConsentConfirmed = true)
        f.answerAgeAndGestation(48.0, 38)
        f.answerEarlyJaundice(false)
        f.answerDangerSigns(false)
        f.answerPriorPhototherapy(false)
        f.decide()
        f.cardChecked(VerificationResult.Verified(batch))
        f.analyze(chestFrame, null) as ScreeningState.Result
    }

    private val chestFrame: ImageFrame
        get() = SyntheticCardRenderer().renderFrame(
            SyntheticRenderConfig(windowContent = SyntheticWindowContent.SKIN_JAUNDICE)
        )

    // the same kind of skin as the chest photo: the two-site decision is as careful as the chest one, so it is used
    private val foreheadFrame: ImageFrame
        get() = SyntheticCardRenderer().renderFrame(
            SyntheticRenderConfig(windowContent = SyntheticWindowContent.SKIN_JAUNDICE)
        )

    private val noCard = ImageFrame(64, 64, IntArray(64 * 64) { 0xFF808080.toInt() })

    private fun sitesOf(state: ScreeningState) =
        (state as ScreeningState.Result).intermediateData.estimationOutput.siteEstimates.keys

    @Test
    fun aGoodChestPhotoOffersTheForeheadPhotoBeforeAnyResultIsShown() {
        val f = flow()
        val result = withChestResult(f)

        assertThat(sitesOf(result)).containsExactly(BodySite.STERNUM)
        assertThat(f.shouldOfferForehead).isTrue()
        assertThat(f.canAddForehead).isTrue()
    }

    @Test
    fun skippingTheForeheadPhotoStillGivesTheResultFromTheChestPhoto() {
        val f = flow()
        val chest = withChestResult(f)
        val before = chest.intermediateData.estimationOutput

        f.skipForehead()

        assertThat(f.shouldOfferForehead).isFalse()
        assertThat(f.currentState).isSameInstanceAs(chest)
        val row = f.record("s", 1L, null, null)!!
        assertThat(row.estimateMgDl).isEqualTo(before.estimate)
        assertThat(row.estimateLowMgDl).isEqualTo(before.lowerBound)
        assertThat(row.estimateHighMgDl).isEqualTo(before.upperBound)
        assertThat(f.decidedAtMs).isGreaterThan(0L)
    }

    @Test
    fun aGoodForeheadPhotoGivesTheWeightedEstimateFromBothSites() = runBlocking<Unit> {
        val f = flow()
        val chest = withChestResult(f)
        val model = estimationEngine.model

        assertThat(f.startForeheadPhoto()).isTrue()
        assertThat(f.awaitingForehead).isTrue()
        val outcome = f.addForehead(foreheadFrame)

        assertThat(outcome).isEqualTo(ScreeningFlow.ForeheadOutcome.Added)
        assertThat(f.awaitingForehead).isFalse()
        val both = f.currentState as ScreeningState.Result
        val sites = both.intermediateData.estimationOutput.siteEstimates
        assertThat(sites.keys).containsExactly(BodySite.STERNUM, BodySite.FOREHEAD)
        // the estimate is the weighted mean of the two sites, the interval half-width the weighted mean of their quantiles
        val wS = model.multiSiteWeights.getValue(BodySite.STERNUM)
        val wF = model.multiSiteWeights.getValue(BodySite.FOREHEAD)
        val expected = (wS * sites.getValue(BodySite.STERNUM).estimate + wF * sites.getValue(BodySite.FOREHEAD).estimate) / (wS + wF)
        val halfWidth = (
            wS * model.sites.getValue(BodySite.STERNUM).residualQuantile90 +
                wF * model.sites.getValue(BodySite.FOREHEAD).residualQuantile90
            ) / (wS + wF)
        val output = both.intermediateData.estimationOutput
        assertThat(output.estimate).isWithin(1e-9).of(expected)
        assertThat(output.upperBound - output.estimate).isWithin(1e-9).of(halfWidth)
        // the chest site is what the chest photo gave, the forehead site is a second reading
        assertThat(sites.getValue(BodySite.STERNUM).estimate)
            .isEqualTo(chest.intermediateData.singleSiteEstimate.estimate)
        // the engine decided again, and the saved row is the two-site result
        assertThat(both.triageResult).isNotNull()
        assertThat(f.shouldOfferForehead).isFalse()
        val row = f.record("s", 1L, null, null)!!
        assertThat(row.estimateMgDl).isEqualTo(output.estimate)
        assertThat(row.estimateLowMgDl).isEqualTo(output.lowerBound)
        assertThat(row.estimateHighMgDl).isEqualTo(output.upperBound)
        assertThat(row.cardBatchId).isEqualTo("BATCH-2026-TEST")
    }

    @Test
    fun aRefusedForeheadPhotoLeavesTheChestResultAsItWas_andAnotherTryIsAllowed() = runBlocking<Unit> {
        val f = flow()
        val chest = withChestResult(f)
        f.startForeheadPhoto()

        val outcome = f.addForehead(noCard)

        assertThat(outcome).isEqualTo(ScreeningFlow.ForeheadOutcome.PhotoProblem(QualityReasonCode.NO_CARD))
        assertThat(f.foreheadProblem).isEqualTo(QualityReasonCode.NO_CARD)
        assertThat(f.foreheadAttempts).isEqualTo(1)
        // the result from the chest photo is the same object: nothing was turned into a blood test
        assertThat(f.currentState).isSameInstanceAs(chest)
        assertThat(f.shouldOfferForehead).isTrue()
        assertThat(f.canAddForehead).isTrue()
    }

    @Test
    fun afterThreeForeheadPhotosThatCannotBeUsedOnlySkippingIsLeft() = runBlocking<Unit> {
        val f = flow()
        val chest = withChestResult(f)
        repeat(ScreeningFlow.MAX_FOREHEAD_ATTEMPTS) {
            assertThat(f.startForeheadPhoto()).isTrue()
            f.addForehead(noCard)
        }

        assertThat(f.canAddForehead).isFalse()
        assertThat(f.startForeheadPhoto()).isFalse()
        assertThat(f.addForehead(foreheadFrame)).isEqualTo(ScreeningFlow.ForeheadOutcome.NotUsed)
        assertThat(f.foreheadAttempts).isEqualTo(ScreeningFlow.MAX_FOREHEAD_ATTEMPTS)
        assertThat(f.currentState).isSameInstanceAs(chest)
        f.skipForehead()
        assertThat(
            f.record("s", 1L, null, null)!!.estimateMgDl
        ).isEqualTo(chest.intermediateData.estimationOutput.estimate)
    }

    @Test
    fun aForeheadAnalysisThatIsTooSlowOrFailsIsNotUsed_andTheChestResultStands() = runBlocking<Unit> {
        // the chest photo is analysed for real once, outside the flow, so that only the forehead run is slow
        val first = flow()
        val chest = withChestResult(first).intermediateData
        val slow = flow(
            timeoutMs = 200,
            run = { PipelineExecutionResult.Success(chest) },
            runForehead = {
                Thread.sleep(3_000)
                PipelineExecutionResult.Error(IllegalStateException("never reached"))
            }
        )
        slow.begin(guardianConsentConfirmed = true)
        slow.answerAgeAndGestation(48.0, 38)
        slow.answerEarlyJaundice(false)
        slow.answerDangerSigns(false)
        slow.answerPriorPhototherapy(false)
        slow.decide()
        slow.cardChecked(VerificationResult.Verified(batch))
        val before = slow.analyze(chestFrame, null)
        slow.startForeheadPhoto()

        val started = System.nanoTime()
        val outcome = slow.addForehead(foreheadFrame)
        val elapsedMs = (System.nanoTime() - started) / 1_000_000

        assertThat(outcome).isEqualTo(ScreeningFlow.ForeheadOutcome.NotUsed)
        assertThat(elapsedMs).isLessThan(1_500L)
        assertThat(slow.currentState).isSameInstanceAs(before)
    }

    @Test
    fun aModelWithNoForeheadSiteNeverOffersTheForeheadPhoto() {
        val model = estimationEngine.model
        val sternumOnly = EstimationEngine(
            model.copy(
                sites = model.sites - BodySite.FOREHEAD,
                multiSiteWeights = model.multiSiteWeights - BodySite.FOREHEAD
            )
        )
        val f = flow(engine = sternumOnly)

        val result = withChestResult(f)

        assertThat(sitesOf(result)).containsExactly(BodySite.STERNUM)
        assertThat(f.shouldOfferForehead).isFalse()
        assertThat(f.canAddForehead).isFalse()
        assertThat(f.startForeheadPhoto()).isFalse()
    }

    @Test
    fun noForeheadPhotoIsOfferedOnceTheRowIsSaved_orToAScreeningWithNoResult() {
        val saved = flow()
        withChestResult(saved)
        saved.skipForehead()
        saved.record("s", 1L, null, null)
        assertThat(saved.shouldOfferForehead).isFalse()

        val fresh = flow()
        fresh.begin(guardianConsentConfirmed = true)
        assertThat(fresh.shouldOfferForehead).isFalse()
        assertThat(fresh.startForeheadPhoto()).isFalse()
    }

    @Test
    fun aNewScreeningStartsWithNoForeheadTriesCarriedOver() = runBlocking<Unit> {
        val f = flow()
        withChestResult(f)
        f.startForeheadPhoto()
        f.addForehead(noCard)
        assertThat(f.foreheadAttempts).isEqualTo(1)

        f.begin(guardianConsentConfirmed = true)

        assertThat(f.foreheadAttempts).isEqualTo(0)
        assertThat(f.foreheadProblem).isNull()
        assertThat(f.awaitingForehead).isFalse()
    }

    @Test
    fun theStateMachineTakesAForeheadResultOnlyWhereThereIsAResult() {
        val data = withChestResult(flow()).intermediateData
        val machine = ScreeningStateMachine({ true })

        // no result yet: nothing changes
        assertThat(machine.dispatch(ScreeningEvent.ForeheadAdded(data))).isEqualTo(ScreeningState.Initial)
    }

    // ---- safety review of the forehead photo: it may only raise the level of care -------------------------------------

    private fun action(a: TriageAction, hours: Int? = null) =
        TriageResult.Action(a, TriageReasonCode.R5, 12.0, null, "cfg", hours)

    @Test
    fun aLessCarefulDecisionIsNeverAsCarefulAsAMoreUrgentOne() {
        val urgent = action(TriageAction.BLOOD_TEST_TODAY)
        val relaxed = action(TriageAction.RECHECK, 24)

        assertThat(isAtLeastAsCareful(urgent, relaxed)).isTrue()
        assertThat(isAtLeastAsCareful(relaxed, urgent)).isFalse()
        assertThat(isAtLeastAsCareful(action(TriageAction.NO_FURTHER_ACTION_FROM_SCREEN), relaxed)).isFalse()
        assertThat(isAtLeastAsCareful(action(TriageAction.REFER_URGENT), urgent)).isTrue()
        assertThat(isAtLeastAsCareful(urgent, urgent)).isTrue()
    }

    @Test
    fun aLaterRecheckIsLessCarefulThanASoonerOne_andNoHoursCountsAsTheMostUrgent() {
        assertThat(isAtLeastAsCareful(action(TriageAction.RECHECK, 12), action(TriageAction.RECHECK, 24))).isTrue()
        assertThat(isAtLeastAsCareful(action(TriageAction.RECHECK, 24), action(TriageAction.RECHECK, 12))).isFalse()
        assertThat(isAtLeastAsCareful(action(TriageAction.RECHECK, null), action(TriageAction.RECHECK, 12))).isTrue()
        assertThat(isAtLeastAsCareful(action(TriageAction.RECHECK, 12), action(TriageAction.RECHECK, null))).isFalse()
    }

    @Test
    fun aForeheadPhotoThatWouldLowerTheLevelOfCareLeavesTheChestResultInPlace() {
        val chest = withChestResult(flow()).intermediateData
        // a chest result that asked for the most urgent action: no two-site decision can be more careful than that
        val urgentChest = chest.copy(triageResult = action(TriageAction.REFER_URGENT))
        val inputs = ScreeningInputs(38, 48.0, false, false, false, true)

        val result = pipeline.addForehead(urgentChest, foreheadFrame, 1, inputs, estimationEngine, triageEngine)

        val kept = (result as PipelineExecutionResult.Success).intermediateData
        assertThat(kept.triageResult).isEqualTo(action(TriageAction.REFER_URGENT))
        assertThat(kept.estimationOutput.siteEstimates.keys).containsExactly(BodySite.STERNUM)
    }

    @Test
    fun aForeheadPhotoThatKeepsOrRaisesTheLevelOfCareIsUsed() {
        val chest = withChestResult(flow()).intermediateData
        // a chest result that asked for the least: whatever the two sites decide is at least as careful
        val relaxedChest = chest.copy(triageResult = action(TriageAction.NO_FURTHER_ACTION_FROM_SCREEN))
        val inputs = ScreeningInputs(38, 48.0, false, false, false, true)

        val result = pipeline.addForehead(relaxedChest, foreheadFrame, 1, inputs, estimationEngine, triageEngine)

        val used = (result as PipelineExecutionResult.Success).intermediateData
        assertThat(used.estimationOutput.siteEstimates.keys).containsExactly(BodySite.STERNUM, BodySite.FOREHEAD)
    }

    @Test
    fun nothingIsSavedWhileTheWorkerHasStillToChooseAboutTheForeheadPhoto() {
        val f = flow()
        withChestResult(f)

        assertThat(f.record("s", 1L, null, null)).isNull()

        f.skipForehead()
        assertThat(f.record("s", 2L, null, null)).isNotNull()
    }

    @Test
    fun skippingTwiceOrAfterTheChoiceIsMadeMovesNothing() = runBlocking<Unit> {
        val f = flow()
        withChestResult(f)
        f.skipForehead()
        val decidedAt = f.decidedAtMs

        Thread.sleep(5)
        f.skipForehead()

        assertThat(f.decidedAtMs).isEqualTo(decidedAt)
        assertThat(f.startForeheadPhoto()).isFalse()
    }

    @Test
    fun aGoodForeheadPhotoReplacesTheResultOnlyInAResultState() {
        val data = withChestResult(flow()).intermediateData
        val replaced = data.copy(triageResult = action(TriageAction.REFER_URGENT))
        val machine = ScreeningStateMachine({ true })
        machine.dispatch(ScreeningEvent.StartScreening)

        // still collecting answers: ignored
        assertThat(
            machine.dispatch(ScreeningEvent.ForeheadAdded(replaced))
        ).isInstanceOf(ScreeningState.Inputs::class.java)
    }
}
