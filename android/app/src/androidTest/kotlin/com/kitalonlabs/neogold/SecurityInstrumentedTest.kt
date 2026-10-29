package com.kitalonlabs.neogold

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import com.kitalonlabs.neogold.core.data.local.NeoGoldDatabase
import com.kitalonlabs.neogold.core.data.local.entity.ScreeningSessionEntity
import com.kitalonlabs.neogold.core.security.DatabaseKeyProvider
import com.kitalonlabs.neogold.core.security.KeystoreHealthStatus
import com.kitalonlabs.neogold.core.security.KeystoreLossException
import com.kitalonlabs.neogold.core.security.MasterKeyProvider
import com.kitalonlabs.neogold.core.security.integrity.BundleManifestVerifier
import com.kitalonlabs.neogold.core.security.integrity.IntegrityResult
import com.kitalonlabs.neogold.core.security.pin.Argon2PinHasher
import com.kitalonlabs.neogold.core.security.pin.KeystorePinHmacKeyProvider
import com.kitalonlabs.neogold.integrity.VerifiedAssets
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.File
import java.security.KeyStore
import java.util.UUID
import javax.crypto.KeyGenerator

/**
 * The security wiring on a real phone or emulator, with the real Android Keystore, SQLCipher and the app's own
 * container. The JVM tests cover the logic; these show it works against the platform (TC-LOG-11, TC-SEC-07,
 * TC-SEC-09, TC-RSH-06). Run by hand: `adb shell am instrument -w` with the debug test APK.
 */
@RunWith(AndroidJUnit4::class)
class SecurityInstrumentedTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val container: NeoGoldContainer
        get() = (context.applicationContext as NeoGoldApplication).container

    private fun keyStore(): KeyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    private fun session() = ScreeningSessionEntity(
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
        registerNumber = "OPD-7",
        registerNumberErasedAt = null,
        participantId = null,
        estimateHidden = 0,
        experimentalLabelShown = 1,
        deviceProfileId = null,
        cardBatchId = null,
        appVersion = "1.0.0",
        modelVersion = null,
        configVersion = "test"
    )

    private fun makeDeviceKey(alias: String) {
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build()
        )
        generator.generateKey()
    }

    @Test
    fun theBundleThePhoneInstalled_isVerifiedByItsOwnKey_TC_SEC_09() {
        val assets = container.assets

        assertThat(assets.integrity).isInstanceOf(IntegrityResult.Valid::class.java)
        assertThat(assets.triageConfig).isNotNull()
        // The shipped config turns triage off (PRD D10): verified files do not switch it on.
        assertThat(assets.triageEnabled).isEqualTo(assets.triageConfig!!.triageEnabled)
    }

    @Test
    fun aChangedConfigAsset_isRefused_andTurnsEverythingOff_TC_SEC_09() {
        val verifier = BundleManifestVerifier(BuildConfig.K3_PUBLIC_KEY_HEX)
        val tampered = "{\"configVersion\":\"x\",\"triageEnabled\":true}".toByteArray()

        val assets = VerifiedAssets.load(
            verifier = verifier,
            assets = { name ->
                if (name == "triage-config.json") ByteArrayInputStream(tampered) else context.assets.open(name)
            },
            isDevelopmentKey = BuildConfig.K3_IS_DEV_KEY,
            isDebuggable = container.isDebuggable
        )

        assertThat(assets.integrity).isInstanceOf(IntegrityResult.HashMismatch::class.java)
        assertThat(assets.isUsable).isFalse()
        assertThat(assets.triageEnabled).isFalse()
    }

    @Test
    fun theHmacKeyK9_isNonExportable_isRemoved_andLeavesAPinUncheckable_TC_RSH_06() {
        val provider = KeystorePinHmacKeyProvider(keyAlias = "neogold_pin_hmac_k9_instrumented")
        provider.deleteKey()
        assertThat(provider.hasKey()).isFalse()

        val first = provider.computeHmac(byteArrayOf(1, 2, 3))
        val again = KeystorePinHmacKeyProvider(keyAlias = "neogold_pin_hmac_k9_instrumented").computeHmac(byteArrayOf(1, 2, 3))
        assertThat(provider.hasKey()).isTrue()
        assertThat(again).isEqualTo(first)
        val key = keyStore().getKey("neogold_pin_hmac_k9_instrumented", null)
        assertThat(key.encoded).isNull() // the key material never leaves the Keystore

        val hasher = Argon2PinHasher(hmacKeyProvider = provider)
        val salt = hasher.generateSalt()
        val stored = hasher.hashPin("482915", salt)
        assertThat(hasher.verifyPin("482915", salt, stored)).isTrue()

        provider.deleteKey()

        assertThat(provider.hasKey()).isFalse()
        assertThrows(KeystoreLossException::class.java) { hasher.verifyPin("482915", salt, stored) }
    }

    @Test
    fun deleteAllData_removesTheDatabaseFilesKeysAndStagingAndCrashFiles_TC_LOG_11() = runBlocking<Unit> {
        val app = container
        val database = app.storage.awaitDatabase()!!
        database.screeningSessionDao().insert(session())
        app.encryptedFiles.writeEncryptedFile("research_instrumented.enc") { it.write("IMG".toByteArray()) }
        app.dataStore.setOnboardingComplete(true)
        val pin = Argon2PinHasher(hmacKeyProvider = app.pinHmacKey)
        pin.hashPin("482915", pin.generateSalt()) // makes K9
        makeDeviceKey("${NeoGoldContainer.DEVICE_KEY_ALIAS_PREFIX}INSTRUMENTED-01")
        val staging = File(context.cacheDir, NeoGoldContainer.EXPORT_STAGING_DIR).apply { mkdirs() }
        File(staging, "checkpoint.json").writeText("keyset")
        val crashes = File(context.filesDir, NeoGoldContainer.CRASH_REPORTS_DIR).apply { mkdirs() }
        File(crashes, "last_crash.enc").writeText("report")
        assertThat(app.pinHmacKey.hasKey()).isTrue()
        assertThat(keyStore().containsAlias("${NeoGoldContainer.DEVICE_KEY_ALIAS_PREFIX}INSTRUMENTED-01")).isTrue()

        val purged = app.deleteAllData()

        assertThat(purged.incomplete).isEmpty()
        assertThat(purged.database.screeningSessionDao().getSessionsByMode("SCREENING")).isEmpty()
        assertThat(app.encryptedFiles.exists("research_instrumented.enc")).isFalse()
        assertThat(app.pinHmacKey.hasKey()).isFalse()
        assertThat(keyStore().containsAlias("${NeoGoldContainer.DEVICE_KEY_ALIAS_PREFIX}INSTRUMENTED-01")).isFalse()
        assertThat(staging.exists()).isFalse()
        assertThat(crashes.exists()).isFalse()
        assertThat(app.dataStore.onboardingComplete.first()).isFalse()

        purged.database.close()
        app.storage.open()
    }

    /** A database key made of one repeated byte, in the raw-key syntax SQLCipher takes: x'<64 hex characters>'. */
    private fun fixedKey(byte: Int) = object : DatabaseKeyProvider {
        private val passphrase = "x'${"%02x".format(byte).repeat(32)}'".toByteArray(Charsets.UTF_8)
        override fun getSqlCipherPassphrase() = passphrase.copyOf()
        override fun getRawKey() = ByteArray(32) { byte.toByte() }
        override fun verifyKeyDecryption() = Unit
        override fun deleteKey() = Unit
        override fun hasWrappedKey() = true
    }

    @Test
    fun aDatabaseOpenedWithTheWrongKey_failsToOpenAndTheFileIsKept_TC_SEC_07() = runBlocking<Unit> {
        val name = "wrong_key_instrumented.db"
        context.deleteDatabase(name)
        val file = context.getDatabasePath(name)

        val created = NeoGoldDatabase.buildDatabase(context, fixedKey(0x11), name)
        created.screeningSessionDao().insert(session())
        created.close()
        val sizeBefore = file.length()
        assertThat(sizeBefore).isGreaterThan(0L)

        val wrong = NeoGoldDatabase.buildDatabase(context, fixedKey(0x22), name)
        val opened = runCatching { wrong.screeningSessionDao().getSessionsByMode("SCREENING") }
        runCatching { wrong.close() }

        // The library's default would delete a file it cannot open; the user is asked first (doc 8 Section 5.1).
        assertThat(opened.isFailure).isTrue()
        assertThat(file.exists()).isTrue()
        assertThat(file.length()).isEqualTo(sizeBefore)

        val right = NeoGoldDatabase.buildDatabase(context, fixedKey(0x11), name)
        assertThat(right.screeningSessionDao().getSessionsByMode("SCREENING")).hasSize(1)
        right.close()
        context.deleteDatabase(name)
    }

    @Test
    fun aLostDatabaseMasterKey_isReportedAndNothingIsDeletedUntilTheUserResets_TC_SEC_07() = runBlocking<Unit> {
        val app = container
        val database = app.storage.awaitDatabase()!!
        database.screeningSessionDao().insert(session())
        database.close() // a lost key is found before the database is opened, so none is open when it happens
        keyStore().deleteEntry(MasterKeyProvider.ALIAS_DB_MASTER) // what a Keystore wipe does

        val health = app.keystoreRecovery.checkKeystoreHealth()

        assertThat(health).isInstanceOf(KeystoreHealthStatus.LossDetected::class.java)
        assertThat(app.databaseKeys.hasWrappedKey()).isTrue() // the encrypted key file is still there: nothing was wiped

        app.storage.resetAndOpen()

        val after = app.storage.awaitDatabase()!!
        assertThat(after.screeningSessionDao().getSessionsByMode("SCREENING")).isEmpty()
        assertThat(app.keystoreRecovery.checkKeystoreHealth()).isEqualTo(KeystoreHealthStatus.Healthy)
    }
}
