package com.kitalonlabs.neogold.core.security.integrity

import java.nio.charset.StandardCharsets

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
 * Result of startup asset integrity verification. Only [Valid] allows screening or research.
 */
sealed class IntegrityResult {
    data class Valid(val manifest: BundleManifest) : IntegrityResult()
    data class HashMismatch(val filename: String, val expectedHash: String, val actualHash: String) : IntegrityResult()
    data class InvalidSignature(val keyId: String) : IntegrityResult()
    data class MissingAsset(val filename: String) : IntegrityResult()
    data class AssetTooLarge(val filename: String) : IntegrityResult()

    /** The file passed verification but the app could not read it as what it should be. */
    data class UnusableAsset(val filename: String) : IntegrityResult()
    data object MissingManifest : IntegrityResult()
    data class MalformedManifest(val message: String) : IntegrityResult()

    /** An installed app that carries a development signing key: only a debuggable build may. */
    data object DevelopmentKeyInRelease : IntegrityResult()

    val isScreeningAllowed: Boolean
        get() = this is Valid
}

/**
 * The bundle files exactly as they were verified. Parse these bytes and no others: opening the asset a second time
 * would let a swapped file through (BUG-2026-007).
 */
class VerifiedBundle internal constructor(
    val manifest: BundleManifest,
    private val files: Map<String, ByteArray>
) {
    val names: Set<String> get() = files.keys

    /** A copy of the verified bytes of [name]. */
    fun bytes(
        name: String
    ): ByteArray = (files[name] ?: throw NoSuchElementException("$name is not in the bundle")).copyOf()

    fun text(name: String): String = String(bytes(name), StandardCharsets.UTF_8)
}

/** Either the verified bundle or the reason it was refused. */
sealed class BundleLoadResult {
    class Loaded(val bundle: VerifiedBundle) : BundleLoadResult()
    data class Rejected(val reason: IntegrityResult) : BundleLoadResult()

    fun toIntegrityResult(): IntegrityResult = when (this) {
        is Loaded -> IntegrityResult.Valid(bundle.manifest)
        is Rejected -> reason
    }
}

fun interface IntegrityChecker {
    fun isScreeningAllowed(): Boolean
}
