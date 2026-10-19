package com.kitalonlabs.neogold.core.data.purge

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.google.common.truth.Truth.assertThat
import com.kitalonlabs.neogold.core.data.datastore.DefaultNeoGoldDataStore
import com.kitalonlabs.neogold.core.data.datastore.NeoGoldDataStore
import com.kitalonlabs.neogold.core.data.local.NeoGoldDatabase
import com.kitalonlabs.neogold.core.data.local.entity.ScreeningSessionEntity
import com.kitalonlabs.neogold.core.security.DatabaseKeyProvider
import com.kitalonlabs.neogold.core.security.DefaultDatabaseKeyProvider
import com.kitalonlabs.neogold.core.security.DefaultEncryptedFileStore
import com.kitalonlabs.neogold.core.security.DefaultFileKeysetProvider
import com.kitalonlabs.neogold.core.security.EncryptedFileStore
import com.kitalonlabs.neogold.core.security.FakeMasterKeyProvider
import com.kitalonlabs.neogold.core.security.FileKeysetProvider
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File
import java.util.UUID

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class DataPurgeManagerTest {

    private lateinit var context: Context
    private lateinit var database: NeoGoldDatabase
    private lateinit var databaseKeyProvider: DatabaseKeyProvider
    private lateinit var fileKeysetProvider: FileKeysetProvider
    private lateinit var encryptedFileStore: EncryptedFileStore
    private lateinit var dataStore: NeoGoldDataStore
    private lateinit var purgeManager: DataPurgeManager

    private lateinit var keyDir: File
    private lateinit var fileKeyDir: File
    private lateinit var imageDir: File
    private lateinit var dataStoreFile: File

    private val testDispatcher = UnconfinedTestDispatcher()
    private val testScope = TestScope(testDispatcher)

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()

        keyDir = File(context.filesDir, "purge_test_db_keys")
        fileKeyDir = File(context.filesDir, "purge_test_file_keys")
        imageDir = File(context.filesDir, "purge_test_images")
        dataStoreFile = File(context.filesDir, "purge_test.preferences_pb")

        keyDir.mkdirs()
        fileKeyDir.mkdirs()
        imageDir.mkdirs()
        dataStoreFile.delete()

        val masterKeyProvider = FakeMasterKeyProvider()
        databaseKeyProvider = DefaultDatabaseKeyProvider(context, masterKeyProvider, keyDir)
        fileKeysetProvider = DefaultFileKeysetProvider(context, masterKeyProvider, fileKeyDir)
        encryptedFileStore = DefaultEncryptedFileStore(context, fileKeysetProvider, imageDir)

        val rawPrefs = PreferenceDataStoreFactory.create(
            scope = testScope,
            produceFile = { dataStoreFile }
        )
        dataStore = DefaultNeoGoldDataStore(rawPrefs)

        val helperFactory = androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory()
        databaseKeyProvider.getSqlCipherPassphrase()

        database = NeoGoldDatabase.buildDatabase(
            context = context,
            databaseKeyProvider = databaseKeyProvider,
            databaseName = "purge_test.db",
            openHelperFactory = helperFactory
        )

        purgeManager = DefaultDataPurgeManager(
            context = context,
            database = database,
            databaseKeyProvider = databaseKeyProvider,
            fileKeysetProvider = fileKeysetProvider,
            encryptedFileStore = encryptedFileStore,
            dataStore = dataStore,
            databaseName = "purge_test.db",
            openHelperFactory = helperFactory
        )
    }

    @After
    fun tearDown() {
        if (database.isOpen) database.close()
        keyDir.deleteRecursively()
        fileKeyDir.deleteRecursively()
        imageDir.deleteRecursively()
        dataStoreFile.delete()
        context.deleteDatabase("purge_test.db")
    }

    @Test
    fun tcLog11_deleteAllData_wipesStoresAndRecreatesEmptyWithAudit() = runTest(testDispatcher) {
        // 1. Populate data: session in database, encrypted file, preferences
        val session = ScreeningSessionEntity(
            id = UUID.randomUUID().toString(),
            mode = "SCREENING",
            status = "COMPLETED",
            createdAt = System.currentTimeMillis(),
            completedAt = System.currentTimeMillis(),
            ageHours = 36.0,
            guardianConsentConfirmed = 1,
            gestationalAgeWeeks = 38,
            flagJaundiceUnder24h = 0,
            flagDangerSigns = 0,
            flagPriorPhototherapy = 0,
            ruleOutcome = null,
            triageAction = "NO_FURTHER_ACTION_FROM_SCREEN",
            reasonCodes = null,
            estimateMgDl = 7.0,
            estimateLowMgDl = 5.5,
            estimateHighMgDl = 8.5,
            thresholdMgDl = 15.0,
            recheckDueAt = null,
            registerNumber = "OPD-123",
            registerNumberErasedAt = null,
            participantId = null,
            estimateHidden = 0,
            experimentalLabelShown = 0,
            deviceProfileId = null,
            cardBatchId = null,
            appVersion = "1.0.0",
            modelVersion = "1.0.0",
            configVersion = "1.0.0"
        )
        database.screeningSessionDao().insert(session)

        val imageFileName = "research_sample.enc"
        encryptedFileStore.writeEncryptedFile(imageFileName) { it.write("IMG_DATA".toByteArray()) }
        assertThat(encryptedFileStore.exists(imageFileName)).isTrue()

        dataStore.setOnboardingComplete(true)
        dataStore.setAppLockEnabled(true)
        assertThat(dataStore.onboardingComplete.first()).isTrue()
        assertThat(dataStore.appLockEnabled.first()).isTrue()

        assertThat(databaseKeyProvider.hasWrappedKey()).isTrue()
        assertThat(fileKeysetProvider.hasWrappedKeyset()).isTrue()

        // 2. Perform Delete All Data (FR-33)
        val freshDb = purgeManager.deleteAllData()

        // 3. Verify files and preferences wiped
        assertThat(encryptedFileStore.exists(imageFileName)).isFalse()
        assertThat(dataStore.onboardingComplete.first()).isFalse() // reset to default
        assertThat(dataStore.appLockEnabled.first()).isFalse()

        // 4. Verify fresh database is empty of past screening records
        val remainingSessions = freshDb.screeningSessionDao().getSessionsByMode("SCREENING")
        assertThat(remainingSessions).isEmpty()

        // 5. Verify DATA_DELETED was audited in the fresh database
        val auditEvents = freshDb.auditEventDao().getEventsByType("DATA_DELETED")
        assertThat(auditEvents).hasSize(1)
        assertThat(auditEvents[0].details).contains("USER_ACTION_FR_33")

        freshDb.close()
    }
}
