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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import com.kitalonlabs.neogold.core.designsystem.R
import com.kitalonlabs.neogold.core.designsystem.components.atoms.NgBadge
import com.kitalonlabs.neogold.core.designsystem.components.atoms.NgNumberStepper
import com.kitalonlabs.neogold.core.designsystem.components.atoms.NgPrimaryButton
import com.kitalonlabs.neogold.core.designsystem.components.atoms.NgYesNoButtons
import com.kitalonlabs.neogold.core.designsystem.components.molecules.NgBirthTimeField
import com.kitalonlabs.neogold.core.designsystem.components.molecules.NgBirthTimePicker
import com.kitalonlabs.neogold.core.designsystem.theme.CardCornerRadius
import com.kitalonlabs.neogold.feature.research.data.ResearchError
import com.kitalonlabs.neogold.feature.research.data.ResearchFormats
import com.kitalonlabs.neogold.feature.research.R as ResearchR

/**
 * R03: Participant & Consent Screen (FR-26, NG-029, doc 6 Section 5).
 * Validates participant code format and paper consent form ID,
 * and collects clinical inputs (birth time, GA, safety questions) for the research session.
 */
@Composable
fun R03ParticipantConsentScreen(
    studyName: String,
    /** Why the participant could not be registered (a code already used in this study, for example). */
    error: ResearchError? = null,
    onContinueToCapture: (
        participantCode: String,
        consentFormId: String,
        birthTimeMillis: Long,
        ageHours: Double,
        gestationalAgeWeeks: Int,
        flagEarlyJaundice: Boolean,
        flagDangerSigns: Boolean,
        flagPriorPhototherapy: Boolean
    ) -> Unit,
    modifier: Modifier = Modifier
) {
    var participantCode by remember { mutableStateOf("") }
    var consentFormId by remember { mutableStateOf("") }

    // No birth time and no age until the health worker chooses them: a pre-filled 48 hours would be recorded as the
    // baby's age whenever nobody looked (A9, FR-02, H2, BUG-2026-044).
    val now = System.currentTimeMillis()
    var birthTimeMillis by remember { mutableStateOf<Long?>(null) }
    var showBirthPicker by remember { mutableStateOf(false) }
    val birthTime = birthTimeMillis
    val birthInFuture = birthTime != null && birthTime > now
    val rawAgeHours: Double? = birthTime?.takeIf { !birthInFuture }?.let { (now - it) / MILLIS_PER_HOUR }
    // The study server takes ages of 0 to 720 hours (doc 7). One hour is left for the captures, because the age is worked out
    // again when the session is saved; a birth time further back than that is a typing mistake.
    val birthTooOld = rawAgeHours != null && rawAgeHours > MAX_AGE_HOURS
    val ageHours: Double? = rawAgeHours?.takeIf { !birthTooOld }

    var gestationalAgeWeeks by remember { mutableStateOf<Int?>(null) } // No silent default (H3)
    var earlyJaundiceAnswer by remember { mutableStateOf<Boolean?>(null) }
    var dangerSignsAnswer by remember { mutableStateOf<Boolean?>(null) }
    var priorPhototherapyAnswer by remember { mutableStateOf<Boolean?>(null) }

    val isParticipantCodeValid = ResearchFormats.isValidParticipantCode(participantCode)
    val isConsentFormIdValid = ResearchFormats.isValidConsentFormId(consentFormId)
    val areInputsComplete = isParticipantCodeValid &&
        isConsentFormIdValid &&
        ageHours != null &&
        gestationalAgeWeeks != null &&
        earlyJaundiceAnswer != null &&
        dangerSignsAnswer != null &&
        priorPhototherapyAnswer != null

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(16.dp)
            .verticalScroll(rememberScrollState())
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = stringResource(ResearchR.string.r03_title),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground
            )
            NgBadge(
                text = stringResource(ResearchR.string.research_badge),
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        if (error != null) {
            Text(
                text = error.text(),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.testTag("r03_error_message")
            )
            Spacer(modifier = Modifier.height(16.dp))
        }

        // Identification Card
        Card(
            shape = RoundedCornerShape(CardCornerRadius),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.padding(16.dp)
            ) {
                Text(
                    text = stringResource(ResearchR.string.r03_study, studyName),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )

                OutlinedTextField(
                    value = participantCode,
                    onValueChange = { participantCode = it.uppercase() },
                    label = { Text(stringResource(ResearchR.string.r03_code_label)) },
                    isError = participantCode.isNotBlank() && !isParticipantCodeValid,
                    supportingText = {
                        if (participantCode.isNotBlank() && !isParticipantCodeValid) {
                            Text(stringResource(ResearchR.string.r03_code_help))
                        }
                    },
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters),
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("participant_code_field")
                )

                OutlinedTextField(
                    value = consentFormId,
                    onValueChange = { consentFormId = it.uppercase() },
                    label = { Text(stringResource(ResearchR.string.r03_consent_label)) },
                    isError = consentFormId.isNotBlank() && !isConsentFormIdValid,
                    supportingText = {
                        if (consentFormId.isNotBlank() && !isConsentFormIdValid) {
                            Text(stringResource(ResearchR.string.r03_consent_help))
                        }
                    },
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters),
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("consent_form_id_field")
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Clinical Inputs
        Card(
            shape = RoundedCornerShape(CardCornerRadius),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                verticalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier.padding(16.dp)
            ) {
                Text(
                    text = stringResource(ResearchR.string.r03_clinical),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )

                val formattedBirth = birthTime?.let {
                    java.time.Instant.ofEpochMilli(it)
                        .atZone(java.time.ZoneId.systemDefault())
                        .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
                } ?: "\u2014" // an em dash: not set yet
                val phoneTimeNow = java.time.Instant.ofEpochMilli(System.currentTimeMillis())
                    .atZone(java.time.ZoneId.systemDefault())
                    .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))

                NgBirthTimeField(
                    formattedBirthDateTime = formattedBirth,
                    ageInHours = ageHours?.toInt(),
                    currentPhoneTime = phoneTimeNow,
                    onEditClicked = { showBirthPicker = true },
                    modifier = Modifier.testTag("birth_time_field")
                )
                if (birthInFuture) {
                    Text(
                        text = stringResource(R.string.birth_time_future_error),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.testTag("birth_time_future_error")
                    )
                }
                if (birthTooOld) {
                    Text(
                        text = stringResource(R.string.birth_time_too_old_error),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.testTag("birth_time_too_old_error")
                    )
                }
                if (showBirthPicker) {
                    NgBirthTimePicker(
                        nowEpochMs = now,
                        initialEpochMs = birthTime,
                        onDismiss = { showBirthPicker = false },
                        onPicked = {
                            birthTimeMillis = it
                            showBirthPicker = false
                        }
                    )
                }

                Column {
                    Text(
                        text = stringResource(ResearchR.string.r03_ga),
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    NgNumberStepper(
                        value = gestationalAgeWeeks,
                        onValueChange = { gestationalAgeWeeks = it },
                        range = 22..44,
                        modifier = Modifier.testTag("gestational_age_stepper")
                    )
                }

                Column {
                    Text(
                        text = stringResource(ResearchR.string.r03_q_early_jaundice),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    NgYesNoButtons(
                        selectedAnswer = earlyJaundiceAnswer,
                        onAnswerSelected = { earlyJaundiceAnswer = it },
                        modifier = Modifier.testTag("early_jaundice_buttons")
                    )
                }

                Column {
                    Text(
                        text = stringResource(ResearchR.string.r03_q_danger),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    NgYesNoButtons(
                        selectedAnswer = dangerSignsAnswer,
                        onAnswerSelected = { dangerSignsAnswer = it },
                        modifier = Modifier.testTag("danger_signs_buttons")
                    )
                }

                Column {
                    Text(
                        text = stringResource(ResearchR.string.r03_q_prior_photo),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    NgYesNoButtons(
                        selectedAnswer = priorPhototherapyAnswer,
                        onAnswerSelected = { priorPhototherapyAnswer = it },
                        modifier = Modifier.testTag("prior_phototherapy_buttons")
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        NgPrimaryButton(
            text = stringResource(ResearchR.string.r03_start),
            enabled = areInputsComplete,
            onClick = {
                onContinueToCapture(
                    participantCode.trim(),
                    consentFormId.trim(),
                    birthTime!!,
                    ageHours!!,
                    gestationalAgeWeeks!!,
                    earlyJaundiceAnswer!!,
                    dangerSignsAnswer!!,
                    priorPhototherapyAnswer!!
                )
            },
            modifier = Modifier.testTag("start_captures_button")
        )
    }
}

private const val MILLIS_PER_HOUR = 3_600_000.0
private const val MAX_AGE_HOURS = 719.0
