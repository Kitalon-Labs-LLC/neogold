package com.kitalonlabs.neogold.feature.onboarding

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
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.kitalonlabs.neogold.core.designsystem.R
import com.kitalonlabs.neogold.core.designsystem.components.atoms.NgPrimaryButton
import com.kitalonlabs.neogold.core.designsystem.theme.Dimens

/**
 * S01: Language choice (NG-002, TC-ONB-01, doc 6 Section 5).
 *
 * Offers Bangla and English at first launch; selection applies to every following screen.
 */
@Composable
fun S01LanguageScreen(
    initialLanguage: String = "bn",
    onLanguageSelected: (String) -> Unit = {},
    onContinue: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    var selectedLang by remember { mutableStateOf(initialLanguage) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(Dimens.ScreenPadding),
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Spacer(modifier = Modifier.height(32.dp))

            Text(
                text = stringResource(R.string.s01_title),
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onBackground
            )

            Spacer(modifier = Modifier.height(Dimens.SpacingSm))

            Text(
                text = stringResource(R.string.s01_subtitle),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(Dimens.SpacingXl))

            // Bangla selection option
            LanguageOptionCard(
                label = stringResource(R.string.lang_bangla),
                nativeLabel = "বাংলা",
                isSelected = selectedLang == "bn",
                onSelect = {
                    selectedLang = "bn"
                    onLanguageSelected("bn")
                },
                testTag = "lang_option_bn"
            )

            Spacer(modifier = Modifier.height(Dimens.SpacingMd))

            // English selection option
            LanguageOptionCard(
                label = stringResource(R.string.lang_english),
                nativeLabel = "English",
                isSelected = selectedLang == "en",
                onSelect = {
                    selectedLang = "en"
                    onLanguageSelected("en")
                },
                testTag = "lang_option_en"
            )
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = Dimens.SpacingLg)
        ) {
            NgPrimaryButton(
                text = stringResource(R.string.btn_continue),
                onClick = onContinue,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("btn_language_continue")
            )
        }
    }
}

@Composable
private fun LanguageOptionCard(
    label: String,
    nativeLabel: String,
    isSelected: Boolean,
    onSelect: () -> Unit,
    testTag: String
) {
    val borderColor = if (isSelected) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.outline
    }
    val borderWidth = if (isSelected) 2.dp else 1.dp
    val cardBackground = if (isSelected) {
        MaterialTheme.colorScheme.surfaceVariant
    } else {
        MaterialTheme.colorScheme.surface
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(Dimens.TouchTargetMin.coerceAtLeast(64.dp))
            .clip(RoundedCornerShape(Dimens.CardCornerRadius))
            .background(cardBackground)
            .border(borderWidth, borderColor, RoundedCornerShape(Dimens.CardCornerRadius))
            .selectable(
                selected = isSelected,
                onClick = onSelect,
                role = Role.RadioButton
            )
            .testTag(testTag)
            .padding(horizontal = Dimens.SpacingMd),
        contentAlignment = Alignment.CenterStart
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column {
                Text(
                    text = nativeLabel,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                if (label != nativeLabel) {
                    Text(
                        text = label,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            RadioButton(
                selected = isSelected,
                onClick = null,
                colors = RadioButtonDefaults.colors(
                    selectedColor = MaterialTheme.colorScheme.primary,
                    unselectedColor = MaterialTheme.colorScheme.outline
                )
            )
        }
    }
}
