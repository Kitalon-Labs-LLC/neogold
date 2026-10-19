package com.kitalonlabs.neogold.feature.settings

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
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import com.kitalonlabs.neogold.core.designsystem.components.atoms.NgIcon
import com.kitalonlabs.neogold.core.designsystem.components.atoms.NgIcons
import com.kitalonlabs.neogold.core.designsystem.theme.Dimens
import com.kitalonlabs.neogold.core.designsystem.theme.LocalTriageColors

/**
 * S25: Delete all data (confirm) (FR-33, NG-026, doc 6 Section 5).
 *
 * Prompts user to permanently erase all local screening sessions,
 * database keys, and settings. Calls DataPurgeManager.deleteAllData().
 */
@Composable
fun S25DeleteAllDataScreen(
    onConfirmDelete: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier
) {
    val triageColors = LocalTriageColors.current

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(Dimens.ScreenPadding),
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Spacer(modifier = Modifier.height(24.dp))

            Text(
                text = stringResource(R.string.delete_data_title),
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onBackground
            )

            Spacer(modifier = Modifier.height(Dimens.SpacingLg))

            // Warning Box
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(Dimens.CardCornerRadius))
                    .background(triageColors.urgentTint)
                    .border(2.dp, triageColors.urgentSolid, RoundedCornerShape(Dimens.CardCornerRadius))
                    .padding(Dimens.SpacingMd)
                    .testTag("delete_data_warning_box")
            ) {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        NgIcon(
                            imageVector = NgIcons.WarningOctagon,
                            contentDescription = stringResource(R.string.delete_data_title),
                            tint = triageColors.urgentSolid,
                            modifier = Modifier.size(32.dp)
                        )
                        Spacer(modifier = Modifier.width(Dimens.SpacingSm))
                        Text(
                            text = "Permanent Deletion",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = triageColors.urgentSolid
                        )
                    }

                    Spacer(modifier = Modifier.height(Dimens.SpacingSm))

                    Text(
                        text = stringResource(R.string.delete_data_warning),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }

        // Action Buttons
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = Dimens.SpacingLg)
        ) {
            Button(
                onClick = onConfirmDelete,
                colors = ButtonDefaults.buttonColors(
                    containerColor = triageColors.urgentSolid,
                    contentColor = androidx.compose.ui.graphics.Color.White
                ),
                shape = RoundedCornerShape(Dimens.ButtonCornerRadius),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(Dimens.PrimaryButtonHeight)
                    .testTag("btn_confirm_delete_all")
            ) {
                Text(
                    text = stringResource(R.string.delete_data_confirm_btn),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }

            Spacer(modifier = Modifier.height(Dimens.SpacingMd))

            OutlinedButton(
                onClick = onCancel,
                shape = RoundedCornerShape(Dimens.ButtonCornerRadius),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(Dimens.PrimaryButtonHeight)
                    .testTag("btn_cancel_delete_all")
            ) {
                Text(
                    text = stringResource(R.string.btn_cancel),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium
                )
            }
        }
    }
}
