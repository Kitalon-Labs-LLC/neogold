@file:Suppress("MaxLineLength") // hardcoded UI text moves to string resources in the localization package (F1)

package com.kitalonlabs.neogold.feature.screening.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.kitalonlabs.neogold.core.designsystem.R
import com.kitalonlabs.neogold.core.designsystem.components.atoms.NgYesNoButtons
import com.kitalonlabs.neogold.core.designsystem.components.molecules.NgBottomActionBar
import com.kitalonlabs.neogold.core.designsystem.components.molecules.NgSafetyQuestion
import com.kitalonlabs.neogold.core.designsystem.components.molecules.NgStepIndicator

/**
 * S11 Early Jaundice Screen (doc 6 Section 5, Section 6.1; FR-07, NG-018, TC-UI-12).
 *
 * Question: "Did yellow color appear in the first 24 hours of life?"
 * No default selection.
 * - Yes: Short-circuits immediately to S14 (R2 Urgent referral).
 * - No: Advances to S12 (Danger signs).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun S11EarlyJaundiceScreen(
    onAnswered: (isEarlyJaundice: Boolean) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    var selectedAnswer by remember { mutableStateOf<Boolean?>(null) }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { NgStepIndicator(currentStep = 2, totalSteps = 4) },
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
        },
        // The answer pair lives in the lower third, under the thumb, instead of at the end of the
        // question text (doc 6 Section 3: one main action, reachable with one hand).
        bottomBar = {
            NgBottomActionBar {
                NgYesNoButtons(
                    selectedAnswer = selectedAnswer,
                    onAnswerSelected = { answer ->
                        selectedAnswer = answer
                        onAnswered(answer)
                    },
                    modifier = Modifier.testTag("early_jaundice_yes_no")
                )
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            NgSafetyQuestion(
                questionText = stringResource(R.string.q_early_jaundice),
                explanationText = "Yellow discoloration within the first 24 hours after birth is a clinical red flag requiring urgent medical care.",
                selectedAnswer = selectedAnswer,
                onAnswerSelected = { answer ->
                    selectedAnswer = answer
                    onAnswered(answer)
                },
                showButtons = false,
                modifier = Modifier.testTag("early_jaundice_question")
            )
        }
    }
}
