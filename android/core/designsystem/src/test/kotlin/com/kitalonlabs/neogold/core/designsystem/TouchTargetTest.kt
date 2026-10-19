package com.kitalonlabs.neogold.core.designsystem

import androidx.compose.ui.unit.dp
import com.google.common.truth.Truth.assertThat
import com.kitalonlabs.neogold.core.designsystem.theme.MinTouchTarget
import com.kitalonlabs.neogold.core.designsystem.theme.PrimaryButtonHeight
import com.kitalonlabs.neogold.core.designsystem.theme.ResultButtonHeight
import com.kitalonlabs.neogold.core.designsystem.theme.YesNoButtonGap
import com.kitalonlabs.neogold.core.designsystem.theme.YesNoButtonHeight
import org.junit.Test

/**
 * TC-UI-07: Touch targets (NFR-07, doc 6 Section 2.3).
 * Verifies that all interactive elements adhere to minimum 48 x 48 dp touch target rules,
 * with primary and clinical yes/no buttons sized appropriately for gloved hands.
 */
class TouchTargetTest {

    @Test
    fun testMinimumTouchTargetIsAtLeast48dp() {
        assertThat(MinTouchTarget.value).isAtLeast(48f)
    }

    @Test
    fun testPrimaryButtonHeightMeetsGloveSpecification() {
        assertThat(PrimaryButtonHeight.value).isAtLeast(56f)
        assertThat(ResultButtonHeight.value).isAtLeast(64f)
    }

    @Test
    fun testYesNoButtonsMeetSpecification() {
        assertThat(YesNoButtonHeight.value).isAtLeast(64f)
        assertThat(YesNoButtonGap.value).isAtLeast(12f)
    }
}
