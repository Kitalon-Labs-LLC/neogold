package com.kitalonlabs.neogold.feature.research.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.kitalonlabs.neogold.core.designsystem.R
import com.kitalonlabs.neogold.core.designsystem.components.atoms.NgBadge
import com.kitalonlabs.neogold.core.designsystem.components.atoms.NgPrimaryButton
import com.kitalonlabs.neogold.core.designsystem.components.atoms.NgSecondaryButton
import com.kitalonlabs.neogold.core.designsystem.theme.CardCornerRadius

/**
 * R09: Leave Study Screen (doc 6 Section 5, doc 7 Section 4.3, TC-SYNC-16).
 * Confirms leaving the study server. Deletes only the hardware device key and server_device_id.
 * All local sessions, captures, and outbox rows are preserved for USB export.
 */
@Composable
fun R09LeaveStudyScreen(
    studyName: String,
    serverDeviceId: String,
    serverHost: String,
    isLeaving: Boolean,
    errorMessage: String?,
    onLeaveConfirmed: () -> Unit,
    onCancelClicked: () -> Unit,
    modifier: Modifier = Modifier
) {
    var showConfirmDialog by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(16.dp)
            .verticalScroll(rememberScrollState())
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier.fillMaxWidth()
        ) {
            NgBadge(
                text = "LEAVE STUDY",
                containerColor = MaterialTheme.colorScheme.error,
                contentColor = MaterialTheme.colorScheme.onError,
                modifier = Modifier.testTag("leave_study_badge")
            )
            Icon(
                imageVector = Icons.Default.Warning,
                contentDescription = "Warning",
                tint = MaterialTheme.colorScheme.error
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = "Leave Study Server",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = "Disconnect this phone from the study server. This will stop automatic cloud syncing.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(24.dp))

        // Warning Info Card
        Card(
            shape = RoundedCornerShape(CardCornerRadius),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f)
            ),
            modifier = Modifier
                .fillMaxWidth()
                .testTag("leave_warning_card")
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "Local Data is Preserved",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
                Text(
                    text = stringResource(R.string.upload_removed),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Study: $studyName",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline
                )
                Text(
                    text = "Device ID: $serverDeviceId",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline
                )
                Text(
                    text = "Server: $serverHost",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline
                )
            }
        }

        if (errorMessage != null) {
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = errorMessage,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.testTag("leave_error_text")
            )
        }

        Spacer(modifier = Modifier.height(32.dp))

        NgPrimaryButton(
            text = if (isLeaving) "Leaving Study..." else "Confirm Leave Study",
            onClick = { showConfirmDialog = true },
            enabled = !isLeaving,
            modifier = Modifier.testTag("leave_confirm_button")
        )

        Spacer(modifier = Modifier.height(12.dp))

        NgSecondaryButton(
            text = "Cancel",
            onClick = onCancelClicked,
            enabled = !isLeaving,
            modifier = Modifier.testTag("leave_cancel_button")
        )
    }

    if (showConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showConfirmDialog = false },
            title = {
                Text(
                    text = "Leave Study?",
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Text(
                    "Are you sure you want to leave $studyName? Cloud sync will be disconnected. Un-uploaded data can still be exported via USB."
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showConfirmDialog = false
                        onLeaveConfirmed()
                    },
                    modifier = Modifier.testTag("leave_dialog_confirm")
                ) {
                    Text(
                        text = "Leave Study",
                        color = MaterialTheme.colorScheme.error
                    )
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showConfirmDialog = false },
                    modifier = Modifier.testTag("leave_dialog_cancel")
                ) {
                    Text("Cancel")
                }
            }
        )
    }
}
