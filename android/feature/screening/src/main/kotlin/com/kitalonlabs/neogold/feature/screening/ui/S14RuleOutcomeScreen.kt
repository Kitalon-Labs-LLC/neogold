package com.kitalonlabs.neogold.feature.screening.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.kitalonlabs.neogold.core.common.validation.RegisterNumberValidator
import com.kitalonlabs.neogold.core.designsystem.R
import com.kitalonlabs.neogold.core.designsystem.components.atoms.NgPrimaryButton
import com.kitalonlabs.neogold.core.designsystem.components.molecules.NgExperimentalBanner
import com.kitalonlabs.neogold.core.designsystem.components.molecules.NgVersionFooter
import com.kitalonlabs.neogold.core.designsystem.components.organisms.NgTriageCard
import com.kitalonlabs.neogold.core.designsystem.components.organisms.TriageUiAction
import com.kitalonlabs.neogold.core.designsystem.theme.ResultButtonHeight
import com.kitalonlabs.neogold.engine.triage.TriageReasonCode

/**
 * S14 Rule Outcome Screen (doc 6 Section 5, Section 6.1; FR-08, NG-019).
 *
 * Displays outcomes from clinical hard rules evaluated before camera capture:
 * - R1: Danger signs -> REFER_URGENT
 * - R2: Jaundice < 24 h -> REFER_URGENT
 * - R3: GA < 35 wk -> CANNOT_SCREEN (blood test)
 * - R4: Prior phototherapy -> CANNOT_SCREEN (blood test)
 * - R5: Age > 336 h (14 days) -> CANNOT_SCREEN (clinical review)
 *
 * Writes a completed session row upon saving so every decision is auditable (doc 4 Section 4.4).
 */
@Composable
fun S14RuleOutcomeScreen(
    ruleCode: String,
    onSaveToLog: (registerNumber: String?) -> Unit,
    appVersion: String = "1.0.0",
    modelVersion: String = "1.0.0",
    configVersion: String = "2026.10.0",
    cardBatchId: String? = "NG-2026-001",
    modifier: Modifier = Modifier
) {
    var registerNumber by remember { mutableStateOf("") }
    var registerError by remember { mutableStateOf<String?>(null) }

    val (action, nextStepText, reasonText) = when (ruleCode) {
        TriageReasonCode.R1 -> Triple(
            TriageUiAction.REFER_URGENT,
            stringResource(R.string.rule_urgent_next_step),
            "Clinical danger signs detected. Immediate emergency medical evaluation required."
        )
        TriageReasonCode.R2 -> Triple(
            TriageUiAction.REFER_URGENT,
            stringResource(R.string.rule_urgent_next_step),
            "Yellow discoloration in the first 24 hours of life indicates severe early hyperbilirubinemia."
        )
        TriageReasonCode.R3 -> Triple(
            TriageUiAction.CANNOT_SCREEN,
            stringResource(R.string.rule_ga_reason),
            "Gestational age is under 35 completed weeks. Use a laboratory blood test."
        )
        TriageReasonCode.R4 -> Triple(
            TriageUiAction.CANNOT_SCREEN,
            stringResource(R.string.rule_phototherapy_reason),
            "Baby has already had light treatment (phototherapy). Use a laboratory blood test."
        )
        TriageReasonCode.R5 -> Triple(
            TriageUiAction.CANNOT_SCREEN,
            stringResource(R.string.rule_age_reason),
            "Baby is older than 14 days (336 hours). Jaundice after two weeks requires doctor review."
        )
        else -> Triple(
            TriageUiAction.CANNOT_SCREEN,
            stringResource(R.string.cannot_screen),
            "Clinical rule triggered."
        )
    }

    Scaffold(modifier = modifier.fillMaxSize()) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            NgExperimentalBanner(modifier = Modifier.testTag("rule_outcome_experimental"))

            NgTriageCard(
                action = action,
                nextStepText = nextStepText,
                reasonText = reasonText,
                modifier = Modifier.testTag("rule_outcome_triage_card")
            )

            Text(
                text = stringResource(R.string.confirm_blood),
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.testTag("confirm_blood_reminder")
            )

            // Register number field
            Column(modifier = Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = registerNumber,
                    onValueChange = { input ->
                        registerNumber = input
                        val validation = RegisterNumberValidator.validate(input)
                        registerError = if (validation is RegisterNumberValidator.Result.Invalid) {
                            "This looks like a phone or ID number. Use the facility register number only."
                        } else {
                            null
                        }
                    },
                    label = { Text(stringResource(R.string.register_label)) },
                    placeholder = { Text("e.g. 12/345 or OPD-2027-15") },
                    isError = registerError != null,
                    supportingText = {
                        Text(
                            text = registerError ?: stringResource(R.string.register_hint),
                            color = if (registerError != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("register_number_field")
                )
            }

            NgPrimaryButton(
                text = stringResource(R.string.btn_save_to_log),
                height = ResultButtonHeight,
                enabled = registerError == null,
                onClick = {
                    val finalReg = registerNumber.trim().ifBlank { null }
                    onSaveToLog(finalReg)
                },
                modifier = Modifier.testTag("btn_save_rule_outcome")
            )

            NgVersionFooter(
                appVersion = appVersion,
                modelVersion = modelVersion,
                configVersion = configVersion,
                cardBatchId = cardBatchId,
                modifier = Modifier
                    .padding(bottom = 16.dp)
                    .testTag("version_footer")
            )
        }
    }
}
