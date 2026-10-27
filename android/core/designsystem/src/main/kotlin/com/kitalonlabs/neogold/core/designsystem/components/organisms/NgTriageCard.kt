package com.kitalonlabs.neogold.core.designsystem.components.organisms

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.kitalonlabs.neogold.core.designsystem.R
import com.kitalonlabs.neogold.core.designsystem.components.atoms.NgIcons
import com.kitalonlabs.neogold.core.designsystem.components.molecules.NgEstimateRangeBar
import com.kitalonlabs.neogold.core.designsystem.theme.CardCornerRadius
import com.kitalonlabs.neogold.core.designsystem.theme.LocalTriageColors

/**
 * NgTriageCard (doc 6 Section 3, Section 4.3).
 * Redundant 4-signal system:
 * 1. Header color
 * 2. Icon (32 dp)
 * 3. Border (4 dp solid for urgent/blood test, 2 dp solid for recheck, 2 dp outline for no further action)
 * 4. Distinct wording
 *
 * Includes required decision support framing (PRD D11):
 * - "Suggested next step" above
 * - "You decide, using your clinical judgment." below
 */
@Composable
fun NgTriageCard(
    action: TriageUiAction,
    recheckHours: Int = 24,
    nextStepText: String,
    reasonText: String? = null,
    estimatedLow: Double? = null,
    estimatedHigh: Double? = null,
    threshold: Double? = null,
    modifier: Modifier = Modifier
) {
    val triageColors = LocalTriageColors.current

    val style = when (action) {
        TriageUiAction.REFER_URGENT -> TriageCardStyle(
            solidHeaderColor = triageColors.urgentSolid,
            bodyTintColor = triageColors.urgentTint,
            bodyTextColor = triageColors.urgentText,
            icon = NgIcons.WarningOctagon,
            actionText = stringResource(R.string.action_refer_urgent),
            borderStroke = BorderStroke(4.dp, triageColors.urgentSolid)
        )
        TriageUiAction.BLOOD_TEST -> TriageCardStyle(
            solidHeaderColor = triageColors.bloodTestSolid,
            bodyTintColor = triageColors.bloodTestTint,
            bodyTextColor = triageColors.bloodTestText,
            icon = NgIcons.BloodDrop,
            actionText = stringResource(R.string.action_blood_test),
            borderStroke = BorderStroke(4.dp, triageColors.bloodTestSolid)
        )
        TriageUiAction.RECHECK -> TriageCardStyle(
            solidHeaderColor = triageColors.recheckSolid,
            bodyTintColor = triageColors.recheckTint,
            bodyTextColor = triageColors.recheckText,
            icon = NgIcons.Clock,
            actionText = stringResource(R.string.action_recheck, recheckHours),
            borderStroke = BorderStroke(2.dp, triageColors.recheckSolid)
        )
        TriageUiAction.NO_FURTHER_ACTION -> TriageCardStyle(
            solidHeaderColor = triageColors.noActionSolid,
            bodyTintColor = triageColors.noActionTint,
            bodyTextColor = triageColors.noActionText,
            icon = NgIcons.Eye,
            actionText = stringResource(R.string.action_no_further),
            borderStroke = BorderStroke(2.dp, triageColors.noActionSolid)
        )
        TriageUiAction.CANNOT_SCREEN -> TriageCardStyle(
            solidHeaderColor = triageColors.noActionSolid,
            bodyTintColor = triageColors.noActionTint,
            bodyTextColor = triageColors.noActionText,
            icon = NgIcons.Block,
            actionText = stringResource(R.string.cannot_screen),
            borderStroke = BorderStroke(2.dp, triageColors.noActionSolid)
        )
    }

    val solidHeaderColor = style.solidHeaderColor
    val bodyTintColor = style.bodyTintColor
    val bodyTextColor = style.bodyTextColor
    val icon = style.icon
    val actionText = style.actionText
    val borderStroke = style.borderStroke

    Column(modifier = modifier.fillMaxWidth()) {
        // Suggested next step label (PRD D11, TC-UI-14)
        Text(
            text = stringResource(R.string.result_suggested),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(8.dp))

        // Main 4-signal triage card
        Card(
            shape = RoundedCornerShape(CardCornerRadius),
            border = borderStroke,
            colors = CardDefaults.cardColors(containerColor = bodyTintColor),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column {
                // Solid Header (Color + Icon + Action Words)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(solidHeaderColor)
                        .padding(horizontal = 16.dp, vertical = 14.dp)
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(32.dp)
                    )
                    Text(
                        text = actionText,
                        style = MaterialTheme.typography.headlineLarge,
                        color = Color.White,
                        fontWeight = FontWeight.Bold
                    )
                }

                // Tinted Body (Next Step line + Reason + Optional range bar)
                Column(
                    modifier = Modifier.padding(16.dp)
                ) {
                    // Next step instruction
                    Text(
                        text = nextStepText,
                        style = MaterialTheme.typography.bodyLarge,
                        color = bodyTextColor,
                        fontWeight = FontWeight.SemiBold
                    )

                    if (!reasonText.isNullOrBlank()) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = reasonText,
                            style = MaterialTheme.typography.bodyMedium,
                            color = bodyTextColor
                        )
                    }

                    if (estimatedLow != null && estimatedHigh != null && threshold != null) {
                        Spacer(modifier = Modifier.height(16.dp))
                        NgEstimateRangeBar(
                            estimatedLow = estimatedLow,
                            estimatedHigh = estimatedHigh,
                            threshold = threshold
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Clinical judgment reminder (PRD D11, TC-UI-14)
        Text(
            text = stringResource(R.string.result_you_decide),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

private data class TriageCardStyle(
    val solidHeaderColor: Color,
    val bodyTintColor: Color,
    val bodyTextColor: Color,
    val icon: ImageVector,
    val actionText: String,
    val borderStroke: BorderStroke
)
