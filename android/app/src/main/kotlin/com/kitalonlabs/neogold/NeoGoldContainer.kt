package com.kitalonlabs.neogold

import android.app.Application
import android.content.pm.ApplicationInfo
import com.kitalonlabs.neogold.core.data.datastore.DefaultNeoGoldDataStore
import com.kitalonlabs.neogold.core.data.datastore.NeoGoldDataStore
import com.kitalonlabs.neogold.core.data.local.NeoGoldDatabase
import com.kitalonlabs.neogold.core.data.purge.DataWiper
import com.kitalonlabs.neogold.core.data.purge.DefaultDataPurgeManager
import com.kitalonlabs.neogold.core.data.purge.PurgeResult
import com.kitalonlabs.neogold.core.data.retention.BackgroundWork
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
import com.kitalonlabs.neogold.core.security.pin.KeystorePinHmacKeyProvider
import com.kitalonlabs.neogold.core.security.pin.PinHmacKeyProvider
import com.kitalonlabs.neogold.core.security.pin.ResearchSessionManager
import com.kitalonlabs.neogold.integrity.VerifiedAssets
import com.kitalonlabs.neogold.storage.StorageCoordinator
import com.kitalonlabs.neogold.storage.StorageState
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
     * Until package E1 replaces the placeholder S19 and S20 save path, the second condition is false whatever the
     * config says: a signed `triageEnabled: true` would otherwise show every baby a made-up "no further action".
     */
    fun screeningAvailable(today: LocalDate = LocalDate.now()): Boolean =
        SCREENING_PIPELINE_WIRED && assets.triageAvailable(today, isPublicBuild = !isDebuggable)

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
        encryptedFileStore = encryptedFiles
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
         * True only when S19 runs the real pipeline and the triage engine. It is false while the screens show a
         * placeholder result (BUG-2026-001). `ScreeningWiringGuardTest` fails if this is flipped while the
         * placeholder is still in `MainActivity`.
         */
        const val SCREENING_PIPELINE_WIRED = false

        const val DEVICE_KEY_ALIAS_PREFIX = "neogold_device_key_"
        const val EXPORT_STAGING_DIR = "export_staging"
        const val CRASH_REPORTS_DIR = "crash_reports"
    }
}
