package com.kitalonlabs.neogold.core.security.pin

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import timber.log.Timber
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.Mac
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec

interface PinHmacKeyProvider {
    /**
     * Computes HMAC-SHA256 of the given data using the device-bound key.
     */
    fun computeHmac(data: ByteArray): ByteArray
}

/**
 * Android Keystore implementation of K9 non-exportable HMAC-SHA256 key (doc 8 Section 6.2).
 */
class KeystorePinHmacKeyProvider(
    private val keyAlias: String = KEY_ALIAS_DEFAULT
) : PinHmacKeyProvider {

    companion object {
        const val KEY_ALIAS_DEFAULT = "neogold_pin_hmac_k9"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val HMAC_ALGORITHM = "HmacSHA256"
    }

    private val secretKey: SecretKey by lazy {
        getOrCreateKey()
    }

    @Suppress("TooGenericExceptionCaught") // BUG-2026-011 (S1): falls back to a published key on any error; fix in B1
    private fun getOrCreateKey(): SecretKey {
        return try {
            val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            if (keyStore.containsAlias(keyAlias)) {
                keyStore.getKey(keyAlias, null) as? SecretKey
                    ?: error("Key for alias $keyAlias is not a SecretKey")
            } else {
                val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_HMAC_SHA256, ANDROID_KEYSTORE)
                val spec = KeyGenParameterSpec.Builder(
                    keyAlias,
                    KeyProperties.PURPOSE_SIGN
                ).build()
                keyGenerator.init(spec)
                keyGenerator.generateKey()
            }
        } catch (e: Exception) {
            Timber.w(e, "AndroidKeyStore not available for HMAC, falling back to software provider")
            // Meant for Robolectric, but it also runs on a device when the key store fails (BUG-2026-011, fixed in B1)
            FakePinHmacKeyProvider.STATIC_KEY
        }
    }

    override fun computeHmac(data: ByteArray): ByteArray {
        val mac = Mac.getInstance(HMAC_ALGORITHM)
        mac.init(secretKey)
        return mac.doFinal(data)
    }
}

/**
 * Test/mock HMAC key provider for JVM and test verification (e.g. TC-RSH-06).
 */
class FakePinHmacKeyProvider(
    private val keyBytes: ByteArray = DEFAULT_TEST_KEY
) : PinHmacKeyProvider {

    companion object {
        val DEFAULT_TEST_KEY = byteArrayOf(
            0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08,
            0x09, 0x0A, 0x0B, 0x0C, 0x0D, 0x0E, 0x0F, 0x10,
            0x11, 0x12, 0x13, 0x14, 0x15, 0x16, 0x17, 0x18,
            0x19, 0x1A, 0x1B, 0x1C, 0x1D, 0x1E, 0x1F, 0x20
        )
        val STATIC_KEY: SecretKey = SecretKeySpec(DEFAULT_TEST_KEY, "HmacSHA256")
    }

    private val secretKey: SecretKey = SecretKeySpec(keyBytes, "HmacSHA256")

    override fun computeHmac(data: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(secretKey)
        return mac.doFinal(data)
    }
}
