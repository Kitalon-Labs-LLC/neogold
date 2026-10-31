package com.kitalonlabs.neogold.core.designsystem

import com.google.common.truth.Truth.assertThat
import com.kitalonlabs.neogold.core.designsystem.components.organisms.TriageRole
import com.kitalonlabs.neogold.core.designsystem.components.organisms.TriageUiAction
import com.kitalonlabs.neogold.core.designsystem.components.organisms.triageSignalSpec
import org.junit.Test
import java.io.File

/**
 * TC-UI-01 and TC-UI-14 on the real card and the real resources, not on constants written in the test (BUG-2026-050, E1).
 * A result must be told apart by four signals at once (colour family, icon, border, words), so that nobody depends on colour
 * alone, and every result carries the decision-support framing of PRD D11.
 */
class TriageCardSignalsTest {

    private val fourResults = listOf(
        TriageUiAction.REFER_URGENT,
        TriageUiAction.BLOOD_TEST,
        TriageUiAction.RECHECK,
        TriageUiAction.NO_FURTHER_ACTION
    )

    @Test
    fun tcUi01_theFourResultsDifferInColourFamilyIconAndWords() {
        val specs = fourResults.map { triageSignalSpec(it) }

        assertThat(specs.map { it.role }.toSet()).hasSize(4)
        assertThat(specs.map { it.icon }.toSet()).hasSize(4)
        assertThat(specs.map { it.wordsRes }.toSet()).hasSize(4)
    }

    @Test
    fun tcUi01_theBordersAreTheOnesDoc6Names_andOnlyNoFurtherActionIsDashed() {
        // doc 6 Section 3: 4 dp solid, 4 dp solid, 2 dp solid, 2 dp dashed
        assertThat(
            triageSignalSpec(TriageUiAction.REFER_URGENT).let { it.borderWidthDp to it.dashedBorder }
        ).isEqualTo(4 to false)
        assertThat(
            triageSignalSpec(TriageUiAction.BLOOD_TEST).let { it.borderWidthDp to it.dashedBorder }
        ).isEqualTo(4 to false)
        assertThat(
            triageSignalSpec(TriageUiAction.RECHECK).let { it.borderWidthDp to it.dashedBorder }
        ).isEqualTo(2 to false)
        assertThat(triageSignalSpec(TriageUiAction.NO_FURTHER_ACTION).let { it.borderWidthDp to it.dashedBorder })
            .isEqualTo(2 to true)
    }

    @Test
    fun tcUi01_cannotScreenIsANeutralCardWithItsOwnIconAndWords() {
        val cannot = triageSignalSpec(TriageUiAction.CANNOT_SCREEN)
        val noAction = triageSignalSpec(TriageUiAction.NO_FURTHER_ACTION)

        assertThat(cannot.role).isEqualTo(TriageRole.NO_ACTION) // the neutral gray of doc 6 Section 3
        assertThat(cannot.icon).isNotEqualTo(noAction.icon) // the block icon, not the eye
        assertThat(cannot.wordsRes).isNotEqualTo(noAction.wordsRes)
        assertThat(cannot.dashedBorder).isFalse()
    }

    @Test
    fun tcUi01_theWordsAreDifferentInEnglishAndInBangla() {
        for (file in listOf("values", "values-bn")) {
            val strings = strings(file)
            val words = listOf("action_refer_urgent", "action_blood_test", "action_recheck", "action_no_further")
                .map { strings.getValue(it) }
            assertThat(words.toSet()).hasSize(4)
        }
        assertThat(strings("values").getValue("action_refer_urgent")).isEqualTo("Refer urgently now")
        assertThat(strings("values").getValue("action_blood_test")).isEqualTo("Blood test today")
        assertThat(strings("values").getValue("action_recheck")).isEqualTo("Screen again in %d hours")
        assertThat(strings("values").getValue("action_no_further")).isEqualTo("No further action from this screen")
    }

    @Test
    fun tcUi14_everyResultIsFramedAsASuggestionTheWorkerDecidesOn() {
        val english = strings("values")
        val bangla = strings("values-bn")

        assertThat(english.getValue("result_suggested")).isEqualTo("Suggested next step")
        assertThat(english.getValue("result_you_decide")).isEqualTo("You decide, using your clinical judgment.")
        assertThat(english.getValue("confirm_blood")).isEqualTo("Always confirm with a blood test.")
        assertThat(english.getValue("experimental")).isEqualTo("Experimental. Not yet validated in Bangladesh.")
        for (key in listOf("result_suggested", "result_you_decide", "confirm_blood", "experimental")) {
            assertThat(bangla.getValue(key)).isNotEmpty()
            assertThat(bangla.getValue(key)).isNotEqualTo(english.getValue(key))
        }
    }

    @Test
    fun tcUi14_theCardDrawsBothFramingLinesForEveryResult() {
        // the one card used by every result carries the header and the footer; no result path bypasses it
        val card = File(designsystemSources(), "components/organisms/NgTriageCard.kt").readText()
        assertThat(card).contains("stringResource(R.string.result_suggested)")
        assertThat(card).contains("stringResource(R.string.result_you_decide)")
    }

    @Test
    fun tcUi14_noResultStringUsesAWordThatPromisesSafety() {
        // the banned words of doc 6 Section 10.2, over the strings a result can show
        val resultKeys = listOf(
            "action_refer_urgent", "action_blood_test", "action_no_further", "next_no_further", "next_blood_test",
            "next_recheck", "rule_urgent_next_step", "cannot_screen", "confirm_blood", "result_suggested", "result_you_decide"
        )
        val english = strings("values")
        val banned = Regex("\\b(safe|normal|healthy|fine|diagnos\\w*|accurate|precise)\\b", RegexOption.IGNORE_CASE)
        for (key in resultKeys) assertThat(banned.containsMatchIn(english.getValue(key))).isFalse()
    }

    // Gradle runs a module's unit tests with the module folder as the working directory.
    private fun designsystemSources() = File("src/main/kotlin/com/kitalonlabs/neogold/core/designsystem")

    private fun strings(folder: String): Map<String, String> {
        val text = File("src/main/res/$folder/strings.xml").readText()
        return Regex("<string name=\"([^\"]+)\"[^>]*>(.*?)</string>", RegexOption.DOT_MATCHES_ALL).findAll(text)
            .associate { it.groupValues[1] to it.groupValues[2].replace("\'", "'").trim() }
    }
}
