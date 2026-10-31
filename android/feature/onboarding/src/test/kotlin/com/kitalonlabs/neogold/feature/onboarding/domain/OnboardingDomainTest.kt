package com.kitalonlabs.neogold.feature.onboarding.domain

import com.google.common.truth.Truth.assertThat
import com.kitalonlabs.neogold.core.data.local.entity.CardBatchEntity
import com.kitalonlabs.neogold.engine.camera.device.DeviceCapabilities
import com.kitalonlabs.neogold.engine.camera.model.CameraHardwareLevel
import com.kitalonlabs.neogold.engine.camera.qualification.CameraQualifier
import com.kitalonlabs.neogold.engine.camera.qualification.QualificationStatus
import com.kitalonlabs.neogold.engine.camera.qualification.RequalificationReason
import com.kitalonlabs.neogold.engine.cardtrust.CardTrustVerifier
import com.kitalonlabs.neogold.engine.cardtrust.TrustListParser
import com.kitalonlabs.neogold.engine.vision.CardGeometry
import com.kitalonlabs.neogold.engine.vision.QualityReasonCode
import com.kitalonlabs.neogold.engine.vision.SyntheticCardRenderer
import com.kitalonlabs.neogold.engine.vision.SyntheticRenderConfig
import com.kitalonlabs.neogold.engine.vision.VisionEngine
import com.kitalonlabs.neogold.feature.onboarding.FakeNeoGoldDataStore
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.io.File
import java.time.LocalDate

/**
 * S06 and S07 without a camera: the scanned card is checked and stored, the three photos are checked and judged, and
 * the profile, the audit event and the re-qualification clock are kept (TC-CARD-01 to TC-CARD-06, TC-DEV-01,
 * TC-DEV-02 to TC-DEV-05, TC-DEV-07, TC-DEV-08). The card payloads are the signed vectors in card/spec/testvectors.
 */
class OnboardingDomainTest {

    private val batches = FakeCardBatchDao()
    private val profiles = FakeDeviceProfileDao()
    private val audit = FakeAuditEventDao()
    private val dataStore = FakeNeoGoldDataStore()
    private var nowMs = 1_800_000_000_000L

    private fun repoFile(relative: String): File =
        listOf(relative, "../$relative", "../../$relative", "../../../$relative").map(::File).first { it.exists() }

    private fun payload(vector: String): ByteArray {
        val hex = Regex(
            "\"payload_hex\"\\s*:\\s*\"([0-9a-f]+)\""
        ).find(repoFile("card/spec/testvectors/$vector").readText())!!
        return hex.groupValues[1].chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    }

    private val scanner = CardScanService(
        verifier = CardTrustVerifier(
            TrustListParser.parse(repoFile("card/spec/testvectors/test_trust_list.json").readText())
        ),
        cardBatches = batches,
        audit = audit,
        clockMs = { nowMs },
        today = { LocalDate.of(2026, 11, 1) }
    )

    // --- S06: the card ---

    @Test
    fun aSignedCardIsAcceptedAndItsBatchIsStored_TC_CARD_01() = runTest {
        val bytes = payload("card_batch_valid.json")

        val outcome = scanner.accept(bytes)

        assertThat(outcome).isInstanceOf(CardScanOutcome.Verified::class.java)
        val stored = batches.getBatch((outcome as CardScanOutcome.Verified).batchId)!!
        assertThat(stored.signatureValid).isEqualTo(1)
        assertThat(stored.qrPayload).isEqualTo(bytes)
        assertThat(stored.serverStatus).isEqualTo("NOT_SENT")
        assertThat(CardBatchReference.fromJson(stored.referenceValues)!!.keys)
            .containsExactlyElementsIn(CardGeometry.PATCHES.keys).inOrder()
        assertThat(audit.rows).isEmpty()
    }

    @Test
    fun aCardThatIsNotTrustedIsRefusedAndNothingIsStored_TC_CARD_02_TC_CARD_03_TC_CARD_04() = runTest {
        for (vector in listOf(
            "card_batch_invalid_tampered.json",
            "card_batch_invalid_unknown_issuer.json",
            "card_batch_invalid_wrong_purpose.json"
        )) {
            assertThat(scanner.accept(payload(vector))).isEqualTo(CardScanOutcome.Rejected(CardRejection.NOT_TRUSTED))
        }

        assertThat(batches.rows).isEmpty()
        assertThat(audit.getEventsByType("CARD_REJECTED")).hasSize(3)
    }

    @Test
    fun anExpiredAndARevokedCardAreToldApart_TC_CARD_05_TC_CARD_06() = runTest {
        assertThat(scanner.accept(payload("card_batch_invalid_expired.json")))
            .isEqualTo(CardScanOutcome.Rejected(CardRejection.EXPIRED))
        assertThat(scanner.accept(payload("card_batch_invalid_revoked.json")))
            .isEqualTo(CardScanOutcome.Rejected(CardRejection.REVOKED))
        assertThat(batches.rows).isEmpty()
    }

    @Test
    fun aQrThatIsNotACardIsUnreadable() = runTest {
        assertThat(scanner.accept(byteArrayOf(1, 2, 3, 4)))
            .isEqualTo(CardScanOutcome.Rejected(CardRejection.UNREADABLE))
    }

    @Test
    fun theAuditEventNamesTheReasonAndCarriesNoPayload_TC_CARD_02() = runTest {
        val bytes = payload("card_batch_invalid_tampered.json")

        scanner.accept(bytes)

        val details = audit.rows.single().details!!
        assertThat(details).isEqualTo("{\"reason\":\"NOT_TRUSTED\"}")
    }

    @Test
    fun scanningTheSameCardAgainKeepsWhenItWasFirstSeen() = runTest {
        val bytes = payload("card_batch_valid.json")
        scanner.accept(bytes)
        val first = batches.rows.values.single()
        batches.rows[first.batchId] = first.copy(serverStatus = "ACCEPTED")
        nowMs += 86_400_000L

        scanner.accept(bytes)

        val again = batches.rows.values.single()
        assertThat(again.firstSeenAt).isEqualTo(first.firstSeenAt)
        assertThat(again.serverStatus).isEqualTo("ACCEPTED")
    }

    @Test
    fun theStoredReferenceValuesRoundTrip() {
        val json = "[" + CardGeometry.PATCHES.values.joinToString(",") {
            "[${it.nominalLab.l},${it.nominalLab.a},${it.nominalLab.b}]"
        } + "]"

        val labs = CardBatchReference.fromJson(json)!!

        assertThat(labs.getValue("G1")).isEqualTo(CardGeometry.PATCHES.getValue("G1").nominalLab)
        assertThat(CardBatchReference.fromJson("[[1,2,3]]")).isNull()
    }

    // --- S07: the phone's camera ---

    private val capabilities = DeviceCapabilities(
        manufacturer = "TECNO",
        deviceModel = "Spark 10",
        androidApi = 33,
        buildFingerprint = "TECNO/KI5k/KI5k:13/TP1A/230918:user/release-keys",
        ramMb = 4096,
        isLowRamDevice = false,
        cameraId = "0",
        cameraHardwareLevel = CameraHardwareLevel.LIMITED,
        supportsAeLock = true,
        supportsAwbLock = false,
        supportsManualSensor = false,
        supportsRaw = false
    )

    private val qualification = QualificationService(
        qualifier = CameraQualifier(),
        vision = VisionEngine(),
        cardBatches = batches,
        profiles = profiles,
        audit = audit,
        dataStore = dataStore,
        appVersion = "1.0.0",
        clockMs = { nowMs }
    )

    private val renderer = SyntheticCardRenderer()

    /** A stored batch whose measured colors are the card design's, as the synthetic photos show them. */
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
                "NOT_SENT", nowMs
            )
        )
        return "NG-2026-001"
    }

    private fun photos(config: SyntheticRenderConfig = SyntheticRenderConfig()) =
        List(3) { renderer.renderFrame(config) }

    @Test
    fun aPhoneThatPassesIsQualifiedAndEverythingIsKept_TC_DEV_01_TC_DEV_02_TC_DEV_08() = runTest {
        val batchId = storeNominalBatch()

        val result = qualification.qualify(photos(), capabilities, batchId)

        assertThat(result.status).isEqualTo(QualificationStatus.QUALIFIED)
        val row = profiles.getActiveProfile()!!
        assertThat(row.id).isEqualTo(result.profile.id)
        // TC-DEV-01: what the device check found is in the profile
        assertThat(row.ramMb).isEqualTo(4096)
        assertThat(row.isLowRamDevice).isEqualTo(0)
        assertThat(row.cameraHardwareLevel).isEqualTo("LIMITED")
        assertThat(row.supportsAeLock).isEqualTo(1)
        assertThat(row.supportsAwbLock).isEqualTo(0)
        assertThat(row.cardBatchId).isEqualTo(batchId)
        assertThat(row.qualification).isEqualTo("QUALIFIED")
        // TC-DEV-08: an audit event with values only
        val event = audit.rows.single()
        assertThat(event.eventType).isEqualTo("DEVICE_QUALIFIED")
        assertThat(event.details).contains("\"repeatabilityDe\"")
        assertThat(dataStore.activeDeviceProfileIdFlow.value).isEqualTo(result.profile.id)
        assertThat(dataStore.qualificationBuildFingerprintFlow.value).isEqualTo(capabilities.buildFingerprint)
    }

    @Test
    fun aPhoneThatChangesColorsUnevenlyIsResearchOnly_TC_DEV_05_TC_DEV_08() = runTest {
        val batchId = storeNominalBatch()

        val result = qualification.qualify(
            photos(SyntheticRenderConfig(duplicateMismatchDeltaE = 4.0)),
            capabilities,
            batchId
        )

        assertThat(result.status).isEqualTo(QualificationStatus.RESEARCH_ONLY)
        assertThat(profiles.getActiveProfile()!!.qualification).isEqualTo("RESEARCH_ONLY")
        assertThat(audit.rows.single().eventType).isEqualTo("DEVICE_RESEARCH_ONLY")
    }

    @Test
    fun aNewQualificationSupersedesTheOldProfile() = runTest {
        val batchId = storeNominalBatch()
        qualification.qualify(photos(), capabilities, batchId)
        val first = profiles.rows.single()

        nowMs += 1_000L
        qualification.qualify(photos(), capabilities, batchId)

        assertThat(profiles.rows).hasSize(2)
        assertThat(profiles.rows.first { it.id == first.id }.status).isEqualTo("SUPERSEDED")
        assertThat(profiles.rows.count { it.status == "ACTIVE" }).isEqualTo(1)
    }

    @Test(expected = IllegalStateException::class)
    fun aBatchThatIsNotStoredCannotBeQualifiedAgainst() = runTest {
        qualification.qualify(photos(), capabilities, "NG-NOT-STORED")
    }

    @Test
    fun aPhotoWithoutTheCardIsAskedAgainAndIsNotHeldAgainstThePhone() = runTest {
        val batchId = storeNominalBatch()
        val reference = qualification.referenceOf(batchId)!!
        val blank = com.kitalonlabs.neogold.engine.vision.ImageFrame(
            640,
            480,
            IntArray(640 * 480) { 0xFF808080.toInt() }
        )

        val check = qualification.checkPhoto(blank, reference)

        assertThat(check).isInstanceOf(PhotoCheck.Retake::class.java)
        assertThat((check as PhotoCheck.Retake).reasons).contains(QualityReasonCode.NO_CARD)
        assertThat(profiles.rows).isEmpty()
        assertThat(audit.rows).isEmpty()
    }

    @Test
    fun aGoodPhotoOfTheCardPasses() = runTest {
        val batchId = storeNominalBatch()

        val check = qualification.checkPhoto(
            renderer.renderFrame(SyntheticRenderConfig()),
            qualification.referenceOf(batchId)!!
        )

        assertThat(check).isEqualTo(PhotoCheck.Good)
    }

    @Test
    fun qualificationIsDueAfter30DaysOrAnotherOsBuild_TC_DEV_07() = runTest {
        val batchId = storeNominalBatch()
        qualification.qualify(photos(), capabilities, batchId)

        assertThat(qualification.requalificationReason(capabilities.buildFingerprint)).isNull()
        assertThat(
            qualification.requalificationReason("another/build")
        ).isEqualTo(RequalificationReason.OS_BUILD_CHANGED)
        nowMs += CameraQualifier.REQUALIFICATION_INTERVAL_MS
        assertThat(qualification.requalificationReason(capabilities.buildFingerprint))
            .isEqualTo(RequalificationReason.EXPIRED_30_DAYS)
    }

    @Test
    fun aProfileThatDoesNotSayWhichBuildItWasMadeOnIsMadeAgain_TC_DEV_07() = runTest {
        val batchId = storeNominalBatch()
        qualification.qualify(photos(), capabilities, batchId)
        dataStore.qualificationBuildFingerprintFlow.value = null // a profile from before the build was remembered

        assertThat(qualification.requalificationReason(capabilities.buildFingerprint))
            .isEqualTo(RequalificationReason.OS_BUILD_CHANGED)
    }

    @Test
    fun theAuditDetailsStayValidJsonWhateverTheModelNameIs_TC_DEV_08() = runTest {
        val batchId = storeNominalBatch()
        val odd = capabilities.copy(deviceModel = "Spark \"10\" \\ Pro\n")

        qualification.qualify(photos(), odd, batchId)

        val details = audit.rows.single().details!!
        assertThat(details).contains("\"deviceModel\":\"Spark \\\"10\\\" \\\\ Pro\\u000a\"")
        assertThat(details).doesNotContain("\n")
    }

    @Test
    fun aClockSetBackToBeforeTheQualificationDoesNotStopItFromExpiring_TC_DEV_07() = runTest {
        val batchId = storeNominalBatch()
        qualification.qualify(photos(), capabilities, batchId)

        nowMs -= 1_000L

        assertThat(qualification.requalificationReason(capabilities.buildFingerprint))
            .isEqualTo(RequalificationReason.EXPIRED_30_DAYS)
    }

    @Test
    fun onlyAQualifiedPhoneThatIsNotDueMayBeOfferedScreening_TC_DEV_05() = runTest {
        // No profile at all
        assertThat(qualification.screeningEligible(capabilities.buildFingerprint)).isFalse()

        // A phone whose camera was measured and passed
        val batchId = storeNominalBatch()
        qualification.qualify(photos(), capabilities, batchId)
        assertThat(qualification.screeningEligible(capabilities.buildFingerprint)).isTrue()

        // The same phone after an OS update, or after 30 days
        assertThat(qualification.screeningEligible("another/build")).isFalse()
        nowMs += CameraQualifier.REQUALIFICATION_INTERVAL_MS
        assertThat(qualification.screeningEligible(capabilities.buildFingerprint)).isFalse()
    }

    @Test
    fun aResearchOnlyPhoneIsNeverOfferedScreening_TC_DEV_05() = runTest {
        val batchId = storeNominalBatch()
        qualification.qualify(photos(SyntheticRenderConfig(duplicateMismatchDeltaE = 4.0)), capabilities, batchId)

        assertThat(profiles.getActiveProfile()!!.qualification).isEqualTo("RESEARCH_ONLY")
        assertThat(qualification.screeningEligible(capabilities.buildFingerprint)).isFalse()
    }

    @Test
    fun theCardOnATableIsNotHeldAgainstThePhoneForHavingNoSkinInItsWindow_TC_DEV_02() = runTest {
        val batchId = storeNominalBatch()
        val onATable =
            SyntheticRenderConfig(
                windowContent = com.kitalonlabs.neogold.engine.vision.SyntheticWindowContent.CLOTH_BLUE
            )
        val reference = qualification.referenceOf(batchId)!!
        // The gate itself does flag the window; the qualification photos leave that check out.
        assertThat(VisionEngine().processFrame(renderer.renderFrame(onATable), reference).failureReasons)
            .contains(QualityReasonCode.WINDOW_NOT_SKIN)

        assertThat(qualification.checkPhoto(renderer.renderFrame(onATable), reference)).isEqualTo(PhotoCheck.Good)
        val result = qualification.qualify(photos(onATable), capabilities, batchId)

        assertThat(result.status).isEqualTo(QualificationStatus.QUALIFIED)
    }

    @Test
    fun aBatchThatWasNotVerifiedIsNeverAReferenceForQualification() = runTest {
        val batchId = storeNominalBatch()
        batches.rows[batchId] = batches.rows.getValue(batchId).copy(signatureValid = 0)

        assertThat(qualification.referenceOf(batchId)).isNull()
    }

    @Test
    fun theSameBatchIdWithOtherContentIsRefusedAndTheStoredBatchIsKept_TC_CARD_01() = runTest {
        val valid = payload("card_batch_valid.json")
        scanner.accept(valid)
        val stored = batches.rows.values.single()
        batches.rows[stored.batchId] = stored.copy(
            qrPayload = stored.qrPayload.copyOf().also {
                it[10] = (it[10] + 1).toByte()
            }
        )

        val outcome = scanner.accept(valid)

        assertThat(outcome).isEqualTo(CardScanOutcome.Rejected(CardRejection.NOT_TRUSTED))
        assertThat(batches.rows.values.single().qrPayload).isNotEqualTo(valid)
        assertThat(audit.getEventsByType("CARD_REJECTED")).hasSize(1)
    }

    @Test
    fun withNoProfileThereIsNothingToRenew() = runTest {
        assertThat(qualification.requalificationReason("any")).isNull()
    }
}
