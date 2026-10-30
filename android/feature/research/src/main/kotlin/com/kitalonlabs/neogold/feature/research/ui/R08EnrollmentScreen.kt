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
import androidx.compose.material.icons.filled.Share
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
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.kitalonlabs.neogold.core.designsystem.R
import com.kitalonlabs.neogold.core.designsystem.components.atoms.NgBadge
import com.kitalonlabs.neogold.core.designsystem.components.atoms.NgPrimaryButton
import com.kitalonlabs.neogold.core.designsystem.components.atoms.NgSecondaryButton
import com.kitalonlabs.neogold.core.designsystem.theme.CardCornerRadius
import com.kitalonlabs.neogold.engine.cardtrust.StudyProfilePayload
import com.kitalonlabs.neogold.feature.research.data.ResearchError
import com.kitalonlabs.neogold.feature.research.R as ResearchR

/**
 * R08: Study Server Enrollment Screen (doc 6 Section 5, doc 7 Section 4, NG-042).
 * Scans the signed study QR again (the phone already has the study; the QR names the server), accepts the one-time
 * enrollment code (Crockford Base32) and confirms with the health worker before anything is sent.
 *
 * The screen shows what the signed QR says, never what a server answers: failures arrive as a typed [ResearchError].
 */
@Composable
fun R08EnrollmentScreen(
    scannedProfile: StudyProfilePayload?,
    isEnrolling: Boolean,
    error: ResearchError?,
    onQrScanned: (qrPayload: ByteArray) -> Unit,
    onEnrollConfirmed: (enrollmentCode: String) -> Unit,
    onCancelClicked: () -> Unit,
    modifier: Modifier = Modifier
) {
    var enrollmentCodeInput by remember { mutableStateOf("") }
    var showConfirmDialog by remember { mutableStateOf(false) }
    var scanning by remember { mutableStateOf(false) }

    if (scanning) {
        StudyQrScanner(
            onPayload = {
                scanning = false
                onQrScanned(it)
            },
            onCancel = { scanning = false },
            modifier = modifier
        )
        return
    }

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
                text = stringResource(ResearchR.string.r08_badge),
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.testTag("enroll_badge")
            )
            Icon(
                imageVector = Icons.Default.Share,
                contentDescription = stringResource(ResearchR.string.r08_cd_cloud),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = stringResource(ResearchR.string.r08_title),
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = stringResource(ResearchR.string.r08_intro),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(24.dp))

        if (scannedProfile == null) {
            Text(
                text = stringResource(ResearchR.string.r08_scan_hint),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(16.dp))
            NgPrimaryButton(
                text = stringResource(ResearchR.string.r08_scan_button),
                onClick = { scanning = true },
                modifier = Modifier.testTag("enroll_scan_button")
            )
        } else {
            StudyCard(scannedProfile)

            Spacer(modifier = Modifier.height(24.dp))

            // One-time code input (Crockford base32)
            OutlinedTextField(
                value = enrollmentCodeInput,
                onValueChange = { input ->
                    enrollmentCodeInput = input.uppercase().filter { it.isLetterOrDigit() || it == '-' }
                },
                label = { Text(stringResource(ResearchR.string.r08_code_label)) },
                placeholder = { Text(stringResource(ResearchR.string.r08_code_hint)) },
                singleLine = true,
                isError = error != null,
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Characters,
                    autoCorrectEnabled = false,
                    keyboardType = KeyboardType.Password
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("enroll_code_input")
            )
        }

        if (error != null) {
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Warning,
                    contentDescription = stringResource(ResearchR.string.r08_cd_error),
                    tint = MaterialTheme.colorScheme.error
                )
                Text(
                    text = error.text(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.testTag("enroll_error_text")
                )
            }
        }

        Spacer(modifier = Modifier.height(32.dp))

        if (scannedProfile != null) {
            NgPrimaryButton(
                text = stringResource(if (isEnrolling) ResearchR.string.r08_joining else ResearchR.string.r08_join),
                onClick = {
                    if (enrollmentCodeInput.isNotBlank()) {
                        showConfirmDialog = true
                    }
                },
                enabled = enrollmentCodeInput.isNotBlank() && !isEnrolling,
                modifier = Modifier.testTag("enroll_join_button")
            )

            Spacer(modifier = Modifier.height(12.dp))
        }

        NgSecondaryButton(
            text = stringResource(ResearchR.string.r08_cancel),
            onClick = onCancelClicked,
            enabled = !isEnrolling,
            modifier = Modifier.testTag("enroll_cancel_button")
        )
    }

    if (showConfirmDialog && scannedProfile != null) {
        AlertDialog(
            onDismissRequest = { showConfirmDialog = false },
            title = {
                Text(
                    text = stringResource(ResearchR.string.r08_confirm_title),
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Text(
                    text = stringResource(
                        R.string.enroll_confirm,
                        scannedProfile.displayName,
                        scannedProfile.partner,
                        scannedProfile.host.orEmpty()
                    )
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showConfirmDialog = false
                        onEnrollConfirmed(enrollmentCodeInput.trim())
                    },
                    modifier = Modifier.testTag("enroll_dialog_confirm")
                ) {
                    Text(stringResource(ResearchR.string.r08_confirm_ok))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showConfirmDialog = false },
                    modifier = Modifier.testTag("enroll_dialog_cancel")
                ) {
                    Text(stringResource(ResearchR.string.r08_cancel))
                }
            }
        )
    }
}

/** What the signed study QR says about the study and the server the data would go to. */
@Composable
private fun StudyCard(profile: StudyProfilePayload) {
    Card(
        shape = RoundedCornerShape(CardCornerRadius),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("enroll_study_card")
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = profile.displayName,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = stringResource(ResearchR.string.r08_partner, profile.partner),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = stringResource(ResearchR.string.r08_server, profile.host.orEmpty()),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline
            )
        }
    }
}
