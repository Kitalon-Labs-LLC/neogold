package com.kitalonlabs.neogold.core.security.pin

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * An HMAC key provider for JVM tests (TC-RSH-06). Its key is in the source tree, so it must never reach production
 * code: `TestDoublesStayOutOfProductionTest` in `:app` fails if a `src/main` file other than this one names it.
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
    }

    private var deleted = false

    override fun computeHmac(data: ByteArray): ByteArray {
        deleted = false
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(keyBytes, "HmacSHA256"))
        return mac.doFinal(data)
    }

    override fun hasKey(): Boolean = !deleted

    override fun deleteKey() {
        deleted = true
    }
}
