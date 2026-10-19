package com.kitalonlabs.neogold.core.designsystem.components.molecules

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.kitalonlabs.neogold.core.designsystem.R
import com.kitalonlabs.neogold.core.designsystem.components.atoms.NgIcons
import com.kitalonlabs.neogold.core.designsystem.theme.CardCornerRadius
import com.kitalonlabs.neogold.core.designsystem.theme.ExperimentalLabelText
import com.kitalonlabs.neogold.core.designsystem.theme.ExperimentalLabelTint
import com.kitalonlabs.neogold.core.designsystem.theme.LocalTriageColors
import com.kitalonlabs.neogold.core.designsystem.util.ClinicalNumberFormatter

/**
 * NgExperimentalBanner (doc 6 Section 4.2).
 * Amber-tinted banner: "Experimental. Not yet validated in Bangladesh."
 * Required on Home and every result screen (PRD Section 13, D11).
 */
@Composable
fun NgExperimentalBanner(
    modifier: Modifier = Modifier
) {
    val triageColors = LocalTriageColors.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(triageColors.experimentalTint)
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Icon(
            imageVector = NgIcons.Science,
            contentDescription = null,
            tint = triageColors.experimentalText,
            modifier = Modifier.size(20.dp)
        )
        Text(
            text = stringResource(R.string.experimental),
            style = MaterialTheme.typography.labelLarge,
            color = triageColors.experimentalText,
            fontWeight = FontWeight.Medium
        )
    }
}

/**
 * NgQualityFeedback (doc 6 Section 4.2).
 * Plain-language reason a photo failed and how to fix it.
 */
@Composable
fun NgQualityFeedback(
    reasonText: String,
    guidanceText: String,
    modifier: Modifier = Modifier
) {
    Card(
        shape = RoundedCornerShape(CardCornerRadius),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        modifier = modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Warning,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(24.dp)
                )
                Text(
                    text = reasonText,
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.error,
                    fontWeight = FontWeight.Bold
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = guidanceText,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

/**
 * NgEstimateRangeBar (doc 6 Section 4.2).
 * Horizontal bar showing the estimated range against the treatment threshold.
 */
@Composable
fun NgEstimateRangeBar(
    estimatedLow: Double,
    estimatedHigh: Double,
    threshold: Double,
    modifier: Modifier = Modifier,
    unit: String = "mg/dL"
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = "Estimated range",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = ClinicalNumberFormatter.formatClinicalRange(estimatedLow, estimatedHigh, unit),
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Range bar graphic: max scale at threshold + 5.0
        val maxScale = (threshold + 5.0).coerceAtLeast(estimatedHigh + 2.0)
        val lowFraction = (estimatedLow / maxScale).toFloat().coerceIn(0f, 1f)
        val highFraction = (estimatedHigh / maxScale).toFloat().coerceIn(0f, 1f)
        val thresholdFraction = (threshold / maxScale).toFloat().coerceIn(0f, 1f)

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(20.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(MaterialTheme.colorScheme.surface)
                .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(6.dp))
        ) {
            // Estimated range highlight
            Box(
                modifier = Modifier
                    .fillMaxWidth(fraction = highFraction)
                    .padding(start = (lowFraction * 300).dp.coerceAtLeast(0.dp))
                    .height(20.dp)
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.35f))
            )
        }

        Spacer(modifier = Modifier.height(4.dp))
        Row(
            horizontalArrangement = Arrangement.End,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = "Threshold: ${ClinicalNumberFormatter.formatClinicalDecimal(threshold)} $unit",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * NgVersionFooter (doc 6 Section 4.2).
 * App, model, config versions and card batch. Non-essential footer in 12 sp.
 */
@Composable
fun NgVersionFooter(
    appVersion: String,
    modelVersion: String,
    configVersion: String,
    cardBatchId: String?,
    modifier: Modifier = Modifier
) {
    val batchPart = if (!cardBatchId.isNullOrBlank()) " • Card $cardBatchId" else ""
    Text(
        text = "App $appVersion • Model $modelVersion • Config $configVersion$batchPart",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier
    )
}

/**
 * NgSyncStatusChip (doc 6 Section 4.2).
 * Research mode only: "X waiting to upload"
 */
@Composable
fun NgSyncStatusChip(
    waitingCount: Int,
    modifier: Modifier = Modifier
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(12.dp))
            .padding(horizontal = 10.dp, vertical = 4.dp)
    ) {
        Icon(
            imageVector = NgIcons.CloudUpload,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(16.dp)
        )
        Text(
            text = stringResource(R.string.upload_waiting, waitingCount),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}
