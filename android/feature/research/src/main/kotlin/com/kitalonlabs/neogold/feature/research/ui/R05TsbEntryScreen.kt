package com.kitalonlabs.neogold.feature.research.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.kitalonlabs.neogold.core.designsystem.components.atoms.NgBadge
import com.kitalonlabs.neogold.core.designsystem.components.atoms.NgPrimaryButton
import com.kitalonlabs.neogold.core.designsystem.components.atoms.NgSecondaryButton
import com.kitalonlabs.neogold.core.designsystem.theme.CardCornerRadius
import com.kitalonlabs.neogold.feature.research.R
import com.kitalonlabs.neogold.feature.research.data.ResearchError
import com.kitalonlabs.neogold.feature.research.data.ResearchFormats

/**
 * R05: TSB Entry Screen (FR-28, NG-031, doc 6 Section 5).
 * Allows entering paired Total Serum Bilirubin (TSB) measurement in mg/dL,
 * measurement method (LAB, POINT_OF_CARE, OTHER), and blood draw time.
 * Can be entered immediately after captures or deferred for later entry by participant code (TC-RSH-11).
 */
@Composable
fun R05TsbEntryScreen(
    initialParticipantCode: String? = null,
    isDeferredEntry: Boolean = false,
    /** Why the session or the value could not be saved, from the layer below. */
    saveError: ResearchError? = null,
    onSaveTsb: (
        participantCode: String,
        tsbMgDl: Double,
        drawTimeMillis: Long,
        method: String
    ) -> Unit,
    onSkipTsb: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    var participantCode by remember { mutableStateOf(initialParticipantCode ?: "") }
    var tsbValueText by remember { mutableStateOf("") }
    var selectedMethod by remember { mutableStateOf("LAB") } // LAB, POINT_OF_CARE, OTHER
    var drawTimeMillis by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var errorMessage by remember { mutableStateOf<Int?>(null) } // a string resource

    val methods = listOf(
        "LAB" to stringResource(R.string.r05_method_lab),
        "POINT_OF_CARE" to stringResource(R.string.r05_method_poc),
        "OTHER" to stringResource(R.string.r05_method_other)
    )

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(16.dp)
            .verticalScroll(rememberScrollState())
            .testTag("r05_screen")
    ) {
        // Research Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            NgBadge(
                text = stringResource(R.string.research_badge),
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.testTag("r05_research_badge")
            )
            if (!initialParticipantCode.isNullOrBlank()) {
                Text(
                    text = initialParticipantCode,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = if (isDeferredEntry) "Deferred TSB Entry" else "Paired TSB Measurement",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground
        )

        Spacer(modifier = Modifier.height(4.dp))

        Text(
            text = stringResource(R.string.r05_intro),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(16.dp))

        Card(
            shape = RoundedCornerShape(CardCornerRadius),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface
            ),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                // Participant code input (editable if not provided or if deferred)
                if (initialParticipantCode.isNullOrBlank()) {
                    Text(
                        text = stringResource(R.string.r05_code_label),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    OutlinedTextField(
                        value = participantCode,
                        onValueChange = {
                            participantCode = it.uppercase()
                            errorMessage = null
                        },
                        placeholder = { Text(stringResource(R.string.r05_code_hint)) },
                        singleLine = true,
                        isError = participantCode.isNotBlank() && !ResearchFormats.isValidParticipantCode(participantCode),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("r05_participant_code_field")
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                }

                // TSB Value Input
                Text(
                    text = stringResource(R.string.r05_value_label),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(4.dp))
                OutlinedTextField(
                    value = tsbValueText,
                    onValueChange = {
                        tsbValueText = it
                        errorMessage = null
                    },
                    placeholder = { Text(stringResource(R.string.r05_value_hint)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("r05_tsb_input")
                )

                Spacer(modifier = Modifier.height(16.dp))

                // Method Selection
                Text(
                    text = stringResource(R.string.r05_method),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(8.dp))
                Column(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    methods.forEach { (methodCode, label) ->
                        val isSelected = selectedMethod == methodCode
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .border(
                                    width = if (isSelected) 2.dp else 1.dp,
                                    color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                                    shape = RoundedCornerShape(8.dp)
                                )
                                .clickable { selectedMethod = methodCode }
                                .padding(horizontal = 12.dp, vertical = 8.dp)
                                .testTag("r05_method_${methodCode.lowercase()}")
                        ) {
                            RadioButton(
                                selected = isSelected,
                                onClick = { selectedMethod = methodCode }
                            )
                            Text(
                                text = label,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.padding(start = 8.dp)
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Blood Draw Time
                Text(
                    text = stringResource(R.string.r05_draw_time),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(4.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(8.dp))
                        .padding(12.dp)
                        .testTag("r05_draw_time"),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = java.time.Instant.ofEpochMilli(drawTimeMillis)
                            .atZone(java.time.ZoneId.systemDefault())
                            .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")),
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }

        val shownError = errorMessage?.let { stringResource(it) } ?: saveError?.text()
        if (shownError != null) {
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = shownError,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.testTag("r05_error_message")
            )
        }

        Spacer(modifier = Modifier.height(24.dp))

        // Save TSB Button
        NgPrimaryButton(
            text = stringResource(R.string.r05_save),
            onClick = {
                val cleanCode = participantCode.trim()
                if (!ResearchFormats.isValidParticipantCode(cleanCode)) {
                    errorMessage = R.string.r05_error_code_format
                    return@NgPrimaryButton
                }
                val tsbVal = tsbValueText.toDoubleOrNull()
                if (tsbVal == null || tsbVal <= 0.0 || tsbVal > 40.0) {
                    errorMessage = R.string.r05_error_tsb_range
                    return@NgPrimaryButton
                }
                onSaveTsb(cleanCode, tsbVal, drawTimeMillis, selectedMethod)
            },
            modifier = Modifier.testTag("r05_save_button")
        )

        // Skip / Enter Later Option
        if (onSkipTsb != null) {
            Spacer(modifier = Modifier.height(12.dp))
            NgSecondaryButton(
                text = stringResource(R.string.r05_later),
                onClick = onSkipTsb,
                modifier = Modifier.testTag("r05_skip_button")
            )
        }
    }
}
