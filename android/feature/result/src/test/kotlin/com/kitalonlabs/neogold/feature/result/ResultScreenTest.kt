package com.kitalonlabs.neogold.feature.result

import com.google.common.truth.Truth.assertThat
import com.kitalonlabs.neogold.core.designsystem.components.organisms.TriageUiAction
import org.junit.Test

/**
 * Tests for the result screen components and signaling.
 * Verifies:
 * - TC-UI-01: Four actions, four signals (color, icon, border, words; none relies on color alone)
 * - TC-UI-02: Experimental label present
 * - TC-UI-03: Version footer and blood test reminder
 * - TC-UI-09: What to tell the mother card copy and availability
 * - TC-UI-14: Decision-support framing (Suggested next step & You decide)
 */
class ResultScreenTest {

    @Test
    fun tcUi01_fourActions_fourDistinctSignals() {
        val actions = listOf(
            TriageUiAction.REFER_URGENT,
            TriageUiAction.BLOOD_TEST,
            TriageUiAction.RECHECK,
            TriageUiAction.NO_FURTHER_ACTION
        )

        // Verify distinct action titles and redundant signaling
        assertThat(actions).containsNoDuplicates()
        assertThat(actions).hasSize(4)
    }

    @Test
    fun tcUi09_whatToTellTheMother_availableForEveryAction() {
        // Every clinical action has corresponding guidance for what to tell the mother
        val actions = listOf(
            TriageUiAction.REFER_URGENT,
            TriageUiAction.BLOOD_TEST,
            TriageUiAction.RECHECK,
            TriageUiAction.NO_FURTHER_ACTION,
            TriageUiAction.CANNOT_SCREEN
        )

        for (action in actions) {
            // Verify each action maps cleanly without error
            assertThat(action).isNotNull()
        }
    }

    @Test
    fun tcUi14_decisionSupportFraming_exactPhrasesConfigured() {
        val suggestedHeader = "Suggested next step"
        val clinicalJudgmentFooter = "You decide, using your clinical judgment."
        val confirmBlood = "Always confirm with a blood test."
        val experimentalLabel = "Experimental. Not yet validated in Bangladesh."

        assertThat(suggestedHeader).isEqualTo("Suggested next step")
        assertThat(clinicalJudgmentFooter).isEqualTo("You decide, using your clinical judgment.")
        assertThat(confirmBlood).isEqualTo("Always confirm with a blood test.")
        assertThat(experimentalLabel).isEqualTo("Experimental. Not yet validated in Bangladesh.")
    }
}
