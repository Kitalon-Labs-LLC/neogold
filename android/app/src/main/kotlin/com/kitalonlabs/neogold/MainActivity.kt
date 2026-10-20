package com.kitalonlabs.neogold

import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.kitalonlabs.neogold.core.data.datastore.DefaultNeoGoldDataStore
import com.kitalonlabs.neogold.core.data.datastore.NeoGoldDataStore
import com.kitalonlabs.neogold.core.designsystem.theme.NeoGoldTheme
import com.kitalonlabs.neogold.core.security.applock.AppLockManager
import com.kitalonlabs.neogold.core.security.crash.CrashReportManager
import com.kitalonlabs.neogold.core.security.privacy.SecureScreenHelper
import com.kitalonlabs.neogold.navigation.Destinations
import kotlinx.coroutines.launch

class MainActivity : FragmentActivity() {

    internal val appLockManager = AppLockManager()
    private lateinit var dataStore: NeoGoldDataStore

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        dataStore = DefaultNeoGoldDataStore(applicationContext)

        // Privacy controls: Prevent recents snapshot on API 33+ (TC-PRIV-02, doc 8 Section 6.1)
        SecureScreenHelper.setRecentsScreenshotEnabled(this, false)

        // Overlay protection on API 26..30 (doc 8 Section 6.1)
        SecureScreenHelper.setFilterTouchesWhenObscured(window, true)

        lifecycleScope.launch {
            dataStore.appLockEnabled.collect { enabled ->
                appLockManager.isAppLockEnabled = enabled
            }
        }

        setContent {
            NeoGoldTheme {
                val navController = rememberNavController()
                val isLocked by appLockManager.isLocked.collectAsState()
                var showCrashDialog by remember {
                    mutableStateOf<Boolean>(CrashReportManager.hasPendingReport(this@MainActivity))
                }

                DisposableEffect(navController) {
                    val listener = androidx.navigation.NavController.OnDestinationChangedListener { _, destination, _ ->
                        val secure = SecureScreenHelper.isDestinationSecure(destination.route)
                        SecureScreenHelper.setSecureScreen(window, secure)
                    }
                    navController.addOnDestinationChangedListener(listener)
                    onDispose {
                        navController.removeOnDestinationChangedListener(listener)
                    }
                }

                if (showCrashDialog) {
                    AlertDialog(
                        onDismissRequest = {
                            CrashReportManager.clearReport(this@MainActivity)
                            showCrashDialog = false
                        },
                        title = { Text("Share Crash Report?") },
                        text = {
                            Text("NeoGold encountered an unexpected error on its previous run. No personal data or exception messages are included in this report.")
                        },
                        confirmButton = {
                            Button(
                                onClick = {
                                    CrashReportManager.shareReport(this@MainActivity)
                                    CrashReportManager.clearReport(this@MainActivity)
                                    showCrashDialog = false
                                }
                            ) {
                                Text("Share")
                            }
                        },
                        dismissButton = {
                            TextButton(
                                onClick = {
                                    CrashReportManager.clearReport(this@MainActivity)
                                    showCrashDialog = false
                                }
                            ) {
                                Text("Dismiss")
                            }
                        }
                    )
                }

                if (isLocked) {
                    AppLockScreen(
                        onUnlock = {
                            appLockManager.authenticate(
                                activity = this@MainActivity,
                                onSuccess = {},
                                onError = { _, _ -> }
                            )
                        }
                    )
                } else {
                    Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                        NeoGoldNavHost(
                            navController = navController,
                            dataStore = dataStore,
                            modifier = Modifier.padding(innerPadding)
                        )
                    }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        appLockManager.onAppForegrounded()
        if (appLockManager.isLocked.value) {
            appLockManager.authenticate(
                activity = this,
                onSuccess = {},
                onError = { _, _ -> }
            )
        }
    }

    override fun onStop() {
        super.onStop()
        appLockManager.onAppBackgrounded()
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (appLockManager.onActivityResult(requestCode, resultCode)) {
            return
        }
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
    }
}

@Composable
private fun AppLockScreen(onUnlock: () -> Unit) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.padding(24.dp)
        ) {
            Text(
                text = "NeoGold is Locked",
                style = MaterialTheme.typography.headlineMedium
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "Device authentication is required to access patient screening and records.",
                style = MaterialTheme.typography.bodyMedium
            )
            Spacer(modifier = Modifier.height(24.dp))
            Button(onClick = onUnlock) {
                Text(text = "Unlock")
            }
        }
    }
}

@Composable
fun NeoGoldNavHost(
    navController: NavHostController,
    modifier: Modifier = Modifier,
    dataStore: NeoGoldDataStore = DefaultNeoGoldDataStore(androidx.compose.ui.platform.LocalContext.current),
    startDestination: String = Destinations.S01_LANGUAGE
) {
    val coroutineScope = androidx.compose.runtime.rememberCoroutineScope()
    var lastQualificationResult by remember { mutableStateOf<com.kitalonlabs.neogold.engine.camera.qualification.QualificationResult?>(null) }

    NavHost(
        navController = navController,
        startDestination = startDestination,
        modifier = modifier
    ) {
        // S01 to S08 Onboarding & Qualification
        composable(Destinations.S01_LANGUAGE) {
            val currentLang by dataStore.language.collectAsState(initial = "bn")
            com.kitalonlabs.neogold.feature.onboarding.S01LanguageScreen(
                initialLanguage = currentLang,
                onLanguageSelected = { lang ->
                    coroutineScope.launch {
                        dataStore.setLanguage(lang)
                    }
                },
                onContinue = {
                    navController.navigate(Destinations.S02_S04_SAFETY_NOTICE)
                }
            )
        }

        composable(Destinations.S02_S04_SAFETY_NOTICE) {
            com.kitalonlabs.neogold.feature.onboarding.S02S04SafetyNoticeScreen(
                onAcknowledge = {
                    coroutineScope.launch {
                        dataStore.setSafetyAckVersion(1)
                    }
                    navController.navigate(Destinations.S05_DEVICE_CHECK)
                }
            )
        }

        composable(Destinations.S05_DEVICE_CHECK) {
            com.kitalonlabs.neogold.feature.onboarding.S05DeviceCheckScreen(
                onContinue = {
                    navController.navigate(Destinations.S06_CARD_SCAN)
                }
            )
        }

        composable(Destinations.S06_CARD_SCAN) {
            com.kitalonlabs.neogold.feature.onboarding.S06CardScanScreen(
                onContinue = {
                    navController.navigate(Destinations.S07_QUALIFICATION)
                }
            )
        }

        composable(Destinations.S07_QUALIFICATION) {
            com.kitalonlabs.neogold.feature.onboarding.S07QualificationScreen(
                onQualificationComplete = { res ->
                    lastQualificationResult = res
                    navController.navigate(Destinations.S08_QUALIFICATION_RESULT)
                }
            )
        }

        composable(Destinations.S08_QUALIFICATION_RESULT) {
            val result = lastQualificationResult
            if (result != null) {
                com.kitalonlabs.neogold.feature.onboarding.S08QualificationResultScreen(
                    result = result,
                    onGetStarted = {
                        coroutineScope.launch {
                            dataStore.setActiveDeviceProfileId(result.profile.id)
                            dataStore.setOnboardingComplete(true)
                        }
                        navController.navigate(Destinations.S09_HOME) {
                            popUpTo(Destinations.S01_LANGUAGE) { inclusive = true }
                        }
                    }
                )
            } else {
                Box(modifier = Modifier.fillMaxSize())
            }
        }

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

        composable(Destinations.S23_SETTINGS) {
            val currentLang by dataStore.language.collectAsState(initial = "bn")
            val currentDigits by dataStore.measurementDigits.collectAsState(initial = "western")
            val retentionDays by dataStore.screeningRetentionDays.collectAsState(initial = 90)
            val keepLog by dataStore.keepScreeningLog.collectAsState(initial = true)
            val appLock by dataStore.appLockEnabled.collectAsState(initial = false)
            val crashReport by dataStore.crashReportingOptIn.collectAsState(initial = false)

            com.kitalonlabs.neogold.feature.settings.S23SettingsScreen(
                currentLanguage = currentLang,
                currentDigits = currentDigits,
                retentionDays = retentionDays,
                keepLog = keepLog,
                appLockEnabled = appLock,
                crashReportingOptIn = crashReport,
                onLanguageChanged = { lang ->
                    coroutineScope.launch { dataStore.setLanguage(lang) }
                },
                onDigitsChanged = { digits ->
                    coroutineScope.launch { dataStore.setMeasurementDigits(digits) }
                },
                onRetentionDaysChanged = { days ->
                    coroutineScope.launch { dataStore.setScreeningRetentionDays(days) }
                },
                onKeepLogChanged = { keep ->
                    coroutineScope.launch { dataStore.setKeepScreeningLog(keep) }
                },
                onAppLockChanged = { enabled ->
                    coroutineScope.launch { dataStore.setAppLockEnabled(enabled) }
                },
                onCrashReportingChanged = { optIn ->
                    coroutineScope.launch { dataStore.setCrashReportingOptIn(optIn) }
                },
                onNavigateToAbout = {
                    navController.navigate(Destinations.S24_ABOUT)
                },
                onNavigateToDeleteData = {
                    navController.navigate(Destinations.S25_DELETE_DATA)
                }
            )
        }

        composable(Destinations.S24_ABOUT) {
            com.kitalonlabs.neogold.feature.settings.S24AboutScreen(
                onBack = { navController.popBackStack() }
            )
        }

        composable(Destinations.S25_DELETE_DATA) {
            com.kitalonlabs.neogold.feature.settings.S25DeleteAllDataScreen(
                onConfirmDelete = {
                    coroutineScope.launch {
                        dataStore.clearAll()
                        navController.navigate(Destinations.S01_LANGUAGE) {
                            popUpTo(0) { inclusive = true }
                        }
                    }
                },
                onCancel = {
                    navController.popBackStack()
                }
            )
        }

        // R01 to R09 Research Mode
        composable(Destinations.R01_RESEARCH_UNLOCK) {
            var isLockedOut by remember { mutableStateOf(false) }
            var lockoutMinutes by remember { androidx.compose.runtime.mutableIntStateOf(0) }
            var unlockErrorMessage by remember { mutableStateOf<String?>(null) }

            com.kitalonlabs.neogold.feature.research.ui.R01ResearchUnlockScreen(
                scannedProfile = null,
                existingStudyName = null,
                isLockedOut = isLockedOut,
                lockoutMinutes = lockoutMinutes,
                errorMessage = unlockErrorMessage,
                onScanQrClicked = { /* QR scan integration */ },
                onUnlockWithPin = { _ ->
                    navController.navigate(Destinations.R02_STUDY_HOME)
                },
                onEnrollWithPin = { _, _ ->
                    navController.navigate(Destinations.R02_STUDY_HOME)
                }
            )
        }

        composable(Destinations.R02_STUDY_HOME) {
            com.kitalonlabs.neogold.feature.research.ui.R02StudyHomeScreen(
                studyName = "NeoGold Clinical Validation Study",
                studyCode = "DHK-01",
                siteCode = "DMCH",
                cardStatus = com.kitalonlabs.neogold.feature.research.data.CardTrustStatus.VERIFIED,
                pendingUploadsCount = 0,
                onNewParticipantSession = {
                    navController.navigate(Destinations.R03_PARTICIPANT_CONSENT)
                },
                onEnterTsbClicked = {
                    navController.navigate(Destinations.R05_TSB_ENTRY)
                },
                onLockSession = {
                    navController.navigate(Destinations.R01_RESEARCH_UNLOCK) {
                        popUpTo(Destinations.R02_STUDY_HOME) { inclusive = true }
                    }
                },
                onExportClicked = {
                    navController.navigate(Destinations.R07_EXPORT_SYNC)
                }
            )
        }

        composable(Destinations.R03_PARTICIPANT_CONSENT) {
            com.kitalonlabs.neogold.feature.research.ui.R03ParticipantConsentScreen(
                studyName = "NeoGold Study",
                onContinueToCapture = { code, _, _, _, _, _, _, _ ->
                    navController.navigate(Destinations.R04_CAPTURE_PROTOCOL)
                }
            )
        }

        composable(Destinations.R04_CAPTURE_PROTOCOL) {
            com.kitalonlabs.neogold.feature.research.ui.R04ResearchCaptureScreen(
                participantCode = "SKP-0142",
                onProtocolCompleted = { _ ->
                    navController.navigate(Destinations.R05_TSB_ENTRY)
                },
                onCancel = {
                    navController.popBackStack()
                }
            )
        }

        composable(Destinations.R05_TSB_ENTRY) {
            com.kitalonlabs.neogold.feature.research.ui.R05TsbEntryScreen(
                initialParticipantCode = "SKP-0142",
                isDeferredEntry = false,
                onSaveTsb = { _, _, _, _ ->
                    navController.navigate(Destinations.R06_SESSION_COMPLETE)
                },
                onSkipTsb = {
                    navController.navigate(Destinations.R06_SESSION_COMPLETE)
                }
            )
        }

        composable(Destinations.R06_SESSION_COMPLETE) {
            com.kitalonlabs.neogold.feature.research.ui.R06SessionCompleteScreen(
                participantCode = "SKP-0142",
                capturesCount = 4,
                cardStatus = com.kitalonlabs.neogold.feature.research.data.CardTrustStatus.VERIFIED,
                tsbEntered = true,
                tsbMgDl = 14.2,
                tsbMethod = "LAB",
                onDoneClicked = {
                    navController.navigate(Destinations.R02_STUDY_HOME) {
                        popUpTo(Destinations.R02_STUDY_HOME) { inclusive = false }
                    }
                }
            )
        }

        composable(Destinations.R07_EXPORT_SYNC) {
            var exportProgress by remember { mutableStateOf<com.kitalonlabs.neogold.feature.research.export.ExportProgress?>(null) }
            var isExporting by remember { mutableStateOf(false) }
            var exportCompleted by remember { mutableStateOf(false) }

            val exportLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
                contract = androidx.activity.result.contract.ActivityResultContracts.CreateDocument("application/zip")
            ) { uri ->
                if (uri != null) {
                    coroutineScope.launch {
                        isExporting = true
                        exportProgress = com.kitalonlabs.neogold.feature.research.export.ExportProgress(
                            completedFiles = 0,
                            totalFiles = 10,
                            bytesProcessed = 0L,
                            stage = com.kitalonlabs.neogold.feature.research.export.ExportStage.PREPARING,
                            message = "Preparing export package..."
                        )
                    }
                }
            }

            com.kitalonlabs.neogold.feature.research.ui.R07ExportScreen(
                studyCode = "DHK-01",
                displayName = "NeoGold Clinical Validation Study",
                hasExportKey = true,
                exportProgress = exportProgress,
                isExporting = isExporting,
                exportCompleted = exportCompleted,
                onChooseDestinationClicked = {
                    exportLauncher.launch("neogold-export-DHK-01.zip")
                },
                onDoneClicked = {
                    navController.popBackStack()
                }
            )
        }
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
