package com.kitalonlabs.neogold.integrity

import com.google.common.truth.Truth.assertThat
import com.kitalonlabs.neogold.BuildConfig
import org.junit.Test
import java.io.File
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

/**
 * The bundle the build really produced (`:app:signBundleManifest`, found through the `neogold.bundle.dir` property) is
 * what `config/` says, and its signature verifies under the key this same build put in BuildConfig (BUG-2026-007,
 * BUG-2026-008, TC-SEC-09, H7). JDK Ed25519 does the check, so no JSON or crypto library is needed in the test.
 */
class GeneratedBundleTest {

    private val bundleDir =
        File(requireNotNull(System.getProperty("neogold.bundle.dir")) { "neogold.bundle.dir is not set" })

    private val repositoryConfig: File by lazy {
        var dir = File(".").canonicalFile
        while (dir.parentFile != null && !File(dir, "config/triage-config.json").isFile) {
            dir = dir.parentFile
        }
        File(dir, "config")
    }

    private val required = listOf("model.json", "triage-config.json", "trust-list.json")
    private val manifestText: String get() = File(bundleDir, "bundle-manifest.json").readText()

    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance(
        "SHA-256"
    ).digest(bytes).joinToString("") { "%02x".format(it) }

    /** name to hash, read from the manifest's `files` object (flat, so a pattern is enough) */
    private fun manifestHashes(): Map<String, String> {
        val files = Regex("\"files\"\\s*:\\s*\\{([^}]*)}").find(manifestText)!!.groupValues[1]
        return Regex(
            "\"([^\"]+)\"\\s*:\\s*\"([0-9a-f]{64})\""
        ).findAll(files).associate { it.groupValues[1] to it.groupValues[2] }
    }

    @Test
    fun theFilesTheBuildShips_areByteForByteTheFilesInConfig() {
        for (name in required) {
            assertThat(File(bundleDir, name).readBytes()).isEqualTo(File(repositoryConfig, name).readBytes())
        }
    }

    @Test
    fun theManifestListsTheHashOfEveryShippedFile() {
        val hashes = manifestHashes()

        assertThat(hashes.keys).containsAtLeastElementsIn(required)
        for ((name, hash) in hashes) {
            assertThat(sha256(File(bundleDir, name).readBytes())).isEqualTo(hash)
        }
    }

    @Test
    fun theSignatureVerifiesUnderTheKeyInBuildConfig() {
        val canonical = manifestHashes().toSortedMap().entries.joinToString("") { "${it.key}:${it.value}\n" }
        val signature = Base64.getDecoder().decode(
            Regex("\"signature\"\\s*:\\s*\"([^\"]+)\"").find(manifestText)!!.groupValues[1]
        )
        val rawKey = BuildConfig.K3_PUBLIC_KEY_HEX.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        val ed25519Prefix = "302a300506032b6570032100".chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        val publicKey = KeyFactory.getInstance("Ed25519").generatePublic(X509EncodedKeySpec(ed25519Prefix + rawKey))

        val verifier = Signature.getInstance("Ed25519")
        verifier.initVerify(publicKey)
        verifier.update(canonical.toByteArray(Charsets.UTF_8))

        assertThat(verifier.verify(signature)).isTrue()
    }

    @Test
    fun aFileChangedAfterSigning_nolongerMatchesItsHash_TC_SEC_09() {
        val changed = File(bundleDir, "triage-config.json").readBytes().also { it[2] = (it[2].toInt() xor 1).toByte() }

        assertThat(sha256(changed)).isNotEqualTo(manifestHashes().getValue("triage-config.json"))
    }

    @Test
    fun buildConfigSaysWhichKindOfKeySignedTheBundle() {
        val kind = Regex(
            "\"keyKind\"\\s*:\\s*\"([^\"]+)\""
        ).find(File(bundleDir, "signing-info.json").readText())!!.groupValues[1]

        assertThat(BuildConfig.K3_IS_DEV_KEY).isEqualTo(kind != "release")
        assertThat(File(bundleDir, "signing-info.json").readText()).contains(BuildConfig.K3_PUBLIC_KEY_HEX)
    }
}
