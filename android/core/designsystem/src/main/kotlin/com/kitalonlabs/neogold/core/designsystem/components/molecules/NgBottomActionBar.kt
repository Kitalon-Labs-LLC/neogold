package com.kitalonlabs.neogold.core.designsystem.components.molecules

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.kitalonlabs.neogold.core.designsystem.theme.YesNoButtonGap

/**
 * NgBottomActionBar (doc 6 Section 3: one main action, reachable with one hand).
 *
 * Pins the screen's action in the lower third so it sits under the thumb instead of at the
 * end of a scrolling column. A hairline divider separates it from the content (doc 6
 * Section 2.3: borders, not shadows), and the bar consumes the navigation-bar inset itself
 * so the button never lands under the gesture pill on a full-screen-gesture phone.
 *
 * Use it as a Scaffold's `bottomBar`, so the Scaffold's content padding shrinks by exactly the
 * bar's height and the scrolling content never hides behind it.
 */
@Composable
fun NgBottomActionBar(
    modifier: Modifier = Modifier,
    showDivider: Boolean = true,
    content: @Composable ColumnScope.() -> Unit
) {
    Surface(
        color = MaterialTheme.colorScheme.background,
        modifier = modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            if (showDivider) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f))
            }
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .windowInsetsPadding(WindowInsets.navigationBars)
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(YesNoButtonGap),
                content = content
            )
        }
    }
}
