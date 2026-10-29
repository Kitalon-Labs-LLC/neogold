package com.kitalonlabs.neogold.core.security.pin

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.kitalonlabs.neogold.core.security.KeystoreLossException
import java.io.IOException
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.security.ProviderException
import javax.crypto.KeyGenerator
import javax.crypto.Mac
import javax.crypto.SecretKey

/**
 * The device-bound HMAC key K9 that binds a research PIN hash to this phone (doc 8 Section 4 and 6.2).
 *
 * Every failure to reach K9 is a [KeystoreLossException]. There is no software fallback: a key that is published in the
 * source tree would let anyone brute-force a copied database off the phone (BUG-2026-011).
 */
interface PinHmacKeyProvider {
    /**
     * HMAC-SHA256 of [data] under K9. Creates K9 on first use, which is when a PIN is set.
     *
     * @throws KeystoreLossException when the Keystore cannot be reached or refuses the key
     */
    fun computeHmac(data: ByteArray): ByteArray

    /**
     * True when K9 is in the Keystore. A stored PIN hash without its key means the key was lost: the PIN is not wrong,
     * it can no longer be checked, and a coordinator has to enroll the phone again.
     *
     * @throws KeystoreLossException when the Keystore cannot be reached
     */
    fun hasKey(): Boolean

    /** Removes K9, when all data is deleted or the phone leaves a study. Does nothing when the key is absent. */
    fun deleteKey()
}

/**
 * Android Keystore implementation of K9: a non-exportable HMAC-SHA256 key (doc 8 Section 6.2).
 *
 * @param openKeyStore opens the key store; tests pass one that fails, to show that nothing falls back to a software key
 */
class KeystorePinHmacKeyProvider(
    private val keyAlias: String = KEY_ALIAS_DEFAULT,
    private val openKeyStore: () -> KeyStore = { KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) } }
) : PinHmacKeyProvider {

    companion object {
        const val KEY_ALIAS_DEFAULT = "neogold_pin_hmac_k9"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val HMAC_ALGORITHM = "HmacSHA256"
    }

    override fun computeHmac(data: ByteArray): ByteArray = keystoreCall("compute the PIN verifier") {
        val mac = Mac.getInstance(HMAC_ALGORITHM)
        mac.init(getOrCreateKey())
        mac.doFinal(data)
    }

    override fun hasKey(): Boolean = keystoreCall("look for the PIN verifier key") {
        openKeyStore().containsAlias(keyAlias)
    }

    override fun deleteKey() = keystoreCall("delete the PIN verifier key") {
        val keyStore = openKeyStore()
        if (keyStore.containsAlias(keyAlias)) {
            keyStore.deleteEntry(keyAlias)
        }
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = openKeyStore()
        if (keyStore.containsAlias(keyAlias)) {
            return keyStore.getKey(keyAlias, null) as? SecretKey
                ?: throw KeystoreLossException("Key '$keyAlias' is not an HMAC key")
        }
        val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_HMAC_SHA256, ANDROID_KEYSTORE)
        keyGenerator.init(KeyGenParameterSpec.Builder(keyAlias, KeyProperties.PURPOSE_SIGN).build())
        return keyGenerator.generateKey()
    }

    /**
     * Runs [block] and turns every way the Keystore can fail into a [KeystoreLossException]. Anything else, such as a
     * cancelled coroutine or a programming error, passes through untouched.
     */
    private inline fun <T> keystoreCall(what: String, block: () -> T): T {
        try {
            return block()
        } catch (e: GeneralSecurityException) {
            throw KeystoreLossException("Could not $what", e)
        } catch (e: ProviderException) {
            throw KeystoreLossException("Could not $what", e)
        } catch (e: IOException) {
            throw KeystoreLossException("Could not $what", e)
        }
    }
}
