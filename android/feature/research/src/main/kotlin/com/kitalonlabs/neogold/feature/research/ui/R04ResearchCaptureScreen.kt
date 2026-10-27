@file:Suppress("MaxLineLength") // hardcoded UI text moves to string resources in the localization package (F1)

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
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.kitalonlabs.neogold.core.designsystem.components.atoms.NgBadge
import com.kitalonlabs.neogold.core.designsystem.components.atoms.NgIcons
import com.kitalonlabs.neogold.core.designsystem.components.atoms.NgPrimaryButton
import com.kitalonlabs.neogold.core.designsystem.components.atoms.NgSecondaryButton
import com.kitalonlabs.neogold.core.designsystem.theme.CardCornerRadius
import com.kitalonlabs.neogold.feature.research.data.ResearchCaptureInput

val RESEARCH_PROTOCOL_STEPS = listOf(
    ProtocolStep(
        siteCode = "STERNUM",
        siteDisplayName = "Sternum",
        guidance = "Place the NeoGold card flat on the infant's sternum with the skin window clear. Hold phone 15–20 cm above the card parallel to the surface.",
        isFlash = false
    ),
    ProtocolStep(
        siteCode = "FOREHEAD",
        siteDisplayName = "Forehead",
        guidance = "Place the card gently on the infant's forehead. Keep the camera parallel to the card under ambient lighting.",
        isFlash = false
    ),
    ProtocolStep(
        siteCode = "SCLERA_FLASH",
        siteDisplayName = "Sclera (Flash)",
        guidance = "Point camera at eye with card adjacent. Flash will fire to capture calibrated scleral reflection.",
        isFlash = true
    ),
    ProtocolStep(
        siteCode = "SCLERA_NOFLASH",
        siteDisplayName = "Sclera (Ambient / No Flash)",
        guidance = "Keep camera positioned at eye with card adjacent under ambient lighting without flash.",
        isFlash = false
    )
)

/**
 * R04: Research Capture Protocol Screen (FR-27, NG-030, NG-041, doc 6 Section 5).
 * Guides 4-site capture: Sternum, Forehead, Sclera Flash, Sclera No-Flash.
 * STRICTLY BLINDED: No estimate, triage category, rule outcome, or threshold is ever rendered
 * or exposed in the accessibility tree (FR-27, TC-RSH-10).
 */
@Composable
fun R04ResearchCaptureScreen(
    participantCode: String,
    onProtocolCompleted: (List<ResearchCaptureInput>) -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
    // Injected mock image generator or real camera capture callback
    onCaptureImage: (site: String, isFlash: Boolean) -> ByteArray = { site, _ ->
        // Synthetic mock image bytes for research testing harness
        "MOCK_IMAGE_DATA_FOR_${site}_${System.currentTimeMillis()}".toByteArray()
    }
) {
    var currentStepIndex by remember { mutableIntStateOf(0) }
    val capturedItems = remember { mutableStateListOf<ResearchCaptureInput>() }

    val currentStep = if (currentStepIndex < RESEARCH_PROTOCOL_STEPS.size) {
        RESEARCH_PROTOCOL_STEPS[currentStepIndex]
    } else {
        null
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(16.dp)
            .verticalScroll(rememberScrollState())
            .testTag("r04_screen")
    ) {
        // Persistent Research Mode Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            NgBadge(
                text = "RESEARCH MODE",
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.testTag("r04_research_badge")
            )
            Text(
                text = participantCode,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.testTag("r04_participant_code")
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = "4-Site Research Protocol",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground
        )

        Spacer(modifier = Modifier.height(12.dp))

        // Step Progress Indicator
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp)
                .testTag("r04_step_indicator"),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            RESEARCH_PROTOCOL_STEPS.forEachIndexed { index, step ->
                val isCompleted = index < capturedItems.size
                val isCurrent = index == currentStepIndex

                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.weight(1f)
                ) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(32.dp)
                            .clip(CircleShape)
                            .background(
                                when {
                                    isCompleted -> MaterialTheme.colorScheme.primary
                                    isCurrent -> MaterialTheme.colorScheme.primaryContainer
                                    else -> MaterialTheme.colorScheme.surfaceVariant
                                }
                            )
                            .border(
                                width = if (isCurrent) 2.dp else 1.dp,
                                color = if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                                shape = CircleShape
                            )
                    ) {
                        if (isCompleted) {
                            Icon(
                                imageVector = Icons.Default.Check,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onPrimary,
                                modifier = Modifier.size(18.dp)
                            )
                        } else {
                            Text(
                                text = "${index + 1}",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                color = if (isCurrent) {
                                    MaterialTheme.colorScheme.onPrimaryContainer
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                }
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(4.dp))

                    Text(
                        text = step.siteDisplayName.split(" ").first(),
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1,
                        color = if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        if (currentStep != null) {
            Card(
                shape = RoundedCornerShape(CardCornerRadius),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface
                ),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("r04_instruction_card")
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Step ${currentStepIndex + 1} of ${RESEARCH_PROTOCOL_STEPS.size}",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = currentStep.siteDisplayName,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.testTag("r04_site_title")
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = currentStep.guidance,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    // Simulated Camera Viewfinder Frame
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(200.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(12.dp))
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            Icon(
                                imageVector = NgIcons.Eye,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(48.dp)
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "Camera Ready • Card Detection Active",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            NgPrimaryButton(
                text = "Capture ${currentStep.siteDisplayName}",
                onClick = {
                    val bytes = onCaptureImage(currentStep.siteCode, currentStep.isFlash)
                    capturedItems.add(
                        ResearchCaptureInput(
                            site = currentStep.siteCode,
                            attemptNumber = 1,
                            imageBytes = bytes
                        )
                    )
                    currentStepIndex++
                },
                modifier = Modifier.testTag("r04_capture_button")
            )
        } else {
            // All 4 captures completed
            Card(
                shape = RoundedCornerShape(CardCornerRadius),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface
                ),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("r04_complete_card")
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(
                        imageVector = Icons.Default.Check,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(48.dp)
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "All 4 Protocol Captures Recorded",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Images have been secured in memory. Next, record paired TSB measurement.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            NgPrimaryButton(
                text = "Continue to TSB Entry",
                onClick = {
                    onProtocolCompleted(capturedItems.toList())
                },
                modifier = Modifier.testTag("r04_proceed_button")
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        NgSecondaryButton(
            text = "Cancel Session",
            onClick = onCancel,
            modifier = Modifier.testTag("r04_cancel_button")
        )
    }
}
