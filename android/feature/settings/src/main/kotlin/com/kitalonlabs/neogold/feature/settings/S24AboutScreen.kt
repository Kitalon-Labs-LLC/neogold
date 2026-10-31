package com.kitalonlabs.neogold.feature.settings

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.kitalonlabs.neogold.core.designsystem.R
import com.kitalonlabs.neogold.core.designsystem.components.atoms.NgTextButton
import com.kitalonlabs.neogold.core.designsystem.theme.Dimens
import com.kitalonlabs.neogold.core.designsystem.util.ClinicalNumberFormatter

/**
 * S24: About and model card (FR-32, NG-027, NG-033, TC-ONB-05, doc 6 Section 5).
 *
 * Shows:
 * - App version, model version, config version, card batch
 * - Model card summary (intended use, target population, limitations, data sources)
 * - Open source license list
 *
 * Every version and the data sources come from what the app actually loaded (the verified model and config), never from a
 * default: a model that is not the one the screen names would be a false statement about the evidence (BUG-2026-091).
 */
@Composable
fun S24AboutScreen(
    appVersion: String,
    modelVersion: String,
    configVersion: String,
    dataSources: String?,
    sampleCount: Int?,
    modelDescription: String?,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    cardBatchId: String? = null
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(Dimens.ScreenPadding)
            .verticalScroll(rememberScrollState())
    ) {
        Spacer(modifier = Modifier.height(16.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(R.string.about_title),
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onBackground
            )

            NgTextButton(
                text = stringResource(R.string.btn_back),
                onClick = onBack,
                modifier = Modifier.testTag("btn_about_back")
            )
        }

        Spacer(modifier = Modifier.height(Dimens.SpacingSm))

        Text(
            text = if (cardBatchId.isNullOrBlank()) {
                stringResource(R.string.about_version, appVersion, modelVersion, configVersion)
            } else {
                stringResource(R.string.version_line_card, appVersion, modelVersion, configVersion, cardBatchId)
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.testTag("about_version_info")
        )

        Spacer(modifier = Modifier.height(Dimens.SpacingLg))

        // Model Card Summary Card
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(Dimens.CardCornerRadius))
                .background(MaterialTheme.colorScheme.surface)
                .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(Dimens.CardCornerRadius))
                .padding(Dimens.SpacingMd)
                .testTag("about_model_card")
        ) {
            Column {
                Text(
                    text = stringResource(R.string.about_model_card_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )

                Spacer(modifier = Modifier.height(Dimens.SpacingSm))

                Text(
                    text = stringResource(R.string.about_intended_use),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )

                Spacer(modifier = Modifier.height(Dimens.SpacingSm))

                Text(
                    text = stringResource(R.string.about_limitations),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )

                Spacer(modifier = Modifier.height(Dimens.SpacingSm))

                // The model says what it is in its own words (a synthetic bench model says so); the experimental label is
                // always shown here, because nothing in this build has been validated on babies.
                if (!modelDescription.isNullOrBlank()) {
                    Text(
                        text = stringResource(R.string.about_model_note, modelDescription),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(Dimens.SpacingSm))
                }
                Text(
                    text = stringResource(R.string.experimental),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )

                Spacer(modifier = Modifier.height(Dimens.SpacingSm))

                Text(
                    text = if (dataSources.isNullOrBlank() || sampleCount == null) {
                        stringResource(R.string.about_data_sources_unknown)
                    } else {
                        stringResource(
                            R.string.about_data_sources,
                            dataSources,
                            ClinicalNumberFormatter.formatClinicalInteger(sampleCount)
                        )
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Spacer(modifier = Modifier.height(Dimens.SpacingLg))

        // Open Source Licenses Card
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(Dimens.CardCornerRadius))
                .background(MaterialTheme.colorScheme.surface)
                .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(Dimens.CardCornerRadius))
                .padding(Dimens.SpacingMd)
                .testTag("about_licenses_card")
        ) {
            Column {
                Text(
                    text = stringResource(R.string.about_license_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )

                Spacer(modifier = Modifier.height(Dimens.SpacingSm))

                LicenseItem(
                    name = "NeoGold Android Code",
                    license = "MIT License",
                    copyright = "Copyright (c) 2026 Kitalon Labs LLC"
                )

                HorizontalDivider(modifier = Modifier.padding(vertical = Dimens.SpacingSm))

                LicenseItem(
                    name = "NeoGold Card Design & Documentation",
                    license = "Creative Commons Attribution 4.0 International (CC BY 4.0)",
                    copyright = "Copyright (c) 2026 Kitalon Labs LLC"
                )

                HorizontalDivider(modifier = Modifier.padding(vertical = Dimens.SpacingSm))

                // The libraries the app is built from. The fonts are the phone's own: Noto is not bundled yet (BUG-2026-091).
                THIRD_PARTY_LICENSES.forEachIndexed { index, item ->
                    if (index > 0) HorizontalDivider(modifier = Modifier.padding(vertical = Dimens.SpacingSm))
                    LicenseItem(name = item.name, license = item.license, copyright = item.copyright)
                }
            }
        }

        Spacer(modifier = Modifier.height(32.dp))
    }
}

private class ThirdPartyLicense(val name: String, val license: String, val copyright: String)

private val THIRD_PARTY_LICENSES = listOf(
    ThirdPartyLicense(
        "AndroidX (Compose, Material icons, CameraX, Room, WorkManager, DataStore, Navigation, Biometric)",
        "Apache License 2.0",
        "Copyright The Android Open Source Project"
    ),
    ThirdPartyLicense(
        "Kotlin and kotlinx (coroutines, serialization)",
        "Apache License 2.0",
        "Copyright JetBrains s.r.o. and Kotlin contributors"
    ),
    ThirdPartyLicense("OpenCV", "Apache License 2.0", "Copyright OpenCV team"),
    ThirdPartyLicense("ZXing", "Apache License 2.0", "Copyright ZXing authors"),
    ThirdPartyLicense("OkHttp and Okio", "Apache License 2.0", "Copyright Square, Inc."),
    ThirdPartyLicense("Tink", "Apache License 2.0", "Copyright Google LLC"),
    ThirdPartyLicense("Timber", "Apache License 2.0", "Copyright Jake Wharton"),
    ThirdPartyLicense(
        "SQLCipher for Android",
        "BSD-3-Clause license",
        "Copyright Zetetic LLC. This product includes software developed by Zetetic LLC."
    ),
    ThirdPartyLicense(
        "Bouncy Castle",
        "Bouncy Castle License (MIT)",
        "Copyright The Legion of the Bouncy Castle Inc."
    )
)

@Composable
private fun LicenseItem(name: String, license: String, copyright: String) {
    Column {
        Text(
            text = name,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface
        )
        Text(
            text = license,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary
        )
        Text(
            text = copyright,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
