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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.kitalonlabs.neogold.core.designsystem.components.atoms.NgBadge
import com.kitalonlabs.neogold.core.designsystem.components.atoms.NgPrimaryButton
import com.kitalonlabs.neogold.core.designsystem.components.atoms.NgSecondaryButton
import com.kitalonlabs.neogold.core.designsystem.theme.CardCornerRadius
import com.kitalonlabs.neogold.feature.research.data.ResearchError
import com.kitalonlabs.neogold.feature.research.R as ResearchR

/**
 * R09: Leave Study Screen (doc 6 Section 5, doc 7 Section 4.3, TC-SYNC-16).
 * Disconnects this phone from the study server after the research PIN is typed again. Deletes only the hardware device key
 * and the server device ID; the study, every session, photo and outbox row stay encrypted on the phone for export.
 */
@Composable
fun R09LeaveStudyScreen(
    studyName: String,
    serverDeviceId: String,
    serverHost: String,
    unsentItems: Int,
    isLeaving: Boolean,
    error: ResearchError?,
    onLeaveConfirmed: (pin: String) -> Unit,
    onCancelClicked: () -> Unit,
    modifier: Modifier = Modifier
) {
    var pin by remember { mutableStateOf("") }
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
                text = stringResource(ResearchR.string.r09_badge),
                containerColor = MaterialTheme.colorScheme.error,
                contentColor = MaterialTheme.colorScheme.onError,
                modifier = Modifier.testTag("leave_study_badge")
            )
            Icon(
                imageVector = Icons.Default.Warning,
                contentDescription = stringResource(ResearchR.string.r09_cd_warning),
                tint = MaterialTheme.colorScheme.error
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = stringResource(ResearchR.string.r09_title),
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = stringResource(ResearchR.string.r09_intro),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(24.dp))

        // What stays on the phone, and what is not yet on the server
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
                    text = stringResource(ResearchR.string.r09_kept_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
                Text(
                    text = stringResource(ResearchR.string.r09_kept_body),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
                if (unsentItems > 0) {
                    Text(
                        text = stringResource(ResearchR.string.r09_unsent, unsentItems),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.testTag("leave_unsent_text")
                    )
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = stringResource(ResearchR.string.r09_study, studyName),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline
                )
                Text(
                    text = stringResource(ResearchR.string.r09_device, serverDeviceId),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline
                )
                Text(
                    text = stringResource(ResearchR.string.r09_server, serverHost),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline
                )
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        // The PIN again: the phone may have been left unlocked, and leaving cannot be undone without a new code.
        OutlinedTextField(
            value = pin,
            onValueChange = { pin = it.filter(Char::isDigit) },
            label = { Text(stringResource(ResearchR.string.r09_pin_label)) },
            singleLine = true,
            isError = error != null,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
            modifier = Modifier
                .fillMaxWidth()
                .testTag("leave_pin_input")
        )

        if (error != null) {
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = error.text(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.testTag("leave_error_text")
            )
        }

        Spacer(modifier = Modifier.height(32.dp))

        NgPrimaryButton(
            text = stringResource(if (isLeaving) ResearchR.string.r09_leaving else ResearchR.string.r09_leave),
            onClick = { showConfirmDialog = true },
            enabled = pin.isNotBlank() && !isLeaving,
            modifier = Modifier.testTag("leave_confirm_button")
        )

        Spacer(modifier = Modifier.height(12.dp))

        NgSecondaryButton(
            text = stringResource(ResearchR.string.r09_cancel),
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
                    text = stringResource(ResearchR.string.r09_dialog_title),
                    fontWeight = FontWeight.Bold
                )
            },
            text = { Text(stringResource(ResearchR.string.r09_dialog_body, serverHost)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showConfirmDialog = false
                        val typed = pin
                        pin = "" // not kept on the screen after it is handed over
                        onLeaveConfirmed(typed)
                    },
                    modifier = Modifier.testTag("leave_dialog_confirm")
                ) {
                    Text(
                        text = stringResource(ResearchR.string.r09_leave),
                        color = MaterialTheme.colorScheme.error
                    )
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showConfirmDialog = false },
                    modifier = Modifier.testTag("leave_dialog_cancel")
                ) {
                    Text(stringResource(ResearchR.string.r09_cancel))
                }
            }
        )
    }
}
