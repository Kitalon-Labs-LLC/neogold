package com.kitalonlabs.neogold.core.designsystem.components.atoms

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kitalonlabs.neogold.core.designsystem.theme.ButtonCornerRadius
import com.kitalonlabs.neogold.core.designsystem.theme.MinTouchTarget
import com.kitalonlabs.neogold.core.designsystem.theme.PrimaryButtonHeight
import com.kitalonlabs.neogold.core.designsystem.theme.YesNoButtonGap
import com.kitalonlabs.neogold.core.designsystem.theme.YesNoButtonHeight

/**
 * NgPrimaryButton (doc 6 Section 4.1).
 * The one main action per screen. Full width, 56 dp default (64 dp on result / capture).
 * Minimum 48 dp touch target enforced.
 */
@Composable
fun NgPrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    height: Dp = PrimaryButtonHeight,
    containerColor: Color = MaterialTheme.colorScheme.primary,
    contentColor: Color = MaterialTheme.colorScheme.onPrimary
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(ButtonCornerRadius),
        colors = ButtonDefaults.buttonColors(
            containerColor = containerColor,
            contentColor = contentColor,
            disabledContainerColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f),
            disabledContentColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
        ),
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .defaultMinSize(minWidth = MinTouchTarget, minHeight = MinTouchTarget)
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge
        )
    }
}

/**
 * NgSecondaryButton (doc 6 Section 4.1).
 * Alternative action, outlined, never more than one per screen.
 */
@Composable
fun NgSecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    height: Dp = PrimaryButtonHeight
) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(ButtonCornerRadius),
        border = BorderStroke(1.dp, if (enabled) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)),
        colors = ButtonDefaults.outlinedButtonColors(
            contentColor = MaterialTheme.colorScheme.primary
        ),
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .defaultMinSize(minWidth = MinTouchTarget, minHeight = MinTouchTarget)
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge
        )
    }
}

/**
 * NgTextButton (doc 6 Section 4.1).
 * Low-emphasis action (help, cancel, skip).
 */
@Composable
fun NgTextButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    TextButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.defaultMinSize(minWidth = MinTouchTarget, minHeight = MinTouchTarget)
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            color = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
        )
    }
}

/**
 * NgYesNoButtons (doc 6 Section 4.1).
 * Two full-width buttons for answering clinical questions.
 * No default selection (doc 6 Section 8, doc 2 PRD).
 * 64 dp tall each, 12 dp gap.
 */
@Composable
fun NgYesNoButtons(
    selectedAnswer: Boolean?,
    onAnswerSelected: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    yesLabel: String = "Yes",
    noLabel: String = "No"
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(YesNoButtonGap),
        modifier = modifier.fillMaxWidth()
    ) {
        // YES button
        val isYesSelected = selectedAnswer == true
        Button(
            onClick = { onAnswerSelected(true) },
            shape = RoundedCornerShape(ButtonCornerRadius),
            colors = ButtonDefaults.buttonColors(
                containerColor = if (isYesSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface,
                contentColor = if (isYesSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
            ),
            border = if (!isYesSelected) BorderStroke(1.5.dp, MaterialTheme.colorScheme.outline) else null,
            modifier = Modifier
                .fillMaxWidth()
                .height(YesNoButtonHeight)
                .defaultMinSize(minWidth = MinTouchTarget, minHeight = MinTouchTarget)
        ) {
            Text(
                text = yesLabel,
                style = MaterialTheme.typography.titleLarge
            )
        }

        // NO button
        val isNoSelected = selectedAnswer == false
        Button(
            onClick = { onAnswerSelected(false) },
            shape = RoundedCornerShape(ButtonCornerRadius),
            colors = ButtonDefaults.buttonColors(
                containerColor = if (isNoSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface,
                contentColor = if (isNoSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
            ),
            border = if (!isNoSelected) BorderStroke(1.5.dp, MaterialTheme.colorScheme.outline) else null,
            modifier = Modifier
                .fillMaxWidth()
                .height(YesNoButtonHeight)
                .defaultMinSize(minWidth = MinTouchTarget, minHeight = MinTouchTarget)
        ) {
            Text(
                text = noLabel,
                style = MaterialTheme.typography.titleLarge
            )
        }
    }
}
