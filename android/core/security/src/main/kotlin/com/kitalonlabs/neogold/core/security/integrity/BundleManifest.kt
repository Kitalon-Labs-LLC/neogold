package com.kitalonlabs.neogold.core.security.integrity

/**
 * Parsed representation of APK assets bundle-manifest.json (doc 4 Section 12).
 */
data class BundleManifest(
    val formatVersion: Int,
    val manifestVersion: String,
    val keyId: String,
    val signerPublicKeyHex: String,
    val files: Map<String, String>,
    val signature: String
)

/**
 * Result of startup asset integrity verification.
 */
sealed class IntegrityResult {
    data class Valid(val manifest: BundleManifest) : IntegrityResult()
    data class HashMismatch(val filename: String, val expectedHash: String, val actualHash: String) : IntegrityResult()
    data class InvalidSignature(val keyId: String) : IntegrityResult()
    data class MissingAsset(val filename: String) : IntegrityResult()
    data object MissingManifest : IntegrityResult()
    data class MalformedManifest(val message: String) : IntegrityResult()

    val isScreeningAllowed: Boolean
        get() = this is Valid
}

fun interface IntegrityChecker {
    fun isScreeningAllowed(): Boolean
}
