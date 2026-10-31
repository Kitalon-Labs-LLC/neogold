package com.kitalonlabs.neogold.core.security.integrity

import android.content.Context
import com.google.crypto.tink.subtle.Ed25519Verify
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.security.GeneralSecurityException
import java.security.MessageDigest
import java.util.Base64
import java.util.Locale

/**
 * Verifies the integrity of the clinical bundle assets (model.json, triage-config.json, trust-list.json) against the
 * K3-signed bundle-manifest.json (docs/4_System_Architecture.md Section 12, docs/8_Security_Compliance_Plan.md
 * Section 6.5).
 *
 * The key is the one the build was signed with, taken from BuildConfig; there is no default, so no code path can trust
 * a key that nobody chose (BUG-2026-008).
 *
 * [load] reads each file once and hashes those bytes, and hands the same bytes to the caller. Verifying a file and then
 * opening it again to parse it would leave a gap in which the file could be swapped (BUG-2026-007).
 */
class BundleManifestVerifier(
    private val trustedK3PublicKeyHex: String
) {

    companion object {
        const val MANIFEST_FILE_NAME = "bundle-manifest.json"

        /** The bundle's files are a few hundred kilobytes; a larger one is not the file that was signed. */
        const val MAX_ASSET_BYTES = 8 * 1024 * 1024

        private const val MAX_MANIFEST_BYTES = 64 * 1024
        private const val ED25519_KEY_BYTES = 32
        private const val ED25519_KEY_HEX_LENGTH = ED25519_KEY_BYTES * 2
        private const val HEX_RADIX = 16
        private const val HEX_DIGIT_BITS = 4
        private const val BYTE_MASK = 0xFF
        private const val READ_BUFFER_BYTES = 8192

        val REQUIRED_FILES = listOf(
            "model.json",
            "triage-config.json",
            "trust-list.json"
        )

        fun hexToBytes(hex: String): ByteArray {
            val clean = hex.trim()
            require(clean.length == ED25519_KEY_HEX_LENGTH) {
                "Ed25519 public key hex must be $ED25519_KEY_HEX_LENGTH characters, got ${clean.length}"
            }
            val bytes = ByteArray(ED25519_KEY_BYTES)
            for (i in 0 until ED25519_KEY_BYTES) {
                val high = Character.digit(clean[i * 2], HEX_RADIX)
                val low = Character.digit(clean[i * 2 + 1], HEX_RADIX)
                require(high != -1 && low != -1) { "Invalid hex character in key" }
                bytes[i] = ((high shl HEX_DIGIT_BITS) or low).toByte()
            }
            return bytes
        }

        fun canonicalSignPayload(fileHashes: Map<String, String>): ByteArray {
            val lines = fileHashes.keys.sorted().map { name -> "$name:${fileHashes[name]}" }
            return (lines.joinToString("\n") + "\n").toByteArray(StandardCharsets.UTF_8)
        }

        fun computeSha256(bytes: ByteArray): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
            val sb = StringBuilder(digest.size * 2)
            for (b in digest) {
                sb.append(String.format(Locale.ROOT, "%02x", b.toInt() and BYTE_MASK))
            }
            return sb.toString()
        }
    }

    private val trustedKeyBytes: ByteArray by lazy {
        hexToBytes(trustedK3PublicKeyHex)
    }

    /** Verifies the assets of [context] and returns the verified bytes, or why they were refused. */
    fun load(context: Context): BundleLoadResult = load { assetName ->
        try {
            context.assets.open(assetName)
        } catch (_: IOException) {
            null
        }
    }

    /** Verifies the assets of [context]. Prefer [load] when the bytes are used afterwards. */
    fun verify(context: Context): IntegrityResult = load(context).toIntegrityResult()

    /** Verifies assets from any stream provider (pure tests, custom loaders). Prefer [load]. */
    fun verify(assetProvider: (String) -> InputStream?): IntegrityResult = load(assetProvider).toIntegrityResult()

    /**
     * Reads the manifest, checks its signature, then reads every listed file once, checks its hash and keeps its
     * bytes. Each failure is one [IntegrityResult]; nothing is returned unless every check passed.
     */
    @Suppress("ReturnCount") // one result per failed check, in the order doc 4 Section 12 lists them
    fun load(assetProvider: (String) -> InputStream?): BundleLoadResult {
        val manifestBytes = when (val read = readBounded(assetProvider(MANIFEST_FILE_NAME), MAX_MANIFEST_BYTES)) {
            is Read.Bytes -> read.bytes
            Read.Absent -> return BundleLoadResult.Rejected(IntegrityResult.MissingManifest)
            Read.TooLarge, Read.Failed ->
                return BundleLoadResult.Rejected(
                    IntegrityResult.MalformedManifest("Could not read $MANIFEST_FILE_NAME")
                )
        }

        val manifest = parseManifest(manifestBytes)
            ?: return BundleLoadResult.Rejected(IntegrityResult.MalformedManifest("Could not parse $MANIFEST_FILE_NAME"))

        for (required in REQUIRED_FILES) {
            if (!manifest.files.containsKey(required)) {
                return BundleLoadResult.Rejected(IntegrityResult.MissingAsset(required))
            }
        }

        if (!signatureIsValid(manifest)) {
            return BundleLoadResult.Rejected(IntegrityResult.InvalidSignature(manifest.keyId))
        }

        val verified = LinkedHashMap<String, ByteArray>()
        for ((fileName, expectedHash) in manifest.files) {
            val stream = assetProvider(fileName)
                ?: return BundleLoadResult.Rejected(IntegrityResult.MissingAsset(fileName))
            val bytes = when (val read = readBounded(stream, MAX_ASSET_BYTES)) {
                is Read.Bytes -> read.bytes
                Read.TooLarge -> return BundleLoadResult.Rejected(IntegrityResult.AssetTooLarge(fileName))
                Read.Absent, Read.Failed -> return BundleLoadResult.Rejected(IntegrityResult.UnusableAsset(fileName))
            }
            val actualHash = computeSha256(bytes)
            if (!actualHash.equals(expectedHash, ignoreCase = true)) {
                return BundleLoadResult.Rejected(IntegrityResult.HashMismatch(fileName, expectedHash, actualHash))
            }
            verified[fileName] = bytes
        }
        return BundleLoadResult.Loaded(VerifiedBundle(manifest, verified))
    }

    private sealed interface Read {
        class Bytes(val bytes: ByteArray) : Read
        data object Absent : Read
        data object TooLarge : Read
        data object Failed : Read
    }

    /**
     * All of [stream], and what went wrong when that is not possible: absent, longer than [limit] bytes, or a read error
     * (a corrupt compressed asset, a flash fault). A read error is a refusal like any other, so the app shows its block
     * screen instead of crashing at launch. The stream is closed.
     */
    private fun readBounded(stream: InputStream?, limit: Int): Read {
        if (stream == null) return Read.Absent
        return try {
            stream.use { input ->
                val out = ByteArrayOutputStream()
                val buffer = ByteArray(READ_BUFFER_BYTES)
                var total = 0
                var read = input.read(buffer)
                while (read != -1) {
                    total += read
                    if (total > limit) return@use Read.TooLarge
                    out.write(buffer, 0, read)
                    read = input.read(buffer)
                }
                Read.Bytes(out.toByteArray())
            }
        } catch (_: IOException) {
            Read.Failed
        }
    }

    @Suppress("TooGenericExceptionCaught") // the manifest is untrusted text: any parse failure is one refusal
    private fun parseManifest(bytes: ByteArray): BundleManifest? {
        return try {
            val root = JSONObject(String(bytes, StandardCharsets.UTF_8))
            val filesObj = root.getJSONObject("files")
            val fileMap = mutableMapOf<String, String>()
            val keys = filesObj.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                fileMap[k] = filesObj.getString(k)
            }
            BundleManifest(
                formatVersion = root.optInt("formatVersion", 1),
                manifestVersion = root.optString("manifestVersion", "1.0.0"),
                keyId = root.optString("keyId", "K3"),
                signerPublicKeyHex = root.optString("signerPublicKeyHex", ""),
                files = fileMap,
                signature = root.getString("signature")
            )
        } catch (_: Exception) {
            null
        }
    }

    /** A signature that cannot even be decoded, or a trusted key that is not a key, is an invalid signature. */
    private fun signatureIsValid(manifest: BundleManifest): Boolean {
        return try {
            val signatureBytes = Base64.getDecoder().decode(manifest.signature)
            Ed25519Verify(trustedKeyBytes).verify(signatureBytes, canonicalSignPayload(manifest.files))
            true
        } catch (_: GeneralSecurityException) {
            false
        } catch (_: IllegalArgumentException) {
            false
        }
    }
}
