package com.kitalonlabs.neogold.feature.research

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.io.File

/**
 * Research is blinded (FR-27, NG-030, TC-RSH-10, doc 8 hazard controls): the estimate is never made, shown, spoken or kept
 * in research mode. Two guarantees that need no screen to be drawn:
 *
 * - the module cannot make an estimate, because it does not depend on the estimation or triage engines;
 * - no screen or capture code names an estimate or triage field.
 *
 * What a drawn screen exposes to TalkBack is checked by the Compose UI tests of package D1 (they need `ui-test-junit4`).
 */
class ResearchBlindingGuardTest {

    private val moduleDir: File by lazy {
        var dir = File(".").canonicalFile
        while (dir.parentFile != null && !File(dir, "settings.gradle.kts").exists()) {
            dir = dir.parentFile
        }
        File(dir, "feature/research")
    }

    private fun sources(vararg folders: String): List<File> = folders.flatMap { folder ->
        File(moduleDir, "src/main/kotlin/com/kitalonlabs/neogold/feature/research/$folder")
            .walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
    }

    @Test
    fun tcRsh10_theResearchModuleCannotMakeAnEstimateOrAnAction() {
        val build = File(moduleDir, "build.gradle.kts").readText()

        assertThat(build).doesNotContain(":engine:estimation")
        assertThat(build).doesNotContain(":engine:triage")
    }

    @Test
    fun tcRsh10_noScreenOrCaptureCodeNamesAnEstimateOrTriageField() {
        val forbidden = listOf(
            "estimateMgDl",
            "estimateLowMgDl",
            "estimateHighMgDl",
            "thresholdMgDl",
            "triageAction",
            "ruleOutcome",
            "TriageEngine",
            "EstimationEngine",
            "engine.estimation",
            "engine.triage"
        )
        val files = sources("ui", "capture")
        assertThat(files).isNotEmpty()

        for (file in files) {
            val text = file.readText()
            for (name in forbidden) {
                assertThat(text).doesNotContain(name)
            }
        }
    }

    @Test
    fun tcRsh10_noTextOfTheResearchScreensSpeaksOfAnEstimateARiskOrATriageZone() {
        // whole words: "reference" (the reference bilirubin result) is not "refer"
        val forbidden = Regex("""\b(estimate|estimated|risk|zone|triage|urgent|referral|refer)\b""")
        val files = listOf("values", "values-bn").map { File(moduleDir, "src/main/res/$it/strings.xml") }
        for (file in files) {
            // only what can be shown: a comment in the file is not text on a screen
            val shown = file.readText().replace(Regex("""<!--.*?-->""", RegexOption.DOT_MATCHES_ALL), "").lowercase()
            assertThat(forbidden.find(shown)?.value).isNull()
        }
    }

    @Test
    fun tcRsh10_theRepositoryWritesEveryEstimateAndTriageFieldAsNullAndMarksTheSessionBlinded() {
        val text = sources("data").single { it.name == "ResearchRepository.kt" }.readText()

        for (field in listOf(
            "estimateMgDl",
            "estimateLowMgDl",
            "estimateHighMgDl",
            "thresholdMgDl",
            "ruleOutcome",
            "triageAction"
        )) {
            assertThat(Regex("""$field\s*=\s*null""").containsMatchIn(text)).isTrue()
        }
        assertThat(Regex("""estimateHidden\s*=\s*1""").containsMatchIn(text)).isTrue()
    }
}
