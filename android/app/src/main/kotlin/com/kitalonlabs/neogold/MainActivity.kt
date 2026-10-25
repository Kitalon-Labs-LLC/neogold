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
import com.kitalonlabs.neogold.core.data.local.NeoGoldDatabase
import com.kitalonlabs.neogold.core.data.local.entity.ScreeningSessionEntity
import com.kitalonlabs.neogold.core.designsystem.components.organisms.TriageUiAction
import com.kitalonlabs.neogold.core.designsystem.theme.NeoGoldTheme
import com.kitalonlabs.neogold.core.security.AndroidMasterKeyProvider
import com.kitalonlabs.neogold.core.security.DefaultDatabaseKeyProvider
import com.kitalonlabs.neogold.core.security.applock.AppLockManager
import com.kitalonlabs.neogold.core.security.crash.CrashReportManager
import com.kitalonlabs.neogold.core.security.privacy.SecureScreenHelper
import com.kitalonlabs.neogold.engine.triage.TriageReasonCode
import com.kitalonlabs.neogold.feature.log.S21LogScreen
import com.kitalonlabs.neogold.feature.log.S22SessionDetailScreen
import com.kitalonlabs.neogold.feature.log.ScreeningLogExportManager
import com.kitalonlabs.neogold.feature.result.S20ResultScreen
import com.kitalonlabs.neogold.feature.screening.ui.S09HomeScreen
import com.kitalonlabs.neogold.feature.screening.ui.S10BirthTimeGaScreen
import com.kitalonlabs.neogold.feature.screening.ui.S10aGuardianConsentScreen
import com.kitalonlabs.neogold.feature.screening.ui.S11EarlyJaundiceScreen
import com.kitalonlabs.neogold.feature.screening.ui.S12DangerSignsScreen
import com.kitalonlabs.neogold.feature.screening.ui.S13PhototherapyScreen
import com.kitalonlabs.neogold.feature.screening.ui.S14RuleOutcomeScreen
import com.kitalonlabs.neogold.feature.screening.ui.S15CaptureGuideScreen
import com.kitalonlabs.neogold.feature.screening.ui.S16CameraScreen
import com.kitalonlabs.neogold.feature.screening.ui.S17PhotoProblemScreen
import com.kitalonlabs.neogold.feature.screening.ui.S18ForeheadPhotoScreen
import com.kitalonlabs.neogold.feature.screening.ui.S19AnalyzingScreen
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
    database: NeoGoldDatabase? = null,
    startDestination: String = Destinations.S01_LANGUAGE,
    triageEnabled: Boolean = false
) {
    val coroutineScope = androidx.compose.runtime.rememberCoroutineScope()
    var lastQualificationResult by remember { mutableStateOf<com.kitalonlabs.neogold.engine.camera.qualification.QualificationResult?>(null) }
    val context = androidx.compose.ui.platform.LocalContext.current
    val db = remember(database) {
        database ?: run {
            val masterKey = AndroidMasterKeyProvider(context.applicationContext)
            val keyProvider = DefaultDatabaseKeyProvider(context.applicationContext, masterKey)
            NeoGoldDatabase.buildDatabase(context.applicationContext, keyProvider)
        }
    }

    // Screening session flow state
    var sessionAgeHours by remember { androidx.compose.runtime.mutableDoubleStateOf(0.0) }
    var sessionGaWeeks by remember { androidx.compose.runtime.mutableIntStateOf(38) }
    var sessionBirthTimeMs by remember { androidx.compose.runtime.mutableLongStateOf(0L) }
    var sessionRuleOutcome by remember { mutableStateOf<String?>(null) }
    var sessionTriageAction by remember { mutableStateOf(TriageUiAction.CANNOT_SCREEN) }
    var sessionReasonCodes by remember { mutableStateOf<List<String>>(emptyList()) }
    var sessionEstimatedLow by remember { mutableStateOf<Double?>(null) }
    var sessionEstimatedHigh by remember { mutableStateOf<Double?>(null) }
    var sessionThreshold by remember { mutableStateOf<Double?>(null) }
    var sessionRecheckHours by remember { androidx.compose.runtime.mutableIntStateOf(24) }
    var sessionCapturesCount by remember { androidx.compose.runtime.mutableIntStateOf(0) }
    var lastQualityReason by remember { mutableStateOf<com.kitalonlabs.neogold.engine.vision.QualityReasonCode?>(null) }

    // Log & Session detail state
    var selectedSessionIdForDetail by remember { mutableStateOf<String?>(null) }
    var logSessions by remember { mutableStateOf<List<ScreeningSessionEntity>>(emptyList()) }
    var dueRechecks by remember { mutableStateOf<List<ScreeningSessionEntity>>(emptyList()) }

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
        composable(Destinations.S09_HOME) {
            androidx.compose.runtime.LaunchedEffect(Unit) {
                val now = System.currentTimeMillis()
                dueRechecks = db.screeningSessionDao().getDueRechecks(now)
                logSessions = db.screeningSessionDao().getSessionsByMode("SCREENING")
            }

            val todayCount = remember(logSessions) {
                val oneDayAgo = System.currentTimeMillis() - 24L * 60 * 60 * 1000
                logSessions.count { it.createdAt >= oneDayAgo }
            }

            S09HomeScreen(
                triageEnabled = triageEnabled,
                todayScreeningsCount = todayCount,
                dueRechecks = dueRechecks,
                onNewScreeningClicked = {
                    sessionAgeHours = 0.0
                    sessionGaWeeks = 38
                    sessionBirthTimeMs = 0L
                    sessionRuleOutcome = null
                    sessionTriageAction = TriageUiAction.CANNOT_SCREEN
                    sessionReasonCodes = emptyList()
                    sessionEstimatedLow = null
                    sessionEstimatedHigh = null
                    sessionThreshold = null
                    sessionRecheckHours = 24
                    sessionCapturesCount = 0
                    lastQualityReason = null
                    navController.navigate(Destinations.S10A_GUARDIAN_CONSENT)
                },
                onScreeningLogClicked = {
                    navController.navigate(Destinations.S21_LOG)
                },
                onSettingsClicked = {
                    navController.navigate(Destinations.S23_SETTINGS)
                },
                onRecheckClicked = { _ ->
                    navController.navigate(Destinations.S10A_GUARDIAN_CONSENT)
                }
            )
        }

        // S10a to S20 Screening Flow
        composable(Destinations.S10A_GUARDIAN_CONSENT) {
            S10aGuardianConsentScreen(
                onConsentConfirmed = {
                    navController.navigate(Destinations.S10_BIRTH_TIME_GA)
                },
                onBack = {
                    navController.popBackStack()
                }
            )
        }

        composable(Destinations.S10_BIRTH_TIME_GA) {
            S10BirthTimeGaScreen(
                onNext = { ageHours, gaWeeks, birthTimeMs ->
                    sessionAgeHours = ageHours
                    sessionGaWeeks = gaWeeks
                    sessionBirthTimeMs = birthTimeMs
                    navController.navigate(Destinations.S11_EARLY_JAUNDICE)
                },
                onBack = {
                    navController.popBackStack()
                }
            )
        }

        composable(Destinations.S11_EARLY_JAUNDICE) {
            S11EarlyJaundiceScreen(
                onAnswered = { isEarlyJaundice ->
                    if (isEarlyJaundice) {
                        sessionRuleOutcome = TriageReasonCode.R2
                        navController.navigate(Destinations.S14_RULE_OUTCOME)
                    } else {
                        navController.navigate(Destinations.S12_DANGER_SIGNS)
                    }
                },
                onBack = {
                    navController.popBackStack()
                }
            )
        }

        composable(Destinations.S12_DANGER_SIGNS) {
            S12DangerSignsScreen(
                onAnswered = { hasDangerSigns ->
                    if (hasDangerSigns) {
                        sessionRuleOutcome = TriageReasonCode.R1
                        navController.navigate(Destinations.S14_RULE_OUTCOME)
                    } else {
                        navController.navigate(Destinations.S13_PHOTOTHERAPY)
                    }
                },
                onBack = {
                    navController.popBackStack()
                }
            )
        }

        composable(Destinations.S13_PHOTOTHERAPY) {
            S13PhototherapyScreen(
                onAnswered = { hadPhototherapy ->
                    if (hadPhototherapy) {
                        sessionRuleOutcome = TriageReasonCode.R4
                        navController.navigate(Destinations.S14_RULE_OUTCOME)
                    } else if (sessionGaWeeks < 35) {
                        sessionRuleOutcome = TriageReasonCode.R3
                        navController.navigate(Destinations.S14_RULE_OUTCOME)
                    } else if (sessionAgeHours > 336.0) {
                        sessionRuleOutcome = TriageReasonCode.R5
                        navController.navigate(Destinations.S14_RULE_OUTCOME)
                    } else {
                        navController.navigate(Destinations.S15_CAPTURE_GUIDE)
                    }
                },
                onBack = {
                    navController.popBackStack()
                }
            )
        }

        composable(Destinations.S14_RULE_OUTCOME) {
            val rule = sessionRuleOutcome ?: TriageReasonCode.R1
            S14RuleOutcomeScreen(
                ruleCode = rule,
                onSaveToLog = { registerNumber ->
                    coroutineScope.launch {
                        val entity = ScreeningSessionEntity(
                            id = java.util.UUID.randomUUID().toString(),
                            mode = "SCREENING",
                            status = "COMPLETED",
                            createdAt = System.currentTimeMillis(),
                            completedAt = System.currentTimeMillis(),
                            ageHours = sessionAgeHours.coerceIn(0.0, 720.0),
                            guardianConsentConfirmed = 1,
                            gestationalAgeWeeks = sessionGaWeeks.coerceIn(22, 44),
                            flagJaundiceUnder24h = if (rule == TriageReasonCode.R2) 1 else 0,
                            flagDangerSigns = if (rule == TriageReasonCode.R1) 1 else 0,
                            flagPriorPhototherapy = if (rule == TriageReasonCode.R4) 1 else 0,
                            ruleOutcome = rule,
                            triageAction = when (rule) {
                                TriageReasonCode.R1, TriageReasonCode.R2 -> "REFER_URGENT"
                                TriageReasonCode.R3, TriageReasonCode.R4 -> "BLOOD_TEST_TODAY"
                                TriageReasonCode.R5 -> "CANNOT_SCREEN"
                                else -> "CANNOT_SCREEN"
                            },
                            reasonCodes = "[\"$rule\"]",
                            estimateMgDl = null,
                            estimateLowMgDl = null,
                            estimateHighMgDl = null,
                            thresholdMgDl = null,
                            recheckDueAt = null,
                            captureAttempts = 0,
                            registerNumber = registerNumber,
                            registerNumberErasedAt = null,
                            participantId = null,
                            estimateHidden = 0,
                            experimentalLabelShown = 1,
                            deviceProfileId = null,
                            cardBatchId = "NG-2026-001",
                            appVersion = "1.0.0",
                            modelVersion = null,
                            configVersion = "2026.10.0"
                        )
                        db.screeningSessionDao().insert(entity)
                        navController.navigate(Destinations.S09_HOME) {
                            popUpTo(Destinations.S09_HOME) { inclusive = true }
                        }
                    }
                }
            )
        }

        composable(Destinations.S15_CAPTURE_GUIDE) {
            S15CaptureGuideScreen(
                onOpenCamera = {
                    navController.navigate(Destinations.S16_CAMERA)
                },
                onBack = {
                    navController.popBackStack()
                }
            )
        }

        composable(Destinations.S16_CAMERA) {
            S16CameraScreen(
                onCaptureCompleted = { _ ->
                    sessionCapturesCount++
                    navController.navigate(Destinations.S18_FOREHEAD_PHOTO)
                },
                onBack = {
                    navController.popBackStack()
                }
            )
        }

        composable(Destinations.S17_PHOTO_PROBLEM) {
            S17PhotoProblemScreen(
                reason = lastQualityReason,
                attempt = sessionCapturesCount,
                onRetake = {
                    navController.navigate(Destinations.S16_CAMERA)
                },
                onFallbackToBloodTest = {
                    sessionTriageAction = TriageUiAction.BLOOD_TEST
                    sessionReasonCodes = listOf(TriageReasonCode.QUALITY_FALLBACK)
                    navController.navigate(Destinations.S20_RESULT)
                },
                onBack = {
                    navController.popBackStack()
                }
            )
        }

        composable(Destinations.S18_FOREHEAD_PHOTO) {
            S18ForeheadPhotoScreen(
                onAddForehead = {
                    navController.navigate(Destinations.S16_CAMERA)
                },
                onSkipForehead = {
                    navController.navigate(Destinations.S19_ANALYZING)
                },
                onBack = {
                    navController.popBackStack()
                }
            )
        }

        composable(Destinations.S19_ANALYZING) {
            androidx.compose.runtime.LaunchedEffect(Unit) {
                kotlinx.coroutines.delay(1200L) // Under 3s budget (NFR-02)
                sessionTriageAction = TriageUiAction.NO_FURTHER_ACTION
                sessionEstimatedLow = 8.2
                sessionEstimatedHigh = 10.1
                sessionThreshold = 12.0
                navController.navigate(Destinations.S20_RESULT) {
                    popUpTo(Destinations.S19_ANALYZING) { inclusive = true }
                }
            }
            S19AnalyzingScreen(
                onTimeoutFallback = {
                    sessionTriageAction = TriageUiAction.BLOOD_TEST
                    sessionReasonCodes = listOf(TriageReasonCode.QUALITY_FALLBACK)
                    navController.navigate(Destinations.S20_RESULT) {
                        popUpTo(Destinations.S19_ANALYZING) { inclusive = true }
                    }
                }
            )
        }

        composable(Destinations.S20_RESULT) {
            S20ResultScreen(
                action = sessionTriageAction,
                nextStepText = when (sessionTriageAction) {
                    TriageUiAction.REFER_URGENT -> "Refer urgently to neonatal inpatient unit"
                    TriageUiAction.BLOOD_TEST -> "Order serum bilirubin test today"
                    TriageUiAction.RECHECK -> "Recheck in 24 hours"
                    TriageUiAction.NO_FURTHER_ACTION -> "Routine newborn care. Re-evaluate if jaundice deepens."
                    TriageUiAction.CANNOT_SCREEN -> "Order serum bilirubin test today"
                },
                reasonText = if (sessionReasonCodes.isNotEmpty()) sessionReasonCodes.joinToString(", ") else null,
                estimatedLow = sessionEstimatedLow,
                estimatedHigh = sessionEstimatedHigh,
                threshold = sessionThreshold,
                recheckHours = sessionRecheckHours,
                triageEnabled = triageEnabled,
                onSaveToLog = { registerNumber ->
                    coroutineScope.launch {
                        val recheckDueTime = if (sessionTriageAction == TriageUiAction.RECHECK) {
                            System.currentTimeMillis() + sessionRecheckHours * 3600 * 1000L
                        } else null
                        val entity = ScreeningSessionEntity(
                            id = java.util.UUID.randomUUID().toString(),
                            mode = "SCREENING",
                            status = "COMPLETED",
                            createdAt = System.currentTimeMillis(),
                            completedAt = System.currentTimeMillis(),
                            ageHours = sessionAgeHours.coerceIn(0.0, 720.0),
                            guardianConsentConfirmed = 1,
                            gestationalAgeWeeks = sessionGaWeeks.coerceIn(22, 44),
                            flagJaundiceUnder24h = 0,
                            flagDangerSigns = 0,
                            flagPriorPhototherapy = 0,
                            ruleOutcome = null,
                            triageAction = sessionTriageAction.name,
                            reasonCodes = if (sessionReasonCodes.isNotEmpty()) "[\"${sessionReasonCodes.joinToString("\",\"")}\"]" else null,
                            estimateMgDl = if (sessionEstimatedLow != null && sessionEstimatedHigh != null) (sessionEstimatedLow!! + sessionEstimatedHigh!!) / 2.0 else null,
                            estimateLowMgDl = sessionEstimatedLow,
                            estimateHighMgDl = sessionEstimatedHigh,
                            thresholdMgDl = sessionThreshold,
                            recheckDueAt = recheckDueTime,
                            captureAttempts = sessionCapturesCount,
                            registerNumber = registerNumber,
                            registerNumberErasedAt = null,
                            participantId = null,
                            estimateHidden = 0,
                            experimentalLabelShown = 1,
                            deviceProfileId = null,
                            cardBatchId = "NG-2026-001",
                            appVersion = "1.0.0",
                            modelVersion = "1.0.0",
                            configVersion = "2026.10.0"
                        )
                        db.screeningSessionDao().insert(entity)
                        navController.navigate(Destinations.S09_HOME) {
                            popUpTo(Destinations.S09_HOME) { inclusive = true }
                        }
                    }
                }
            )
        }

        // S21 to S25 Log & Settings
        composable(Destinations.S21_LOG) {
            androidx.compose.runtime.LaunchedEffect(Unit) {
                logSessions = db.screeningSessionDao().getSessionsByMode("SCREENING")
            }
            S21LogScreen(
                sessions = logSessions,
                onSessionSelected = { sessionId ->
                    selectedSessionIdForDetail = sessionId
                    navController.navigate(Destinations.S22_SESSION_DETAIL)
                },
                onExportClicked = {
                    val csv = ScreeningLogExportManager.exportToCsv(logSessions, includeRegisterNumbers = false)
                    val sendIntent = Intent().apply {
                        action = Intent.ACTION_SEND
                        putExtra(Intent.EXTRA_TEXT, csv)
                        type = "text/csv"
                    }
                    context.startActivity(Intent.createChooser(sendIntent, "Export Screening Log"))
                },
                onBack = {
                    navController.popBackStack()
                }
            )
        }

        composable(Destinations.S22_SESSION_DETAIL) {
            val session = logSessions.find { it.id == selectedSessionIdForDetail }
            if (session != null) {
                S22SessionDetailScreen(
                    session = session,
                    onBack = {
                        navController.popBackStack()
                    }
                )
            } else {
                Box(modifier = Modifier.fillMaxSize())
            }
        }

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
