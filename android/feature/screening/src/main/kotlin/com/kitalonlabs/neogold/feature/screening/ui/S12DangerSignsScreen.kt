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
import com.kitalonlabs.neogold.core.designsystem.components.molecules.NgDangerSignsList
import com.kitalonlabs.neogold.core.designsystem.components.molecules.NgSafetyQuestion
import com.kitalonlabs.neogold.core.designsystem.components.molecules.NgStepIndicator

/**
 * S12 Danger Signs Screen (doc 6 Section 5, Section 6.1; FR-07, NG-018, TC-UI-12).
 *
 * Question: "Does the baby have any of these danger signs?"
 * Lists the four danger signs with icons (feeding, sleepy, cry, arching).
 * No default selection.
 * - Yes: Short-circuits immediately to S14 (R1 Urgent referral).
 * - No: Advances to S13 (Phototherapy).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun S12DangerSignsScreen(
    onAnswered: (hasDangerSigns: Boolean) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    var selectedAnswer by remember { mutableStateOf<Boolean?>(null) }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { NgStepIndicator(currentStep = 3, totalSteps = 4) },
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
                .padding(horizontal = 16.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            NgSafetyQuestion(
                questionText = stringResource(R.string.q_danger_signs),
                explanationText = "Check the baby carefully for acute clinical danger signs.",
                selectedAnswer = selectedAnswer,
                onAnswerSelected = { answer ->
                    selectedAnswer = answer
                    onAnswered(answer)
                },
                content = {
                    NgDangerSignsList(modifier = Modifier.testTag("danger_signs_list"))
                },
                modifier = Modifier.testTag("danger_signs_question")
            )
        }
    }
}
