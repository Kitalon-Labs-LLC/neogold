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

/**
 * S24: About and model card (FR-32, NG-027, NG-033, TC-ONB-05, doc 6 Section 5).
 *
 * Shows:
 * - App version, model version, config version, card batch
 * - Model card summary (intended use, target population, limitations, data sources)
 * - Open source license list
 */
@Composable
fun S24AboutScreen(
    appVersion: String = "1.0.0",
    modelVersion: String = "1.0.0",
    configVersion: String = "2026.10.0",
    cardBatchId: String = "NG-2026-001",
    onBack: () -> Unit = {},
    modifier: Modifier = Modifier
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
            text = "App $appVersion • Model $modelVersion • Config $configVersion • Card $cardBatchId",
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

                Text(
                    text = "Data sources: Multicenter prospective observational validation study data.",
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

                LicenseItem(
                    name = "Noto Sans & Noto Sans Bengali",
                    license = "SIL Open Font License 1.1",
                    copyright = "Copyright 2012 Google Inc."
                )

                HorizontalDivider(modifier = Modifier.padding(vertical = Dimens.SpacingSm))

                LicenseItem(
                    name = "Material Symbols & OpenCV Android",
                    license = "Apache License 2.0",
                    copyright = "The Android Open Source Project / OpenCV team"
                )
            }
        }

        Spacer(modifier = Modifier.height(32.dp))
    }
}

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
