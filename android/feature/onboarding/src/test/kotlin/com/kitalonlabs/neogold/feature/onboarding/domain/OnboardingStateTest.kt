package com.kitalonlabs.neogold.feature.onboarding.domain

import android.hardware.camera2.CameraCharacteristics
import com.google.common.truth.Truth.assertThat
import com.kitalonlabs.neogold.core.data.local.dao.AuditEventDao
import com.kitalonlabs.neogold.core.data.local.dao.CardBatchDao
import com.kitalonlabs.neogold.core.data.local.entity.AuditEventEntity
import com.kitalonlabs.neogold.core.data.local.entity.CardBatchEntity
import com.kitalonlabs.neogold.engine.camera.CapturedFrame
import com.kitalonlabs.neogold.engine.camera.device.DeviceCapabilities
import com.kitalonlabs.neogold.engine.camera.host.CameraSession
import com.kitalonlabs.neogold.engine.camera.model.BurstCaptureResult
import com.kitalonlabs.neogold.engine.camera.model.CameraHardwareLevel
import com.kitalonlabs.neogold.engine.camera.model.CaptureMetadata
import com.kitalonlabs.neogold.engine.camera.qualification.CameraQualifier
import com.kitalonlabs.neogold.engine.camera.qualification.QualificationStatus
import com.kitalonlabs.neogold.engine.cardtrust.CardTrustVerifier
import com.kitalonlabs.neogold.engine.cardtrust.TrustListParser
import com.kitalonlabs.neogold.engine.vision.CardGeometry
import com.kitalonlabs.neogold.engine.vision.ImageFrame
import com.kitalonlabs.neogold.engine.vision.QualityReasonCode
import com.kitalonlabs.neogold.engine.vision.SyntheticCardRenderer
import com.kitalonlabs.neogold.engine.vision.SyntheticRenderConfig
import com.kitalonlabs.neogold.engine.vision.VisionEngine
import com.kitalonlabs.neogold.feature.onboarding.FakeNeoGoldDataStore
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.io.File
import java.time.LocalDate

/**
 * What S06 and S07 show as things happen: the card is checked once however often the camera reads it, a bad photo is
 * taken again and does not count, and the verdict comes only from three good photos (TC-CARD-02, TC-DEV-02 to
 * TC-DEV-05, NG-007).
 */
class OnboardingStateTest {

    private val batches = FakeCardBatchDao()
    private val profiles = FakeDeviceProfileDao()
    private val audit = FakeAuditEventDao()
    private val dataStore = FakeNeoGoldDataStore()

    private fun repoFile(relative: String): File =
        listOf(relative, "../$relative", "../../$relative", "../../../$relative").map(::File).first { it.exists() }

    private fun payload(vector: String): ByteArray {
        val text = repoFile("card/spec/testvectors/$vector").readText()
        val hex = Regex("\"payload_hex\"\\s*:\\s*\"([0-9a-f]+)\"").find(text)!!.groupValues[1]
        return hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    }

    private fun scanner(cardBatches: CardBatchDao = batches) = CardScanService(
        verifier = CardTrustVerifier(
            TrustListParser.parse(repoFile("card/spec/testvectors/test_trust_list.json").readText())
        ),
        cardBatches = cardBatches,
        audit = audit,
        clockMs = { 1_800_000_000_000L },
        today = { LocalDate.of(2026, 11, 1) }
    )

    // --- S06 ---

    @Test
    fun theCardIsCheckedOnceWhateverNumberOfTimesTheCameraReadsIt_TC_CARD_02() = runTest {
        val state = CardScanState(scanner(), this)

        repeat(6) { state.onPayload(payload("card_batch_invalid_tampered.json")) }
        advanceUntilIdle()

        assertThat(state.phase.value).isEqualTo(CardScanState.Phase.Rejected(CardRejection.NOT_TRUSTED))
        assertThat(audit.getEventsByType("CARD_REJECTED")).hasSize(1)
    }

    @Test
    fun aVerifiedCardStaysVerified_TC_CARD_01() = runTest {
        val state = CardScanState(scanner(), this)

        state.onPayload(payload("card_batch_valid.json"))
        advanceUntilIdle()
        state.onPayload(payload("card_batch_invalid_tampered.json")) // a later read does not undo it
        state.scanAgain()
        advanceUntilIdle()

        val phase = state.phase.value as CardScanState.Phase.Verified
        assertThat(phase.batchId).isEqualTo("NG-2026-001")
        assertThat(batches.rows).hasSize(1)
    }

    @Test
    fun afterARefusalTheUserCanScanAnotherCard() = runTest {
        val state = CardScanState(scanner(), this)
        state.onPayload(payload("card_batch_invalid_expired.json"))
        advanceUntilIdle()
        assertThat(state.phase.value).isEqualTo(CardScanState.Phase.Rejected(CardRejection.EXPIRED))

        state.scanAgain()
        assertThat(state.phase.value).isEqualTo(CardScanState.Phase.Scanning)
        state.onPayload(payload("card_batch_valid.json"))
        advanceUntilIdle()

        assertThat(state.phase.value).isInstanceOf(CardScanState.Phase.Verified::class.java)
    }

    @Test
    fun aStorageFailureIsNotBlamedOnTheCard() = runTest {
        val failing = object : CardBatchDao by FakeCardBatchDao() {
            override suspend fun getBatch(batchId: String): CardBatchEntity? = error("disk full")
        }
        val state = CardScanState(scanner(failing), this)

        state.onPayload(payload("card_batch_valid.json"))
        advanceUntilIdle()

        assertThat(state.phase.value).isEqualTo(CardScanState.Phase.Rejected(CardRejection.NOT_SAVED))
    }

    // --- S07 ---

    private val capabilities = DeviceCapabilities(
        manufacturer = "TECNO",
        deviceModel = "Spark 10",
        androidApi = 33,
        buildFingerprint = "TECNO/KI5k/KI5k:13/TP1A/230918:user/release-keys",
        ramMb = 4096,
        isLowRamDevice = false,
        cameraId = "0",
        cameraHardwareLevel = CameraHardwareLevel.FULL,
        supportsAeLock = true,
        supportsAwbLock = true,
        supportsManualSensor = true,
        supportsRaw = false
    )

    private val renderer = SyntheticCardRenderer()
    private val blank = ImageFrame(640, 480, IntArray(640 * 480) { 0xFF808080.toInt() })

    private class ScriptedSession(private val results: MutableList<() -> ImageFrame>) : CameraSession {
        override val characteristics: CameraCharacteristics? = null
        var taken = 0
        override suspend fun captureFrame(): CapturedFrame {
            taken++
            val frame = results.removeAt(0)()
            return CapturedFrame(
                frame,
                CaptureMetadata(null, null, false, false, false, false, null, null, null, CameraHardwareLevel.FULL)
            )
        }
        override suspend fun captureBurst(): BurstCaptureResult = error("not used in qualification")
        override fun close() = Unit
    }

    private fun service() = QualificationService(
        qualifier = CameraQualifier(),
        vision = VisionEngine(),
        cardBatches = batches,
        profiles = profiles,
        audit = audit,
        dataStore = dataStore,
        appVersion = "1.0.0",
        clockMs = { 1_800_000_000_000L }
    )

    private suspend fun storeNominalBatch(): String {
        val json = "[" + CardGeometry.PATCHES.values.joinToString(",") {
            "[${it.nominalLab.l},${it.nominalLab.a},${it.nominalLab.b}]"
        } + "]"
        batches.insert(
            CardBatchEntity(
                "NG-2026-001", "TEST-CARD-KEY-01", 1, json, 0L, Long.MAX_VALUE, 1,
                ByteArray(
                    1
                ),
                "NOT_SENT", 0L
            )
        )
        return "NG-2026-001"
    }

    private fun TestScope.stateFor(batchId: String) = QualificationState(
        service = service(),
        capabilities = capabilities,
        cardBatchId = batchId,
        scope = this,
        cpu = StandardTestDispatcher(testScheduler)
    )

    private fun good() = renderer.renderFrame(SyntheticRenderConfig())

    @Test
    fun threeGoodPhotosGiveAMeasuredVerdict_TC_DEV_02() = runTest {
        val state = stateFor(storeNominalBatch())
        advanceUntilIdle()
        val session = ScriptedSession(mutableListOf({ good() }, { good() }, { good() }))
        assertThat(state.phase.value).isEqualTo(QualificationState.Phase.Photographing(taken = 0))

        repeat(3) {
            state.takePhoto(session)
            advanceUntilIdle()
        }

        val done = state.phase.value as QualificationState.Phase.Done
        assertThat(done.result.status).isEqualTo(QualificationStatus.QUALIFIED)
        assertThat(profiles.rows).hasSize(1)
        assertThat(audit.rows.map { it.eventType }).containsExactly("DEVICE_QUALIFIED")
    }

    @Test
    fun aPhotoWithoutTheCardIsRetakenAndNotCounted_TC_DEV_05() = runTest {
        val state = stateFor(storeNominalBatch())
        advanceUntilIdle()
        val session = ScriptedSession(mutableListOf({ blank }, { good() }))

        state.takePhoto(session)
        advanceUntilIdle()

        val phase = state.phase.value as QualificationState.Phase.Photographing
        assertThat(phase.taken).isEqualTo(0)
        assertThat(phase.problem).contains(QualityReasonCode.NO_CARD)
        assertThat(profiles.rows).isEmpty()

        state.takePhoto(session)
        advanceUntilIdle()
        assertThat((state.phase.value as QualificationState.Phase.Photographing).taken).isEqualTo(1)
    }

    @Test
    fun aCameraFailureAsksAgainWithoutLosingTheGoodPhotos() = runTest {
        val state = stateFor(storeNominalBatch())
        advanceUntilIdle()
        val session = ScriptedSession(mutableListOf({ good() }, { error("camera closed") }, { good() }))

        state.takePhoto(session)
        advanceUntilIdle()
        state.takePhoto(session)
        advanceUntilIdle()

        assertThat(state.phase.value).isEqualTo(QualificationState.Phase.Photographing(taken = 1, cameraFailed = true))
        state.takePhoto(session)
        advanceUntilIdle()
        assertThat((state.phase.value as QualificationState.Phase.Photographing).taken).isEqualTo(2)
    }

    @Test
    fun aResultThatCannotBeSavedDiscardsThePhotosAndAsksAgain() = runTest {
        val failingAudit = object : AuditEventDao by FakeAuditEventDao() {
            override suspend fun insert(event: AuditEventEntity) = error("disk full")
        }
        val state = QualificationState(
            service = QualificationService(
                qualifier = CameraQualifier(),
                vision = VisionEngine(),
                cardBatches = batches,
                profiles = profiles,
                audit = failingAudit,
                dataStore = dataStore,
                appVersion = "1.0.0",
                clockMs = { 1_800_000_000_000L }
            ),
            capabilities = capabilities,
            cardBatchId = storeNominalBatch(),
            scope = this,
            cpu = StandardTestDispatcher(testScheduler)
        )
        advanceUntilIdle()
        val session = ScriptedSession(mutableListOf({ good() }, { good() }, { good() }))

        repeat(3) {
            state.takePhoto(session)
            advanceUntilIdle()
        }

        assertThat(state.phase.value).isEqualTo(QualificationState.Phase.Photographing(taken = 0, notSaved = true))
    }

    @Test
    fun aBatchThatIsNotOnThePhoneLeavesNothingToCompareWith() = runTest {
        val state = stateFor("NG-NOT-STORED")
        advanceUntilIdle()

        assertThat(state.phase.value).isEqualTo(QualificationState.Phase.CardMissing)
        val session = ScriptedSession(mutableListOf({ good() }))
        state.takePhoto(session)
        advanceUntilIdle()
        assertThat(session.taken).isEqualTo(0)
    }

    @Test
    fun nothingHappensUntilTheReferenceHasLoaded() = runTest {
        val state = stateFor(storeNominalBatch())
        val session = ScriptedSession(mutableListOf({ good() }))

        state.takePhoto(session) // the batch has not been read yet
        assertThat(session.taken).isEqualTo(0)
        advanceUntilIdle()
    }
}
