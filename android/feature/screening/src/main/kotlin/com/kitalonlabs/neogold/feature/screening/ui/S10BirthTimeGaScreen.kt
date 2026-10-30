@file:Suppress("MagicNumber") // calendar and clock arithmetic of the pickers

package com.kitalonlabs.neogold.feature.screening.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.kitalonlabs.neogold.core.designsystem.R
import com.kitalonlabs.neogold.core.designsystem.components.atoms.NgNumberStepper
import com.kitalonlabs.neogold.core.designsystem.components.atoms.NgPrimaryButton
import com.kitalonlabs.neogold.core.designsystem.components.molecules.NgBirthTimeField
import com.kitalonlabs.neogold.core.designsystem.components.molecules.NgStepIndicator
import com.kitalonlabs.neogold.core.designsystem.theme.CardCornerRadius
import com.kitalonlabs.neogold.core.designsystem.theme.PrimaryButtonHeight
import com.kitalonlabs.neogold.feature.screening.clock.ClockIntegrityValidator
import java.time.Instant
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * S10 Birth Time and Gestational Age Screen (doc 6 Section 5, Section 7.2; FR-05, FR-06, NG-017).
 *
 * Implements:
 * - TC-UI-10: "Phone time now" line shows the phone's current date and time.
 * - TC-UI-11: Gestational age stepper has no pre-filled value; Next stays disabled until set.
 * - TC-REL-03: Clock before build date blocks screening with clock_wrong_block.
 * - TC-REL-04: Automatic date and time off shows warning banner clock_auto_off.
 * - TC-REL-06: Birth time in future refused.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun S10BirthTimeGaScreen(
    currentPhoneTimeEpochMs: Long = System.currentTimeMillis(),
    isAutoTimeEnabled: Boolean = true,
    isClockDriftExceeded: Boolean = false,
    initialBirthTimeMs: Long? = null,
    initialGestationalAgeWeeks: Int? = null,
    onNext: (ageHours: Double, gestationalAgeWeeks: Int, birthTimeMs: Long) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    // The birth time has NO pre-filled value: the worker sets it (FR-02, A9, BUG-2026-044). Until then Next stays off.
    var birthTimeSetMs by remember { mutableStateOf<Long?>(initialBirthTimeMs) }
    // Gestational age stepper: NO pre-filled value by default (H3, TC-UI-11)
    var gestationalAgeWeeks by remember {
        mutableStateOf<Int?>(initialGestationalAgeWeeks)
    }

    var showDatePicker by remember { mutableStateOf(false) }
    var showTimePicker by remember { mutableStateOf(false) }
    // where the pickers open when nothing is set yet: now, which is only a starting point and is never saved
    val pickerStartMs = birthTimeSetMs ?: currentPhoneTimeEpochMs
    var pendingDateEpochMs by remember { mutableLongStateOf(pickerStartMs) }

    val isClockBeforeBuild = ClockIntegrityValidator.isClockBeforeBuildTime(currentPhoneTimeEpochMs)
    val birthTimeMs = birthTimeSetMs
    val isBirthInFuture = birthTimeMs != null && ClockIntegrityValidator.isBirthTimeInFuture(birthTimeMs, currentPhoneTimeEpochMs)
    val ageHours = birthTimeMs?.let { ClockIntegrityValidator.computeAgeInHours(it, currentPhoneTimeEpochMs) }

    val phoneTimeFormatted = remember(currentPhoneTimeEpochMs) {
        ClockIntegrityValidator.formatPhoneTimeNow(currentPhoneTimeEpochMs)
    }

    val notSet = stringResource(R.string.birth_time_not_set)
    val birthTimeFormatted = remember(birthTimeMs, notSet) {
        if (birthTimeMs == null) {
            notSet
        } else {
            val formatter = DateTimeFormatter.ofPattern("d MMM yyyy   hh:mm a", Locale.getDefault())
                .withZone(ZoneId.systemDefault())
            formatter.format(Instant.ofEpochMilli(birthTimeMs))
        }
    }

    // Next is enabled ONLY when the birth time and the gestational age are both set by the worker, the clock is valid, and
    // the birth is not in the future (an older baby goes on: the engine's rule R5 sends a baby over 14 days to a doctor's review)
    val isNextEnabled = birthTimeMs != null &&
        gestationalAgeWeeks != null &&
        !isClockBeforeBuild &&
        !isClockDriftExceeded &&
        !isBirthInFuture

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { NgStepIndicator(currentStep = 1, totalSteps = 4) },
                navigationIcon = {
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier.testTag("btn_back")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.btn_back)
                        )
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            // Clock before build time warning (TC-REL-03, H2)
            if (isClockBeforeBuild) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                    shape = RoundedCornerShape(CardCornerRadius),
                    modifier = Modifier.fillMaxWidth().testTag("clock_wrong_block_card")
                ) {
                    Row(
                        modifier = Modifier.padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Warning,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(24.dp)
                        )
                        Text(
                            text = stringResource(R.string.clock_wrong_block),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }

            // Automatic date and time off warning (TC-REL-04, H2)
            if (!isAutoTimeEnabled) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                    shape = RoundedCornerShape(CardCornerRadius),
                    modifier = Modifier.fillMaxWidth().testTag("clock_auto_off_card")
                ) {
                    Row(
                        modifier = Modifier.padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Warning,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(24.dp)
                        )
                        Text(
                            text = stringResource(R.string.clock_auto_off),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // Birth Time Section with Phone Time Now (TC-UI-10, H2)
            NgBirthTimeField(
                formattedBirthDateTime = birthTimeFormatted,
                ageInHours = if (isBirthInFuture || ageHours == null) null else ageHours.toInt(),
                currentPhoneTime = phoneTimeFormatted,
                onEditClicked = { showDatePicker = true },
                modifier = Modifier.testTag("birth_time_field")
            )

            // Future birth error banner (TC-REL-06)
            if (isBirthInFuture) {
                Text(
                    text = stringResource(R.string.birth_time_future_error),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.testTag("birth_future_error")
                )
            }

            Spacer(modifier = Modifier.height(4.dp))

            // Gestational Age Stepper Section (TC-UI-11, FR-06, H3)
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = stringResource(R.string.ga_label),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = "Select completed weeks (22 to 44)",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.height(4.dp))

                NgNumberStepper(
                    value = gestationalAgeWeeks,
                    onValueChange = { weeks -> gestationalAgeWeeks = weeks },
                    range = 22..44,
                    unselectedPlaceholder = "--",
                    modifier = Modifier.testTag("ga_number_stepper")
                )
            }

            Spacer(modifier = Modifier.weight(1f))

            // Primary Next Button (disabled until gestational age set and clock valid)
            NgPrimaryButton(
                text = stringResource(R.string.btn_next),
                height = PrimaryButtonHeight,
                enabled = isNextEnabled,
                onClick = {
                    val finalWeeks = gestationalAgeWeeks ?: return@NgPrimaryButton
                    val finalBirthMs = birthTimeMs ?: return@NgPrimaryButton
                    val finalAge = ageHours ?: return@NgPrimaryButton
                    onNext(finalAge, finalWeeks, finalBirthMs)
                },
                modifier = Modifier.testTag("btn_next")
            )
        }

        // Date Picker Dialog
        if (showDatePicker) {
            val datePickerState = rememberDatePickerState(
                initialSelectedDateMillis = pickerStartMs
            )
            DatePickerDialog(
                onDismissRequest = { showDatePicker = false },
                confirmButton = {
                    Button(
                        onClick = {
                            val selectedMillis = datePickerState.selectedDateMillis
                            if (selectedMillis != null) {
                                pendingDateEpochMs = selectedMillis
                                showDatePicker = false
                                showTimePicker = true
                            }
                        }
                    ) {
                        Text(stringResource(R.string.btn_next))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showDatePicker = false }) {
                        Text(stringResource(R.string.btn_cancel))
                    }
                }
            ) {
                DatePicker(state = datePickerState)
            }
        }

        // Time Picker Dialog
        if (showTimePicker) {
            val localTime = LocalDateTime.ofInstant(
                Instant.ofEpochMilli(pickerStartMs),
                ZoneId.systemDefault()
            ).toLocalTime()
            val timePickerState = rememberTimePickerState(
                initialHour = localTime.hour,
                initialMinute = localTime.minute,
                is24Hour = false
            )
            androidx.compose.material3.AlertDialog(
                onDismissRequest = { showTimePicker = false },
                confirmButton = {
                    Button(
                        onClick = {
                            val selectedDate = Instant.ofEpochMilli(pendingDateEpochMs)
                                .atZone(ZoneId.systemDefault())
                                .toLocalDate()
                            val combined = LocalDateTime.of(
                                selectedDate,
                                LocalTime.of(timePickerState.hour, timePickerState.minute)
                            )
                            birthTimeSetMs = combined.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
                            showTimePicker = false
                        }
                    ) {
                        Text(stringResource(R.string.btn_continue))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showTimePicker = false }) {
                        Text(stringResource(R.string.btn_cancel))
                    }
                },
                title = { Text(stringResource(R.string.birth_time)) },
                text = {
                    TimePicker(state = timePickerState)
                }
            )
        }
    }
}
