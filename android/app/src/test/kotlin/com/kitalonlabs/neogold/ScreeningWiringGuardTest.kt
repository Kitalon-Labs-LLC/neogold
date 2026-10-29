package com.kitalonlabs.neogold

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.io.File

/**
 * Screening must not be offered while its result is a placeholder (H1 of the package B1 safety review, BUG-2026-001).
 * `MainActivity` still sets a made-up estimate on S19; until package E1 replaces it with the pipeline and the triage
 * engine, `NeoGoldContainer.SCREENING_PIPELINE_WIRED` stays false, so a signed config with `triageEnabled: true` cannot
 * show a baby "no further action".
 */
class ScreeningWiringGuardTest {

    private val appSources: File by lazy {
        var dir = File(".").canonicalFile
        while (dir.parentFile != null && !File(dir, "settings.gradle.kts").exists()) {
            dir = dir.parentFile
        }
        File(dir, "app/src/main/kotlin")
    }

    private fun productionText(): String = appSources.walkTopDown().filter {
        it.isFile && it.extension == "kt"
    }.joinToString("\n") { it.readText() }

    private val placeholderResult = Regex("sessionEstimated(Low|High)\\s*=\\s*[0-9]")

    @Test
    fun theWiringFlagStaysFalse_whileAnyScreenAssignsAMadeUpEstimate() {
        val stillAPlaceholder = placeholderResult.containsMatchIn(productionText())

        if (stillAPlaceholder) {
            assertThat(NeoGoldContainer.SCREENING_PIPELINE_WIRED).isFalse()
        }
    }

    @Test
    fun theWiringFlagIsOffWhileThePlaceholderIsThere_soTheGuardAboveHasSomethingToGuard() {
        // If this fails because the placeholder is gone, package E1 landed: flip the flag and delete both tests.
        assertThat(placeholderResult.containsMatchIn(productionText())).isTrue()
        assertThat(NeoGoldContainer.SCREENING_PIPELINE_WIRED).isFalse()
    }

    @Test
    fun noProductionCodeBuildsAnIntegrityCheckThatAlwaysSaysYes() {
        val alwaysTrue = Regex("IntegrityChecker\\s*\\{\\s*true\\s*}")

        assertThat(alwaysTrue.containsMatchIn(productionText())).isFalse()
    }
}
