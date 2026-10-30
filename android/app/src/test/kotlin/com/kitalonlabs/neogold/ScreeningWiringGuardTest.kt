package com.kitalonlabs.neogold

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.io.File

/**
 * Screening must not be offered while any of its results is made up (H1 of the package B1 safety review, BUG-2026-001), and
 * since package E1 it is offered only because every result comes from the pipeline and the triage engine. These tests keep
 * it that way: `NeoGoldContainer.SCREENING_PIPELINE_WIRED` may be true only while no screen assigns a made-up estimate, takes
 * a stand-in photo or decides a rule outside the engine. The shipped config still has `triageEnabled: false` for the closed
 * research build (PRD D10); a build that turns it on needs the clinical lead's review of A1, A2, A3, A8 and A9 first.
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

    private val madeUpEstimate = Regex("sessionEstimated(Low|High)\\s*=\\s*[0-9]")

    @Test
    fun theWiringFlagIsTrueOnlyWhileNoScreenAssignsAMadeUpEstimate() {
        val text = productionText()

        if (NeoGoldContainer.SCREENING_PIPELINE_WIRED) {
            assertThat(madeUpEstimate.containsMatchIn(text)).isFalse()
            assertThat(text).doesNotContain("createDefaultCaptureFrame")
        }
    }

    @Test
    fun everyScreeningResultComesFromTheFlowAndTheEngine_notFromNavigationCode() {
        val activity = File(appSources, "com/kitalonlabs/neogold/MainActivity.kt").readText()

        // the flow decides; the route only asks it
        assertThat(activity).contains("flow.decide()")
        assertThat(activity).contains("flow.analyze(")
        assertThat(activity).contains("newScreening()")
        // an outcome that is settled is shown at once, and a route that lost its flow goes Home, never to an empty screen
        assertThat(activity).contains("flow?.decideEarly()")
        assertThat(activity).contains("screening == null ->")
        // the screening is held by the app, so rotation and the app lock do not lose it
        assertThat(activity).contains("var screening by screeningHolder::flow")
        // nothing in a route sets a rule outcome or a result by hand
        for (handMade in listOf(
            "sessionRuleOutcome",
            "sessionTriageAction",
            "sessionReasonCodes",
            "TriageReasonCode.R"
        )) {
            assertThat(activity).doesNotContain(handMade)
        }
        // an age is stored as it was entered, never clamped into range, and no version is invented for a record
        assertThat(activity).doesNotContain("coerceIn(0.0, 720.0)")
        assertThat(activity).doesNotContain("coerceIn(22, 44)")
    }

    @Test
    fun theShippedConfigStillHasTriageSwitchedOff_forTheClosedResearchBuild() {
        // app/src/main/kotlin -> main -> src -> app -> android -> the repository
        val repository = appSources.parentFile.parentFile.parentFile.parentFile.parentFile
        val config = File(repository, "config/triage-config.json").readText()

        assertThat(Regex("\"triageEnabled\"\\s*:\\s*false").containsMatchIn(config)).isTrue()
    }

    @Test
    fun noProductionCodeBuildsAnIntegrityCheckThatAlwaysSaysYes() {
        val alwaysTrue = Regex("IntegrityChecker\\s*\\{\\s*true\\s*}")

        assertThat(alwaysTrue.containsMatchIn(productionText())).isFalse()
    }
}
