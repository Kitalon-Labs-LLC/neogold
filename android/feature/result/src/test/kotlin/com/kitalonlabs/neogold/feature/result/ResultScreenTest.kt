package com.kitalonlabs.neogold.feature.result

import com.google.common.truth.Truth.assertThat
import com.kitalonlabs.neogold.core.designsystem.components.organisms.TriageUiAction
import org.junit.Test
import java.io.File

/**
 * Tests for the result screen, on the real resources and the real screen source (package E1 replaced the first versions of
 * these tests, which compared constants written in the test with themselves):
 * - TC-UI-01: the four results have four different titles (the card's other signals are tested in `:core:designsystem`)
 * - TC-UI-09: What to tell the mother card copy and availability
 * - TC-UI-14: Decision-support framing (Suggested next step and You decide)
 * - TC-TRI-26: with triage off the screen shows the blood test message and no result
 */
class ResultScreenTest {

    // Gradle runs a module's unit tests with the module folder as the working directory.
    private val designsystemRes = File("../../core/designsystem/src/main/res")
    private val sources = "src/main/kotlin/com/kitalonlabs/neogold/feature/result"

    private fun source(name: String): String = File("$sources/$name").readText().replace("\r\n", "\n")

    private fun strings(folder: String): Map<String, String> {
        val text = File(designsystemRes, "$folder/strings.xml").readText()
        val pattern = Regex("<string name=\"([^\"]+)\"[^>]*>(.*?)</string>", RegexOption.DOT_MATCHES_ALL)
        val result = mutableMapOf<String, String>()
        for (match in pattern.findAll(text)) {
            result[match.groupValues[1]] = match.groupValues[2].replace("\\'", "'").trim()
        }
        return result
    }

    private fun scriptKey(action: TriageUiAction): String = when (action) {
        TriageUiAction.REFER_URGENT -> "mother_card_refer_urgent"
        TriageUiAction.BLOOD_TEST -> "mother_card_blood_test"
        TriageUiAction.RECHECK -> "mother_card_recheck"
        TriageUiAction.NO_FURTHER_ACTION -> "mother_card_no_further"
        TriageUiAction.CANNOT_SCREEN -> "cannot_screen"
    }

    @Test
    fun tcUi01_theFourResultsHaveFourDifferentTitles_inBothLanguages() {
        val keys = listOf("action_refer_urgent", "action_blood_test", "action_recheck", "action_no_further")
        for (folder in listOf("values", "values-bn")) {
            val all = strings(folder)
            val titles = keys.map { key -> all.getValue(key) }
            assertThat(titles.toSet()).hasSize(4)
        }
    }

    @Test
    fun tcUi09_everyResultHasAMotherScript_andTheClinicalOnesSayAnAppWasUsed() {
        val english = strings("values")
        val bangla = strings("values-bn")
        for (action in TriageUiAction.entries) {
            assertThat(english.getValue(scriptKey(action))).isNotEmpty()
            assertThat(bangla.getValue(scriptKey(action))).isNotEmpty()
        }
        // the disclosure that an app helped decide (Personal Data Protection Ordinance 2025 section 13(6), doc 8 Section 13.1)
        val disclosure = "A phone app was used to help decide whether a blood test is needed"
        for (action in TriageUiAction.entries) {
            if (action == TriageUiAction.CANNOT_SCREEN) continue
            assertThat(english.getValue(scriptKey(action))).contains(disclosure)
        }
    }

    @Test
    fun tcUi09_theScriptShownIsTheOneForTheActionOnScreen() {
        val card = source("WhatToTellTheMotherCard.kt")
        for (action in TriageUiAction.entries) {
            assertThat(card).contains("TriageUiAction.${action.name} -> R.string.${scriptKey(action)}")
        }
    }

    @Test
    fun tcUi14_theScreenFramesEveryResultAsASuggestion() {
        val english = strings("values")
        assertThat(english.getValue("result_suggested")).isEqualTo("Suggested next step")
        assertThat(english.getValue("result_you_decide")).isEqualTo("You decide, using your clinical judgment.")
        assertThat(english.getValue("confirm_blood")).isEqualTo("Always confirm with a blood test.")
        assertThat(english.getValue("experimental")).isEqualTo("Experimental. Not yet validated in Bangladesh.")
        // the screen draws the blood-test reminder and the experimental banner on every path
        val screen = source("S20ResultScreen.kt")
        assertThat(screen).contains("NgExperimentalBanner(")
        assertThat(screen).contains("stringResource(R.string.confirm_blood)")
        assertThat(screen).contains("NgTriageCard(")
    }

    @Test
    fun tcTri26_withTriageOffTheScreenShowsOnlyTheBloodTestMessage() {
        val screen = source("S20ResultScreen.kt")
        val disabledBranch = screen.substringAfter("if (!triageEnabled) {").substringBefore("} else {")
        assertThat(disabledBranch).contains("TriageUiAction.CANNOT_SCREEN")
        assertThat(disabledBranch).contains("R.string.confirm_blood")
        // none of the result inputs (the screen's own parameters) is passed on in that branch
        val resultInputs =
            listOf("estimatedLow", "estimatedHigh", "threshold", "= reasonText", "= nextStepText", "= action")
        for (input in resultInputs) {
            assertThat(disabledBranch).doesNotContain(input)
        }
        // the mother card describes a result, so it is drawn only when triage is on
        val beforeMotherCard = screen.substringBefore("WhatToTellTheMotherCard(")
        assertThat(beforeMotherCard.trimEnd()).endsWith("if (triageEnabled) {")
    }
}
