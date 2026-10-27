package com.kitalonlabs.neogold.core.security.integrity

import android.content.Context
import android.content.res.AssetManager
import com.google.crypto.tink.subtle.Ed25519Verify
import org.json.JSONObject
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.security.GeneralSecurityException
import java.security.MessageDigest
import java.util.Base64
import java.util.Locale

/**
 * Verifies the integrity of clinical bundle assets (model.json, triage-config.json, trust-list.json)
 * against the K3-signed bundle-manifest.json per docs/4_System_Architecture.md Section 12 and
 * docs/8_Security_Compliance_Plan.md Section 6.5.
 */
class BundleManifestVerifier(
    private val trustedK3PublicKeyHex: String = DEFAULT_TEST_K3_PUBLIC_HEX
) {

    companion object {
        const val MANIFEST_FILE_NAME = "bundle-manifest.json"

        // Default test K3 Ed25519 public key hex
        const val DEFAULT_TEST_K3_PUBLIC_HEX =
            "7ea85ce6309d111e3446befeb992a4335907473c37534ca42df7e99e191c4772"

        val REQUIRED_FILES = listOf(
            "model.json",
            "triage-config.json",
            "trust-list.json"
        )

        fun hexToBytes(hex: String): ByteArray {
            val clean = hex.trim()
            require(clean.length == 64) { "Ed25519 public key hex must be 64 characters, got ${clean.length}" }
            val bytes = ByteArray(32)
            for (i in 0 until 32) {
                val high = Character.digit(clean[i * 2], 16)
                val low = Character.digit(clean[i * 2 + 1], 16)
                require(high != -1 && low != -1) { "Invalid hex character in key" }
                bytes[i] = ((high shl 4) or low).toByte()
            }
            return bytes
        }

        fun canonicalSignPayload(fileHashes: Map<String, String>): ByteArray {
            val lines = fileHashes.keys.sorted().map { name -> "$name:${fileHashes[name]}" }
            return (lines.joinToString("\n") + "\n").toByteArray(StandardCharsets.UTF_8)
        }

        fun computeStreamSha256(inputStream: InputStream): String {
            val md = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(8192)
            var read: Int
            while (inputStream.read(buffer).also { read = it } != -1) {
                md.update(buffer, 0, read)
            }
            val digest = md.digest()
            val sb = StringBuilder(digest.size * 2)
            for (b in digest) {
                sb.append(String.format(Locale.ROOT, "%02x", b.toInt() and 0xFF))
            }
            return sb.toString()
        }

        fun verifyAssets(
            context: Context,
            trustedK3PublicKeyHex: String = DEFAULT_TEST_K3_PUBLIC_HEX
        ): IntegrityResult {
            return BundleManifestVerifier(trustedK3PublicKeyHex).verify(context)
        }
    }

    private val trustedKeyBytes: ByteArray by lazy {
        hexToBytes(trustedK3PublicKeyHex)
    }

    /**
     * Verifies assets loaded via Android [AssetManager].
     */
    fun verify(context: Context): IntegrityResult {
        return verify { assetName ->
            try {
                context.assets.open(assetName)
            } catch (_: Exception) {
                null
            }
        }
    }

    /**
     * Verifies assets loaded via arbitrary stream provider (for pure tests or custom loaders).
     */
    @Suppress(
        "ReturnCount",
        "TooGenericExceptionCaught"
    ) // any failure means the bundle is not verified, one result per failure; reworked in B1
    fun verify(assetProvider: (String) -> InputStream?): IntegrityResult {
        // Step 1: Read bundle manifest
        val manifestStream = assetProvider(MANIFEST_FILE_NAME)
            ?: return IntegrityResult.MissingManifest

        val manifestString = manifestStream.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }

        // Step 2: Parse JSON
        val manifest = try {
            val root = JSONObject(manifestString)
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
        } catch (e: Exception) {
            return IntegrityResult.MalformedManifest("Failed to parse $MANIFEST_FILE_NAME: ${e.message}")
        }

        // Step 3: Check required assets presence in manifest
        for (req in REQUIRED_FILES) {
            if (!manifest.files.containsKey(req)) {
                return IntegrityResult.MissingAsset(req)
            }
        }

        // Step 4: Verify Ed25519 signature
        val canonicalBytes = canonicalSignPayload(manifest.files)
        val signatureBytes = try {
            Base64.getDecoder().decode(manifest.signature)
        } catch (e: Exception) {
            return IntegrityResult.MalformedManifest("Invalid base64 signature: ${e.message}")
        }

        val signatureValid = try {
            val verifier = Ed25519Verify(trustedKeyBytes)
            verifier.verify(signatureBytes, canonicalBytes)
            true
        } catch (_: GeneralSecurityException) {
            false
        } catch (_: Exception) {
            false
        }

        if (!signatureValid) {
            return IntegrityResult.InvalidSignature(manifest.keyId)
        }

        // Step 5: Verify SHA-256 for all listed asset files
        for ((fileName, expectedHash) in manifest.files) {
            val fileStream = assetProvider(fileName)
                ?: return IntegrityResult.MissingAsset(fileName)

            val actualHash = try {
                fileStream.use { computeStreamSha256(it) }
            } catch (e: Exception) {
                return IntegrityResult.MalformedManifest("Failed to read asset '$fileName': ${e.message}")
            }

            if (!actualHash.equals(expectedHash, ignoreCase = true)) {
                return IntegrityResult.HashMismatch(
                    filename = fileName,
                    expectedHash = expectedHash,
                    actualHash = actualHash
                )
            }
        }

        return IntegrityResult.Valid(manifest)
    }
}
