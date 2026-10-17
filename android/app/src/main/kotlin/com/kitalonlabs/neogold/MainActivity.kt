package com.kitalonlabs.neogold

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.kitalonlabs.neogold.core.designsystem.theme.NeoGoldTheme
import com.kitalonlabs.neogold.navigation.Destinations

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            NeoGoldTheme {
                val navController = rememberNavController()
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    NeoGoldNavHost(
                        navController = navController,
                        modifier = Modifier.padding(innerPadding)
                    )
                }
            }
        }
    }
}

@Composable
fun NeoGoldNavHost(
    navController: NavHostController,
    modifier: Modifier = Modifier
) {
    NavHost(
        navController = navController,
        startDestination = Destinations.S01_LANGUAGE,
        modifier = modifier
    ) {
        // S01 to S08 Onboarding & Qualification
        composable(Destinations.S01_LANGUAGE) { PlaceholderScreen("S01: Language Choice") }
        composable(Destinations.S02_S04_SAFETY_NOTICE) { PlaceholderScreen("S02-S04: Safety Notice") }
        composable(Destinations.S05_DEVICE_CHECK) { PlaceholderScreen("S05: Device Check") }
        composable(Destinations.S06_CARD_SCAN) { PlaceholderScreen("S06: Card Scan") }
        composable(Destinations.S07_QUALIFICATION) { PlaceholderScreen("S07: Qualification Capture") }
        composable(Destinations.S08_QUALIFICATION_RESULT) { PlaceholderScreen("S08: Qualification Result") }

        // S09 Home
        composable(Destinations.S09_HOME) { PlaceholderScreen("S09: Home") }

        // S10a to S20 Screening Flow
        composable(Destinations.S10A_GUARDIAN_CONSENT) { PlaceholderScreen("S10a: Guardian Consent") }
        composable(Destinations.S10_BIRTH_TIME_GA) { PlaceholderScreen("S10: Birth Time & Gestational Age") }
        composable(Destinations.S11_EARLY_JAUNDICE) { PlaceholderScreen("S11: Early Jaundice") }
        composable(Destinations.S12_DANGER_SIGNS) { PlaceholderScreen("S12: Danger Signs") }
        composable(Destinations.S13_PHOTOTHERAPY) { PlaceholderScreen("S13: Phototherapy") }
        composable(Destinations.S14_RULE_OUTCOME) { PlaceholderScreen("S14: Rule Outcome") }
        composable(Destinations.S15_CAPTURE_GUIDE) { PlaceholderScreen("S15: Capture Guide") }
        composable(Destinations.S16_CAMERA) { PlaceholderScreen("S16: Camera") }
        composable(Destinations.S17_PHOTO_PROBLEM) { PlaceholderScreen("S17: Photo Problem") }
        composable(Destinations.S18_FOREHEAD_PHOTO) { PlaceholderScreen("S18: Forehead Photo") }
        composable(Destinations.S19_ANALYZING) { PlaceholderScreen("S19: Analyzing") }
        composable(Destinations.S20_RESULT) { PlaceholderScreen("S20: Result") }

        // S21 to S25 Log & Settings
        composable(Destinations.S21_LOG) { PlaceholderScreen("S21: Screening Log") }
        composable(Destinations.S22_SESSION_DETAIL) { PlaceholderScreen("S22: Session Detail") }
        composable(Destinations.S23_SETTINGS) { PlaceholderScreen("S23: Settings") }
        composable(Destinations.S24_ABOUT) { PlaceholderScreen("S24: About & Model Card") }
        composable(Destinations.S25_DELETE_DATA) { PlaceholderScreen("S25: Delete All Data") }

        // R01 to R09 Research Mode
        composable(Destinations.R01_RESEARCH_UNLOCK) { PlaceholderScreen("R01: Research Unlock") }
        composable(Destinations.R02_STUDY_HOME) { PlaceholderScreen("R02: Study Home") }
        composable(Destinations.R03_PARTICIPANT_CONSENT) { PlaceholderScreen("R03: Participant & Consent") }
        composable(Destinations.R04_CAPTURE_PROTOCOL) { PlaceholderScreen("R04: Research Capture Protocol") }
        composable(Destinations.R05_TSB_ENTRY) { PlaceholderScreen("R05: TSB Entry") }
        composable(Destinations.R06_SESSION_COMPLETE) { PlaceholderScreen("R06: Session Complete") }
        composable(Destinations.R07_EXPORT_SYNC) { PlaceholderScreen("R07: Export & Sync") }
        composable(Destinations.R08_ENROLLMENT) { PlaceholderScreen("R08: Study Enrollment") }
        composable(Destinations.R09_LEAVE_STUDY) { PlaceholderScreen("R09: Leave Study") }
    }
}

@Composable
private fun PlaceholderScreen(title: String) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Text(text = title)
    }
}
