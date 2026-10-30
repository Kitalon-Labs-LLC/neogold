package com.kitalonlabs.neogold

import android.app.Application
import android.content.pm.ApplicationInfo
import android.os.Build
import com.kitalonlabs.neogold.core.data.datastore.DefaultNeoGoldDataStore
import com.kitalonlabs.neogold.core.data.datastore.NeoGoldDataStore
import com.kitalonlabs.neogold.core.data.local.NeoGoldDatabase
import com.kitalonlabs.neogold.core.data.local.entity.CardBatchEntity
import com.kitalonlabs.neogold.core.data.pin.ResearchPinManager
import com.kitalonlabs.neogold.core.data.purge.DataWiper
import com.kitalonlabs.neogold.core.data.purge.DefaultDataPurgeManager
import com.kitalonlabs.neogold.core.data.purge.PurgeResult
import com.kitalonlabs.neogold.core.data.retention.BackgroundWork
import com.kitalonlabs.neogold.core.data.retention.NoOpResearchRetentionServerConfirmationHook
import com.kitalonlabs.neogold.core.data.retention.RetentionManager
import com.kitalonlabs.neogold.core.security.AndroidMasterKeyProvider
import com.kitalonlabs.neogold.core.security.DatabaseKeyProvider
import com.kitalonlabs.neogold.core.security.DefaultDatabaseKeyProvider
import com.kitalonlabs.neogold.core.security.DefaultEncryptedFileStore
import com.kitalonlabs.neogold.core.security.DefaultFileKeysetProvider
import com.kitalonlabs.neogold.core.security.DefaultKeystoreRecoveryManager
import com.kitalonlabs.neogold.core.security.EncryptedFileStore
import com.kitalonlabs.neogold.core.security.FileKeysetProvider
import com.kitalonlabs.neogold.core.security.KeystoreRecoveryManager
import com.kitalonlabs.neogold.core.security.MasterKeyProvider
import com.kitalonlabs.neogold.core.security.integrity.BundleManifestVerifier
import com.kitalonlabs.neogold.core.security.integrity.DefaultIntegrityStateManager
import com.kitalonlabs.neogold.core.security.integrity.IntegrityStateManager
import com.kitalonlabs.neogold.core.security.pin.Argon2PinHasher
import com.kitalonlabs.neogold.core.security.pin.KeystorePinHmacKeyProvider
import com.kitalonlabs.neogold.core.security.pin.PinHmacKeyProvider
import com.kitalonlabs.neogold.core.security.pin.ResearchSessionManager
import com.kitalonlabs.neogold.engine.camera.qualification.CameraQualifier
import com.kitalonlabs.neogold.engine.camera.qualification.QualificationThresholds
import com.kitalonlabs.neogold.engine.cardtrust.CardBatchPayload
import com.kitalonlabs.neogold.engine.cardtrust.CardTrustVerifier
import com.kitalonlabs.neogold.engine.cardtrust.VerificationResult
import com.kitalonlabs.neogold.engine.triage.TriageEngine
import com.kitalonlabs.neogold.engine.vision.QualityThresholds
import com.kitalonlabs.neogold.engine.vision.VisionEngine
import com.kitalonlabs.neogold.feature.onboarding.domain.CardBatchReference
import com.kitalonlabs.neogold.feature.onboarding.domain.CardScanService
import com.kitalonlabs.neogold.feature.onboarding.domain.QualificationService
import com.kitalonlabs.neogold.feature.research.capture.ResearchCaptureProcessor
import com.kitalonlabs.neogold.feature.research.data.CardTrustStatus
import com.kitalonlabs.neogold.feature.research.data.ResearchProvenance
import com.kitalonlabs.neogold.feature.research.data.ResearchRepository
import com.kitalonlabs.neogold.feature.research.export.ResearchExportManager
import com.kitalonlabs.neogold.feature.research.ui.ResearchViewModel
import com.kitalonlabs.neogold.feature.research.ui.StudyServerViewModel
import com.kitalonlabs.neogold.feature.screening.domain.NO_CARD_SCANNED
import com.kitalonlabs.neogold.feature.screening.domain.ScreeningFlow
import com.kitalonlabs.neogold.feature.screening.domain.ScreeningPipelineUseCase
import com.kitalonlabs.neogold.feature.screening.domain.VersionsRecord
import com.kitalonlabs.neogold.integrity.VerifiedAssets
import com.kitalonlabs.neogold.onboarding.OnboardingServices
import com.kitalonlabs.neogold.storage.StorageCoordinator
import com.kitalonlabs.neogold.storage.StorageState
import com.kitalonlabs.neogold.sync.research.ResearchSyncGraph
import com.kitalonlabs.neogold.sync.research.outbox.OutboxProcessor
import com.kitalonlabs.neogold.wipe.DirectoryWiper
import com.kitalonlabs.neogold.wipe.KeystoreAliasWiper
import com.kitalonlabs.neogold.wipe.PinKeyWiper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import java.io.IOException
import java.security.KeyStore
import java.time.LocalDate
import kotlin.coroutines.cancellation.CancellationException

/** Where "Delete all data" stands. */
sealed interface DeleteState {
    data object Idle : DeleteState
    data object Deleting : DeleteState

    /** [items] names what could not be removed (never a value from the data). Nothing was restarted. */
    data class Incomplete(val items: List<String>) : DeleteState
}

/**
 * The app's one object graph, built by hand (ADR-017, no DI framework). The Application owns it, so a worker or a
 * screen never builds a second set of keys or a second database of its own.
 *
 * Everything is lazy: nothing touches the Keystore until something needs it. The order that matters is fixed in
 * [NeoGoldApplication.onCreate]: the bundle is verified first, then the keys are checked, then the database opens.
 */
@Suppress("TooManyFunctions") // the app's service locator: one factory per feature, split with the feature graphs (F4)
class NeoGoldContainer(private val application: Application) {

    /** Outlives every screen; used for work that must finish even if the activity goes away. */
    val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val isDebuggable: Boolean =
        (application.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0

    // Keys and encrypted stores
    val masterKeys: MasterKeyProvider by lazy { AndroidMasterKeyProvider(application) }
    val databaseKeys: DatabaseKeyProvider by lazy { DefaultDatabaseKeyProvider(application, masterKeys) }
    val fileKeyset: FileKeysetProvider by lazy { DefaultFileKeysetProvider(application, masterKeys) }
    val encryptedFiles: EncryptedFileStore by lazy { DefaultEncryptedFileStore(application, fileKeyset) }
    val pinHmacKey: PinHmacKeyProvider by lazy { KeystorePinHmacKeyProvider() }
    val keystoreRecovery: KeystoreRecoveryManager by lazy {
        DefaultKeystoreRecoveryManager(application, databaseKeys, fileKeyset, encryptedFiles)
    }

    val dataStore: NeoGoldDataStore by lazy { DefaultNeoGoldDataStore(application) }
    val researchSession = ResearchSessionManager()

    /** The screening in progress; cleared by Reset and Delete all data. */
    val screeningHolder = ScreeningHolder()

    // Integrity of the bundled clinical files (BUG-2026-007)
    val integrity: IntegrityStateManager = DefaultIntegrityStateManager()

    @Volatile
    var assets: VerifiedAssets = VerifiedAssets.NotVerified
        private set

    /** The database, opened after the keys were checked and off the main thread. */
    val storage: StorageCoordinator<NeoGoldDatabase> = StorageCoordinator(
        // Looked up when the check runs, inside its try: building the Keystore objects can itself fail on some phones,
        // and that must show the "could not be opened" screen, not crash Application.onCreate.
        recovery = object : KeystoreRecoveryManager {
            override fun checkKeystoreHealth() = keystoreRecovery.checkKeystoreHealth()
            override fun resetEncryptedStores() = keystoreRecovery.resetEncryptedStores()
        },
        openDatabase = ::openDatabase,
        onOpened = { database -> retentionManagerFor(database).runAllRetentionJobs() },
        onReset = ::removeEverythingBesidesTheStores
    )

    /**
     * Whether the screening screens are offered today: the verified config turns triage on, is signed for a public
     * build and is not past `validUntil` ([VerifiedAssets.triageAvailable]), AND the screens really compute a result.
     * Since package E1 they do (the pipeline and the triage engine decide every result), but the shipped config still has
     * `triageEnabled: false` for the closed research build, and turning it on needs the clinical lead's review first.
     */
    fun screeningAvailable(today: LocalDate = LocalDate.now()): Boolean =
        SCREENING_PIPELINE_WIRED && assets.triageAvailable(today, isPublicBuild = !isDebuggable)

    /**
     * The first-run flow (S06, S07) over the open [database]. The quality limits come from the signed config; if the
     * bundle did not verify there is no trust list, [OnboardingServices.cardScan] is null, and no card is accepted.
     */
    fun onboardingServices(database: NeoGoldDatabase): OnboardingServices {
        val limits = assets.triageConfig?.qualityThresholds.orEmpty()
        val vision = VisionEngine(thresholds = QualityThresholds.fromMap(limits))
        return OnboardingServices(
            cardScan = assets.trustList?.let {
                CardScanService(CardTrustVerifier(it), database.cardBatchDao(), database.auditEventDao())
            },
            qualification = QualificationService(
                qualifier = CameraQualifier(
                    thresholds = QualificationThresholds.fromMap(limits),
                    visionEngine = vision
                ),
                vision = vision,
                cardBatches = database.cardBatchDao(),
                profiles = database.deviceProfileDao(),
                audit = database.auditEventDao(),
                dataStore = dataStore,
                appVersion = BuildConfig.VERSION_NAME
            ),
            dataStore = dataStore,
            buildFingerprint = Build.FINGERPRINT
        )
    }

    @Volatile
    private var research: Pair<NeoGoldDatabase, ResearchViewModel>? = null

    /**
     * The research session over the open [database]. There is one per database, so the unlocked state and the
     * participant in progress survive a recomposition. Null while the trust list is not verified: without it no study
     * QR or card can be checked, and research does not run (BUG-2026-007).
     */
    @Synchronized
    fun researchViewModel(database: NeoGoldDatabase): ResearchViewModel? {
        research?.takeIf { it.first === database }?.let { return it.second }
        val trustList = assets.trustList
        val configVersion = assets.triageConfig?.configVersion
        if (trustList == null || configVersion == null) return null
        val hasher = Argon2PinHasher(pinHmacKey)
        val repository = ResearchRepository(
            database = database,
            encryptedFileStore = encryptedFiles,
            trustList = trustList,
            pinManager = ResearchPinManager(database.researchStudyDao(), database.auditEventDao(), hasher),
            pinHasher = hasher,
            // read when a session is saved, so the record names the profile that is active then
            provenance = {
                ResearchProvenance(
                    appVersion = BuildConfig.VERSION_NAME,
                    modelVersion = assets.modelVersion,
                    configVersion = configVersion,
                    deviceProfileId = database.deviceProfileDao().getActiveProfile()?.id
                )
            }
        )
        return ResearchViewModel(
            repository = repository,
            sessionManager = researchSession,
            // A saved session or TSB value is queued for the study server right away, if the phone is set up on one.
            onDataQueued = { studyServer(database)?.scheduleUploads() }
        ).also { research = database to it }
    }

    @Volatile
    private var sync: Pair<NeoGoldDatabase, ResearchSyncGraph>? = null

    /**
     * The one set of objects that talk to the study server, over the open [database] and the keys the app holds. Null while
     * the trust list is not verified: without it a study QR cannot be checked, so nothing is sent (BUG-2026-007). The
     * upload worker, the enrollment screens and the retention job all use this graph and build nothing of their own.
     */
    @Synchronized
    fun syncGraph(database: NeoGoldDatabase): ResearchSyncGraph? {
        sync?.takeIf { it.first === database }?.let { return it.second }
        val trustList = assets.trustList ?: return null
        return ResearchSyncGraph(
            database = database,
            encryptedFiles = encryptedFiles,
            cardTrustVerifier = CardTrustVerifier(trustList),
            appVersion = BuildConfig.VERSION_NAME
        ).also { sync = database to it }
    }

    /** The upload processor for the worker, or null while storage cannot be read or the bundle check failed. */
    suspend fun outboxProcessor(): OutboxProcessor? =
        storage.awaitDatabase()?.let { syncGraph(it)?.outboxProcessor }

    @Volatile
    private var studyServerSync: Pair<NeoGoldDatabase, StudyServerSync>? = null

    /** Joining, leaving and uploading, for the research screens (R07 to R09). Null while [syncGraph] is. */
    @Synchronized
    fun studyServer(database: NeoGoldDatabase): StudyServerSync? {
        studyServerSync?.takeIf { it.first === database }?.let { return it.second }
        val graph = syncGraph(database) ?: return null
        return StudyServerSync(application, database, graph, applicationScope).also { studyServerSync = database to it }
    }

    @Volatile
    private var studyServerViewModel: Pair<NeoGoldDatabase, StudyServerViewModel>? = null

    /**
     * The server side of research mode over the open [database]. One per database, so a scanned QR and a running join survive
     * a recomposition. It needs the research session (for the lock) and the graph, so it is null when either is.
     */
    @Synchronized
    fun studyServerViewModel(database: NeoGoldDatabase): StudyServerViewModel? {
        studyServerViewModel?.takeIf { it.first === database }?.let { return it.second }
        val research = researchViewModel(database)
        val server = studyServer(database)
        if (research == null || server == null) return null
        return StudyServerViewModel(
            repository = research.repository,
            server = server,
            sessionManager = researchSession,
            onStudyChanged = research::loadActiveStudy
        ).also { studyServerViewModel = database to it }
    }

    /** Starts the background upload if the phone is set up on a server and rows are waiting; called at app start. */
    suspend fun scheduleUploads() {
        storage.awaitDatabase()?.let { studyServer(it)?.scheduleUploads() }
    }

    /**
     * A new screening over the verified bundle, or null when there is none to run on: the triage config, the model and the
     * integrity check must all be good. The engine is told whether this is a public build, so an unsigned config stops a
     * release build (TC-TRI-26). Every decision in the flow is the engine's (package E1).
     */
    fun newScreening(): ScreeningFlow? {
        // The quality limits come from the signed config only. With none, the code's own defaults would be used silently, so
        // screening does not start (BUG-2026-071 item 1).
        val config = assets.triageConfig?.takeIf { assets.isUsable && it.qualityThresholds.isNotEmpty() }
        val model = assets.estimationEngine
        val modelVersion = assets.modelVersion
        if (config == null || model == null || modelVersion == null) return null
        return ScreeningFlow(
            integrityChecker = { assets.isUsable },
            triageEngine = TriageEngine(config, isPublicBuild = !isDebuggable),
            estimationEngine = model,
            baseVersions = VersionsRecord(
                appVersion = BuildConfig.VERSION_NAME,
                modelVersion = modelVersion,
                configVersion = config.configVersion,
                cardBatchId = NO_CARD_SCANNED,
                deviceModel = Build.MODEL,
                deviceManufacturer = Build.MANUFACTURER,
                osVersion = "Android ${Build.VERSION.RELEASE}"
            ),
            pipeline = ScreeningPipelineUseCase(
                integrityChecker = { assets.isUsable },
                visionEngine = VisionEngine(thresholds = QualityThresholds.fromMap(config.qualityThresholds))
            )
        )
    }

    /**
     * The card batch a screening is checked against: the same one a research session would name ([chooseResearchCard]),
     * checked against the trust list now. A card that is missing, expired or not trusted is a failed check, never a pass.
     */
    suspend fun screeningCardCheck(database: NeoGoldDatabase): VerificationResult<CardBatchPayload> =
        withContext(Dispatchers.IO) {
            val trustList = assets.trustList ?: return@withContext VerificationResult.Malformed
            val batches = database.cardBatchDao()
            val profileCard = database.deviceProfileDao().getActiveProfile()?.cardBatchId?.let { batches.getBatch(it) }
            val card = chooseResearchCard(profileCard, batches.getAllBatches(), System.currentTimeMillis())
                ?: return@withContext VerificationResult.Malformed
            CardTrustVerifier(trustList).verifyCardBatch(card.qrPayload)
        }

    /** The id of the phone's active device profile, for the screening record; null if there is none. */
    suspend fun activeDeviceProfileId(database: NeoGoldDatabase): String? =
        withContext(Dispatchers.IO) { database.deviceProfileDao().getActiveProfile()?.id }

    /**
     * What a research photo session needs from the card the phone works with: the camera step that takes and checks the
     * photos, and the signed bytes of the card, which the session record keeps. The card is the one of the active
     * device profile, else the newest one whose signature was valid that has not expired (see [chooseResearchCard]).
     * Without a card the photos are still taken, with no color residual measured, and the record says the card was not
     * verified.
     */
    class ResearchCaptureSetup(val processor: ResearchCaptureProcessor, val cardSignedBytes: ByteArray?)

    suspend fun researchCaptureSetup(database: NeoGoldDatabase): ResearchCaptureSetup = withContext(Dispatchers.IO) {
        val batches = database.cardBatchDao()
        val now = System.currentTimeMillis()
        val profileCard = database.deviceProfileDao().getActiveProfile()?.cardBatchId?.let { batches.getBatch(it) }
        val batch = chooseResearchCard(profileCard, batches.getAllBatches(), now)
        val usableCard = batch?.takeIf { !cardHasExpired(it, now) }
        val limits = assets.triageConfig?.qualityThresholds.orEmpty()
        ResearchCaptureSetup(
            processor = ResearchCaptureProcessor(
                vision = VisionEngine(thresholds = QualityThresholds.fromMap(limits)),
                // An expired card still goes into the record (as EXPIRED) but gives the quality gate no reference.
                reference = usableCard?.let { CardBatchReference.fromJson(it.referenceValues) }
            ),
            cardSignedBytes = batch?.qrPayload
        )
    }

    /**
     * The status of the card the phone works with, for R02 before any session is saved: the same card a session would name
     * ([chooseResearchCard]), checked against the trust list now, exactly as a session save checks it. So a card whose
     * issuer key was revoked after it was scanned no longer shows as verified. No trust list, or no card, is UNVERIFIED.
     */
    suspend fun researchCardStatus(database: NeoGoldDatabase): CardTrustStatus = withContext(Dispatchers.IO) {
        val trustList = assets.trustList ?: return@withContext CardTrustStatus.UNVERIFIED
        val batches = database.cardBatchDao()
        val profileCard = database.deviceProfileDao().getActiveProfile()?.cardBatchId?.let { batches.getBatch(it) }
        val card = chooseResearchCard(profileCard, batches.getAllBatches(), System.currentTimeMillis())
            ?: return@withContext CardTrustStatus.UNVERIFIED
        cardStatusOf(CardTrustVerifier(trustList).verifyCardBatch(card.qrPayload))
    }

    /**
     * The export of research data to a drive (R07, FR-30): encrypted on this phone, de-identified, written to a document
     * the health worker chooses. It makes a new data key for every export and keeps nothing between exports.
     */
    fun researchExportManager(database: NeoGoldDatabase): ResearchExportManager =
        ResearchExportManager(application, database, encryptedFiles)

    /** Verifies the bundle once and keeps the result. Called first thing at start-up. */
    fun verifyAssets() {
        val verifier = BundleManifestVerifier(BuildConfig.K3_PUBLIC_KEY_HEX)
        assets = VerifiedAssets.load(
            verifier = verifier,
            assets = { name ->
                try {
                    application.assets.open(name)
                } catch (_: IOException) {
                    null
                }
            },
            isDevelopmentKey = BuildConfig.K3_IS_DEV_KEY,
            isDebuggable = isDebuggable
        )
        integrity.updateIntegrity(assets.integrity)
    }

    private suspend fun openDatabase(): NeoGoldDatabase = withContext(Dispatchers.IO) {
        NeoGoldDatabase.buildDatabase(application, databaseKeys).also { database ->
            // Room opens lazily. Opening here, with the key, is what makes a wrong or lost key fail now.
            database.openHelper.writableDatabase
        }
    }

    private fun retentionManagerFor(database: NeoGoldDatabase) = RetentionManager(
        database = database,
        dataStore = dataStore,
        encryptedFileStore = encryptedFiles,
        // Research photos and rows are purged only after the study server says it has them (doc 5 Section 2.5). With no
        // graph (the bundle did not verify) the default does what is safe: it confirms nothing, so nothing is purged.
        serverConfirmationHook = syncGraph(database)?.retentionHook ?: NoOpResearchRetentionServerConfirmationHook()
    )

    /** The retention jobs over the open database, or null while storage cannot be read. */
    suspend fun retentionManager(): RetentionManager? =
        storage.awaitDatabase()?.let { retentionManagerFor(it) }

    /** Research items the study has not acknowledged, for the warning before "Delete all data". */
    suspend fun unsentResearchItems(): Int = storage.awaitDatabase()?.outboxDao()?.countUnsent() ?: 0

    private val wipers: List<DataWiper> by lazy {
        listOf(
            PinKeyWiper(pinHmacKey),
            KeystoreAliasWiper(
                name = "keystore-keys",
                keyStore = { KeyStore.getInstance("AndroidKeyStore").apply { load(null) } },
                prefixes = listOf(DEVICE_KEY_ALIAS_PREFIX),
                exactAliases = listOf(
                    MasterKeyProvider.ALIAS_CRASH_REPORT,
                    MasterKeyProvider.ALIAS_PIN_VERIFIER
                )
            ),
            DirectoryWiper("export-staging", File(application.cacheDir, EXPORT_STAGING_DIR)),
            DirectoryWiper("crash-reports", File(application.filesDir, CRASH_REPORTS_DIR))
        )
    }

    private val _deleteState = MutableStateFlow<DeleteState>(DeleteState.Idle)

    /** Progress of "Delete all data". It lives here, not in a screen, so rotating the phone cannot lose it. */
    val deleteState: StateFlow<DeleteState> = _deleteState.asStateFlow()

    /**
     * Runs "Delete all data" on the application scope, so it finishes even if the activity goes away, and restarts
     * the app empty when everything is gone. If something could not be removed the user is told, and nothing restarts.
     */
    @Suppress("TooGenericExceptionCaught") // any failure leaves data behind, which is reported, never swallowed
    fun startDeleteAll() {
        if (_deleteState.value == DeleteState.Deleting) return
        _deleteState.value = DeleteState.Deleting
        applicationScope.launch {
            val incomplete = try {
                deleteAllData().incomplete
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.w("Delete all data failed: %s", e.javaClass.name)
                listOf("storage")
            }
            if (incomplete.isEmpty()) {
                AppRestarter.restart(application)
            } else {
                _deleteState.value = DeleteState.Incomplete(incomplete)
            }
        }
    }

    /**
     * After a delete that could not finish, closing the message restarts the app: the database it held is closed, and
     * a fresh start is the only state that is sure to be consistent. The user can run Delete all data again.
     */
    fun restartAfterIncompleteDelete() {
        AppRestarter.restart(application)
    }

    /**
     * What "Reset saved data" removes besides the encrypted stores, so a reset does not leave a half-configured app: the
     * other keys and folders, settings that pointed at deleted rows, queued work and an unlocked research session.
     */
    private suspend fun removeEverythingBesidesTheStores() {
        BackgroundWork.cancelAll(application)
        researchSession.lock()
        screeningHolder.flow = null
        wipers.forEach { it.wipe() }
        dataStore.clearAll()
    }

    /**
     * Delete all data (FR-33, TC-LOG-11): stops background work, locks research, then removes the database, the
     * encrypted files, every key and the staging and crash files. The caller restarts the process afterwards.
     *
     * @return what was left behind, if anything; empty means everything is gone
     */
    suspend fun deleteAllData(): PurgeResult {
        BackgroundWork.cancelAll(application)
        researchSession.lock()
        screeningHolder.flow = null

        // Storage that cannot be read is reset by StorageCoordinator.resetAndOpen, not deleted from Settings.
        val current = (storage.state.value as? StorageState.Ready)?.database
            ?: error("Delete all data needs open storage")
        return DefaultDataPurgeManager(
            context = application,
            database = current,
            databaseKeyProvider = databaseKeys,
            fileKeysetProvider = fileKeyset,
            encryptedFileStore = encryptedFiles,
            dataStore = dataStore,
            wipers = wipers
        ).deleteAllData()
    }

    companion object {
        /**
         * True only when every screening result comes from the pipeline and the triage engine (package E1,
         * BUG-2026-001). `ScreeningWiringGuardTest` fails if it is true while a made-up estimate or a stand-in photo is
         * still in `MainActivity`. It does not switch screening on: that is the signed config's `triageEnabled`.
         */
        const val SCREENING_PIPELINE_WIRED = true

        const val DEVICE_KEY_ALIAS_PREFIX = "neogold_device_key_"
        const val EXPORT_STAGING_DIR = "export_staging"
        const val CRASH_REPORTS_DIR = "crash_reports"
    }
}

/**
 * The card a research session works with. The card of the phone's active device profile is the one the health worker was
 * qualified on, so it is used whatever its date: if it has expired the record says EXPIRED (TC-CARD-05), and a different
 * card is never named in its place. Without a profile card, the first stored card (newest first) whose signature verified and
 * that has not expired, else the first whose signature verified. A card whose signature did not verify is never chosen.
 */
internal fun chooseResearchCard(
    profileCard: CardBatchEntity?,
    others: List<CardBatchEntity>,
    now: Long
): CardBatchEntity? {
    if (profileCard != null && profileCard.signatureValid == 1) return profileCard
    val signed = others.filter { it.signatureValid == 1 }
    return signed.firstOrNull { !cardHasExpired(it, now) } ?: signed.firstOrNull()
}

/**
 * Whether a stored card has expired. `expiresAt` is the start of the last valid day (UTC), and the card trust verifier
 * accepts a card through that whole day, so the card has expired once a full day has passed since then.
 */
internal fun cardHasExpired(card: CardBatchEntity, now: Long): Boolean = now >= card.expiresAt + MILLIS_PER_DAY

private const val MILLIS_PER_DAY = 86_400_000L

/** What R02 and the session record say about a card, from the verifier's answer (the same mapping the save uses). */
internal fun cardStatusOf(result: VerificationResult<*>): CardTrustStatus = when (result) {
    is VerificationResult.Verified<*> -> CardTrustStatus.VERIFIED
    is VerificationResult.Expired -> CardTrustStatus.EXPIRED
    else -> CardTrustStatus.UNVERIFIED
}
