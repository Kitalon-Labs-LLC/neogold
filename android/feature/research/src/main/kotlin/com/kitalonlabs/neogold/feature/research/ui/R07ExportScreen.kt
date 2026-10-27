@file:Suppress("MaxLineLength") // hardcoded UI text moves to string resources in the localization package (F1)

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
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import com.kitalonlabs.neogold.feature.research.export.ExportProgress

/**
 * R07: Research Export Screen (doc 6 Section 5, doc 8 Section 5.2, FR-30, NG-036).
 * Facilitates de-identified, HPKE/Streaming-AEAD encrypted export to local storage or USB
 * via Storage Access Framework (SAF).
 */
@Composable
fun R07ExportScreen(
    studyCode: String,
    displayName: String,
    hasExportKey: Boolean,
    exportProgress: ExportProgress?,
    isExporting: Boolean,
    exportCompleted: Boolean,
    onChooseDestinationClicked: () -> Unit,
    onDoneClicked: () -> Unit,
    pendingUploadsCount: Int = 0,
    serverDeviceId: String? = null,
    onUploadNowClicked: () -> Unit = {},
    onEnrollClicked: () -> Unit = {},
    onLeaveStudyClicked: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(16.dp)
            .verticalScroll(rememberScrollState())
    ) {
        // Persistent Research Mode Header
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier.fillMaxWidth()
        ) {
            NgBadge(
                text = "RESEARCH MODE",
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.testTag("export_research_badge")
            )
            Icon(
                imageVector = Icons.Default.Lock,
                contentDescription = "Encrypted export",
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = "Export Study Data",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = "Creates an encrypted research export archive (.zip) containing de-identified CSV data tables and research images.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(16.dp))

        // Security Info Card
        Card(
            shape = RoundedCornerShape(CardCornerRadius),
            colors = CardDefaults.cardColors(
                containerColor = if (hasExportKey) {
                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                } else {
                    MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f)
                }
            ),
            modifier = Modifier
                .fillMaxWidth()
                .testTag("export_study_info_card")
        ) {
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(16.dp)
            ) {
                Text(
                    text = "Study: $displayName ($studyCode)",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )

                if (hasExportKey) {
                    Text(
                        text = "• End-to-end encryption: Files are encrypted on-device using Tink Streaming AEAD (AES-256-GCM).\n" +
                            "• Public-key envelope: The encryption key is wrapped to the study coordinator's offline key using Tink HPKE (X25519).\n" +
                            "• Lost USB safe: If the export drive is lost, study records cannot be read without the coordinator's offline private key.\n" +
                            "• De-identified: Only participant codes are included. Zero patient names, birth dates, or facility register numbers.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Warning,
                            contentDescription = "Missing key",
                            tint = MaterialTheme.colorScheme.error
                        )
                        Text(
                            text = "Export public key missing from study profile. Cannot generate encrypted export.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        // Cloud Study Sync Section (doc 6 Section 5, Section 10.4, NG-042)
        Card(
            shape = RoundedCornerShape(CardCornerRadius),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            modifier = Modifier
                .fillMaxWidth()
                .testTag("cloud_sync_card")
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Share,
                        contentDescription = "Cloud Sync",
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = "Cloud Sync Status",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }

                if (serverDeviceId != null) {
                    val waitingText = if (pendingUploadsCount > 0) {
                        stringResource(R.string.upload_waiting, pendingUploadsCount)
                    } else {
                        "All study data uploaded to server"
                    }
                    Text(
                        text = waitingText,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.testTag("upload_waiting_text")
                    )

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        NgPrimaryButton(
                            text = stringResource(R.string.upload_now),
                            onClick = onUploadNowClicked,
                            modifier = Modifier
                                .weight(1f)
                                .testTag("upload_now_button")
                        )
                        NgSecondaryButton(
                            text = "Leave Study",
                            onClick = onLeaveStudyClicked,
                            modifier = Modifier
                                .weight(1f)
                                .testTag("leave_study_button")
                        )
                    }
                } else {
                    Text(
                        text = "This device is not enrolled on the study server. Research sessions can only be exported via USB until enrolled.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    NgPrimaryButton(
                        text = "Join Study Server (R08)",
                        onClick = onEnrollClicked,
                        modifier = Modifier.testTag("join_server_button")
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        // Progress Section
        if (isExporting && exportProgress != null) {
            Card(
                shape = RoundedCornerShape(CardCornerRadius),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("export_progress_card")
            ) {
                Column(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(16.dp)
                ) {
                    Text(
                        text = "Export in Progress",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )

                    val progressFraction = if (exportProgress.totalFiles > 0) {
                        exportProgress.completedFiles.toFloat() / exportProgress.totalFiles.toFloat()
                    } else {
                        0f
                    }

                    LinearProgressIndicator(
                        progress = { progressFraction.coerceIn(0f, 1f) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(8.dp)
                            .testTag("export_progress_indicator")
                    )

                    Text(
                        text = "${exportProgress.message} (${exportProgress.completedFiles}/${exportProgress.totalFiles})",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.testTag("export_status_text")
                    )
                }
            }
            Spacer(modifier = Modifier.height(24.dp))
        }

        // Completion Section
        if (exportCompleted) {
            Card(
                shape = RoundedCornerShape(CardCornerRadius),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("export_completed_card")
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.padding(16.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.CheckCircle,
                        contentDescription = "Complete",
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Column {
                        Text(
                            text = "Export Archive Created",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "The encrypted .zip file was written successfully.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(24.dp))
        }

        // Action Buttons
        if (!exportCompleted) {
            NgPrimaryButton(
                text = if (isExporting) "Exporting..." else "Select Export Destination (SAF)",
                onClick = onChooseDestinationClicked,
                enabled = hasExportKey && !isExporting,
                modifier = Modifier.testTag("export_select_location_button")
            )
        } else {
            NgPrimaryButton(
                text = "Done",
                onClick = onDoneClicked,
                modifier = Modifier.testTag("export_done_button")
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        if (!isExporting && !exportCompleted) {
            NgSecondaryButton(
                text = "Back to Study Home",
                onClick = onDoneClicked,
                modifier = Modifier.testTag("export_back_button")
            )
        }
    }
}
