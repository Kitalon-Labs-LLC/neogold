package com.kitalonlabs.neogold.feature.research.ui

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Divider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.kitalonlabs.neogold.core.designsystem.components.atoms.NgBadge
import com.kitalonlabs.neogold.core.designsystem.components.atoms.NgIcons
import com.kitalonlabs.neogold.core.designsystem.components.atoms.NgPrimaryButton
import com.kitalonlabs.neogold.core.designsystem.theme.CardCornerRadius
import com.kitalonlabs.neogold.feature.research.data.CardTrustStatus

/**
 * R06: Research Session Complete Screen (FR-27, doc 6 Section 5).
 * Displays session summary: participant code, captures count, card trust status, paired TSB status,
 * and outbox synchronization queue state.
 *
 * STRICTLY BLINDED (FR-27, TC-RSH-10):
 * Bilirubin estimate is NEVER rendered, spoken, or present in this or any research mode screen.
 */
@Composable
fun R06SessionCompleteScreen(
    participantCode: String,
    capturesCount: Int,
    cardStatus: CardTrustStatus,
    tsbEntered: Boolean,
    tsbMgDl: Double?,
    tsbMethod: String?,
    onDoneClicked: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(16.dp)
            .verticalScroll(rememberScrollState())
            .testTag("r06_screen")
    ) {
        // Research Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            NgBadge(
                text = "RESEARCH MODE",
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.testTag("r06_research_badge")
            )
            NgBadge(
                text = "SAVED & QUEUED",
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
                contentColor = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Spacer(modifier = Modifier.height(20.dp))

        // Success Icon Circle
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.fillMaxWidth()
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(64.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primaryContainer)
                    .border(2.dp, MaterialTheme.colorScheme.primary, CircleShape)
            ) {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(36.dp)
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            Text(
                text = "Session Complete",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground
            )

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = "Research data encrypted and saved to local database.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Spacer(modifier = Modifier.height(24.dp))

        // Summary Card
        Card(
            shape = RoundedCornerShape(CardCornerRadius),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface
            ),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "Session Summary",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )

                Spacer(modifier = Modifier.height(12.dp))

                // Participant Code
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Participant Code",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = participantCode,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.testTag("r06_participant_code")
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))
                Divider(color = MaterialTheme.colorScheme.outlineVariant)
                Spacer(modifier = Modifier.height(12.dp))

                // Protocol Captures
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Protocol Captures",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "$capturesCount / 4 Sites",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.testTag("r06_captures_count")
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))
                Divider(color = MaterialTheme.colorScheme.outlineVariant)
                Spacer(modifier = Modifier.height(12.dp))

                // Card Trust Status
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Color Card Status",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    NgBadge(
                        text = when (cardStatus) {
                            CardTrustStatus.VERIFIED -> "VERIFIED"
                            CardTrustStatus.EXPIRED -> "EXPIRED"
                            CardTrustStatus.UNVERIFIED -> "UNVERIFIED"
                        },
                        containerColor = when (cardStatus) {
                            CardTrustStatus.VERIFIED -> MaterialTheme.colorScheme.primaryContainer
                            CardTrustStatus.EXPIRED -> MaterialTheme.colorScheme.errorContainer
                            CardTrustStatus.UNVERIFIED -> MaterialTheme.colorScheme.surfaceVariant
                        },
                        contentColor = when (cardStatus) {
                            CardTrustStatus.VERIFIED -> MaterialTheme.colorScheme.onPrimaryContainer
                            CardTrustStatus.EXPIRED -> MaterialTheme.colorScheme.onErrorContainer
                            CardTrustStatus.UNVERIFIED -> MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        modifier = Modifier.testTag("r06_card_status")
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))
                Divider(color = MaterialTheme.colorScheme.outlineVariant)
                Spacer(modifier = Modifier.height(12.dp))

                // Paired TSB Status
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Paired TSB",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = if (tsbEntered && tsbMgDl != null) {
                            "$tsbMgDl mg/dL (${tsbMethod ?: "LAB"})"
                        } else {
                            "Deferred (Enter later)"
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = if (tsbEntered) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.testTag("r06_tsb_status")
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))
                Divider(color = MaterialTheme.colorScheme.outlineVariant)
                Spacer(modifier = Modifier.height(12.dp))

                // Outbox Queue
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(
                            imageVector = NgIcons.CloudUpload,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(16.dp)
                        )
                        Text(
                            text = "Outbox Queue",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Text(
                        text = "Queued for Sync",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(32.dp))

        NgPrimaryButton(
            text = "Done",
            onClick = onDoneClicked,
            modifier = Modifier.testTag("r06_done_button")
        )
    }
}
