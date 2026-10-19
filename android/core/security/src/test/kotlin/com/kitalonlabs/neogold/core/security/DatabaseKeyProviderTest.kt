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

@RunWith(RobolectricTestRunner::class)
class DatabaseKeyProviderTest {

    private lateinit var context: Context
    private lateinit var keyDir: File
    private lateinit var masterKeyProvider: MasterKeyProvider
    private lateinit var databaseKeyProvider: DatabaseKeyProvider

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        keyDir = File(context.filesDir, "test_security")
        keyDir.mkdirs()
        masterKeyProvider = FakeMasterKeyProvider()
        databaseKeyProvider = DefaultDatabaseKeyProvider(context, masterKeyProvider, keyDir)
    }

    @After
    fun tearDown() {
        databaseKeyProvider.deleteKey()
        keyDir.deleteRecursively()
    }

    @Test
    fun getRawKey_returns32BytesRandomKey() {
        val raw1 = databaseKeyProvider.getRawKey()
        assertThat(raw1.size).isEqualTo(32)

        // Reading again should return identical key
        val raw2 = databaseKeyProvider.getRawKey()
        assertThat(raw1).isEqualTo(raw2)
    }

    @Test
    fun getSqlCipherPassphrase_formatsAsRawHex67Bytes() {
        val passphrase = databaseKeyProvider.getSqlCipherPassphrase()
        val passphraseStr = String(passphrase, Charsets.UTF_8)

        // Raw-key form: x'<64 hex characters>' -> total length 67 chars
        assertThat(passphraseStr).startsWith("x'")
        assertThat(passphraseStr).endsWith("'")
        assertThat(passphraseStr.length).isEqualTo(67)

        val hexPart = passphraseStr.substring(2, 66)
        assertThat(hexPart.matches(Regex("^[0-9a-f]{64}$"))).isTrue()
    }

    @Test
    fun persistenceAcrossProviderInstances() {
        val raw1 = databaseKeyProvider.getRawKey()

        // Create new provider instance pointing to same file
        val provider2 = DefaultDatabaseKeyProvider(context, masterKeyProvider, keyDir)
        val raw2 = provider2.getRawKey()

        assertThat(raw1).isEqualTo(raw2)
    }

    @Test
    fun deleteKey_removesWrappedFile() {
        databaseKeyProvider.getRawKey()
        assertThat(databaseKeyProvider.hasWrappedKey()).isTrue()

        databaseKeyProvider.deleteKey()
        assertThat(databaseKeyProvider.hasWrappedKey()).isFalse()
    }
}
