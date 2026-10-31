package com.kitalonlabs.neogold.core.security

import com.google.common.truth.Truth.assertThat
import com.google.crypto.tink.subtle.Ed25519Sign
import com.kitalonlabs.neogold.core.security.integrity.BundleLoadResult
import com.kitalonlabs.neogold.core.security.integrity.BundleManifestVerifier
import com.kitalonlabs.neogold.core.security.integrity.IntegrityResult
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.Base64

/**
 * The bundle manifest check (doc 4 Section 12, doc 8 Section 6.5). Every key here is made by the test: the app trusts
 * the key its own build was signed with, and no key is written down in the repository (BUG-2026-008).
 */
@RunWith(RobolectricTestRunner::class)
class BundleManifestVerifierTest {

    private class TestKey {
        private val pair = Ed25519Sign.KeyPair.newKeyPair()
        val privateKey: ByteArray = pair.privateKey
        val publicKeyHex: String = pair.publicKey.joinToString("") { "%02x".format(it) }
    }

    private val k3 = TestKey()

    private val goodFiles = mapOf(
        "model.json" to "{\"model\": \"test\"}",
        "triage-config.json" to "{\"triage\": \"test\"}",
        "trust-list.json" to "{\"trust\": \"test\"}"
    )

    private fun createSignedAssets(
        fileContents: Map<String, String>,
        signer: TestKey = k3,
        corruptSignature: Boolean = false
    ): Map<String, ByteArray> {
        val assets = mutableMapOf<String, ByteArray>()
        val fileHashes = mutableMapOf<String, String>()

        for ((name, content) in fileContents) {
            val bytes = content.toByteArray(StandardCharsets.UTF_8)
            assets[name] = bytes
            fileHashes[name] = BundleManifestVerifier.computeSha256(bytes)
        }

        val canonicalBytes = BundleManifestVerifier.canonicalSignPayload(fileHashes)
        val signatureBytes = Ed25519Sign(signer.privateKey).sign(canonicalBytes)

        val finalSigBytes = if (corruptSignature) {
            signatureBytes.clone().apply { this[0] = (this[0].toInt() xor 0xFF).toByte() }
        } else {
            signatureBytes
        }

        val manifestObj = JSONObject().apply {
            put("formatVersion", 1)
            put("manifestVersion", "1.0.0")
            put("keyId", "K3")
            put("signerPublicKeyHex", signer.publicKeyHex)
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

    private fun Map<String, ByteArray>.provider(): (String) -> ByteArrayInputStream? = { name ->
        this[name]?.let { ByteArrayInputStream(it) }
    }

    @Test
    fun validManifestAndAssets_returnsValid() {
        val assets = createSignedAssets(goodFiles)
        val verifier = BundleManifestVerifier(k3.publicKeyHex)

        val result = verifier.verify(assets.provider())

        assertThat(result).isInstanceOf(IntegrityResult.Valid::class.java)
        assertThat(result.isScreeningAllowed).isTrue()
        val valid = result as IntegrityResult.Valid
        assertThat(valid.manifest.files).hasSize(3)
        assertThat(valid.manifest.keyId).isEqualTo("K3")
    }

    @Test
    fun tamperedAsset_returnsHashMismatch_TC_SEC_09() {
        val assets = createSignedAssets(
            goodFiles + ("triage-config.json" to "{\"triage\": \"original_thresholds\"}")
        ).toMutableMap()
        assets["triage-config.json"] = "{\"triage\": \"TAMPERED_thresholds\"}".toByteArray(StandardCharsets.UTF_8)

        val result = BundleManifestVerifier(k3.publicKeyHex).verify(assets.provider())

        assertThat(result).isInstanceOf(IntegrityResult.HashMismatch::class.java)
        assertThat(result.isScreeningAllowed).isFalse()
        assertThat((result as IntegrityResult.HashMismatch).filename).isEqualTo("triage-config.json")
    }

    @Test
    fun corruptedSignature_returnsInvalidSignature() {
        val assets = createSignedAssets(goodFiles, corruptSignature = true)

        val result = BundleManifestVerifier(k3.publicKeyHex).verify(assets.provider())

        assertThat(result).isInstanceOf(IntegrityResult.InvalidSignature::class.java)
        assertThat(result.isScreeningAllowed).isFalse()
    }

    @Test
    fun missingRequiredAsset_returnsMissingAsset() {
        val assets = createSignedAssets(goodFiles).toMutableMap()
        assets.remove("trust-list.json")

        val result = BundleManifestVerifier(k3.publicKeyHex).verify(assets.provider())

        assertThat(result).isInstanceOf(IntegrityResult.MissingAsset::class.java)
        assertThat(result.isScreeningAllowed).isFalse()
        assertThat((result as IntegrityResult.MissingAsset).filename).isEqualTo("trust-list.json")
    }

    @Test
    fun missingManifest_returnsMissingManifest() {
        val result = BundleManifestVerifier(k3.publicKeyHex).verify { _ -> null }

        assertThat(result).isInstanceOf(IntegrityResult.MissingManifest::class.java)
        assertThat(result.isScreeningAllowed).isFalse()
    }

    @Test
    fun signedWithDifferentKey_returnsInvalidSignature() {
        val assets = createSignedAssets(goodFiles, signer = TestKey())

        val result = BundleManifestVerifier(k3.publicKeyHex).verify(assets.provider())

        assertThat(result).isInstanceOf(IntegrityResult.InvalidSignature::class.java)
        assertThat(result.isScreeningAllowed).isFalse()
    }

    @Test
    fun aTrustedKeyThatIsNotAKey_failsClosed() {
        val assets = createSignedAssets(goodFiles)

        val result = BundleManifestVerifier("not-a-key").verify(assets.provider())

        assertThat(result).isInstanceOf(IntegrityResult.InvalidSignature::class.java)
        assertThat(result.isScreeningAllowed).isFalse()
    }

    @Test
    fun aManifestThatIsNotJson_isMalformed() {
        val result = BundleManifestVerifier(k3.publicKeyHex).verify { name ->
            if (name == BundleManifestVerifier.MANIFEST_FILE_NAME) ByteArrayInputStream("{nope".toByteArray()) else null
        }

        assertThat(result).isInstanceOf(IntegrityResult.MalformedManifest::class.java)
        assertThat(result.isScreeningAllowed).isFalse()
    }

    @Test
    fun load_handsBackTheVerifiedBytes_andOpensEachFileOnce_BUG_2026_007() {
        val assets = createSignedAssets(goodFiles)
        val opens = mutableMapOf<String, Int>()
        val provider: (String) -> ByteArrayInputStream? = { name ->
            opens[name] = (opens[name] ?: 0) + 1
            assets[name]?.let { ByteArrayInputStream(it) }
        }

        val result = BundleManifestVerifier(k3.publicKeyHex).load(provider)

        assertThat(result).isInstanceOf(BundleLoadResult.Loaded::class.java)
        val bundle = (result as BundleLoadResult.Loaded).bundle
        for ((name, content) in goodFiles) {
            assertThat(bundle.text(name)).isEqualTo(content)
        }
        assertThat(opens.values.toSet()).containsExactly(1)
        assertThat(opens.keys).containsExactly(
            BundleManifestVerifier.MANIFEST_FILE_NAME,
            "model.json",
            "triage-config.json",
            "trust-list.json"
        )
    }

    @Test
    fun aFileSwappedAfterItWasRead_cannotReachTheCaller_BUG_2026_007() {
        val assets = createSignedAssets(goodFiles)
        val tampered = "{\"triage\": \"TAMPERED_thresholds\"}".toByteArray(StandardCharsets.UTF_8)
        val opened = mutableSetOf<String>()
        // The file is swapped the moment after it has been read once.
        val provider: (String) -> ByteArrayInputStream? = { name ->
            val first = opened.add(name)
            val bytes = if (!first && name == "triage-config.json") tampered else assets[name]
            bytes?.let { ByteArrayInputStream(it) }
        }

        val bundle = (BundleManifestVerifier(k3.publicKeyHex).load(provider) as BundleLoadResult.Loaded).bundle

        // A second read gets the swapped file, which is why callers must parse the verified bytes instead.
        assertThat(provider("triage-config.json")!!.readBytes()).isEqualTo(tampered)
        assertThat(bundle.text("triage-config.json")).isEqualTo(goodFiles.getValue("triage-config.json"))
    }

    @Test
    fun theBytesOfABundle_areCopies() {
        val assets = createSignedAssets(goodFiles)
        val bundle = (BundleManifestVerifier(k3.publicKeyHex).load(assets.provider()) as BundleLoadResult.Loaded).bundle

        bundle.bytes("model.json")[0] = 0

        assertThat(bundle.text("model.json")).isEqualTo(goodFiles.getValue("model.json"))
    }

    private fun vectorFiles(): Map<String, ByteArray> {
        var dir = File(".").canonicalFile
        while (dir.parentFile != null && !File(dir, "testdata/bundle-manifest").isDirectory) {
            dir = dir.parentFile
        }
        val folder = File(dir, "testdata/bundle-manifest")
        return folder.listFiles()!!.filter { it.isFile }.associate { it.name to it.readBytes() }
    }

    @Test
    fun aBundleSignedByTheBuildTool_isAcceptedByTheKotlinVerifier() {
        val files = vectorFiles()
        val publicKeyHex = String(files.getValue("public-key.hex"), StandardCharsets.US_ASCII).trim()

        val result = BundleManifestVerifier(publicKeyHex).verify(files.provider())

        assertThat(result).isInstanceOf(IntegrityResult.Valid::class.java)
        assertThat((result as IntegrityResult.Valid).manifest.files.keys)
            .containsExactly("model.json", "triage-config.json", "trust-list.json")
    }

    @Test
    fun aBundleSignedByTheBuildTool_withOneByteChanged_isRefused_TC_SEC_09() {
        val files = vectorFiles().toMutableMap()
        val publicKeyHex = String(files.getValue("public-key.hex"), StandardCharsets.US_ASCII).trim()
        files["triage-config.json"] = files.getValue("triage-config.json").also { it[2] = (it[2].toInt() xor 1).toByte() }

        val result = BundleManifestVerifier(publicKeyHex).verify(files.provider())

        assertThat(result).isInstanceOf(IntegrityResult.HashMismatch::class.java)
        assertThat(result.isScreeningAllowed).isFalse()
    }

    @Test
    fun aBundleSignedByTheBuildTool_isRefusedUnderAnotherKey() {
        val result = BundleManifestVerifier(TestKey().publicKeyHex).verify(vectorFiles().provider())

        assertThat(result).isInstanceOf(IntegrityResult.InvalidSignature::class.java)
    }

    private class FailingStream : java.io.InputStream() {
        override fun read(): Int = throw java.io.IOException("flash fault")
    }

    @Test
    fun aReadErrorInsideAnAsset_isARefusal_notACrash() {
        val assets = createSignedAssets(goodFiles)
        val provider: (String) -> java.io.InputStream? = { name ->
            if (name == "triage-config.json") FailingStream() else assets[name]?.let { ByteArrayInputStream(it) }
        }

        val result = BundleManifestVerifier(k3.publicKeyHex).verify(provider)

        assertThat(result).isEqualTo(IntegrityResult.UnusableAsset("triage-config.json"))
        assertThat(result.isScreeningAllowed).isFalse()
    }

    @Test
    fun aReadErrorInsideTheManifest_isARefusal_notACrash() {
        val result = BundleManifestVerifier(k3.publicKeyHex).verify { name ->
            if (name == BundleManifestVerifier.MANIFEST_FILE_NAME) FailingStream() else null
        }

        assertThat(result).isInstanceOf(IntegrityResult.MalformedManifest::class.java)
        assertThat(result.isScreeningAllowed).isFalse()
    }

    @Test
    fun anAssetLargerThanTheLimit_isRefused() {
        val big = "x".repeat(BundleManifestVerifier.MAX_ASSET_BYTES + 1)
        val assets = createSignedAssets(goodFiles + ("model.json" to big))

        val result = BundleManifestVerifier(k3.publicKeyHex).verify(assets.provider())

        assertThat(result).isEqualTo(IntegrityResult.AssetTooLarge("model.json"))
        assertThat(result.isScreeningAllowed).isFalse()
    }
}
