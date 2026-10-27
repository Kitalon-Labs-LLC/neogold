package com.kitalonlabs.neogold.feature.settings

import androidx.compose.foundation.background
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
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
import com.kitalonlabs.neogold.core.designsystem.theme.Dimens
import com.kitalonlabs.neogold.core.designsystem.util.ClinicalNumberFormatter

/**
 * S23: Settings (NG-002, NG-005, NFR-06, TC-ONB-04, doc 6 Section 5).
 *
 * Controls:
 * - Language (Bangla / English)
 * - Clinical number digits (Western / Bangla)
 * - Screening log retention (30 to 365 days)
 * - Keep screening log toggle
 * - App lock toggle
 * - Crash reporting opt-in toggle
 * - Links to S24 (About) and S25 (Delete All Data)
 */
@Composable
fun S23SettingsScreen(
    currentLanguage: String = "bn",
    currentDigits: String = "western",
    retentionDays: Int = 90,
    keepLog: Boolean = true,
    appLockEnabled: Boolean = false,
    crashReportingOptIn: Boolean = false,
    onLanguageChanged: (String) -> Unit = {},
    onDigitsChanged: (String) -> Unit = {},
    onRetentionDaysChanged: (Int) -> Unit = {},
    onKeepLogChanged: (Boolean) -> Unit = {},
    onAppLockChanged: (Boolean) -> Unit = {},
    onCrashReportingChanged: (Boolean) -> Unit = {},
    onNavigateToAbout: () -> Unit = {},
    onNavigateToDeleteData: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    var selectedLang by remember(currentLanguage) { mutableStateOf(currentLanguage) }
    var selectedDigits by remember(currentDigits) { mutableStateOf(currentDigits) }
    var retentionSlider by remember(retentionDays) { mutableFloatStateOf(retentionDays.toFloat()) }
    var keepLogState by remember(keepLog) { mutableStateOf(keepLog) }
    var appLockState by remember(appLockEnabled) { mutableStateOf(appLockEnabled) }
    var crashReportState by remember(crashReportingOptIn) { mutableStateOf(crashReportingOptIn) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(Dimens.ScreenPadding)
            .verticalScroll(rememberScrollState())
    ) {
        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = stringResource(R.string.settings_title),
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onBackground
        )

        Spacer(modifier = Modifier.height(Dimens.SpacingLg))

        // Language Section
        SettingsSectionHeader(stringResource(R.string.settings_language))

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable {
                    selectedLang = "bn"
                    onLanguageChanged("bn")
                }
                .testTag("setting_lang_bn")
                .padding(vertical = Dimens.SpacingSm),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(R.string.lang_bangla),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
            RadioButton(
                selected = selectedLang == "bn",
                onClick = {
                    selectedLang = "bn"
                    onLanguageChanged("bn")
                }
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable {
                    selectedLang = "en"
                    onLanguageChanged("en")
                }
                .testTag("setting_lang_en")
                .padding(vertical = Dimens.SpacingSm),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(R.string.lang_english),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
            RadioButton(
                selected = selectedLang == "en",
                onClick = {
                    selectedLang = "en"
                    onLanguageChanged("en")
                }
            )
        }

        HorizontalDivider(modifier = Modifier.padding(vertical = Dimens.SpacingMd))

        // Clinical Digits Section
        SettingsSectionHeader(stringResource(R.string.settings_digits))

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable {
                    selectedDigits = "western"
                    onDigitsChanged("western")
                }
                .testTag("setting_digits_western")
                .padding(vertical = Dimens.SpacingSm),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(R.string.settings_digits_western),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
            RadioButton(
                selected = selectedDigits == "western",
                onClick = {
                    selectedDigits = "western"
                    onDigitsChanged("western")
                }
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable {
                    selectedDigits = "bangla"
                    onDigitsChanged("bangla")
                }
                .testTag("setting_digits_bangla")
                .padding(vertical = Dimens.SpacingSm),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(R.string.settings_digits_bangla),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
            RadioButton(
                selected = selectedDigits == "bangla",
                onClick = {
                    selectedDigits = "bangla"
                    onDigitsChanged("bangla")
                }
            )
        }

        HorizontalDivider(modifier = Modifier.padding(vertical = Dimens.SpacingMd))

        // Retention Days Slider
        val currentDaysInt = retentionSlider.toInt()
        Text(
            text = stringResource(
                R.string.settings_retention_days,
                ClinicalNumberFormatter.formatInteger(currentDaysInt)
            ),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface
        )

        Slider(
            value = retentionSlider,
            onValueChange = {
                retentionSlider = it
                onRetentionDaysChanged(it.toInt())
            },
            valueRange = 30f..365f,
            steps = 10,
            modifier = Modifier
                .fillMaxWidth()
                .testTag("slider_retention_days")
        )

        HorizontalDivider(modifier = Modifier.padding(vertical = Dimens.SpacingMd))

        // Keep Log Switch
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = Dimens.SpacingSm),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(R.string.settings_keep_log),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
            Switch(
                checked = keepLogState,
                onCheckedChange = {
                    keepLogState = it
                    onKeepLogChanged(it)
                },
                modifier = Modifier.testTag("switch_keep_log")
            )
        }

        // App Lock Switch
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = Dimens.SpacingSm),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(R.string.settings_app_lock),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
            Switch(
                checked = appLockState,
                onCheckedChange = {
                    appLockState = it
                    onAppLockChanged(it)
                },
                modifier = Modifier.testTag("switch_app_lock")
            )
        }

        // Crash Reporting Switch (Opt-in only per FR-34)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = Dimens.SpacingSm),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(R.string.settings_crash_reports),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
            Switch(
                checked = crashReportState,
                onCheckedChange = {
                    crashReportState = it
                    onCrashReportingChanged(it)
                },
                modifier = Modifier.testTag("switch_crash_reports")
            )
        }

        HorizontalDivider(modifier = Modifier.padding(vertical = Dimens.SpacingMd))

        // Navigation Links
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onNavigateToAbout)
                .testTag("link_about")
                .padding(vertical = Dimens.SpacingMd),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(R.string.settings_about),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.SemiBold
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onNavigateToDeleteData)
                .testTag("link_delete_all_data")
                .padding(vertical = Dimens.SpacingMd),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(R.string.settings_delete_all),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.error,
                fontWeight = FontWeight.SemiBold
            )
        }

        Spacer(modifier = Modifier.height(32.dp))
    }
}

@Composable
private fun SettingsSectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onSurface
    )
    Spacer(modifier = Modifier.height(Dimens.SpacingSm))
}
