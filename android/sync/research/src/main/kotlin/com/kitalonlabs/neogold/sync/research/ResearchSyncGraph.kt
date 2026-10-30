package com.kitalonlabs.neogold.sync.research

import com.kitalonlabs.neogold.core.data.local.NeoGoldDatabase
import com.kitalonlabs.neogold.core.security.EncryptedFileStore
import com.kitalonlabs.neogold.engine.cardtrust.CardTrustVerifier
import com.kitalonlabs.neogold.sync.research.api.ResearchApiClient
import com.kitalonlabs.neogold.sync.research.crypto.AndroidDeviceKeyManager
import com.kitalonlabs.neogold.sync.research.crypto.ClockManager
import com.kitalonlabs.neogold.sync.research.crypto.DeviceKeyManager
import com.kitalonlabs.neogold.sync.research.enrollment.StudyEnrollmentManager
import com.kitalonlabs.neogold.sync.research.enrollment.StudyRevocationManager
import com.kitalonlabs.neogold.sync.research.outbox.OutboxProcessor
import com.kitalonlabs.neogold.sync.research.outbox.ReconciliationManager
import com.kitalonlabs.neogold.sync.research.outbox.ResearchRetentionConfirmationHook
import okhttp3.OkHttpClient

/**
 * Everything the app needs to talk to a study server, built over the one open [database] and the one set of keys the app
 * already holds. The HTTP client is made here and nowhere else: this is the only module allowed to depend on it (doc 8
 * Section 6.1, `tools/check_dependencies.py`). The upload worker, the enrollment screens and the retention job all take
 * their objects from one graph, so none of them builds a database or a key store of its own (BUG-2026-083 item 33).
 */
class ResearchSyncGraph(
    private val database: NeoGoldDatabase,
    private val encryptedFiles: EncryptedFileStore,
    cardTrustVerifier: CardTrustVerifier,
    private val appVersion: String,
    private val keyManager: DeviceKeyManager = AndroidDeviceKeyManager(),
    okHttpClient: OkHttpClient = defaultClient()
) {
    private val apiClient = ResearchApiClient(
        okHttpClient = okHttpClient,
        clockManager = ClockManager(),
        keyManager = keyManager
    )

    val revocationManager = StudyRevocationManager(
        apiClient = apiClient,
        keyManager = keyManager,
        studyDao = database.researchStudyDao(),
        auditDao = database.auditEventDao(),
        appVersion = appVersion
    )

    val enrollmentManager = StudyEnrollmentManager(
        apiClient = apiClient,
        keyManager = keyManager,
        cardTrustVerifier = cardTrustVerifier,
        studyDao = database.researchStudyDao(),
        appVersion = appVersion
    )

    private val reconciliationManager = ReconciliationManager(
        apiClient = apiClient,
        outboxDao = database.outboxDao(),
        studyDao = database.researchStudyDao(),
        sessionDao = database.screeningSessionDao(),
        captureDao = database.captureDao(),
        batchDao = database.cardBatchDao(),
        participantDao = database.researchParticipantDao(),
        tsbDao = database.tsbMeasurementDao(),
        appVersion = appVersion
    )

    val outboxProcessor = OutboxProcessor(
        apiClient = apiClient,
        outboxDao = database.outboxDao(),
        studyDao = database.researchStudyDao(),
        batchDao = database.cardBatchDao(),
        participantDao = database.researchParticipantDao(),
        sessionDao = database.screeningSessionDao(),
        captureDao = database.captureDao(),
        tsbDao = database.tsbMeasurementDao(),
        encryptedFileStore = encryptedFiles,
        revocationManager = revocationManager,
        reconciliationManager = reconciliationManager,
        appVersion = appVersion
    )

    /** Asked by the retention job before a research session is purged: has the server really got it? */
    val retentionHook = ResearchRetentionConfirmationHook(reconciliationManager, database.researchStudyDao())

    companion object {
        /**
         * A redirect would send the request body, and the device assertion's claims, to a server the study never named,
         * so none is followed.
         */
        fun defaultClient(): OkHttpClient =
            OkHttpClient.Builder().followRedirects(false).followSslRedirects(false).build()
    }
}

/**
 * What the upload worker needs from the app. The Application implements it, so the worker uses the one database and the one
 * set of keys the app opened instead of building a second set (same pattern as the retention worker).
 */
interface ResearchSyncComponent {
    /**
     * The upload processor over the open database, or null while it must not run: storage cannot be read (a lost Keystore
     * key), the bundle check failed, or research mode is off. The worker then tries again later.
     */
    suspend fun outboxProcessor(): OutboxProcessor?
}
