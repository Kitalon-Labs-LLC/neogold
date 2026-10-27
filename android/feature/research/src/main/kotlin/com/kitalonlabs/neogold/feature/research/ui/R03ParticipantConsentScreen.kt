@file:Suppress("MagicNumber") // pre-filled age default, removed in C2 (A9, FR-02, BUG-2026-044)

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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import com.kitalonlabs.neogold.core.designsystem.components.atoms.NgBadge
import com.kitalonlabs.neogold.core.designsystem.components.atoms.NgNumberStepper
import com.kitalonlabs.neogold.core.designsystem.components.atoms.NgPrimaryButton
import com.kitalonlabs.neogold.core.designsystem.components.atoms.NgYesNoButtons
import com.kitalonlabs.neogold.core.designsystem.components.molecules.NgBirthTimeField
import com.kitalonlabs.neogold.core.designsystem.theme.CardCornerRadius
import com.kitalonlabs.neogold.feature.research.data.ResearchFormats

/**
 * R03: Participant & Consent Screen (FR-26, NG-029, doc 6 Section 5).
 * Validates participant code format and paper consent form ID,
 * and collects clinical inputs (birth time, GA, safety questions) for the research session.
 */
@Composable
fun R03ParticipantConsentScreen(
    studyName: String,
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

    val now = System.currentTimeMillis()
    var birthTimeMillis by remember { mutableStateOf(now - 48 * 3600 * 1000L) } // default 48h
    var calculatedAgeHours by remember { mutableStateOf(48.0) }

    var gestationalAgeWeeks by remember { mutableStateOf<Int?>(null) } // No silent default (H3)
    var earlyJaundiceAnswer by remember { mutableStateOf<Boolean?>(null) }
    var dangerSignsAnswer by remember { mutableStateOf<Boolean?>(null) }
    var priorPhototherapyAnswer by remember { mutableStateOf<Boolean?>(null) }

    val isParticipantCodeValid = ResearchFormats.isValidParticipantCode(participantCode)
    val isConsentFormIdValid = ResearchFormats.isValidConsentFormId(consentFormId)
    val areInputsComplete = isParticipantCodeValid &&
        isConsentFormIdValid &&
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
                text = "Participant & Consent",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground
            )
            NgBadge(
                text = "RESEARCH MODE",
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

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
                    text = "Study: $studyName",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )

                OutlinedTextField(
                    value = participantCode,
                    onValueChange = { participantCode = it.uppercase() },
                    label = { Text("Participant Code (e.g. SKP-0142)") },
                    isError = participantCode.isNotBlank() && !isParticipantCodeValid,
                    supportingText = {
                        if (participantCode.isNotBlank() && !isParticipantCodeValid) {
                            Text("Format must match ^[A-Z]{2,5}-[0-9]{3,6}$")
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
                    label = { Text("Paper Consent Form ID (e.g. SKP-C-0142)") },
                    isError = consentFormId.isNotBlank() && !isConsentFormIdValid,
                    supportingText = {
                        if (consentFormId.isNotBlank() && !isConsentFormIdValid) {
                            Text("Format must match ^[A-Z]{2,5}-[A-Z0-9-]{2,26}$")
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
                    text = "Clinical Details",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )

                val formattedBirth = java.time.Instant.ofEpochMilli(birthTimeMillis)
                    .atZone(java.time.ZoneId.systemDefault())
                    .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
                val phoneTimeNow = java.time.Instant.ofEpochMilli(System.currentTimeMillis())
                    .atZone(java.time.ZoneId.systemDefault())
                    .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))

                NgBirthTimeField(
                    formattedBirthDateTime = formattedBirth,
                    ageInHours = calculatedAgeHours.toInt(),
                    currentPhoneTime = phoneTimeNow,
                    onEditClicked = { },
                    modifier = Modifier.testTag("birth_time_field")
                )

                Column {
                    Text(
                        text = "Gestational age (weeks)",
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
                        text = "Yellow color in first 24 hours?",
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
                        text = "Any danger signs present?",
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
                        text = "Prior phototherapy received?",
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
            text = "Start Captures",
            enabled = areInputsComplete,
            onClick = {
                onContinueToCapture(
                    participantCode.trim(),
                    consentFormId.trim(),
                    birthTimeMillis,
                    calculatedAgeHours,
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
