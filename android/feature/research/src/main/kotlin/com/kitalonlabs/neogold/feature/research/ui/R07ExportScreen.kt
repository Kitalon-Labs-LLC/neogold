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
import com.kitalonlabs.neogold.feature.research.export.ExportStage
import com.kitalonlabs.neogold.feature.research.R as ResearchR

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
    exportFailed: Boolean = false,
    showCloudSync: Boolean = true,
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
                text = stringResource(ResearchR.string.research_badge),
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.testTag("export_research_badge")
            )
            Icon(
                imageVector = Icons.Default.Lock,
                contentDescription = stringResource(ResearchR.string.r07_cd_encrypted),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = stringResource(ResearchR.string.r07_title),
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = stringResource(ResearchR.string.r07_intro),
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
                    text = stringResource(ResearchR.string.r01_study_named, displayName, studyCode),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )

                if (hasExportKey) {
                    Text(
                        text = listOf(
                            stringResource(ResearchR.string.r07_point_encrypt),
                            stringResource(ResearchR.string.r07_point_wrap),
                            stringResource(ResearchR.string.r07_point_lost),
                            stringResource(ResearchR.string.r07_point_deid)
                        ).joinToString("\n"),
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
                            contentDescription = stringResource(ResearchR.string.r07_cd_missing_key),
                            tint = MaterialTheme.colorScheme.error
                        )
                        Text(
                            text = stringResource(ResearchR.string.r07_no_key),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        if (showCloudSync) {
            CloudSyncCard(
                serverDeviceId = serverDeviceId,
                pendingUploadsCount = pendingUploadsCount,
                onUploadNowClicked = onUploadNowClicked,
                onEnrollClicked = onEnrollClicked,
                onLeaveStudyClicked = onLeaveStudyClicked
            )
            Spacer(modifier = Modifier.height(24.dp))
        }

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
                        text = stringResource(ResearchR.string.r07_progress_title),
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
                        text = stringResource(
                            ResearchR.string.r07_progress,
                            stageText(exportProgress.stage),
                            exportProgress.completedFiles,
                            exportProgress.totalFiles
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.testTag("export_status_text")
                    )
                }
            }
            Spacer(modifier = Modifier.height(24.dp))
        }

        // Failure Section: no exception text, nothing was saved
        if (exportFailed && !isExporting) {
            Card(
                shape = RoundedCornerShape(CardCornerRadius),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("export_failed_card")
            ) {
                Column(
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier.padding(16.dp)
                ) {
                    Text(
                        text = stringResource(ResearchR.string.r07_failed_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                    Text(
                        text = stringResource(ResearchR.string.r07_failed_body),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer
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
                        contentDescription = stringResource(ResearchR.string.r07_cd_complete),
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Column {
                        Text(
                            text = stringResource(ResearchR.string.r07_done_title),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = stringResource(ResearchR.string.r07_done_body),
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
                text = stringResource(
                    if (isExporting) ResearchR.string.r07_button_exporting else ResearchR.string.r07_button_choose
                ),
                onClick = onChooseDestinationClicked,
                enabled = hasExportKey && !isExporting,
                modifier = Modifier.testTag("export_select_location_button")
            )
        } else {
            NgPrimaryButton(
                text = stringResource(ResearchR.string.r07_done),
                onClick = onDoneClicked,
                modifier = Modifier.testTag("export_done_button")
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        if (!isExporting && !exportCompleted) {
            NgSecondaryButton(
                text = stringResource(ResearchR.string.r07_back),
                onClick = onDoneClicked,
                modifier = Modifier.testTag("export_back_button")
            )
        }
    }
}

/** The server side of R07 (doc 6 Section 5, Section 10.4, NG-042). Shown only once enrollment and upload are wired (C4). */
@Composable
private fun CloudSyncCard(
    serverDeviceId: String?,
    pendingUploadsCount: Int,
    onUploadNowClicked: () -> Unit,
    onEnrollClicked: () -> Unit,
    onLeaveStudyClicked: () -> Unit
) {
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
                    contentDescription = stringResource(ResearchR.string.r07_cd_cloud),
                    tint = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = stringResource(ResearchR.string.r07_cloud_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }

            if (serverDeviceId != null) {
                val waitingText = if (pendingUploadsCount > 0) {
                    stringResource(R.string.upload_waiting, pendingUploadsCount)
                } else {
                    stringResource(ResearchR.string.r07_all_uploaded)
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
                        text = stringResource(ResearchR.string.r07_leave_study),
                        onClick = onLeaveStudyClicked,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("leave_study_button")
                    )
                }
            } else {
                Text(
                    text = stringResource(ResearchR.string.r07_not_enrolled),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                NgPrimaryButton(
                    text = stringResource(ResearchR.string.r07_join_server),
                    onClick = onEnrollClicked,
                    modifier = Modifier.testTag("join_server_button")
                )
            }
        }
    }
}

/** What the export is doing, in the language the screen shows. The manager's own message text is never shown. */
@Composable
private fun stageText(stage: ExportStage): String = when (stage) {
    ExportStage.PREPARING, ExportStage.FAILED -> stringResource(ResearchR.string.r07_stage_preparing)
    ExportStage.ENCRYPTING_METADATA, ExportStage.ENCRYPTING_DATA -> stringResource(ResearchR.string.r07_stage_data)
    ExportStage.ENCRYPTING_IMAGES -> stringResource(ResearchR.string.r07_stage_images)
    ExportStage.PACKAGING_ZIP, ExportStage.COMPLETED -> stringResource(ResearchR.string.r07_stage_packaging)
}
