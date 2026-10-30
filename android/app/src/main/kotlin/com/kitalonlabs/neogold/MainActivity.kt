@file:Suppress(
    "MaxLineLength",
    "MagicNumber"
) // hardcoded UI text (F1) and placeholder session values, both replaced by the wiring in B1 and E1

package com.kitalonlabs.neogold

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
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
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.kitalonlabs.neogold.core.data.datastore.NeoGoldDataStore
import com.kitalonlabs.neogold.core.data.local.NeoGoldDatabase
import com.kitalonlabs.neogold.core.data.local.entity.ScreeningSessionEntity
import com.kitalonlabs.neogold.core.designsystem.components.organisms.TriageUiAction
import com.kitalonlabs.neogold.core.designsystem.theme.NeoGoldTheme
import com.kitalonlabs.neogold.core.security.applock.AppLockManager
import com.kitalonlabs.neogold.core.security.crash.CrashReportManager
import com.kitalonlabs.neogold.core.security.privacy.SecureScreenHelper
import com.kitalonlabs.neogold.engine.camera.device.DeviceChecker
import com.kitalonlabs.neogold.engine.camera.ui.CameraPermissionState
import com.kitalonlabs.neogold.engine.camera.ui.LocalCameraPermission
import com.kitalonlabs.neogold.engine.triage.TriageReasonCode
import com.kitalonlabs.neogold.feature.log.S21LogScreen
import com.kitalonlabs.neogold.feature.log.S22SessionDetailScreen
import com.kitalonlabs.neogold.feature.log.ScreeningLogExportManager
import com.kitalonlabs.neogold.feature.onboarding.domain.CardScanState
import com.kitalonlabs.neogold.feature.onboarding.domain.QualificationState
import com.kitalonlabs.neogold.feature.research.data.CardTrustStatus
import com.kitalonlabs.neogold.feature.research.export.ExportProgress
import com.kitalonlabs.neogold.feature.research.export.ResearchExportManager
import com.kitalonlabs.neogold.feature.research.export.exportFileName
import com.kitalonlabs.neogold.feature.research.ui.ResearchViewModel
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
import com.kitalonlabs.neogold.integrity.VerifiedAssets
import com.kitalonlabs.neogold.navigation.Destinations
import com.kitalonlabs.neogold.onboarding.FirstRunState
import com.kitalonlabs.neogold.onboarding.OnboardingServices
import com.kitalonlabs.neogold.storage.StorageState
import kotlinx.coroutines.launch

class MainActivity : FragmentActivity() {

    internal val appLockManager = AppLockManager()
    private lateinit var dataStore: NeoGoldDataStore

    private val container: NeoGoldContainer
        get() = (application as NeoGoldApplication).container

    /** Whether the camera may be used. The screens that open it read this through [LocalCameraPermission]. */
    private var cameraGranted by mutableStateOf(false)

    private fun cameraPermissionState() = CameraPermissionState(
        granted = cameraGranted,
        request = { ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), CAMERA_REQUEST_CODE) },
        openSettings = {
            startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null))
            )
        }
    )

    private fun observeAppLockSetting() {
        lifecycleScope.launch {
            dataStore.appLockEnabled.collect { enabled ->
                appLockManager.isAppLockEnabled = enabled
            }
        }
    }

    private fun hasCameraPermission() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        dataStore = container.dataStore
        cameraGranted = hasCameraPermission()

        // Privacy controls: Prevent recents snapshot on API 33+ (TC-PRIV-02, doc 8 Section 6.1)
        SecureScreenHelper.setRecentsScreenshotEnabled(this, false)

        // Overlay protection on API 26..30 (doc 8 Section 6.1)
        SecureScreenHelper.setFilterTouchesWhenObscured(window, true)

        observeAppLockSetting()

        setContent {
            val cameraPermission = remember(cameraGranted) { cameraPermissionState() }
            CompositionLocalProvider(LocalCameraPermission provides cameraPermission) {
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
                        CrashReportDialog(
                            onShare = { CrashReportManager.shareReport(this@MainActivity) },
                            onFinished = {
                                CrashReportManager.clearReport(this@MainActivity)
                                showCrashDialog = false
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
                        Scaffold(
                            modifier = Modifier.fillMaxSize().then(testIds(container.isDebuggable))
                        ) { innerPadding ->
                            val storageState by container.storage.state.collectAsState()
                            val deleteState by container.deleteState.collectAsState()
                            val content = Modifier.padding(innerPadding)

                            when {
                                deleteState is DeleteState.Deleting -> DeletingAllDataScreen(content)
                                deleteState is DeleteState.Incomplete -> DeleteIncompleteScreen(
                                    incomplete = (deleteState as DeleteState.Incomplete).items,
                                    onRetry = container::startDeleteAll,
                                    onClose = container::restartAfterIncompleteDelete,
                                    modifier = content
                                )
                                else -> when (val state = storageState) {
                                    StorageState.Opening -> StorageOpeningScreen(content)
                                    StorageState.Unreadable -> StorageUnreadableScreen(
                                        onTryAgain = { container.applicationScope.launch { container.storage.open() } },
                                        onReset = { container.applicationScope.launch { container.storage.resetAndOpen() } },
                                        modifier = content
                                    )
                                    is StorageState.Ready -> OpenWhenOnboardingIsKnown(
                                        database = state.database,
                                        modifier = content
                                    ) { onboarding, firstRun ->
                                        NeoGoldNavHost(
                                            navController = navController,
                                            dataStore = dataStore,
                                            database = state.database,
                                            assets = container.assets,
                                            appVersion = BuildConfig.VERSION_NAME,
                                            configAllowsScreening = container.screeningAvailable(),
                                            unsentResearchItems = container::unsentResearchItems,
                                            onDeleteAllData = container::startDeleteAll,
                                            onboarding = onboarding,
                                            modifier = content,
                                            firstRun = firstRun,
                                            research = container.researchViewModel(state.database),
                                            researchCaptureSetup = { container.researchCaptureSetup(state.database) },
                                            researchCardStatus = { container.researchCardStatus(state.database) },
                                            researchExport = container.researchExportManager(state.database)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        container.researchSession.onAppForegrounded() // the research session asks for the PIN again after 5 minutes away
        cameraGranted = hasCameraPermission() // the user may have changed it in the system settings
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
        container.researchSession.onAppBackgrounded()
        appLockManager.onAppBackgrounded()
    }

    // The system permission dialog is asked for with a small request code and answered here, not through the
    // activity-result registry: the registry's request codes do not fit what FragmentActivity accepts for permissions.
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == CAMERA_REQUEST_CODE) cameraGranted = hasCameraPermission()
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (appLockManager.onActivityResult(requestCode, resultCode)) {
            return
        }
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
    }

    private companion object {
        const val CAMERA_REQUEST_CODE = 41
    }
}

/**
 * Builds the first-run services for [database] and finds where the app opens, then shows [content]. While that is
 * being read the "opening" screen is shown, so the navigation graph never starts on a guess.
 */
@Composable
private fun OpenWhenOnboardingIsKnown(
    database: NeoGoldDatabase,
    modifier: Modifier,
    content: @Composable (OnboardingServices, FirstRunState) -> Unit
) {
    val appContext = androidx.compose.ui.platform.LocalContext.current.applicationContext
    val services = remember(database) { (appContext as NeoGoldApplication).container.onboardingServices(database) }
    val firstRun by androidx.compose.runtime.produceState<FirstRunState?>(null, services) {
        value = services.firstRunState()
    }
    val state = firstRun
    if (state == null) StorageOpeningScreen(modifier) else content(services, state)
}

/**
 * In a debuggable build, makes the screens' test tags visible to UI automation (Maestro's `id:`) as resource ids. A
 * release build does not expose them.
 */
@OptIn(ExperimentalComposeUiApi::class)
private fun testIds(debuggable: Boolean): Modifier =
    if (debuggable) Modifier.semantics { testTagsAsResourceId = true } else Modifier

/** Asks whether to share the report of the previous crash (no personal data or exception messages are in it). */
@Composable
private fun CrashReportDialog(onShare: () -> Unit, onFinished: () -> Unit) {
    AlertDialog(
        onDismissRequest = onFinished,
        title = { Text("Share Crash Report?") },
        text = {
            Text(
                "NeoGold encountered an unexpected error on its previous run. No personal data or exception messages are included in this report."
            )
        },
        confirmButton = {
            Button(
                onClick = {
                    onShare()
                    onFinished()
                }
            ) {
                Text("Share")
            }
        },
        dismissButton = { TextButton(onClick = onFinished) { Text("Dismiss") } }
    )
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

/**
 * The app's navigation graph. There are no defaults for what must come from the container: the database, the
 * integrity result and the triage switch are passed in, so a caller cannot forget them and get a fail-open screen.
 *
 * @param assets the startup check of the bundled files; screening and research screens show a block screen unless it
 * is valid (BUG-2026-007). Saved records take the config and model versions from it.
 * @param configAllowsScreening whether screening is offered today: the signed config says so, it is not past `validUntil`,
 * and the app really computes a result (`NeoGoldContainer.screeningAvailable`). The screening screens are not
 * reachable otherwise.
 * @param unsentResearchItems asks how many research items the study has not acknowledged, for S25's warning
 * @param research the research session over this database; null when the bundle did not verify, and then no research
 * screen opens
 * @param researchCaptureSetup the camera step and the signed card bytes for one research photo session (R04)
 */
@Composable
@Suppress(
    "LongParameterList"
) // the container's pieces, passed in and not defaulted; shrinks with the per-screen ViewModels (E1)
fun NeoGoldNavHost(
    navController: NavHostController,
    dataStore: NeoGoldDataStore,
    database: NeoGoldDatabase,
    assets: VerifiedAssets,
    appVersion: String,
    configAllowsScreening: Boolean,
    unsentResearchItems: suspend () -> Int,
    onDeleteAllData: () -> Unit,
    onboarding: OnboardingServices,
    firstRun: FirstRunState,
    research: ResearchViewModel?,
    researchCaptureSetup: suspend () -> NeoGoldContainer.ResearchCaptureSetup,
    researchCardStatus: suspend () -> CardTrustStatus,
    researchExport: ResearchExportManager,
    modifier: Modifier = Modifier
) {
    val coroutineScope = androidx.compose.runtime.rememberCoroutineScope()
    var lastQualificationResult by remember {
        mutableStateOf<com.kitalonlabs.neogold.engine.camera.qualification.QualificationResult?>(null)
    }
    // Screening needs the config's switch AND a phone whose camera was measured to qualify and is not due again
    // (H5, TC-DEV-05). A phone that is research-only, or has no profile, never gets it.
    var qualifiedForScreening by remember { mutableStateOf(firstRun.screeningEligible) }
    val triageEnabled = configAllowsScreening && qualifiedForScreening
    // A research session in progress: the signed bytes of the card the photos were taken with, and the blood test
    // value entered with them (R04 to R06). Neither is kept after R06.
    var researchCardBytes by remember { mutableStateOf<ByteArray?>(null) }
    var researchTsbEntered by remember { mutableStateOf<Pair<Double, String>?>(null) }
    val researchUnlocked by (research?.isUnlocked ?: remember { kotlinx.coroutines.flow.MutableStateFlow(false) })
        .collectAsState()
    // The research session locks itself (5 minutes away, screen off, Lock): whichever research screen is open, the
    // next one is R01 and nothing of the session in progress is kept.
    androidx.compose.runtime.LaunchedEffect(researchUnlocked) {
        val route = navController.currentDestination?.route
        val insideSession = route != null && route.startsWith("r0") && route != Destinations.R01_RESEARCH_UNLOCK
        if (!researchUnlocked && insideSession) {
            research?.clearSessionState()
            researchCardBytes = null
            researchTsbEntered = null
            navController.navigate(Destinations.R01_RESEARCH_UNLOCK) {
                popUpTo(Destinations.R02_STUDY_HOME) { inclusive = true }
            }
        }
    }
    // The card S06 verified, which S07 qualifies the camera against.
    var scannedBatchId by remember { mutableStateOf<String?>(null) }
    val context = androidx.compose.ui.platform.LocalContext.current
    val db = database

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

    val integrity = assets.integrity
    val bundleUsable = integrity.isScreeningAllowed

    NavHost(
        navController = navController,
        startDestination = firstRun.startDestination,
        modifier = modifier
    ) {
        // Research runs only on files that passed the startup check (BUG-2026-007, TC-SEC-09).
        fun NavGraphBuilder.researchComposable(
            route: String,
            content: @Composable androidx.compose.animation.AnimatedContentScope.(NavBackStackEntry) -> Unit
        ) = composable(route) { entry ->
            if (bundleUsable) content(entry) else IntegrityBlockedScreen(integrity)
        }

        // Screening needs the same, and a switch that is on today (TC-TRI-26): with triage off no screening screen
        // is reachable, whichever button or saved recheck leads to it, and Home is where the user lands.
        fun NavGraphBuilder.screeningComposable(
            route: String,
            content: @Composable androidx.compose.animation.AnimatedContentScope.(NavBackStackEntry) -> Unit
        ) = composable(route) { entry ->
            when {
                !bundleUsable -> IntegrityBlockedScreen(integrity)
                !triageEnabled -> {
                    androidx.compose.runtime.LaunchedEffect(Unit) {
                        navController.navigate(Destinations.S09_HOME) { popUpTo(0) }
                    }
                    Box(modifier = Modifier.fillMaxSize())
                }
                else -> content(entry)
            }
        }

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

        // The card is checked against the signed trust list and the camera is qualified on the card it names, so both
        // need a bundle that verified (BUG-2026-007).
        researchComposable(Destinations.S06_CARD_SCAN) {
            val scope = androidx.compose.runtime.rememberCoroutineScope()
            val state = remember(onboarding) { onboarding.cardScan?.let { CardScanState(it, scope) } }
            if (state != null) {
                com.kitalonlabs.neogold.feature.onboarding.S06CardScanScreen(
                    state = state,
                    onContinue = { batchId ->
                        scannedBatchId = batchId
                        navController.navigate(Destinations.S07_QUALIFICATION)
                    },
                    requalification = firstRun.requalification
                )
            } else {
                IntegrityBlockedScreen(integrity)
            }
        }

        researchComposable(Destinations.S07_QUALIFICATION) {
            val batchId = scannedBatchId
            if (batchId == null) {
                // The flow was interrupted (the app was closed and reopened here): start again from the card.
                androidx.compose.runtime.LaunchedEffect(Unit) {
                    navController.navigate(Destinations.S06_CARD_SCAN) {
                        popUpTo(Destinations.S06_CARD_SCAN) { inclusive = true }
                    }
                }
                Box(modifier = Modifier.fillMaxSize())
            } else {
                val scope = androidx.compose.runtime.rememberCoroutineScope()
                val state = remember(batchId) {
                    QualificationState(
                        service = onboarding.qualification,
                        capabilities = DeviceChecker.checkDevice(context).capabilities,
                        cardBatchId = batchId,
                        scope = scope
                    )
                }
                com.kitalonlabs.neogold.feature.onboarding.S07QualificationScreen(
                    state = state,
                    onQualificationComplete = { res ->
                        lastQualificationResult = res
                        navController.navigate(Destinations.S08_QUALIFICATION_RESULT)
                    }
                )
            }
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
                        qualifiedForScreening = result.isQualified
                        navController.navigate(Destinations.S09_HOME) { popUpTo(0) }
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
        screeningComposable(Destinations.S10A_GUARDIAN_CONSENT) {
            S10aGuardianConsentScreen(
                onConsentConfirmed = {
                    navController.navigate(Destinations.S10_BIRTH_TIME_GA)
                },
                onBack = {
                    navController.popBackStack()
                }
            )
        }

        screeningComposable(Destinations.S10_BIRTH_TIME_GA) {
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

        screeningComposable(Destinations.S11_EARLY_JAUNDICE) {
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

        screeningComposable(Destinations.S12_DANGER_SIGNS) {
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

        screeningComposable(Destinations.S13_PHOTOTHERAPY) {
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

        screeningComposable(Destinations.S14_RULE_OUTCOME) {
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
                            cardBatchId = null, // the batch a card scan stored; scanning is wired in C1 (BUG-2026-060)
                            appVersion = appVersion,
                            modelVersion = null,
                            configVersion = assets.triageConfig?.configVersion ?: "unknown"
                        )
                        db.screeningSessionDao().insert(entity)
                        navController.navigate(Destinations.S09_HOME) {
                            popUpTo(Destinations.S09_HOME) { inclusive = true }
                        }
                    }
                }
            )
        }

        screeningComposable(Destinations.S15_CAPTURE_GUIDE) {
            S15CaptureGuideScreen(
                onOpenCamera = {
                    navController.navigate(Destinations.S16_CAMERA)
                },
                onBack = {
                    navController.popBackStack()
                }
            )
        }

        screeningComposable(Destinations.S16_CAMERA) {
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

        screeningComposable(Destinations.S17_PHOTO_PROBLEM) {
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

        screeningComposable(Destinations.S18_FOREHEAD_PHOTO) {
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

        screeningComposable(Destinations.S19_ANALYZING) {
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

        screeningComposable(Destinations.S20_RESULT) {
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
                appVersion = appVersion,
                modelVersion = assets.modelVersion ?: "unknown",
                configVersion = assets.triageConfig?.configVersion ?: "unknown",
                onSaveToLog = { registerNumber ->
                    coroutineScope.launch {
                        val recheckDueTime = if (sessionTriageAction == TriageUiAction.RECHECK) {
                            System.currentTimeMillis() + sessionRecheckHours * 3600 * 1000L
                        } else {
                            null
                        }
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
                            triageAction = if (triageEnabled) sessionTriageAction.name else TriageUiAction.CANNOT_SCREEN.name,
                            reasonCodes = if (sessionReasonCodes.isNotEmpty()) {
                                "[\"${sessionReasonCodes.joinToString(
                                    "\",\""
                                )}\"]"
                            } else {
                                null
                            },
                            estimateMgDl = if (triageEnabled && sessionEstimatedLow != null && sessionEstimatedHigh != null) {
                                (sessionEstimatedLow!! + sessionEstimatedHigh!!) / 2.0
                            } else {
                                null
                            },
                            estimateLowMgDl = sessionEstimatedLow.takeIf { triageEnabled },
                            estimateHighMgDl = sessionEstimatedHigh.takeIf { triageEnabled },
                            thresholdMgDl = sessionThreshold.takeIf { triageEnabled },
                            recheckDueAt = recheckDueTime,
                            captureAttempts = sessionCapturesCount,
                            registerNumber = registerNumber,
                            registerNumberErasedAt = null,
                            participantId = null,
                            estimateHidden = 0,
                            experimentalLabelShown = 1,
                            deviceProfileId = null,
                            cardBatchId = null, // the batch a card scan stored; scanning is wired in C1 (BUG-2026-060)
                            appVersion = appVersion,
                            modelVersion = null, // no model runs until E1 wires the pipeline (BUG-2026-001)
                            configVersion = assets.triageConfig?.configVersion ?: "unknown"
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
                },
                onNavigateToResearch = { navController.navigate(Destinations.R01_RESEARCH_UNLOCK) }
            )
        }

        composable(Destinations.S24_ABOUT) {
            com.kitalonlabs.neogold.feature.settings.S24AboutScreen(
                onBack = { navController.popBackStack() }
            )
        }

        composable(Destinations.S25_DELETE_DATA) {
            // Delete all data removes the database, encrypted files, every key and the staging and crash files,
            // then restarts the app empty (FR-33, TC-LOG-11). It also loses research items the study has not
            // acknowledged, so the screen says how many (doc 8 Section 7.3).
            val unsent by androidx.compose.runtime.produceState(initialValue = 0) { value = unsentResearchItems() }
            com.kitalonlabs.neogold.feature.settings.S25DeleteAllDataScreen(
                unsentResearchItems = unsent,
                onConfirmDelete = onDeleteAllData,
                onCancel = {
                    navController.popBackStack()
                }
            )
        }

        // R01 to R09 Research Mode. Research needs the verified bundle and a research session over the open database
        // (BUG-2026-007); the session locks itself after 5 minutes away or when the screen goes off, and every research
        // screen then returns to R01.
        fun NavGraphBuilder.researchSessionComposable(
            route: String,
            content: @Composable androidx.compose.animation.AnimatedContentScope.(ResearchViewModel) -> Unit
        ) = researchComposable(route) { _ ->
            val session = research
            if (session != null) content(session) else IntegrityBlockedScreen(integrity)
        }

        researchSessionComposable(Destinations.R01_RESEARCH_UNLOCK) { session ->
            val study by session.activeStudy.collectAsState()
            val scanned by session.scannedProfile.collectAsState()
            val lockedOut by session.isLockedOut.collectAsState()
            val lockoutMinutes by session.lockoutMinutes.collectAsState()
            val error by session.error.collectAsState()
            val unlocked by session.isUnlocked.collectAsState()
            val openHome = {
                navController.navigate(Destinations.R02_STUDY_HOME) {
                    popUpTo(Destinations.R01_RESEARCH_UNLOCK) { inclusive = true }
                }
            }
            // A message from the last visit is not shown again.
            androidx.compose.runtime.LaunchedEffect(Unit) { session.clearError() }
            // Already unlocked (Settings was opened again within the session): no second PIN.
            androidx.compose.runtime.LaunchedEffect(unlocked) { if (unlocked) openHome() }

            com.kitalonlabs.neogold.feature.research.ui.R01ResearchUnlockScreen(
                scannedProfile = scanned,
                existingStudyName = study?.displayName,
                isLockedOut = lockedOut,
                lockoutMinutes = lockoutMinutes,
                error = error,
                onQrScanned = session::onStudyQrScanned,
                onUnlockWithPin = { pin -> session.unlockWithPin(pin) { openHome() } },
                onEnrollWithPin = { pin, confirmPin ->
                    session.enrollWithScannedProfile(pin, confirmPin) { openHome() }
                }
            )
        }

        researchSessionComposable(Destinations.R02_STUDY_HOME) { session ->
            val study by session.activeStudy.collectAsState()
            val pending by session.pendingUploadsCount.collectAsState()
            // the card the phone works with, not the card of the last saved session (which is nothing before the first)
            val cardStatus by androidx.compose.runtime.produceState(CardTrustStatus.UNVERIFIED) {
                // the badge starts at UNVERIFIED and stays there if the storage is closing (a lock, a reset)
                value = runCatching { researchCardStatus() }.getOrDefault(CardTrustStatus.UNVERIFIED)
            }
            androidx.compose.runtime.LaunchedEffect(Unit) { session.refreshPendingUploads() }

            study?.let {
                com.kitalonlabs.neogold.feature.research.ui.R02StudyHomeScreen(
                    studyName = it.displayName,
                    studyCode = it.studyCode,
                    siteCode = it.siteCode,
                    cardStatus = cardStatus,
                    pendingUploadsCount = pending,
                    onNewParticipantSession = { navController.navigate(Destinations.R03_PARTICIPANT_CONSENT) },
                    onEnterTsbClicked = { navController.navigate(Destinations.R05_TSB_ENTRY) },
                    // locking turns the session state off, which sends every research screen back to R01
                    onLockSession = session::lockResearchSession,
                    onExportClicked = { navController.navigate(Destinations.R07_EXPORT_SYNC) }
                )
            }
        }

        researchSessionComposable(Destinations.R03_PARTICIPANT_CONSENT) { session ->
            val study by session.activeStudy.collectAsState()
            val error by session.error.collectAsState()
            // a message from the last screen (a save error from R05, a PIN message from R01) is not shown here
            androidx.compose.runtime.LaunchedEffect(Unit) { session.clearError() }
            com.kitalonlabs.neogold.feature.research.ui.R03ParticipantConsentScreen(
                studyName = study?.displayName.orEmpty(),
                error = error,
                onContinueToCapture = { code, consentFormId, birthTime, ageHours, gestationalWeeks, early, danger, prior ->
                    session.registerParticipantAndInputs(
                        participantCode = code,
                        consentFormId = consentFormId,
                        birthTimeMillis = birthTime,
                        ageHours = ageHours,
                        gestationalAgeWeeks = gestationalWeeks,
                        flagEarlyJaundice = early,
                        flagDangerSigns = danger,
                        flagPriorPhototherapy = prior,
                        onSuccess = { navController.navigate(Destinations.R04_CAPTURE_PROTOCOL) }
                    )
                }
            )
        }

        researchSessionComposable(Destinations.R04_CAPTURE_PROTOCOL) { session ->
            val participant by session.currentParticipant.collectAsState()
            val setup by androidx.compose.runtime.produceState<NeoGoldContainer.ResearchCaptureSetup?>(null) {
                value = researchCaptureSetup()
            }
            val current = participant
            val ready = setup
            if (current != null && ready != null) {
                com.kitalonlabs.neogold.feature.research.ui.R04ResearchCaptureScreen(
                    participantCode = current.participantCode,
                    processor = ready.processor,
                    onProtocolCompleted = { captures ->
                        researchCardBytes = ready.cardSignedBytes
                        session.onCapturesCompleted(captures) { navController.navigate(Destinations.R05_TSB_ENTRY) }
                    },
                    onCancel = {
                        session.clearSessionState()
                        navController.popBackStack(Destinations.R02_STUDY_HOME, inclusive = false)
                    }
                )
            }
        }

        researchSessionComposable(Destinations.R05_TSB_ENTRY) { session ->
            val participant by session.currentParticipant.collectAsState()
            val error by session.error.collectAsState()
            androidx.compose.runtime.LaunchedEffect(Unit) { session.clearError() }
            val current = participant
            val finish = { researchTsb: Pair<Double, String>? ->
                researchTsbEntered = researchTsb
                navController.navigate(Destinations.R06_SESSION_COMPLETE)
            }
            // With a session in progress this closes it. Opened from R02 there is none: the value is added to an earlier
            // participant by their code (TC-RSH-11).
            com.kitalonlabs.neogold.feature.research.ui.R05TsbEntryScreen(
                initialParticipantCode = current?.participantCode,
                isDeferredEntry = current == null,
                saveError = error,
                onSaveTsb = { code, tsbMgDl, drawTime, method ->
                    if (current != null) {
                        session.completeSessionWithTsb(tsbMgDl, drawTime, method, researchCardBytes) {
                            finish(tsbMgDl to method)
                        }
                    } else {
                        session.recordDeferredTsb(code, tsbMgDl, drawTime, method) {
                            navController.popBackStack(Destinations.R02_STUDY_HOME, inclusive = false)
                        }
                    }
                },
                onSkipTsb = if (current != null) {
                    { session.completeSessionWithTsb(null, null, null, researchCardBytes) { finish(null) } }
                } else {
                    null
                }
            )
        }

        researchSessionComposable(Destinations.R06_SESSION_COMPLETE) { session ->
            val result by session.lastSessionResult.collectAsState()
            result?.let {
                com.kitalonlabs.neogold.feature.research.ui.R06SessionCompleteScreen(
                    participantCode = it.participantCode,
                    capturesCount = it.capturesCount,
                    cardStatus = it.cardStatus,
                    tsbEntered = it.tsbEntered,
                    tsbMgDl = researchTsbEntered?.first,
                    tsbMethod = researchTsbEntered?.second,
                    onDoneClicked = {
                        session.clearSessionState()
                        researchCardBytes = null
                        researchTsbEntered = null
                        navController.popBackStack(Destinations.R02_STUDY_HOME, inclusive = false)
                    }
                )
            }
        }

        // Export is wired in package C3; until then this screen only shows the study it belongs to.
        researchSessionComposable(Destinations.R07_EXPORT_SYNC) { session ->
            val study by session.activeStudy.collectAsState()
            var exportProgress by remember { mutableStateOf<ExportProgress?>(null) }
            var isExporting by remember { mutableStateOf(false) }
            var exportCompleted by remember { mutableStateOf(false) }
            var exportFailed by remember { mutableStateOf(false) }
            // Tied to this screen: leaving it, or a lock that sends the worker back to R01, cancels the export, removes what
            // it staged and discards the file it was writing.
            val exportScope = androidx.compose.runtime.rememberCoroutineScope()
            val contentResolver = androidx.compose.ui.platform.LocalContext.current.contentResolver

            val exportLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
                contract = androidx.activity.result.contract.ActivityResultContracts.CreateDocument("application/zip")
            ) { uri ->
                if (uri != null && !isExporting) {
                    isExporting = true
                    exportFailed = false
                    exportProgress = null
                    exportScope.launch {
                        val result = researchExport.exportToUri(contentResolver, uri) { exportProgress = it }
                        isExporting = false
                        result.onSuccess { exportCompleted = true }.onFailure { exportFailed = true }
                    }
                }
            }

            study?.let {
                com.kitalonlabs.neogold.feature.research.ui.R07ExportScreen(
                    studyCode = it.studyCode,
                    displayName = it.displayName,
                    hasExportKey = it.exportPublicKey != null,
                    exportProgress = exportProgress,
                    isExporting = isExporting,
                    exportCompleted = exportCompleted,
                    exportFailed = exportFailed,
                    // the server side (enrollment, upload now, leave study) is wired in package C4
                    showCloudSync = false,
                    onChooseDestinationClicked = {
                        exportLauncher.launch(exportFileName(it.studyCode, java.time.Instant.now()))
                    },
                    onDoneClicked = { navController.popBackStack() }
                )
            }
        }
        researchComposable(Destinations.R08_ENROLLMENT) { PlaceholderScreen("R08: Study Enrollment") }
        researchComposable(Destinations.R09_LEAVE_STUDY) { PlaceholderScreen("R09: Leave Study") }
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
