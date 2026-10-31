package com.kitalonlabs.neogold.core.designsystem.components.organisms

import androidx.annotation.StringRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
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
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.kitalonlabs.neogold.core.designsystem.R
import com.kitalonlabs.neogold.core.designsystem.components.atoms.NgIcons
import com.kitalonlabs.neogold.core.designsystem.components.molecules.NgEstimateRangeBar
import com.kitalonlabs.neogold.core.designsystem.theme.CardCornerRadius
import com.kitalonlabs.neogold.core.designsystem.theme.LocalTriageColors

/** Which colour family a result uses (doc 6 Section 3). The composable resolves it against the theme. */
internal enum class TriageRole { URGENT, BLOOD_TEST, RECHECK, NO_ACTION }

/**
 * The redundant signals of one result, as plain values so a test can check them without drawing anything (TC-UI-01,
 * BUG-2026-050): the colour family, the icon, the border width and style, and the words.
 */
internal data class TriageSignalSpec(
    val role: TriageRole,
    val icon: ImageVector,
    val borderWidthDp: Int,
    val dashedBorder: Boolean,
    @param:StringRes val wordsRes: Int
)

/** Doc 6 Section 3: red/octagon/4 dp solid, orange/blood drop/4 dp solid, blue/clock/2 dp solid, slate/eye/2 dp DASHED. */
internal fun triageSignalSpec(action: TriageUiAction): TriageSignalSpec = when (action) {
    TriageUiAction.REFER_URGENT ->
        TriageSignalSpec(TriageRole.URGENT, NgIcons.WarningOctagon, 4, false, R.string.action_refer_urgent)
    TriageUiAction.BLOOD_TEST ->
        TriageSignalSpec(TriageRole.BLOOD_TEST, NgIcons.BloodDrop, 4, false, R.string.action_blood_test)
    TriageUiAction.RECHECK ->
        TriageSignalSpec(TriageRole.RECHECK, NgIcons.Clock, 2, false, R.string.action_recheck)
    TriageUiAction.NO_FURTHER_ACTION ->
        TriageSignalSpec(TriageRole.NO_ACTION, NgIcons.Eye, 2, true, R.string.action_no_further)
    // "Cannot screen" is a neutral gray card with the block icon (doc 6 Section 3)
    TriageUiAction.CANNOT_SCREEN ->
        TriageSignalSpec(TriageRole.NO_ACTION, NgIcons.Block, 2, false, R.string.cannot_screen)
}

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

    val spec = triageSignalSpec(action)
    val (solid, tint, text) = when (spec.role) {
        TriageRole.URGENT -> Triple(triageColors.urgentSolid, triageColors.urgentTint, triageColors.urgentText)
        TriageRole.BLOOD_TEST -> Triple(
            triageColors.bloodTestSolid,
            triageColors.bloodTestTint,
            triageColors.bloodTestText
        )
        TriageRole.RECHECK -> Triple(triageColors.recheckSolid, triageColors.recheckTint, triageColors.recheckText)
        TriageRole.NO_ACTION -> Triple(triageColors.noActionSolid, triageColors.noActionTint, triageColors.noActionText)
    }
    val style = TriageCardStyle(
        solidHeaderColor = solid,
        bodyTintColor = tint,
        bodyTextColor = text,
        icon = spec.icon,
        actionText = if (action == TriageUiAction.RECHECK) {
            stringResource(spec.wordsRes, recheckHours)
        } else {
            stringResource(spec.wordsRes)
        },
        // a dashed border is drawn, not set: BorderStroke has no dash (BUG-2026-050)
        borderStroke = if (spec.dashedBorder) null else BorderStroke(spec.borderWidthDp.dp, solid),
        dashedWidthDp = if (spec.dashedBorder) spec.borderWidthDp else 0
    )

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
            modifier = Modifier
                .fillMaxWidth()
                .dashedOutline(style.dashedWidthDp, solidHeaderColor)
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
    val borderStroke: BorderStroke?,
    val dashedWidthDp: Int
)

/** Draws a dashed rounded outline of [widthDp] on top of the content; nothing when it is 0. */
private fun Modifier.dashedOutline(widthDp: Int, color: Color): Modifier =
    if (widthDp <= 0) {
        this
    } else {
        drawWithContent {
            drawContent()
            val strokeWidth = widthDp.dp.toPx()
            val dash = DASH_ON_DP.dp.toPx()
            drawRoundRect(
                color = color,
                topLeft = Offset(strokeWidth / 2, strokeWidth / 2),
                size = Size(size.width - strokeWidth, size.height - strokeWidth),
                cornerRadius = CornerRadius(CardCornerRadius.toPx()),
                style = Stroke(width = strokeWidth, pathEffect = PathEffect.dashPathEffect(floatArrayOf(dash, dash)))
            )
        }
    }

private const val DASH_ON_DP = 8
