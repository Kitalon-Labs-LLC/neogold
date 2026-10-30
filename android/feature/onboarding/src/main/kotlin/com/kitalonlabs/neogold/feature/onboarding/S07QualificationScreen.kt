package com.kitalonlabs.neogold.feature.onboarding

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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.kitalonlabs.neogold.core.designsystem.R
import com.kitalonlabs.neogold.core.designsystem.components.atoms.NgPrimaryButton
import com.kitalonlabs.neogold.core.designsystem.components.molecules.NgStepIndicator
import com.kitalonlabs.neogold.core.designsystem.theme.CameraOverlayBackground
import com.kitalonlabs.neogold.core.designsystem.theme.Dimens
import com.kitalonlabs.neogold.engine.camera.host.CameraMode
import com.kitalonlabs.neogold.engine.camera.host.CameraSession
import com.kitalonlabs.neogold.engine.camera.qualification.QualificationResult
import com.kitalonlabs.neogold.engine.camera.ui.CameraPreviewHost
import com.kitalonlabs.neogold.engine.vision.QualityReasonCode
import com.kitalonlabs.neogold.feature.onboarding.domain.QualificationState
import com.kitalonlabs.neogold.feature.onboarding.R as OnboardingR

/**
 * S07: Camera qualification (3 photos) (NG-007, doc 6 Section 5).
 *
 * Takes three real photos of the card under room lighting on a flat surface, with the phone's own camera. A photo the
 * quality gate refuses is a bad photo and is taken again; only three good photos are judged, and the result is the
 * measured one (TC-DEV-02 to TC-DEV-05). There is no built-in result: if nothing is measured, nothing is qualified.
 */
@Composable
fun S07QualificationScreen(
    state: QualificationState,
    onQualificationComplete: (QualificationResult) -> Unit,
    modifier: Modifier = Modifier
) {
    val phase by state.phase.collectAsState()
    var session by remember { mutableStateOf<CameraSession?>(null) }

    LaunchedEffect(phase) {
        (phase as? QualificationState.Phase.Done)?.let { onQualificationComplete(it.result) }
    }
    DisposableEffect(state) { onDispose { state.release() } }

    val taken = (phase as? QualificationState.Phase.Photographing)?.taken ?: 0
    val busy = phase is QualificationState.Phase.CheckingPhoto || phase is QualificationState.Phase.Judging

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(Dimens.ScreenPadding),
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Spacer(modifier = Modifier.height(16.dp))

            NgStepIndicator(currentStep = taken + 1, totalSteps = 3)

            Spacer(modifier = Modifier.height(Dimens.SpacingSm))

            Text(
                text = stringResource(R.string.s07_title),
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onBackground
            )

            Spacer(modifier = Modifier.height(Dimens.SpacingSm))

            Text(
                text = stringResource(R.string.s07_instruction, taken + 1),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(Dimens.SpacingLg))

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(260.dp)
                    .clip(RoundedCornerShape(Dimens.CardCornerRadius))
                    .background(CameraOverlayBackground)
                    .border(2.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(Dimens.CardCornerRadius))
                    .testTag("qualification_viewfinder"),
                contentAlignment = Alignment.Center
            ) {
                if (phase !is QualificationState.Phase.CardMissing) {
                    CameraPreviewHost(
                        mode = CameraMode.Stills,
                        onSession = { session = it },
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }

            Spacer(modifier = Modifier.height(Dimens.SpacingMd))

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                for (i in 0 until 3) {
                    Box(
                        modifier = Modifier
                            .padding(horizontal = 6.dp)
                            .size(16.dp)
                            .clip(CircleShape)
                            .background(
                                if (i < taken) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
                            )
                    )
                }
            }

            Spacer(modifier = Modifier.height(Dimens.SpacingMd))

            Message(phase)
        }

        Column(modifier = Modifier.fillMaxWidth().padding(bottom = Dimens.SpacingLg)) {
            NgPrimaryButton(
                text = stringResource(R.string.btn_take_photo),
                onClick = { session?.let(state::takePhoto) },
                enabled = session != null && !busy && phase is QualificationState.Phase.Photographing,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("btn_take_qualification_photo")
            )
        }
    }
}

@Composable
private fun Message(phase: QualificationState.Phase) {
    val error = MaterialTheme.colorScheme.error
    val plain = MaterialTheme.colorScheme.onSurfaceVariant
    when (phase) {
        QualificationState.Phase.CheckingPhoto -> Line(
            stringResource(OnboardingR.string.s07_checking),
            plain,
            "qualification_checking"
        )
        QualificationState.Phase.Judging -> Line(
            stringResource(OnboardingR.string.s07_judging),
            plain,
            "qualification_judging"
        )
        QualificationState.Phase.CardMissing -> Line(
            stringResource(OnboardingR.string.s07_card_missing),
            error,
            "qualification_card_missing"
        )
        is QualificationState.Phase.Photographing -> when {
            phase.notSaved -> Line(stringResource(OnboardingR.string.s07_not_saved), error, "qualification_not_saved")
            phase.cameraFailed -> Line(
                stringResource(OnboardingR.string.s07_photo_failed),
                error,
                "qualification_photo_failed"
            )
            phase.problem.isNotEmpty() -> Line(retakeMessage(phase.problem.first()), error, "qualification_retake")
        }
        is QualificationState.Phase.Done -> Unit
    }
}

@Composable
private fun Line(text: String, color: Color, tag: String) {
    Text(text = text, style = MaterialTheme.typography.bodyMedium, color = color, modifier = Modifier.testTag(tag))
}

@Composable
private fun retakeMessage(reason: QualityReasonCode): String = stringResource(
    when (reason) {
        QualityReasonCode.NO_CARD -> OnboardingR.string.s07_retake_no_card // the card is on a table here, not on a baby
        QualityReasonCode.CARD_ANGLE -> R.string.retake_card_angle
        QualityReasonCode.NONUNIFORM_PATCHES -> R.string.retake_nonuniform_patches
        QualityReasonCode.BLUR -> R.string.retake_blur
        QualityReasonCode.GLARE -> R.string.retake_glare
        QualityReasonCode.UNDEREXPOSED -> R.string.retake_underexposed
        QualityReasonCode.OVEREXPOSED -> R.string.retake_overexposed
        QualityReasonCode.UNEVEN_LIGHT -> R.string.retake_uneven_light
        QualityReasonCode.HIGH_RESIDUAL -> R.string.retake_high_residual
        QualityReasonCode.WINDOW_NOT_SKIN -> R.string.retake_general // the window check is for babies, not the card on a table
    }
)
