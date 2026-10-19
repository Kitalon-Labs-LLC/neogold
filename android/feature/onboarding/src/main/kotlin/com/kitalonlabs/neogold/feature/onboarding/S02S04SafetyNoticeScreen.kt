package com.kitalonlabs.neogold.feature.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.kitalonlabs.neogold.core.designsystem.components.organisms.NgSafetyNoticePager

/**
 * S02 to S04: Mandatory Safety Notice (NG-003, TC-ONB-02, TC-ONB-03, doc 6 Section 5).
 *
 * Pager displaying 3 safety notice pages:
 * - What NeoGold does (decision support for trained health workers)
 * - What NeoGold does not do (never claims baby is free of jaundice)
 * - Always confirm with a blood test
 *
 * Cannot proceed without tapping "I understand" on the final page.
 */
@Composable
fun S02S04SafetyNoticeScreen(
    onAcknowledge: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        NgSafetyNoticePager(
            onAcknowledged = onAcknowledge,
            modifier = Modifier.fillMaxSize()
        )
    }
}
