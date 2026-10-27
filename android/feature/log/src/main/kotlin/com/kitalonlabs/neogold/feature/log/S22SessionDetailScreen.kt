package com.kitalonlabs.neogold.feature.log

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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.kitalonlabs.neogold.core.data.local.entity.ScreeningSessionEntity
import com.kitalonlabs.neogold.core.designsystem.R
import com.kitalonlabs.neogold.core.designsystem.components.atoms.NgIcons
import com.kitalonlabs.neogold.core.designsystem.theme.LocalTriageColors
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * S22 Session Detail screen.
 * Reconstructs the exact session outcome, threshold, interval, versions, and card batch (TC-LOG-12, T16, NFR-10).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun S22SessionDetailScreen(
    session: ScreeningSessionEntity,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val dateFormat = remember { SimpleDateFormat("dd MMM yyyy, HH:mm", Locale.getDefault()) }
    val formattedDate = remember(session.createdAt) { dateFormat.format(Date(session.createdAt)) }
    val triageColors = LocalTriageColors.current

    val (actionTitle, actionColor, actionIcon) = when (session.triageAction) {
        "REFER_URGENT" -> Triple(
            stringResource(R.string.action_refer_urgent),
            triageColors.urgentSolid,
            NgIcons.WarningOctagon
        )
        "BLOOD_TEST_TODAY" -> Triple(
            stringResource(R.string.action_blood_test),
            triageColors.bloodTestSolid,
            NgIcons.BloodDrop
        )
        "RECHECK" -> Triple(
            stringResource(R.string.action_recheck, 24),
            triageColors.recheckSolid,
            NgIcons.Clock
        )
        "NO_FURTHER_ACTION_FROM_SCREEN" -> Triple(
            stringResource(R.string.action_no_further),
            triageColors.noActionSolid,
            NgIcons.Eye
        )
        "CANNOT_SCREEN_BLOOD_TEST", "CANNOT_SCREEN_CLINICAL_REVIEW" -> Triple(
            stringResource(R.string.cannot_screen),
            triageColors.bloodTestSolid,
            Icons.Default.Warning
        )
        else -> Triple(
            session.triageAction ?: "SCREENED",
            MaterialTheme.colorScheme.onSurfaceVariant,
            Icons.Default.Warning
        )
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.session_detail_title),
                        fontWeight = FontWeight.SemiBold
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.onBackground,
                    navigationIconContentColor = MaterialTheme.colorScheme.onBackground
                )
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 20.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Spacer(modifier = Modifier.height(12.dp))

            // Experimental Banner
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        color = MaterialTheme.colorScheme.surface,
                        shape = RoundedCornerShape(8.dp)
                    )
                    .padding(12.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = stringResource(R.string.experimental),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Decision support framing: Suggested next step
            Text(
                text = stringResource(R.string.result_suggested),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(8.dp))

            // Action Card
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = actionColor.copy(alpha = 0.12f)
                )
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(
                        imageVector = actionIcon,
                        contentDescription = null,
                        tint = actionColor,
                        modifier = Modifier.size(40.dp)
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = actionTitle,
                        color = actionColor,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Decision support framing: You decide
            Text(
                text = stringResource(R.string.result_you_decide),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(12.dp))

            Text(
                text = stringResource(R.string.confirm_blood),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(24.dp))

            // Reconstructed Clinical Details Card
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                ) {
                    Text(
                        text = "Clinical Information",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    DetailRow(label = "Date & Time", value = formattedDate)
                    DetailRow(label = "Register No.", value = session.registerNumber ?: "None")
                    DetailRow(label = "Gestational Age", value = "${session.gestationalAgeWeeks} weeks")
                    DetailRow(label = "Age at Screening", value = "${session.ageHours.toInt()} hours")

                    if (session.estimateMgDl != null) {
                        val est = "%.1f mg/dL".format(session.estimateMgDl)
                        val range = if (session.estimateLowMgDl != null && session.estimateHighMgDl != null) {
                            " (%.1f – %.1f)".format(session.estimateLowMgDl, session.estimateHighMgDl)
                        } else {
                            ""
                        }
                        DetailRow(label = "Estimated Bilirubin", value = "$est$range")
                    }

                    if (session.thresholdMgDl != null) {
                        DetailRow(label = "Treatment Threshold", value = "%.1f mg/dL".format(session.thresholdMgDl))
                    }

                    val reasons = session.reasonCodes
                    if (!reasons.isNullOrEmpty()) {
                        DetailRow(label = "Reason Codes", value = reasons)
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Traceability Card (NFR-10, NG-025, TC-LOG-12)
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                ) {
                    Text(
                        text = "Traceability & Versions",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    DetailRow(label = "App Version", value = session.appVersion)
                    DetailRow(label = "Model Version", value = session.modelVersion ?: "N/A")
                    DetailRow(label = "Config Version", value = session.configVersion)
                    DetailRow(label = "Card Batch ID", value = session.cardBatchId ?: "N/A")
                    DetailRow(label = "Session ID", value = session.id)
                }
            }

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

@Composable
private fun DetailRow(
    label: String,
    value: String,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}
