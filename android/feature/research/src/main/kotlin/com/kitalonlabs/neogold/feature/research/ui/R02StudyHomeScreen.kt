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
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.kitalonlabs.neogold.core.designsystem.components.atoms.NgBadge
import com.kitalonlabs.neogold.core.designsystem.components.atoms.NgPrimaryButton
import com.kitalonlabs.neogold.core.designsystem.components.atoms.NgSecondaryButton
import com.kitalonlabs.neogold.core.designsystem.components.molecules.NgSyncStatusChip
import com.kitalonlabs.neogold.core.designsystem.theme.CardCornerRadius
import com.kitalonlabs.neogold.feature.research.data.CardTrustStatus
import com.kitalonlabs.neogold.feature.research.R as ResearchR

/**
 * R02: Study Home Screen (doc 6 Section 5, Section 6.2).
 * Overview of current study, upload status, card verification status,
 * and primary actions for participant sessions and deferred TSB entry.
 */
@Composable
fun R02StudyHomeScreen(
    studyName: String,
    studyCode: String,
    siteCode: String,
    cardStatus: CardTrustStatus,
    pendingUploadsCount: Int,
    onNewParticipantSession: () -> Unit,
    onEnterTsbClicked: () -> Unit,
    onLockSession: () -> Unit,
    onExportClicked: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(16.dp)
            .verticalScroll(rememberScrollState())
    ) {
        // Persistent Research Mode Header (doc 6 Section 8)
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier.fillMaxWidth()
        ) {
            NgBadge(
                text = stringResource(ResearchR.string.research_badge),
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.testTag("research_mode_badge")
            )
            IconButton(
                onClick = onLockSession,
                modifier = Modifier.testTag("lock_session_button")
            ) {
                Icon(
                    imageVector = Icons.Default.Lock,
                    contentDescription = stringResource(ResearchR.string.r02_lock_description),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Study Details Card
        Card(
            shape = RoundedCornerShape(CardCornerRadius),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            modifier = Modifier
                .fillMaxWidth()
                .testTag("study_info_card")
        ) {
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(16.dp)
            ) {
                Text(
                    text = studyName,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = stringResource(ResearchR.string.r02_study_site, studyCode, siteCode),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.height(4.dp))

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    val (cardBadgeText, cardBadgeColor) = when (cardStatus) {
                        CardTrustStatus.VERIFIED -> "CARD VERIFIED" to MaterialTheme.colorScheme.primary
                        CardTrustStatus.EXPIRED -> "CARD EXPIRED" to MaterialTheme.colorScheme.error
                        CardTrustStatus.UNVERIFIED -> "CARD UNVERIFIED" to MaterialTheme.colorScheme.outline
                    }
                    NgBadge(
                        text = cardBadgeText,
                        containerColor = cardBadgeColor.copy(alpha = 0.15f),
                        contentColor = cardBadgeColor,
                        modifier = Modifier.testTag("card_status_badge")
                    )

                    NgSyncStatusChip(
                        waitingCount = pendingUploadsCount,
                        modifier = Modifier.testTag("sync_status_chip")
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(32.dp))

        // Primary: New Participant Session
        NgPrimaryButton(
            text = stringResource(ResearchR.string.r02_new_session),
            onClick = onNewParticipantSession,
            modifier = Modifier.testTag("new_session_button")
        )

        Spacer(modifier = Modifier.height(16.dp))

        // Secondary: Enter TSB later by participant code
        NgSecondaryButton(
            text = stringResource(ResearchR.string.r02_enter_tsb),
            onClick = onEnterTsbClicked,
            modifier = Modifier.testTag("enter_tsb_button")
        )

        Spacer(modifier = Modifier.height(16.dp))

        // Export encrypted study data (SAF)
        NgSecondaryButton(
            text = stringResource(ResearchR.string.r02_export),
            onClick = onExportClicked,
            modifier = Modifier.testTag("export_data_button")
        )
    }
}
