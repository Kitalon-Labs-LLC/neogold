package com.kitalonlabs.neogold.feature.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.kitalonlabs.neogold.core.designsystem.R
import com.kitalonlabs.neogold.core.designsystem.components.atoms.NgBadge
import com.kitalonlabs.neogold.core.designsystem.components.atoms.NgIcon
import com.kitalonlabs.neogold.core.designsystem.components.atoms.NgIcons
import com.kitalonlabs.neogold.core.designsystem.components.atoms.NgPrimaryButton
import com.kitalonlabs.neogold.core.designsystem.util.ClinicalNumberFormatter
import com.kitalonlabs.neogold.core.designsystem.theme.Dimens
import com.kitalonlabs.neogold.core.designsystem.theme.LocalTriageColors
import com.kitalonlabs.neogold.engine.camera.qualification.QualificationResult
import com.kitalonlabs.neogold.engine.camera.qualification.QualificationStatus

/**
 * S08: Qualification result (NG-007, TC-DEV-10, doc 6 Section 5).
 *
 * Displays outcome:
 * - "This phone can be used for screening" or
 * - "This phone's camera changes colors unevenly, so it can be used for research only."
 */
@Composable
fun S08QualificationResultScreen(
    result: QualificationResult,
    onGetStarted: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val isQualified = result.status == QualificationStatus.QUALIFIED
    val triageColors = LocalTriageColors.current

    val (cardBg, cardBorder, statusIcon) = if (isQualified) {
        Triple(
            triageColors.noActionTint,
            triageColors.recheckSolid,
            NgIcons.CheckCircle
        )
    } else {
        Triple(
            triageColors.bloodTestTint,
            triageColors.bloodTestSolid,
            NgIcons.WarningOctagon
        )
    }

    val outcomeText = if (isQualified) {
        stringResource(R.string.phone_qualified)
    } else {
        stringResource(R.string.phone_research_only)
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(Dimens.ScreenPadding),
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = stringResource(R.string.s08_title),
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onBackground
            )

            Spacer(modifier = Modifier.height(Dimens.SpacingLg))

            // Outcome Result Card
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(Dimens.CardCornerRadius))
                    .background(cardBg)
                    .border(2.dp, cardBorder, RoundedCornerShape(Dimens.CardCornerRadius))
                    .padding(Dimens.SpacingMd)
                    .testTag("qualification_result_card")
            ) {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        NgIcon(
                            imageVector = statusIcon,
                            contentDescription = outcomeText,
                            tint = cardBorder,
                            modifier = Modifier.size(32.dp)
                        )
                        Spacer(modifier = Modifier.width(Dimens.SpacingSm))
                        Text(
                            text = if (isQualified) "QUALIFIED" else "RESEARCH ONLY",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }

                    Spacer(modifier = Modifier.height(Dimens.SpacingSm))

                    Text(
                        text = outcomeText,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }

            Spacer(modifier = Modifier.height(Dimens.SpacingLg))

            // Metrics Card
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(Dimens.CardCornerRadius))
                    .background(MaterialTheme.colorScheme.surface)
                    .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(Dimens.CardCornerRadius))
                    .padding(Dimens.SpacingMd)
            ) {
                Column {
                    val repVal = ClinicalNumberFormatter.formatDecimal(result.repeatabilityDe)
                    val uniVal = ClinicalNumberFormatter.formatDecimal(result.uniformityDe)
                    val accVal = ClinicalNumberFormatter.formatDecimal(result.accuracyDe)

                    MetricRow(
                        label = stringResource(R.string.s08_repeatability, repVal),
                        passed = result.repeatabilityPassed
                    )
                    Spacer(modifier = Modifier.height(Dimens.SpacingXs))
                    MetricRow(
                        label = stringResource(R.string.s08_uniformity, uniVal),
                        passed = result.uniformityPassed
                    )
                    Spacer(modifier = Modifier.height(Dimens.SpacingXs))
                    MetricRow(
                        label = stringResource(R.string.s08_accuracy, accVal),
                        passed = result.accuracyPassed
                    )
                }
            }
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = Dimens.SpacingLg)
        ) {
            NgPrimaryButton(
                text = stringResource(R.string.s08_btn_get_started),
                onClick = onGetStarted,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("btn_get_started")
            )
        }
    }
}

@Composable
private fun MetricRow(label: String, passed: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        NgBadge(
            text = if (passed) "PASS" else "FAIL"
        )
    }
}
