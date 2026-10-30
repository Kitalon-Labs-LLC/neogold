package com.kitalonlabs.neogold.core.designsystem.components.molecules

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import com.kitalonlabs.neogold.core.designsystem.R
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * Picks a date and then a time of birth (doc 6 Section 7.2). There is no value to start from: the user chooses both,
 * and a date after today cannot be chosen. Nothing is returned until the time is confirmed.
 *
 * The date picker answers with midnight UTC of the day that was tapped, so the day is read in UTC; reading it in the
 * phone's zone would shift it by a day on phones west of Greenwich.
 *
 * @param nowEpochMs the phone's time, which bounds the dates that can be chosen
 * @param initialEpochMs a time to open on, or null to open on today
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NgBirthTimePicker(
    nowEpochMs: Long,
    initialEpochMs: Long?,
    onDismiss: () -> Unit,
    onPicked: (epochMs: Long) -> Unit
) {
    val zone = ZoneId.systemDefault()
    val start = initialEpochMs ?: nowEpochMs
    var chosenDate by remember { mutableStateOf<LocalDate?>(null) }
    val today = Instant.ofEpochMilli(nowEpochMs).atZone(zone).toLocalDate()

    val date = chosenDate
    if (date == null) {
        val datePickerState = rememberDatePickerState(
            initialSelectedDateMillis = Instant.ofEpochMilli(start).atZone(zone).toLocalDate()
                .atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
            selectableDates = object : SelectableDates {
                override fun isSelectableDate(utcTimeMillis: Long): Boolean =
                    !Instant.ofEpochMilli(utcTimeMillis).atZone(ZoneOffset.UTC).toLocalDate().isAfter(today)
            }
        )
        DatePickerDialog(
            onDismissRequest = onDismiss,
            confirmButton = {
                Button(
                    onClick = {
                        datePickerState.selectedDateMillis?.let {
                            chosenDate = Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate()
                        }
                    }
                ) { Text(stringResource(R.string.btn_next)) }
            },
            dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.btn_cancel)) } }
        ) { DatePicker(state = datePickerState) }
    } else {
        val startTime = Instant.ofEpochMilli(start).atZone(zone).toLocalTime()
        val timePickerState = rememberTimePickerState(
            initialHour = startTime.hour,
            initialMinute = startTime.minute,
            is24Hour = false
        )
        AlertDialog(
            onDismissRequest = onDismiss,
            confirmButton = {
                Button(
                    onClick = {
                        val picked = LocalDateTime.of(date, LocalTime.of(timePickerState.hour, timePickerState.minute))
                        onPicked(picked.atZone(zone).toInstant().toEpochMilli())
                    }
                ) { Text(stringResource(R.string.btn_continue)) }
            },
            dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.btn_cancel)) } },
            title = { Text(stringResource(R.string.birth_time)) },
            text = { TimePicker(state = timePickerState) }
        )
    }
}
