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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.kitalonlabs.neogold.core.designsystem.R
import com.kitalonlabs.neogold.core.designsystem.components.atoms.NgBadge
import com.kitalonlabs.neogold.core.designsystem.components.atoms.NgPrimaryButton
import com.kitalonlabs.neogold.core.designsystem.components.atoms.NgTextButton
import com.kitalonlabs.neogold.core.designsystem.theme.Dimens
import com.kitalonlabs.neogold.engine.camera.host.CameraMode
import com.kitalonlabs.neogold.engine.camera.qualification.RequalificationReason
import com.kitalonlabs.neogold.engine.camera.ui.CameraPreviewHost
import com.kitalonlabs.neogold.feature.onboarding.domain.CardRejection
import com.kitalonlabs.neogold.feature.onboarding.domain.CardScanState
import com.kitalonlabs.neogold.feature.onboarding.R as OnboardingR

/**
 * S06: Card scan (QR) (NG-039, doc 6 Section 5).
 *
 * Reads the QR on the calibration card with the camera and checks it against the trust list. Nothing is shown as
 * verified until the card's signature, issuer, purpose and dates have passed (TC-CARD-01 to TC-CARD-06); a card that
 * fails says why, and Continue stays off.
 */
@Composable
fun S06CardScanScreen(
    state: CardScanState,
    onContinue: (batchId: String) -> Unit,
    modifier: Modifier = Modifier,
    requalification: RequalificationReason? = null
) {
    val phase by state.phase.collectAsState()

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

            if (requalification != null) {
                Text(
                    text = stringResource(
                        if (requalification == RequalificationReason.OS_BUILD_CHANGED) {
                            OnboardingR.string.requalify_os_changed
                        } else {
                            OnboardingR.string.requalify_30_days
                        }
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.padding(bottom = Dimens.SpacingSm).testTag("requalify_reason")
                )
            }

            Text(
                text = stringResource(R.string.s06_instruction),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(Dimens.SpacingXl))

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(260.dp)
                    .clip(RoundedCornerShape(Dimens.CardCornerRadius))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(Dimens.CardCornerRadius))
                    .testTag("card_scan_viewfinder"),
                contentAlignment = Alignment.Center
            ) {
                // The camera runs only while the card is being looked for; once there is an outcome it is released.
                if (phase is CardScanState.Phase.Scanning || phase is CardScanState.Phase.Checking) {
                    CameraPreviewHost(
                        mode = CameraMode.ReadQr(onPayload = state::onPayload),
                        onSession = {},
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }

            Spacer(modifier = Modifier.height(Dimens.SpacingLg))

            ScanStatus(phase = phase, onScanAgain = state::scanAgain)
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = Dimens.SpacingLg)
        ) {
            val verified = phase as? CardScanState.Phase.Verified
            NgPrimaryButton(
                text = stringResource(R.string.btn_continue),
                onClick = { verified?.let { onContinue(it.batchId) } },
                enabled = verified != null,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("btn_card_scan_continue")
            )
        }
    }
}

@Composable
private fun ScanStatus(phase: CardScanState.Phase, onScanAgain: () -> Unit) {
    when (phase) {
        CardScanState.Phase.Scanning -> StatusText(stringResource(OnboardingR.string.s06_looking), "scan_looking")
        CardScanState.Phase.Checking -> StatusText(stringResource(OnboardingR.string.s06_checking), "scan_checking")
        is CardScanState.Phase.Verified -> Box(
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
                    text = stringResource(R.string.s06_card_verified, phase.batchId),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                NgBadge(
                    text = stringResource(OnboardingR.string.s06_verified_badge),
                    modifier = Modifier.testTag("badge_card_verified")
                )
            }
        }
        is CardScanState.Phase.Rejected -> Column(modifier = Modifier.fillMaxWidth()) {
            StatusText(rejectionMessage(phase.reason), "card_rejected", MaterialTheme.colorScheme.error)
            NgTextButton(
                text = stringResource(OnboardingR.string.s06_scan_again),
                onClick = onScanAgain,
                modifier = Modifier.testTag("btn_scan_again")
            )
        }
    }
}

@Composable
private fun StatusText(
    text: String,
    tag: String,
    color: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onSurfaceVariant
) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = color,
        modifier = Modifier.testTag(tag)
    )
}

@Composable
private fun rejectionMessage(reason: CardRejection): String = when (reason) {
    CardRejection.NOT_TRUSTED -> stringResource(R.string.card_invalid)
    CardRejection.EXPIRED -> stringResource(OnboardingR.string.s06_card_expired)
    CardRejection.REVOKED -> stringResource(OnboardingR.string.s06_card_revoked)
    CardRejection.UNREADABLE -> stringResource(OnboardingR.string.s06_card_unreadable)
    CardRejection.NOT_SAVED -> stringResource(OnboardingR.string.s06_card_not_saved)
}
