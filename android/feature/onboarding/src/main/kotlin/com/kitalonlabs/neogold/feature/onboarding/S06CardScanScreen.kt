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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.kitalonlabs.neogold.core.designsystem.R
import com.kitalonlabs.neogold.core.designsystem.components.atoms.NgBadge
import com.kitalonlabs.neogold.core.designsystem.components.atoms.NgIcon
import com.kitalonlabs.neogold.core.designsystem.components.atoms.NgIcons
import com.kitalonlabs.neogold.core.designsystem.components.atoms.NgPrimaryButton
import com.kitalonlabs.neogold.core.designsystem.theme.Dimens

/**
 * S06: Card scan (QR) (NG-039, doc 6 Section 5).
 *
 * Scans or confirms the verified NeoGold calibration card QR batch.
 */
@Composable
fun S06CardScanScreen(
    initialBatchId: String = "NG-2026-001",
    onCardVerified: (batchId: String) -> Unit = {},
    onContinue: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    var verifiedBatchId by remember { mutableStateOf<String?>(initialBatchId) }

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
                text = stringResource(R.string.s06_title),
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onBackground
            )

            Spacer(modifier = Modifier.height(Dimens.SpacingSm))

            Text(
                text = stringResource(R.string.s06_instruction),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(Dimens.SpacingXl))

            // Viewfinder Mock / Card Outline Box
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(240.dp)
                    .clip(RoundedCornerShape(Dimens.CardCornerRadius))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(Dimens.CardCornerRadius))
                    .testTag("card_scan_viewfinder"),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    NgIcon(
                        imageVector = NgIcons.Science,
                        contentDescription = stringResource(R.string.btn_scan_card),
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(48.dp)
                    )
                    Spacer(modifier = Modifier.height(Dimens.SpacingSm))
                    Text(
                        text = "NeoGold Card Viewfinder",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(modifier = Modifier.height(Dimens.SpacingLg))

            // Card status display
            verifiedBatchId?.let { batch ->
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(Dimens.CardCornerRadius))
                        .background(MaterialTheme.colorScheme.surface)
                        .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(Dimens.CardCornerRadius))
                        .padding(Dimens.SpacingMd)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = stringResource(R.string.s06_card_verified, batch),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        NgBadge(
                            text = "VERIFIED",
                            modifier = Modifier.testTag("badge_card_verified")
                        )
                    }
                }
            }
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = Dimens.SpacingLg)
        ) {
            NgPrimaryButton(
                text = stringResource(R.string.btn_continue),
                onClick = {
                    verifiedBatchId?.let(onCardVerified)
                    onContinue()
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("btn_card_scan_continue")
            )
        }
    }
}
