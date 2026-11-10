package com.kitalonlabs.neogold.feature.screening.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.kitalonlabs.neogold.core.data.local.entity.ScreeningSessionEntity
import com.kitalonlabs.neogold.core.designsystem.R
import com.kitalonlabs.neogold.core.designsystem.components.atoms.NgIcons
import com.kitalonlabs.neogold.core.designsystem.components.atoms.NgPrimaryButton
import com.kitalonlabs.neogold.core.designsystem.components.molecules.NgBottomActionBar
import com.kitalonlabs.neogold.core.designsystem.components.molecules.NgExperimentalBanner
import com.kitalonlabs.neogold.core.designsystem.theme.CardCornerRadius
import com.kitalonlabs.neogold.core.designsystem.theme.ResultButtonHeight
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * S09 Home Screen (doc 6 Section 5, Section 7.1).
 *
 * Implements:
 * - Experimental banner (NG-022)
 * - Due rechecks list (FR-36, NFR-12, H10, TC-LOG-08): Works fully even if notifications are blocked.
 * - Today's screening counter
 * - Screening log entry point
 * - Card batch validity display
 * - Gating on `triageEnabled`: In study/release builds without clinical sign-off,
 *   the "New screening" button is hidden (PRD D10, TC-TRI-26).
 *
 * Surfaces are drawn with a hairline outline rather than a shadow (doc 6 Section 2.3: shadows disappear in glare).
 */
@Composable
fun S09HomeScreen(
    triageEnabled: Boolean,
    todayScreeningsCount: Int,
    dueRechecks: List<ScreeningSessionEntity>,
    cardBatchId: String = "NG-2026-001",
    cardValidUntilFormatted: String = "31 Jan 2027",
    onNewScreeningClicked: () -> Unit,
    onScreeningLogClicked: () -> Unit,
    onSettingsClicked: () -> Unit,
    onRecheckClicked: (sessionId: String) -> Unit = {},
    modifier: Modifier = Modifier
) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 12.dp)
            ) {
                Text(
                    text = stringResource(R.string.home_title),
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.semantics { heading() }
                )
                IconButton(
                    onClick = onSettingsClicked,
                    modifier = Modifier.testTag("btn_settings")
                ) {
                    Icon(
                        imageVector = Icons.Default.Settings,
                        contentDescription = stringResource(R.string.settings_title),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        // The one main action sits in the lower third, under the thumb, rather than in the top
        // quarter above the summary (doc 6 Section 3: reachable with one hand). Gated on
        // triageEnabled: hidden in study builds where triageEnabled is false (TC-TRI-26).
        bottomBar = {
            if (triageEnabled) {
                NgBottomActionBar {
                    NgPrimaryButton(
                        text = stringResource(R.string.home_btn_new_screening),
                        height = ResultButtonHeight,
                        onClick = onNewScreeningClicked,
                        modifier = Modifier.testTag("btn_new_screening")
                    )
                }
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp)
                .padding(top = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Experimental label (NG-022)
            NgExperimentalBanner(modifier = Modifier.testTag("home_experimental_banner"))

            // Today's summary card
            Card(
                shape = RoundedCornerShape(CardCornerRadius),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.6f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = stringResource(R.string.home_today_screenings, todayScreeningsCount),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.testTag("today_screenings_count")
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f))

                    Spacer(modifier = Modifier.height(12.dp))

                    if (dueRechecks.isEmpty()) {
                        Text(
                            text = stringResource(R.string.home_no_rechecks),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.testTag("no_rechecks_label")
                        )
                    } else {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.testTag("rechecks_summary")
                        ) {
                            Icon(
                                imageVector = NgIcons.Clock,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp)
                            )
                            Text(
                                text = stringResource(R.string.home_rechecks_due_count, dueRechecks.size),
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        LazyColumn(
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("due_rechecks_list")
                        ) {
                            items(dueRechecks) { session ->
                                val dueTime = session.recheckDueAt?.let { ms ->
                                    val timeFormatter = DateTimeFormatter.ofPattern("h:mm a", Locale.getDefault())
                                        .withZone(ZoneId.systemDefault())
                                    timeFormatter.format(Instant.ofEpochMilli(ms))
                                } ?: ""
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { onRecheckClicked(session.id) }
                                        .padding(vertical = 8.dp)
                                ) {
                                    val age = session.ageHours.toInt()
                                    val register = session.registerNumber
                                    Text(
                                        text = if (!register.isNullOrBlank()) {
                                            stringResource(R.string.home_recent_reg, register, age)
                                        } else {
                                            stringResource(R.string.home_recent_session, age)
                                        },
                                        style = MaterialTheme.typography.bodyLarge,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    Text(
                                        text = stringResource(R.string.home_rechecks_due_at, dueTime),
                                        style = MaterialTheme.typography.labelLarge,
                                        fontWeight = FontWeight.SemiBold,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // Screening log row
            Card(
                onClick = onScreeningLogClicked,
                shape = RoundedCornerShape(CardCornerRadius),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.6f)),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("btn_screening_log")
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 18.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.List,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(24.dp)
                    )
                    Text(
                        text = stringResource(R.string.home_screening_log),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f)
                    )
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(modifier = Modifier.weight(1f))

            // Card batch validity footer
            Text(
                text = stringResource(R.string.home_card_valid, cardBatchId, cardValidUntilFormatted),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .padding(bottom = 16.dp)
                    .testTag("card_batch_validity")
            )
        }
    }
}
