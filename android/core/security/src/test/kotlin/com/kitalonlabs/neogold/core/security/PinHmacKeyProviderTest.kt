package com.kitalonlabs.neogold.core.security

import com.google.common.truth.Truth.assertThat
import com.kitalonlabs.neogold.core.security.pin.Argon2PinHasher
import com.kitalonlabs.neogold.core.security.pin.FakePinHmacKeyProvider
import com.kitalonlabs.neogold.core.security.pin.KeystorePinHmacKeyProvider
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.IOException
import java.security.KeyStore
import java.security.KeyStoreException
import java.security.ProviderException
import java.security.UnrecoverableKeyException

/**
 * K9 binds the PIN hash to the phone (TC-RSH-06). It used to fall back to a key that is published in the source tree
 * on any Keystore error, which made the binding void exactly when the Keystore misbehaved (BUG-2026-011). These tests
 * show that every way the Keystore can fail now stops with a [KeystoreLossException] and produces no hash at all.
 */
@RunWith(RobolectricTestRunner::class)
class PinHmacKeyProviderTest {

    private fun brokenKeystore(failure: Exception): () -> KeyStore = { throw failure }

    private val keystoreFailures = listOf(
        KeyStoreException("the Keystore daemon is not running"),
        IOException("the Keystore could not be loaded"),
        ProviderException("the secure hardware refused the request"),
        UnrecoverableKeyException("the key cannot be recovered")
    )

    @Test
    fun everyKeystoreFailure_stopsComputingTheHmac_TC_RSH_06() {
        for (failure in keystoreFailures) {
            val provider = KeystorePinHmacKeyProvider(openKeyStore = brokenKeystore(failure))

            val thrown = assertThrows(KeystoreLossException::class.java) {
                provider.computeHmac(byteArrayOf(1, 2, 3))
            }

            assertThat(thrown.cause).isSameInstanceAs(failure)
        }
    }

    @Test
    fun everyKeystoreFailure_stopsTheKeyLookupAndTheDelete_TC_RSH_06() {
        for (failure in keystoreFailures) {
            val provider = KeystorePinHmacKeyProvider(openKeyStore = brokenKeystore(failure))

            assertThrows(KeystoreLossException::class.java) { provider.hasKey() }
            assertThrows(KeystoreLossException::class.java) { provider.deleteKey() }
        }
    }

    @Test
    fun aProgrammingErrorIsNotDisguisedAsAKeyLoss() {
        val provider = KeystorePinHmacKeyProvider(
            openKeyStore = { throw IllegalStateException("a bug, not a lost key") }
        )

        assertThrows(IllegalStateException::class.java) { provider.computeHmac(byteArrayOf(1)) }
    }

    @Test
    fun hashingAPin_withAKeystoreThatFails_producesNoHash_TC_RSH_06() {
        val hasher = Argon2PinHasher(
            hmacKeyProvider = KeystorePinHmacKeyProvider(openKeyStore = brokenKeystore(KeyStoreException("down")))
        )

        assertThrows(KeystoreLossException::class.java) {
            hasher.hashPin("482915", hasher.generateSalt())
        }
    }

    @Test
    fun checkingAPin_whoseKeyIsGone_isAKeyLossAndNotAWrongPin_TC_RSH_06() {
        val keyProvider = FakePinHmacKeyProvider()
        val hasher = Argon2PinHasher(hmacKeyProvider = keyProvider)
        val salt = hasher.generateSalt()
        val stored = hasher.hashPin("482915", salt)
        assertThat(hasher.verifyPin("482915", salt, stored)).isTrue()

        keyProvider.deleteKey()

        assertThrows(KeystoreLossException::class.java) { hasher.verifyPin("482915", salt, stored) }
    }

    @Test
    fun settingANewPin_afterTheKeyWasLost_makesANewKey() {
        val keyProvider = FakePinHmacKeyProvider()
        val hasher = Argon2PinHasher(hmacKeyProvider = keyProvider)
        keyProvider.deleteKey()
        val salt = hasher.generateSalt()

        val stored = hasher.hashPin("739204", salt)

        assertThat(keyProvider.hasKey()).isTrue()
        assertThat(hasher.verifyPin("739204", salt, stored)).isTrue()
    }
}
