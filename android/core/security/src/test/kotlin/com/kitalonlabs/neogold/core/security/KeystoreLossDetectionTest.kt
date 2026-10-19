package com.kitalonlabs.neogold.core.security

import android.content.Context
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File
import org.junit.Assert.assertThrows

@RunWith(RobolectricTestRunner::class)
class KeystoreLossDetectionTest {

    private lateinit var context: Context
    private lateinit var keyDir: File
    private lateinit var storageDir: File
    private lateinit var fakeMasterKeyProvider: FakeMasterKeyProvider
    private lateinit var databaseKeyProvider: DatabaseKeyProvider
    private lateinit var fileKeysetProvider: FileKeysetProvider
    private lateinit var encryptedFileStore: EncryptedFileStore
    private lateinit var recoveryManager: KeystoreRecoveryManager

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        keyDir = File(context.filesDir, "sec_test_keys")
        storageDir = File(context.filesDir, "sec_test_storage")
        keyDir.mkdirs()
        storageDir.mkdirs()

        fakeMasterKeyProvider = FakeMasterKeyProvider()
        databaseKeyProvider = DefaultDatabaseKeyProvider(context, fakeMasterKeyProvider, keyDir)
        fileKeysetProvider = DefaultFileKeysetProvider(context, fakeMasterKeyProvider, keyDir)
        encryptedFileStore = DefaultEncryptedFileStore(context, fileKeysetProvider, storageDir)
        recoveryManager = DefaultKeystoreRecoveryManager(
            context = context,
            databaseKeyProvider = databaseKeyProvider,
            fileKeysetProvider = fileKeysetProvider,
            encryptedFileStore = encryptedFileStore,
            databaseName = "test_neogold.db"
        )
    }

    @After
    fun tearDown() {
        recoveryManager.resetEncryptedStores()
        keyDir.deleteRecursively()
        storageDir.deleteRecursively()
    }

    @Test
    fun keystoreLossThrowsTypedException_whenMasterKeyIsDeleted() {
        // Initialize keys and write files
        val key = databaseKeyProvider.getRawKey()
        assertThat(key).isNotEmpty()
        encryptedFileStore.writeEncryptedFile("sample.jpg") { it.write("SAMPLE_IMAGE".toByteArray()) }

        assertThat(recoveryManager.checkKeystoreHealth()).isEqualTo(KeystoreHealthStatus.Healthy)

        // Simulate Keystore loss: delete master keys while wrapped files remain on disk (TC-SEC-07)
        fakeMasterKeyProvider.deleteMasterKey(MasterKeyProvider.ALIAS_DB_MASTER)

        // Verifying DatabaseKeyProvider throws typed KeystoreLossException
        val provider2 = DefaultDatabaseKeyProvider(context, fakeMasterKeyProvider, keyDir)
        val thrown = assertThrows(KeystoreLossException::class.java) {
            provider2.getRawKey()
        }
        assertThat(thrown.message).contains("missing from Keystore")

        // Recovery manager detects loss with typed message
        val health = recoveryManager.checkKeystoreHealth()
        assertThat(health).isInstanceOf(KeystoreHealthStatus.LossDetected::class.java)
        val loss = health as KeystoreHealthStatus.LossDetected
        assertThat(loss.reason).contains("missing from Keystore")
    }

    @Test
    fun resetEncryptedStores_resetsStateAndAllowsAppToContinueWorking() {
        // Create initial encrypted data
        databaseKeyProvider.getRawKey()
        encryptedFileStore.writeEncryptedFile("img1.jpg") { it.write("DATA".toByteArray()) }

        // Simulate Keystore loss
        fakeMasterKeyProvider.deleteMasterKey(MasterKeyProvider.ALIAS_DB_MASTER)
        fakeMasterKeyProvider.deleteMasterKey(MasterKeyProvider.ALIAS_FILE_MASTER)

        // Reset encrypted stores as specified in doc 8 Section 5.1
        recoveryManager.resetEncryptedStores()

        // Verify stores were cleaned
        assertThat(databaseKeyProvider.hasWrappedKey()).isFalse()
        assertThat(fileKeysetProvider.hasWrappedKeyset()).isFalse()
        assertThat(encryptedFileStore.exists("img1.jpg")).isFalse()

        // Verify app can generate fresh keys and continue functioning without error
        val freshKey = databaseKeyProvider.getRawKey()
        assertThat(freshKey.size).isEqualTo(32)

        encryptedFileStore.writeEncryptedFile("fresh_img.jpg") { it.write("NEW_DATA".toByteArray()) }
        assertThat(encryptedFileStore.exists("fresh_img.jpg")).isTrue()

        val checkHealth = recoveryManager.checkKeystoreHealth()
        assertThat(checkHealth).isEqualTo(KeystoreHealthStatus.Healthy)
    }
}
