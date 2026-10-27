@file:Suppress("MagicNumber") // gestational age stepper bounds; the first-tap value is revisited in E1

package com.kitalonlabs.neogold.core.designsystem.components.atoms

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedIconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.kitalonlabs.neogold.core.designsystem.theme.MinTouchTarget

/**
 * NgNumberStepper (doc 6 Section 4.1).
 * Gestational age stepper: large minus and plus buttons around the number, range 22 to 44.
 * Starts unselected/null if not chosen (PRD H3: no silent defaults).
 */
@Composable
fun NgNumberStepper(
    value: Int?,
    onValueChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
    range: IntRange = 22..44,
    unselectedPlaceholder: String = "--"
) {
    val canDecrement = value != null && value > range.first
    val canIncrement = value != null && value < range.last

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
        modifier = modifier
    ) {
        // Decrement button
        OutlinedIconButton(
            onClick = {
                if (value == null) {
                    onValueChange(38)
                } else if (canDecrement) {
                    onValueChange(value - 1)
                }
            },
            enabled = value == null || canDecrement,
            modifier = Modifier
                .size(56.dp)
                .defaultMinSize(minWidth = MinTouchTarget, minHeight = MinTouchTarget)
        ) {
            Text(
                text = "-",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold
            )
        }

        // Display value
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .padding(horizontal = 24.dp)
                .defaultMinSize(minWidth = 80.dp)
        ) {
            Text(
                text = value?.toString() ?: unselectedPlaceholder,
                style = MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.Bold,
                color = if (value != null) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outline
            )
        }

        // Increment button
        OutlinedIconButton(
            onClick = {
                if (value == null) {
                    onValueChange(38)
                } else if (canIncrement) {
                    onValueChange(value + 1)
                }
            },
            enabled = value == null || canIncrement,
            modifier = Modifier
                .size(56.dp)
                .defaultMinSize(minWidth = MinTouchTarget, minHeight = MinTouchTarget)
        ) {
            Text(
                text = "+",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

/**
 * NgIcon (doc 6 Section 4.1).
 * Enforces a mandatory contentDescription for accessibility (TalkBack),
 * or requires isDecorative = true to mark as null in the accessibility tree.
 */
@Composable
fun NgIcon(
    imageVector: ImageVector,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    tint: Color = MaterialTheme.colorScheme.onSurface,
    isDecorative: Boolean = false
) {
    require(isDecorative || !contentDescription.isNullOrBlank()) {
        "NgIcon must either have a non-blank contentDescription or be marked isDecorative = true."
    }

    Icon(
        imageVector = imageVector,
        contentDescription = if (isDecorative) null else contentDescription,
        tint = tint,
        modifier = modifier.size(24.dp)
    )
}

/**
 * NgBadge (doc 6 Section 4.1).
 * Small status tag. Text required.
 */
@Composable
fun NgBadge(
    text: String,
    modifier: Modifier = Modifier,
    containerColor: Color = MaterialTheme.colorScheme.surface,
    contentColor: Color = MaterialTheme.colorScheme.onSurface,
    borderColor: Color? = MaterialTheme.colorScheme.outline
) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .background(containerColor, RoundedCornerShape(8.dp))
            .then(
                if (borderColor != null) {
                    Modifier.background(containerColor, RoundedCornerShape(8.dp))
                } else {
                    Modifier
                }
            )
            .padding(horizontal = 8.dp, vertical = 4.dp)
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = contentColor
        )
    }
}
