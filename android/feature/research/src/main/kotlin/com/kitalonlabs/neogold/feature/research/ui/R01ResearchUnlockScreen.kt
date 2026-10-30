package com.kitalonlabs.neogold.feature.research.ui

import androidx.compose.foundation.background
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.kitalonlabs.neogold.core.designsystem.R
import com.kitalonlabs.neogold.core.designsystem.components.atoms.NgBadge
import com.kitalonlabs.neogold.core.designsystem.components.atoms.NgIcons
import com.kitalonlabs.neogold.core.designsystem.components.atoms.NgPrimaryButton
import com.kitalonlabs.neogold.core.designsystem.components.atoms.NgSecondaryButton
import com.kitalonlabs.neogold.core.designsystem.theme.CardCornerRadius
import com.kitalonlabs.neogold.engine.camera.host.CameraMode
import com.kitalonlabs.neogold.engine.camera.ui.CameraPreviewHost
import com.kitalonlabs.neogold.engine.cardtrust.StudyProfilePayload
import com.kitalonlabs.neogold.feature.research.data.ResearchError
import com.kitalonlabs.neogold.feature.research.R as ResearchR

/**
 * R01: Research Mode Unlock & Enrollment Screen (FR-25, NG-028, doc 6 Section 5).
 * Allows scanning signed study QR, confirming study metadata, and unlocking via research PIN.
 */
@Composable
fun R01ResearchUnlockScreen(
    scannedProfile: StudyProfilePayload?,
    existingStudyName: String?,
    isLockedOut: Boolean,
    lockoutMinutes: Int,
    error: ResearchError?,
    onQrScanned: (qrPayload: ByteArray) -> Unit,
    onUnlockWithPin: (pin: String) -> Unit,
    onEnrollWithPin: (pin: String, confirmPin: String) -> Unit,
    modifier: Modifier = Modifier
) {
    var enteredPin by remember { mutableStateOf("") }
    var confirmPin by remember { mutableStateOf("") }
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
        // Research Mode Badge Header
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = stringResource(ResearchR.string.r01_title),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground
            )
            NgBadge(
                text = stringResource(ResearchR.string.research_badge),
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        if (isLockedOut) {
            Card(
                shape = RoundedCornerShape(CardCornerRadius),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("lockout_card")
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.padding(16.dp)
                ) {
                    Icon(
                        imageVector = NgIcons.Block,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(32.dp)
                    )
                    Text(
                        text = stringResource(R.string.pin_locked, lockoutMinutes),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
        }

        if (error != null) {
            Card(
                shape = RoundedCornerShape(CardCornerRadius),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("error_card")
            ) {
                Text(
                    text = error.text(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.padding(16.dp)
                )
            }
            Spacer(modifier = Modifier.height(16.dp))
        }

        if (scannedProfile != null) {
            // New Study Confirmation (doc 8 Section 7.1)
            Card(
                shape = RoundedCornerShape(CardCornerRadius),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("study_confirmation_card")
            ) {
                Column(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(16.dp)
                ) {
                    Text(
                        text = stringResource(ResearchR.string.r01_confirm_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = stringResource(
                            ResearchR.string.r01_study_named,
                            scannedProfile.displayName,
                            scannedProfile.studyCode
                        ),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = stringResource(ResearchR.string.r01_partner, scannedProfile.partner),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = stringResource(
                            ResearchR.string.r01_host,
                            scannedProfile.host ?: stringResource(ResearchR.string.r01_no_host)
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = stringResource(ResearchR.string.r01_valid_until, scannedProfile.expiresAt.toString()),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = stringResource(ResearchR.string.r01_set_pin),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onBackground
            )

            Spacer(modifier = Modifier.height(8.dp))

            OutlinedTextField(
                value = enteredPin,
                onValueChange = { if (it.length <= 12) enteredPin = it },
                label = { Text(stringResource(ResearchR.string.r01_pin_label)) },
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("set_pin_field")
            )

            Spacer(modifier = Modifier.height(8.dp))

            OutlinedTextField(
                value = confirmPin,
                onValueChange = { if (it.length <= 12) confirmPin = it },
                label = { Text(stringResource(ResearchR.string.r01_pin_confirm)) },
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("confirm_pin_field")
            )

            Spacer(modifier = Modifier.height(24.dp))

            NgPrimaryButton(
                text = stringResource(ResearchR.string.r01_pin_set_button),
                enabled = !isLockedOut && enteredPin.length >= 6 && enteredPin == confirmPin,
                onClick = { onEnrollWithPin(enteredPin, confirmPin) },
                modifier = Modifier.testTag("enroll_button")
            )
        } else {
            // Existing Study Unlock or Prompt to Scan
            if (existingStudyName != null) {
                Card(
                    shape = RoundedCornerShape(CardCornerRadius),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                        modifier = Modifier.padding(16.dp)
                    ) {
                        Text(
                            text = stringResource(ResearchR.string.r01_enrolled_study),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = existingStudyName,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                OutlinedTextField(
                    value = enteredPin,
                    onValueChange = { if (it.length <= 12) enteredPin = it },
                    label = { Text(stringResource(ResearchR.string.r01_enter_pin)) },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    singleLine = true,
                    enabled = !isLockedOut,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("unlock_pin_field")
                )

                Spacer(modifier = Modifier.height(24.dp))

                NgPrimaryButton(
                    text = stringResource(ResearchR.string.r01_unlock),
                    enabled = !isLockedOut && enteredPin.length >= 6,
                    onClick = { onUnlockWithPin(enteredPin) },
                    modifier = Modifier.testTag("unlock_button")
                )

                Spacer(modifier = Modifier.height(16.dp))
            }

            NgSecondaryButton(
                text = stringResource(ResearchR.string.r01_scan_qr),
                onClick = { scanning = true },
                modifier = Modifier.testTag("scan_study_qr_button")
            )
        }
    }
}

/** The camera, looking for the signed study QR; it is released as soon as a payload arrives or the scan is cancelled. */
@Composable
private fun StudyQrScanner(onPayload: (ByteArray) -> Unit, onCancel: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(16.dp)
    ) {
        Text(
            text = stringResource(ResearchR.string.r01_scan_hint),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onBackground
        )
        Spacer(modifier = Modifier.height(16.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(320.dp)
                .clip(RoundedCornerShape(CardCornerRadius))
                .testTag("study_qr_viewfinder")
        ) {
            CameraPreviewHost(
                mode = CameraMode.ReadQr(onPayload = onPayload),
                onSession = {},
                modifier = Modifier.fillMaxSize()
            )
        }
        Spacer(modifier = Modifier.height(16.dp))
        NgSecondaryButton(
            text = stringResource(ResearchR.string.r01_scan_cancel),
            onClick = onCancel,
            modifier = Modifier.testTag("scan_cancel_button")
        )
    }
}
