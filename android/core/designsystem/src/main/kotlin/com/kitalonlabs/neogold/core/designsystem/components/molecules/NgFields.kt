package com.kitalonlabs.neogold.core.designsystem.components.molecules

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.kitalonlabs.neogold.core.designsystem.R
import com.kitalonlabs.neogold.core.designsystem.components.atoms.NgIcons
import com.kitalonlabs.neogold.core.designsystem.components.atoms.NgYesNoButtons
import com.kitalonlabs.neogold.core.designsystem.theme.CardCornerRadius
import com.kitalonlabs.neogold.core.designsystem.util.ClinicalNumberFormatter

/**
 * NgStepIndicator (doc 6 Section 4.2).
 * Shows "Step X of Y" and dots.
 */
@Composable
fun NgStepIndicator(
    currentStep: Int,
    totalSteps: Int,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier) {
        Text(
            text = stringResource(R.string.step_of, currentStep, totalSteps),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(6.dp))
        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            for (step in 1..totalSteps) {
                val isActive = step == currentStep
                val isCompleted = step < currentStep
                Box(
                    modifier = Modifier
                        .size(if (isActive) 10.dp else 8.dp)
                        .clip(CircleShape)
                        .background(
                            when {
                                isActive -> MaterialTheme.colorScheme.primary
                                isCompleted -> MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
                                else -> MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)
                            }
                        )
                )
            }
        }
    }
}

/**
 * NgBirthTimeField (doc 6 Section 4.2, Section 7.2).
 * Date and time of birth with live "Age: X hours" readout so health worker can sanity-check,
 * and "Phone time now" line to catch wrong system clocks (H2).
 */
@Composable
fun NgBirthTimeField(
    formattedBirthDateTime: String,
    ageInHours: Int?,
    currentPhoneTime: String,
    onEditClicked: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        onClick = onEditClicked,
        shape = RoundedCornerShape(CardCornerRadius),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        modifier = modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = stringResource(R.string.birth_time),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = formattedBirthDateTime,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(modifier = Modifier.height(8.dp))
            if (ageInHours != null) {
                Text(
                    text = stringResource(
                        R.string.age_now_hours,
                        ClinicalNumberFormatter.formatClinicalInteger(ageInHours)
                    ),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.clock_now, currentPhoneTime),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * NgSafetyQuestion (doc 6 Section 4.2).
 * One question, its explanation, and NgYesNoButtons.
 */
@Composable
fun NgSafetyQuestion(
    questionText: String,
    explanationText: String?,
    selectedAnswer: Boolean?,
    onAnswerSelected: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    yesLabel: String = stringResource(R.string.btn_yes),
    noLabel: String = stringResource(R.string.btn_no),
    content: @Composable (() -> Unit)? = null
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = questionText,
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold
        )
        if (!explanationText.isNullOrBlank()) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = explanationText,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (content != null) {
            Spacer(modifier = Modifier.height(16.dp))
            content()
        }
        Spacer(modifier = Modifier.height(24.dp))
        NgYesNoButtons(
            selectedAnswer = selectedAnswer,
            onAnswerSelected = onAnswerSelected,
            yesLabel = yesLabel,
            noLabel = noLabel
        )
    }
}

/**
 * NgDangerSignsList (doc 6 Section 4.2).
 * The four danger signs with icons, displayed inside the danger signs question.
 */
@Composable
fun NgDangerSignsList(
    modifier: Modifier = Modifier
) {
    Card(
        shape = RoundedCornerShape(CardCornerRadius),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        modifier = modifier.fillMaxWidth()
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.padding(16.dp)
        ) {
            DangerSignRow(
                icon = NgIcons.DangerFeeding,
                text = stringResource(R.string.danger_feeding)
            )
            DangerSignRow(
                icon = NgIcons.DangerSleepy,
                text = stringResource(R.string.danger_sleepy)
            )
            DangerSignRow(
                icon = NgIcons.DangerCry,
                text = stringResource(R.string.danger_cry)
            )
            DangerSignRow(
                icon = NgIcons.DangerArching,
                text = stringResource(R.string.danger_arching)
            )
        }
    }
}

@Composable
private fun DangerSignRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    text: String
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.error,
            modifier = Modifier.size(28.dp)
        )
        Text(
            text = text,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}
