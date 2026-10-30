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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.kitalonlabs.neogold.core.designsystem.R
import com.kitalonlabs.neogold.core.designsystem.components.atoms.NgBadge
import com.kitalonlabs.neogold.core.designsystem.components.atoms.NgPrimaryButton
import com.kitalonlabs.neogold.core.designsystem.components.atoms.NgSecondaryButton
import com.kitalonlabs.neogold.core.designsystem.theme.CardCornerRadius
import com.kitalonlabs.neogold.engine.camera.host.CameraMode
import com.kitalonlabs.neogold.engine.camera.host.CameraSession
import com.kitalonlabs.neogold.engine.camera.ui.CameraPreviewHost
import com.kitalonlabs.neogold.engine.vision.QualityReasonCode
import com.kitalonlabs.neogold.feature.research.capture.CaptureTooLargeException
import com.kitalonlabs.neogold.feature.research.capture.ProcessedCapture
import com.kitalonlabs.neogold.feature.research.capture.ResearchCaptureProcessor
import com.kitalonlabs.neogold.feature.research.data.ResearchCaptureInput
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import com.kitalonlabs.neogold.feature.research.R as ResearchR

val RESEARCH_PROTOCOL_STEPS = listOf(
    ProtocolStep("STERNUM", ResearchR.string.r04_site_sternum, ResearchR.string.r04_guidance_sternum, isFlash = false),
    ProtocolStep(
        "FOREHEAD",
        ResearchR.string.r04_site_forehead,
        ResearchR.string.r04_guidance_forehead,
        isFlash = false
    ),
    ProtocolStep(
        "SCLERA_FLASH",
        ResearchR.string.r04_site_sclera_flash,
        ResearchR.string.r04_guidance_sclera_flash,
        isFlash = true
    ),
    ProtocolStep(
        "SCLERA_NOFLASH",
        ResearchR.string.r04_site_sclera_ambient,
        ResearchR.string.r04_guidance_sclera_ambient,
        isFlash = false
    )
)

private enum class CaptureProblem { FAILED, TOO_LARGE }

/**
 * R04: Research Capture Protocol Screen (FR-27, NG-030, NG-041, doc 6 Section 5).
 * Guides 4-site capture: Sternum, Forehead, Sclera Flash, Sclera No-Flash, with the phone's own camera.
 * STRICTLY BLINDED: No estimate, triage category, rule outcome, or threshold is ever rendered
 * or exposed in the accessibility tree (FR-27, TC-RSH-10). The only thing said about a photo is a quality problem the
 * health worker can fix by retaking it.
 *
 * Every photo is checked, sanitized and sized by [processor]; a photo with a quality problem is offered for a retake and
 * can still be kept, because research continues with the card and the light that are there (TC-CARD-02).
 */
@Composable
fun R04ResearchCaptureScreen(
    participantCode: String,
    processor: ResearchCaptureProcessor,
    onProtocolCompleted: (List<ResearchCaptureInput>) -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier
) {
    var currentStepIndex by remember { mutableIntStateOf(0) }
    val capturedItems = remember { mutableStateListOf<ResearchCaptureInput>() }
    var session by remember { mutableStateOf<CameraSession?>(null) }
    var busy by remember { mutableStateOf(false) }
    var pending by remember { mutableStateOf<ProcessedCapture?>(null) }
    var problem by remember { mutableStateOf<CaptureProblem?>(null) }
    var attempt by remember { mutableIntStateOf(1) }
    val scope = rememberCoroutineScope()

    val currentStep = RESEARCH_PROTOCOL_STEPS.getOrNull(currentStepIndex)

    fun keep(capture: ResearchCaptureInput) {
        capturedItems.add(capture)
        pending = null
        attempt = 1
        currentStepIndex++
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(16.dp)
            .verticalScroll(rememberScrollState())
            .testTag("r04_screen")
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            NgBadge(
                text = stringResource(ResearchR.string.research_badge),
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
            text = stringResource(ResearchR.string.r04_title),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground
        )

        Spacer(modifier = Modifier.height(12.dp))

        StepIndicator(currentStepIndex = currentStepIndex, completed = capturedItems.size)

        Spacer(modifier = Modifier.height(16.dp))

        if (currentStep != null) {
            Card(
                shape = RoundedCornerShape(CardCornerRadius),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
                modifier = Modifier.fillMaxWidth().testTag("r04_instruction_card")
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = stringResource(
                            ResearchR.string.r04_step_of,
                            currentStepIndex + 1,
                            RESEARCH_PROTOCOL_STEPS.size
                        ),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = stringResource(currentStep.siteNameRes),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.testTag("r04_site_title")
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = stringResource(currentStep.guidanceRes),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(260.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(12.dp))
                            .testTag("r04_viewfinder")
                    ) {
                        CameraPreviewHost(
                            mode = CameraMode.Stills,
                            onSession = { session = it },
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            val issue = pending
            if (issue != null) {
                QualityDecision(
                    problems = issue.qualityProblems,
                    onRetake = {
                        pending = null
                        attempt++
                    },
                    onKeep = { keep(issue.input) }
                )
            } else {
                problem?.let {
                    Text(
                        text = stringResource(
                            if (it == CaptureProblem.TOO_LARGE) ResearchR.string.r04_too_large else ResearchR.string.r04_capture_failed
                        ),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(bottom = 8.dp).testTag("r04_error")
                    )
                }
                NgPrimaryButton(
                    text = if (busy) {
                        stringResource(ResearchR.string.r04_capturing)
                    } else {
                        stringResource(ResearchR.string.r04_capture_button, stringResource(currentStep.siteNameRes))
                    },
                    enabled = session != null && !busy,
                    onClick = {
                        val open = session ?: return@NgPrimaryButton
                        busy = true
                        problem = null
                        scope.launch {
                            try {
                                val result = processor.capture(open, currentStep.siteCode, attempt, currentStep.isFlash)
                                if (result.qualityProblems.isEmpty()) keep(result.input) else pending = result
                            } catch (e: CancellationException) {
                                throw e
                            } catch (_: CaptureTooLargeException) {
                                problem = CaptureProblem.TOO_LARGE
                            } catch (_: Exception) {
                                problem = CaptureProblem.FAILED
                            } finally {
                                busy = false
                            }
                        }
                    },
                    modifier = Modifier.testTag("r04_capture_button")
                )
            }
        } else {
            Card(
                shape = RoundedCornerShape(CardCornerRadius),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
                modifier = Modifier.fillMaxWidth().testTag("r04_complete_card")
            ) {
                Column(modifier = Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        imageVector = Icons.Default.Check,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(48.dp)
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = stringResource(ResearchR.string.r04_complete_title),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = stringResource(ResearchR.string.r04_complete_body),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            NgPrimaryButton(
                text = stringResource(ResearchR.string.r04_continue_tsb),
                onClick = { onProtocolCompleted(capturedItems.toList()) },
                modifier = Modifier.testTag("r04_proceed_button")
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        NgSecondaryButton(
            text = stringResource(ResearchR.string.r04_cancel),
            onClick = onCancel,
            modifier = Modifier.testTag("r04_cancel_button")
        )
    }
}

@Composable
private fun StepIndicator(currentStepIndex: Int, completed: Int) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp).testTag("r04_step_indicator"),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        RESEARCH_PROTOCOL_STEPS.forEachIndexed { index, step ->
            val isCompleted = index < completed
            val isCurrent = index == currentStepIndex
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.weight(1f)) {
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
                    text = stringResource(step.siteNameRes).substringBefore(" ("),
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                    color = if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/** The photo has a quality problem the health worker can fix: say which, and let them retake it or keep it. */
@Composable
private fun QualityDecision(problems: List<QualityReasonCode>, onRetake: () -> Unit, onKeep: () -> Unit) {
    Column(modifier = Modifier.fillMaxWidth().testTag("r04_quality_issue")) {
        Text(
            text = stringResource(ResearchR.string.r04_quality_issue, stringResource(reasonText(problems.first()))),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error
        )
        Spacer(modifier = Modifier.height(12.dp))
        NgPrimaryButton(
            text = stringResource(ResearchR.string.r04_retake),
            onClick = onRetake,
            modifier = Modifier.testTag("r04_retake_button")
        )
        Spacer(modifier = Modifier.height(8.dp))
        NgSecondaryButton(
            text = stringResource(ResearchR.string.r04_keep),
            onClick = onKeep,
            modifier = Modifier.testTag("r04_keep_button")
        )
    }
}

private fun reasonText(reason: QualityReasonCode): Int = when (reason) {
    QualityReasonCode.NO_CARD -> R.string.retake_no_card
    QualityReasonCode.CARD_ANGLE -> R.string.retake_card_angle
    QualityReasonCode.NONUNIFORM_PATCHES -> R.string.retake_nonuniform_patches
    QualityReasonCode.BLUR -> R.string.retake_blur
    QualityReasonCode.GLARE -> R.string.retake_glare
    QualityReasonCode.UNDEREXPOSED -> R.string.retake_underexposed
    QualityReasonCode.OVEREXPOSED -> R.string.retake_overexposed
    QualityReasonCode.UNEVEN_LIGHT -> R.string.retake_uneven_light
    QualityReasonCode.HIGH_RESIDUAL -> R.string.retake_high_residual
    QualityReasonCode.WINDOW_NOT_SKIN -> R.string.retake_window_not_skin
}
