package com.kitalonlabs.neogold.core.security

import com.google.common.truth.Truth.assertThat
import com.google.crypto.tink.subtle.Ed25519Sign
import com.kitalonlabs.neogold.core.security.integrity.BundleManifestVerifier
import com.kitalonlabs.neogold.core.security.integrity.IntegrityResult
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.util.Base64

@RunWith(RobolectricTestRunner::class)
class BundleManifestVerifierTest {

    private val testPrivateKeyHex =
        "86b074c2d51b34c46b927d10f9c94bcc087bc317de256d849594045f4bf08893"
    private val testPublicKeyHex =
        "7ea85ce6309d111e3446befeb992a4335907473c37534ca42df7e99e191c4772"

    private fun createSignedAssets(
        fileContents: Map<String, String>,
        privateKeyHex: String = testPrivateKeyHex,
        corruptSignature: Boolean = false
    ): Map<String, ByteArray> {
        val assets = mutableMapOf<String, ByteArray>()
        val fileHashes = mutableMapOf<String, String>()

        for ((name, content) in fileContents) {
            val bytes = content.toByteArray(StandardCharsets.UTF_8)
            assets[name] = bytes
            val md = java.security.MessageDigest.getInstance("SHA-256")
            val hash = md.digest(bytes).joinToString("") { "%02x".format(it) }
            fileHashes[name] = hash
        }

        val canonicalBytes = BundleManifestVerifier.canonicalSignPayload(fileHashes)
        val privKeyBytes = BundleManifestVerifier.hexToBytes(privateKeyHex)
        val signer = Ed25519Sign(privKeyBytes)
        val signatureBytes = signer.sign(canonicalBytes)

        val finalSigBytes = if (corruptSignature) {
            signatureBytes.clone().apply { this[0] = (this[0].toInt() xor 0xFF).toByte() }
        } else {
            signatureBytes
        }

        val manifestObj = JSONObject().apply {
            put("formatVersion", 1)
            put("manifestVersion", "1.0.0")
            put("keyId", "K3")
            put("signerPublicKeyHex", testPublicKeyHex)
            put("signature", Base64.getEncoder().encodeToString(finalSigBytes))
            val filesObj = JSONObject()
            for ((k, v) in fileHashes) {
                filesObj.put(k, v)
            }
            put("files", filesObj)
        }

        assets[BundleManifestVerifier.MANIFEST_FILE_NAME] =
            manifestObj.toString().toByteArray(StandardCharsets.UTF_8)

        return assets
    }

    @Test
    fun validManifestAndAssets_returnsValid() {
        val files = mapOf(
            "model.json" to "{\"model\": \"test\"}",
            "triage-config.json" to "{\"triage\": \"test\"}",
            "trust-list.json" to "{\"trust\": \"test\"}"
        )
        val assets = createSignedAssets(files)
        val verifier = BundleManifestVerifier(testPublicKeyHex)

        val result = verifier.verify { name ->
            assets[name]?.let { ByteArrayInputStream(it) }
        }

        assertThat(result).isInstanceOf(IntegrityResult.Valid::class.java)
        assertThat(result.isScreeningAllowed).isTrue()
        val valid = result as IntegrityResult.Valid
        assertThat(valid.manifest.files).hasSize(3)
        assertThat(valid.manifest.keyId).isEqualTo("K3")
    }

    @Test
    fun tamperedAsset_returnsHashMismatch() {
        val files = mapOf(
            "model.json" to "{\"model\": \"test\"}",
            "triage-config.json" to "{\"triage\": \"original_thresholds\"}",
            "trust-list.json" to "{\"trust\": \"test\"}"
        )
        val assets = createSignedAssets(files).toMutableMap()
        // Tamper with triage-config.json
        assets["triage-config.json"] = "{\"triage\": \"TAMPERED_thresholds\"}".toByteArray(StandardCharsets.UTF_8)

        val verifier = BundleManifestVerifier(testPublicKeyHex)
        val result = verifier.verify { name ->
            assets[name]?.let { ByteArrayInputStream(it) }
        }

        assertThat(result).isInstanceOf(IntegrityResult.HashMismatch::class.java)
        assertThat(result.isScreeningAllowed).isFalse()
        val mismatch = result as IntegrityResult.HashMismatch
        assertThat(mismatch.filename).isEqualTo("triage-config.json")
    }

    @Test
    fun corruptedSignature_returnsInvalidSignature() {
        val files = mapOf(
            "model.json" to "{\"model\": \"test\"}",
            "triage-config.json" to "{\"triage\": \"test\"}",
            "trust-list.json" to "{\"trust\": \"test\"}"
        )
        val assets = createSignedAssets(files, corruptSignature = true)
        val verifier = BundleManifestVerifier(testPublicKeyHex)

        val result = verifier.verify { name ->
            assets[name]?.let { ByteArrayInputStream(it) }
        }

        assertThat(result).isInstanceOf(IntegrityResult.InvalidSignature::class.java)
        assertThat(result.isScreeningAllowed).isFalse()
    }

    @Test
    fun missingRequiredAsset_returnsMissingAsset() {
        val files = mapOf(
            "model.json" to "{\"model\": \"test\"}",
            "triage-config.json" to "{\"triage\": \"test\"}",
            "trust-list.json" to "{\"trust\": \"test\"}"
        )
        val assets = createSignedAssets(files).toMutableMap()
        assets.remove("trust-list.json")

        val verifier = BundleManifestVerifier(testPublicKeyHex)
        val result = verifier.verify { name ->
            assets[name]?.let { ByteArrayInputStream(it) }
        }

        assertThat(result).isInstanceOf(IntegrityResult.MissingAsset::class.java)
        assertThat(result.isScreeningAllowed).isFalse()
        val missing = result as IntegrityResult.MissingAsset
        assertThat(missing.filename).isEqualTo("trust-list.json")
    }

    @Test
    fun missingManifest_returnsMissingManifest() {
        val verifier = BundleManifestVerifier(testPublicKeyHex)
        val result = verifier.verify { _ -> null }

        assertThat(result).isInstanceOf(IntegrityResult.MissingManifest::class.java)
        assertThat(result.isScreeningAllowed).isFalse()
    }

    @Test
    fun signedWithDifferentKey_returnsInvalidSignature() {
        val files = mapOf(
            "model.json" to "{\"model\": \"test\"}",
            "triage-config.json" to "{\"triage\": \"test\"}",
            "trust-list.json" to "{\"trust\": \"test\"}"
        )
        // Sign with an unauthorized key
        val attackerPrivHex = "11".repeat(32)
        val assets = createSignedAssets(files, privateKeyHex = attackerPrivHex)

        // Verifier uses the genuine K3 key
        val verifier = BundleManifestVerifier(testPublicKeyHex)
        val result = verifier.verify { name ->
            assets[name]?.let { ByteArrayInputStream(it) }
        }

        assertThat(result).isInstanceOf(IntegrityResult.InvalidSignature::class.java)
        assertThat(result.isScreeningAllowed).isFalse()
    }

    @Test
    fun realAppAssets_verifySuccessfully() {
        val assetsDir = File("../../app/src/main/assets").takeIf { it.exists() }
            ?: File("android/app/src/main/assets").takeIf { it.exists() }
            ?: error("Could not find the app assets directory (expected ../../app/src/main/assets)")

        val verifier = BundleManifestVerifier(testPublicKeyHex)
        val result = verifier.verify { name ->
            val f = File(assetsDir, name)
            if (f.exists()) FileInputStream(f) else null
        }

        assertThat(result).isInstanceOf(IntegrityResult.Valid::class.java)
        assertThat(result.isScreeningAllowed).isTrue()
    }

    @Test
    fun realAppAssets_tamperedTriageConfig_disablesScreening_TC_SEC_09() {
        val assetsDir = File("../../app/src/main/assets").takeIf { it.exists() }
            ?: File("android/app/src/main/assets").takeIf { it.exists() }
            ?: error("Could not find the app assets directory (expected ../../app/src/main/assets)")

        val verifier = BundleManifestVerifier(testPublicKeyHex)
        val result = verifier.verify { name ->
            if (name == "triage-config.json") {
                // Return tampered config asset bytes
                ByteArrayInputStream("{\"tampered\": true}".toByteArray(StandardCharsets.UTF_8))
            } else {
                val f = File(assetsDir, name)
                if (f.exists()) FileInputStream(f) else null
            }
        }

        assertThat(result).isInstanceOf(IntegrityResult.HashMismatch::class.java)
        assertThat(result.isScreeningAllowed).isFalse()
        val mismatch = result as IntegrityResult.HashMismatch
        assertThat(mismatch.filename).isEqualTo("triage-config.json")
    }
}

