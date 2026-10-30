package com.kitalonlabs.neogold.feature.screening.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
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
import com.kitalonlabs.neogold.core.designsystem.R
import com.kitalonlabs.neogold.core.designsystem.components.atoms.NgPrimaryButton
import com.kitalonlabs.neogold.core.designsystem.components.atoms.NgYesNoButtons
import com.kitalonlabs.neogold.core.designsystem.theme.CardCornerRadius
import com.kitalonlabs.neogold.core.designsystem.theme.PrimaryButtonHeight

/**
 * S10a Guardian Consent Screen (doc 6 Section 5, Section 6.1; PRD D12, FR-37).
 *
 * Implements:
 * - TC-UI-13: Guardian consent at screening.
 * - Confirms that parent or guardian agreed to screening before any camera access or inputs.
 * - No default selection on Yes/No buttons.
 * - If "No": shows consent_no message, ends the screen with zero data saved.
 * - If "Yes": continues to S10 with guardian_consent_confirmed = 1.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun S10aGuardianConsentScreen(
    onConsentConfirmed: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    var consentAnswer by remember { mutableStateOf<Boolean?>(null) }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.home_title)) },
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
                .padding(horizontal = 16.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            Text(
                text = stringResource(R.string.consent_question),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.testTag("consent_question_text")
            )

            Text(
                text = stringResource(R.string.consent_explanation),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(8.dp))

            // Yes / No buttons with NO default selection (doc 8 Section 14, TC-UI-13)
            NgYesNoButtons(
                selectedAnswer = consentAnswer,
                onAnswerSelected = { answer ->
                    consentAnswer = answer
                    if (answer) {
                        onConsentConfirmed()
                    }
                },
                modifier = Modifier.testTag("consent_yes_no_buttons")
            )

            // If "No" was selected: show explanation and option to exit
            if (consentAnswer == false) {
                Spacer(modifier = Modifier.height(12.dp))
                Card(
                    shape = RoundedCornerShape(CardCornerRadius),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surface
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("consent_refusal_card")
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Info,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(28.dp)
                        )
                        Text(
                            text = stringResource(R.string.consent_no),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.testTag("consent_no_message")
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        NgPrimaryButton(
                            text = stringResource(R.string.btn_return_home),
                            height = PrimaryButtonHeight,
                            onClick = onBack,
                            modifier = Modifier.testTag("btn_return_home")
                        )
                    }
                }
            }
        }
    }
}
