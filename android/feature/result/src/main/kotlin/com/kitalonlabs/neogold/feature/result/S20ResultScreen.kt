package com.kitalonlabs.neogold.feature.result

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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

/**
 * S20 Result Screen (doc 6 Section 5, Section 7.4).
 *
 * Implements:
 * - FR-21, FR-22, NG-021, NG-022, NG-047
 * - TC-UI-01: Four actions, four signals (color, icon, border, words)
 * - TC-UI-02: Experimental label visible at 360 dp without scrolling
 * - TC-UI-03: Model version, validation summary, and confirm_blood
 * - TC-UI-09: What to tell the mother card
 * - TC-UI-14: Decision-support framing (Suggested next step & You decide)
 * - TC-TRI-26: When triage is disabled, blood test message shown instead
 */
@Composable
fun S20ResultScreen(
    action: TriageUiAction,
    nextStepText: String,
    reasonText: String?,
    estimatedLow: Double?,
    estimatedHigh: Double?,
    threshold: Double?,
    recheckHours: Int = 24,
    appVersion: String = "1.0.0",
    modelVersion: String = "1.0.0",
    configVersion: String = "2026.10.0",
    cardBatchId: String? = "NG-2026-001",
    triageEnabled: Boolean = true,
    onSaveToLog: (registerNumber: String?) -> Unit,
    modifier: Modifier = Modifier
) {
    var registerNumber by remember { mutableStateOf("") }
    var registerError by remember { mutableStateOf<String?>(null) }
    val scrollState = rememberScrollState()

    Scaffold(modifier = modifier.fillMaxSize()) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(scrollState)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Experimental label (NG-022, TC-UI-02) - visible at top without scrolling at 360 dp
            NgExperimentalBanner(modifier = Modifier.testTag("experimental_banner"))

            if (!triageEnabled) {
                // TC-TRI-26: When triage is disabled, every path that would show a result
                // shows the blood test message instead.
                NgTriageCard(
                    action = TriageUiAction.CANNOT_SCREEN,
                    nextStepText = stringResource(R.string.confirm_blood),
                    reasonText = stringResource(R.string.cannot_screen),
                    modifier = Modifier.testTag("triage_card_disabled")
                )
            } else {
                // Main 4-signal Action Card with suggested next step & you decide framing (TC-UI-01, TC-UI-14)
                NgTriageCard(
                    action = action,
                    recheckHours = recheckHours,
                    nextStepText = nextStepText,
                    reasonText = reasonText,
                    estimatedLow = estimatedLow,
                    estimatedHigh = estimatedHigh,
                    threshold = threshold,
                    modifier = Modifier.testTag("triage_card")
                )
            }

            // Always confirm with a blood test reminder (FR-22, TC-UI-03)
            Text(
                text = stringResource(R.string.confirm_blood),
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.testTag("confirm_blood_reminder")
            )

            // Validation population summary (FR-22, TC-UI-03)
            Text(
                text = stringResource(R.string.about_limitations),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag("validation_summary")
            )

            // "What to tell the mother" card (NG-047, TC-UI-09)
            if (triageEnabled) {
                WhatToTellTheMotherCard(
                    action = action,
                    modifier = Modifier.testTag("what_to_tell_mother_card")
                )
            }

            // Register number field (FR-23, NG-023, TC-LOG-02, TC-LOG-03)
            Column(modifier = Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = registerNumber,
                    onValueChange = { input ->
                        registerNumber = input
                        val validation = RegisterNumberValidator.validate(input)
                        registerError = if (validation is RegisterNumberValidator.Result.Invalid) {
                            // register_rejected string
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
                            color = if (registerError != null) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            }
                        )
                    },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("register_number_field")
                )
            }

            // Save to log action button (64 dp tall for gloved hands)
            NgPrimaryButton(
                text = stringResource(R.string.btn_save_to_log),
                height = ResultButtonHeight,
                enabled = registerError == null,
                onClick = {
                    val finalRegisterNumber = registerNumber.trim().ifBlank { null }
                    onSaveToLog(finalRegisterNumber)
                },
                modifier = Modifier.testTag("btn_save_to_log")
            )

            // Versions footer: App, Model, Config, Card batch (FR-22, TC-UI-03)
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
