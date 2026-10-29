package com.kitalonlabs.neogold.integrity

import com.google.common.truth.Truth.assertThat
import com.kitalonlabs.neogold.core.security.integrity.BundleManifest
import org.junit.Test
import java.io.File
import java.time.LocalDate

/**
 * Whether the screening screens are offered (H8, TC-TRI-26): the verified config turns triage on, is signed for a public
 * build, and is not past `validUntil`. The triage engine applies the same three rules when it evaluates a result.
 */
class ScreeningAvailabilityTest {

    private val manifest =
        BundleManifest(1, "1.0.0", "K3", "00".repeat(32), mapOf("triage-config.json" to "hash"), "sig")

    private val shipped: String by lazy {
        var dir = File(".").canonicalFile
        while (dir.parentFile != null && !File(dir, "config/triage-config.json").isFile) {
            dir = dir.parentFile
        }
        File(dir, "config/triage-config.json").readText()
    }

    private val triageSwitch = Regex("\"triageEnabled\"\\s*:\\s*(true|false)")
    private val validUntil = Regex("\"validUntil\"\\s*:\\s*\"([0-9-]+)\"")
    private val signoffStatus = Regex("\"status\"\\s*:\\s*\"(DRAFT|SIGNED)\"")

    private fun assets(enabled: Boolean, status: String? = null, expires: String? = null): VerifiedAssets {
        var text = shipped.replace(triageSwitch, "\"triageEnabled\": $enabled")
        if (status != null) text = text.replace(signoffStatus, "\"status\": \"$status\"")
        if (expires != null) text = text.replace(validUntil, "\"validUntil\": \"$expires\"")
        return VerifiedAssets.interpret(manifest, text, isDevelopmentKey = false, isDebuggable = false)
    }

    private val today = LocalDate.of(2026, 10, 30)

    @Test
    fun switchOff_isNeverAvailable() {
        assertThat(
            assets(enabled = false, status = "SIGNED", expires = "2099-01-01").triageAvailable(today, true)
        ).isFalse()
    }

    @Test
    fun switchOnAndSigned_isAvailableUntilItExpires_TC_TRI_26() {
        val signed = assets(enabled = true, status = "SIGNED", expires = "2026-12-31")

        assertThat(signed.triageAvailable(LocalDate.of(2026, 12, 31), isPublicBuild = true)).isTrue()
        assertThat(signed.triageAvailable(LocalDate.of(2027, 1, 1), isPublicBuild = true)).isFalse()
    }

    @Test
    fun anUnsignedDraftIsNotOfferedByAPublicBuild_butIsByADebuggableOne() {
        val draft = assets(enabled = true, status = "DRAFT", expires = "2099-01-01")

        assertThat(draft.triageAvailable(today, isPublicBuild = true)).isFalse()
        assertThat(draft.triageAvailable(today, isPublicBuild = false)).isTrue()
    }

    @Test
    fun anExpiredConfigIsNotOffered_evenByADebuggableBuild() {
        assertThat(
            assets(enabled = true, status = "SIGNED", expires = "2026-01-01").triageAvailable(today, false)
        ).isFalse()
    }

    @Test
    fun anUnverifiedBundleOffersNothing() {
        assertThat(VerifiedAssets.NotVerified.triageAvailable(today, isPublicBuild = false)).isFalse()
    }

    @Test
    fun theModelVersionComesFromTheVerifiedModelFile() {
        val withModel = VerifiedAssets.interpret(
            manifest,
            shipped,
            isDevelopmentKey = false,
            isDebuggable = false,
            modelText = "{ \"modelVersion\": \"1.0.0-synthetic\" }"
        )
        val withoutModel = VerifiedAssets.interpret(manifest, shipped, isDevelopmentKey = false, isDebuggable = false)

        assertThat(withModel.modelVersion).isEqualTo("1.0.0-synthetic")
        assertThat(withoutModel.modelVersion).isNull()
    }
}
