package com.kitalonlabs.neogold.integrity

import com.google.common.truth.Truth.assertThat
import com.kitalonlabs.neogold.core.security.integrity.BundleManifest
import com.kitalonlabs.neogold.core.security.integrity.IntegrityResult
import org.junit.Test
import java.io.File

/**
 * What the app does with a bundle that passed its signature and hash checks (BUG-2026-007, BUG-2026-008, TC-TRI-26,
 * TC-SEC-09): triage runs only when the signed config says so, and a build that carries a development key never runs.
 */
class VerifiedAssetsTest {

    private val manifest = BundleManifest(
        formatVersion = 1,
        manifestVersion = "1.0.0",
        keyId = "K3",
        signerPublicKeyHex = "00".repeat(32),
        files = mapOf("triage-config.json" to "hash"),
        signature = "sig"
    )

    private val repositoryRoot: File by lazy {
        var dir = File(".").canonicalFile
        while (dir.parentFile != null && !File(dir, "config/triage-config.json").isFile) {
            dir = dir.parentFile
        }
        dir
    }

    /** The real, signed-off config as it stands in the repository: triage is off in v1.0 (PRD D10). */
    private val shippedConfig: String by lazy { File(repositoryRoot, "config/triage-config.json").readText() }

    private val triageSwitch = Regex("\"triageEnabled\"\\s*:\\s*(true|false)")

    private fun withTriage(enabled: Boolean) = shippedConfig.replace(triageSwitch, "\"triageEnabled\": $enabled")

    @Test
    fun theShippedConfig_hasTriageOff_inV1() {
        val assets = VerifiedAssets.interpret(manifest, shippedConfig, isDevelopmentKey = false, isDebuggable = false)

        assertThat(assets.isUsable).isTrue()
        assertThat(assets.triageConfig).isNotNull()
        assertThat(assets.triageEnabled).isFalse()
    }

    @Test
    fun aVerifiedConfigThatTurnsTriageOn_turnsItOn_TC_TRI_26() {
        val assets = VerifiedAssets.interpret(
            manifest,
            withTriage(true),
            isDevelopmentKey = false,
            isDebuggable = false
        )

        assertThat(assets.isUsable).isTrue()
        assertThat(assets.triageEnabled).isTrue()
    }

    @Test
    fun aDevelopmentKeyInAnInstalledRelease_turnsEverythingOff_TC_SEC_09() {
        val assets = VerifiedAssets.interpret(manifest, withTriage(true), isDevelopmentKey = true, isDebuggable = false)

        assertThat(assets.integrity).isEqualTo(IntegrityResult.DevelopmentKeyInRelease)
        assertThat(assets.isUsable).isFalse()
        assertThat(assets.triageEnabled).isFalse()
        assertThat(assets.triageConfig).isNull()
    }

    @Test
    fun aDevelopmentKeyInADebuggableBuild_isAllowed() {
        val assets = VerifiedAssets.interpret(manifest, withTriage(true), isDevelopmentKey = true, isDebuggable = true)

        assertThat(assets.isUsable).isTrue()
        assertThat(assets.triageEnabled).isTrue()
    }

    @Test
    fun aConfigTheAppCannotRead_failsClosed() {
        val assets = VerifiedAssets.interpret(manifest, "{\"not\": \"a triage config\"}", false, false)

        assertThat(assets.integrity).isEqualTo(IntegrityResult.UnusableAsset("triage-config.json"))
        assertThat(assets.isUsable).isFalse()
        assertThat(assets.triageEnabled).isFalse()
    }

    @Test
    fun beforeTheCheckHasRun_nothingIsUsable() {
        assertThat(VerifiedAssets.NotVerified.isUsable).isFalse()
        assertThat(VerifiedAssets.NotVerified.triageEnabled).isFalse()
        assertThat(VerifiedAssets.NotVerified.triageConfig).isNull()
    }
}
