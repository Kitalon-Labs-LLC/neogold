package com.kitalonlabs.neogold.core.data.local

import android.database.sqlite.SQLiteConstraintException
import com.google.common.truth.Truth.assertThat
import com.kitalonlabs.neogold.core.data.local.entity.CardBatchEntity
import com.kitalonlabs.neogold.core.data.local.entity.ScreeningSessionEntity
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.util.UUID

/**
 * BUG-2026-060. The database enforces foreign keys, so a screening that names a card batch the phone never
 * stored is refused, and a card batch that sessions name cannot be deleted. The app used to do the first on every
 * "Save to log", and its card batch insert did the second whenever a card was scanned again.
 */
@RunWith(RobolectricTestRunner::class)
class ForeignKeyEnforcementTest {

    private lateinit var database: NeoGoldDatabase

    @Before
    fun setUp() {
        database = NeoGoldDatabase.buildInMemory(RuntimeEnvironment.getApplication())
    }

    @After
    fun tearDown() {
        database.close()
    }

    private fun session(cardBatchId: String?) = ScreeningSessionEntity(
        id = UUID.randomUUID().toString(),
        mode = "SCREENING",
        status = "COMPLETED",
        createdAt = 1_000L,
        completedAt = 2_000L,
        ageHours = 40.0,
        guardianConsentConfirmed = 1,
        gestationalAgeWeeks = 38,
        flagJaundiceUnder24h = 0,
        flagDangerSigns = 0,
        flagPriorPhototherapy = 0,
        ruleOutcome = null,
        triageAction = "BLOOD_TEST_TODAY",
        reasonCodes = null,
        estimateMgDl = null,
        estimateLowMgDl = null,
        estimateHighMgDl = null,
        thresholdMgDl = null,
        recheckDueAt = null,
        registerNumber = null,
        registerNumberErasedAt = null,
        participantId = null,
        estimateHidden = 0,
        experimentalLabelShown = 1,
        deviceProfileId = null,
        cardBatchId = cardBatchId,
        appVersion = "1.0.0",
        modelVersion = null,
        configVersion = "2026.10.0"
    )

    private fun cardBatch(serverStatus: String) = CardBatchEntity(
        batchId = "NG-2026-001",
        issuerId = "issuer-test",
        formatVersion = 1,
        referenceValues = "[]",
        issuedAt = 1_000L,
        expiresAt = 9_000L,
        signatureValid = 1,
        qrPayload = byteArrayOf(1, 2, 3),
        serverStatus = serverStatus,
        firstSeenAt = 1_500L
    )

    @Test
    fun sessionNamingACardBatchThatWasNeverStored_isRefused() = runTest {
        val failure = runCatching { database.screeningSessionDao().insert(session(cardBatchId = "NG-2026-001")) }
            .exceptionOrNull()

        assertThat(failure).isInstanceOf(SQLiteConstraintException::class.java)
        assertThat(database.screeningSessionDao().getSessionsByMode("SCREENING")).isEmpty()
    }

    @Test
    fun sessionWithNoCardBatch_isAccepted() = runTest {
        database.screeningSessionDao().insert(session(cardBatchId = null))

        assertThat(database.screeningSessionDao().getSessionsByMode("SCREENING")).hasSize(1)
    }

    @Test
    fun sessionNamingAStoredCardBatch_isAccepted() = runTest {
        database.cardBatchDao().insert(cardBatch(serverStatus = "NOT_SENT"))

        database.screeningSessionDao().insert(session(cardBatchId = "NG-2026-001"))

        assertThat(database.screeningSessionDao().getSessionsByMode("SCREENING")).hasSize(1)
    }

    @Test
    fun scanningACardBatchAgain_keepsTheSessionsThatNameIt() = runTest {
        database.cardBatchDao().insert(cardBatch(serverStatus = "NOT_SENT"))
        database.screeningSessionDao().insert(session(cardBatchId = "NG-2026-001"))

        database.cardBatchDao().insert(cardBatch(serverStatus = "ACCEPTED"))

        assertThat(database.screeningSessionDao().getSessionsByMode("SCREENING")).hasSize(1)
        assertThat(database.cardBatchDao().getBatch("NG-2026-001")?.serverStatus).isEqualTo("ACCEPTED")
    }
}
