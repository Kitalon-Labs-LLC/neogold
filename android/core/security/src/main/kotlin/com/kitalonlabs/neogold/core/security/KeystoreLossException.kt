package com.kitalonlabs.neogold.core.security

import java.security.GeneralSecurityException

/**
 * Typed exception thrown when an Android Keystore key is missing, invalidated,
 * or corrupted, preventing decryption of local encrypted stores (K7 database key or K8 file keyset).
 *
 * As specified in doc 8 Section 5.1 and TC-SEC-07:
 * If a Keystore key disappears, decryption fails with this typed exception.
 * The app then presents a clear message, resets encrypted stores, and continues operating.
 */
class KeystoreLossException(
    message: String,
    cause: Throwable? = null
) : GeneralSecurityException(message, cause)
