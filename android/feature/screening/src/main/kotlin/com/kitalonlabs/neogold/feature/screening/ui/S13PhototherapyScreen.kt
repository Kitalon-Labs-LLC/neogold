package com.kitalonlabs.neogold.feature.screening.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
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
import com.kitalonlabs.neogold.core.designsystem.components.molecules.NgSafetyQuestion
import com.kitalonlabs.neogold.core.designsystem.components.molecules.NgStepIndicator

/**
 * S13 Prior Phototherapy Screen (doc 6 Section 5, Section 6.1; FR-07, NG-018, TC-UI-12).
 *
 * Question: "Has the baby already had phototherapy (light treatment)?"
 * No default selection.
 * - Yes: Short-circuits immediately to S14 (R4 Cannot screen: blood test).
 * - No: Evaluates remaining hard rules (GA < 35 wk -> R3, Age > 336 h -> R5).
 *   If all pass, advances to S15 Capture Guide.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun S13PhototherapyScreen(
    onAnswered: (hadPhototherapy: Boolean) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    var selectedAnswer by remember { mutableStateOf<Boolean?>(null) }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { NgStepIndicator(currentStep = 4, totalSteps = 4) },
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
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            NgSafetyQuestion(
                questionText = stringResource(R.string.q_phototherapy),
                explanationText = "Phototherapy light alters skin chromaticity, reducing phone camera estimate reliability. These babies require laboratory testing.",
                selectedAnswer = selectedAnswer,
                onAnswerSelected = { answer ->
                    selectedAnswer = answer
                    onAnswered(answer)
                },
                modifier = Modifier.testTag("phototherapy_question")
            )
        }
    }
}
