@file:Suppress(
    "MaxLineLength",
    "MagicNumber"
) // hardcoded UI text (F1) and placeholder session values, both replaced by the wiring in B1 and E1

package com.kitalonlabs.neogold

import android.Manifest
import android.annotation.SuppressLint
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
import androidx.compose.ui.res.stringResource
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
import com.kitalonlabs.neogold.engine.camera.model.CaptureMetadata
import com.kitalonlabs.neogold.engine.camera.ui.CameraPermissionState
import com.kitalonlabs.neogold.engine.camera.ui.LocalCameraPermission
import com.kitalonlabs.neogold.engine.cardtrust.CardBatchPayload
import com.kitalonlabs.neogold.engine.cardtrust.VerificationResult
import com.kitalonlabs.neogold.engine.vision.ImageFrame
import com.kitalonlabs.neogold.feature.log.S21LogScreen
import com.kitalonlabs.neogold.feature.log.S22SessionDetailScreen
import com.kitalonlabs.neogold.feature.log.ScreeningLogExportManager
import com.kitalonlabs.neogold.feature.onboarding.domain.CardScanState
import com.kitalonlabs.neogold.feature.onboarding.domain.QualificationState
import com.kitalonlabs.neogold.feature.research.data.CardTrustStatus
import com.kitalonlabs.neogold.feature.research.export.ExportDestination
import com.kitalonlabs.neogold.feature.research.export.ExportFileRemains
import com.kitalonlabs.neogold.feature.research.export.ExportProgress
import com.kitalonlabs.neogold.feature.research.export.PendingPick
import com.kitalonlabs.neogold.feature.research.export.ResearchExportManager
import com.kitalonlabs.neogold.feature.research.export.exportFileName
import com.kitalonlabs.neogold.feature.research.ui.ResearchViewModel
import com.kitalonlabs.neogold.feature.research.ui.StudyServerViewModel
import com.kitalonlabs.neogold.feature.result.S20ResultScreen
import com.kitalonlabs.neogold.feature.screening.domain.ScreeningFlow
import com.kitalonlabs.neogold.feature.screening.domain.ScreeningState
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
import com.kitalonlabs.neogold.feature.screening.ui.S19AnalyzingScreen
import com.kitalonlabs.neogold.feature.screening.ui.nextStepText
import com.kitalonlabs.neogold.feature.screening.ui.reasonText
import com.kitalonlabs.neogold.integrity.VerifiedAssets
import com.kitalonlabs.neogold.navigation.Destinations
import com.kitalonlabs.neogold.onboarding.FirstRunState
import com.kitalonlabs.neogold.onboarding.OnboardingServices
import com.kitalonlabs.neogold.storage.StorageState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch

class MainActivity : FragmentActivity() {

    internal val appLockManager = AppLockManager()
    private lateinit var dataStore: NeoGoldDataStore

    private val container: NeoGoldContainer
        get() = (application as NeoGoldApplication).container

    /**
     * The "save the export" picker belongs to the activity, not to a screen: when the research session locks while the picker
     * is open the screen is gone, and the empty document the picker already created is deleted here (BUG-083 item 30).
     */
    private val pendingExportPick = PendingPick<Uri> { uri ->
        // not cancelled with the activity: the empty document is deleted even when the activity is leaving
        lifecycleScope.launch(
            Dispatchers.IO + NonCancellable
        ) { ExportDestination.forUri(contentResolver, uri).discard() }
    }

    /**
     * Lint asks for androidx.fragment 1.3.0 or newer (the app resolves 1.2.5, through the biometric library) because older
     * FragmentActivity versions mishandle the results of permission requests made through the ActivityResult API. This
     * launcher is only the document picker; the camera permission is asked with ActivityCompat and its own request code.
     * Raising the fragment version is a dependency change and waits for the owner (BUG-2026-083 item 30).
     */
    @SuppressLint("InvalidFragmentVersionForActivityResult")
    private val exportPickerLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.CreateDocument("application/zip")
    ) { uri -> pendingExportPick.deliver(uri) }
    private val exportPicker = ExportPicker(pendingExportPick) { exportPickerLauncher.launch(it) }

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
                                            studyServer = container.studyServerViewModel(state.database),
                                            screeningHolder = container.screeningHolder,
                                            newScreening = container::newScreening,
                                            screeningCardCheck = { container.screeningCardCheck(state.database) },
                                            activeDeviceProfileId = { container.activeDeviceProfileId(state.database) },
                                            researchCaptureSetup = { container.researchCaptureSetup(state.database) },
                                            researchCardStatus = { container.researchCardStatus(state.database) },
                                            researchExport = container.researchExportManager(state.database),
                                            exportPicker = exportPicker
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
 * @param studyServer joining, leaving and uploading to the study server (R07 to R09); null when [research] is, and when
 * the bundle did not verify
 * @param screeningHolder the screening in progress, kept across rotation and the app lock
 * @param newScreening starts a screening over the verified bundle, or null when there is none to run on
 * @param screeningCardCheck checks the card the phone works with against the trust list
 * @param activeDeviceProfileId the phone's active device profile, for the saved record
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
    studyServer: StudyServerViewModel?,
    screeningHolder: ScreeningHolder,
    newScreening: () -> ScreeningFlow?,
    screeningCardCheck: suspend () -> VerificationResult<CardBatchPayload>,
    activeDeviceProfileId: suspend () -> String?,
    researchCaptureSetup: suspend () -> NeoGoldContainer.ResearchCaptureSetup,
    researchCardStatus: suspend () -> CardTrustStatus,
    researchExport: ResearchExportManager,
    exportPicker: ExportPicker,
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
            studyServer?.clearScan()
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

    // The screening in progress. It holds the worker's answers (none pre-filled) and the engine's decisions; the screens only
    // show its state (package E1). A photo waits here in memory between S16 and the end of the analysis and is never written.
    var screening by screeningHolder::flow
    var pendingPhoto by remember { mutableStateOf<Pair<ImageFrame, CaptureMetadata?>?>(null) }

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
                // A screening that was lost (the process was restarted) cannot go on; Home is where the worker starts again
                screening == null -> {
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

            // A new screening starts from nothing: the flow of the last one is dropped with its answers and any photo
            val startScreening = {
                screening = newScreening()
                pendingPhoto = null
                if (screening != null) navController.navigate(Destinations.S10A_GUARDIAN_CONSENT)
            }
            S09HomeScreen(
                triageEnabled = triageEnabled,
                todayScreeningsCount = todayCount,
                dueRechecks = dueRechecks,
                onNewScreeningClicked = startScreening,
                onScreeningLogClicked = {
                    navController.navigate(Destinations.S21_LOG)
                },
                onSettingsClicked = {
                    navController.navigate(Destinations.S23_SETTINGS)
                },
                onRecheckClicked = { _ -> startScreening() }
            )
        }

        // S10a to S20 Screening Flow. Every route reads the flow; with none (the bundle is not usable) a route shows nothing.
        screeningComposable(Destinations.S10A_GUARDIAN_CONSENT) {
            S10aGuardianConsentScreen(
                onConsentConfirmed = {
                    screening?.begin(guardianConsentConfirmed = true)
                    navController.navigate(Destinations.S10_BIRTH_TIME_GA)
                },
                onBack = {
                    navController.popBackStack()
                }
            )
        }

        screeningComposable(Destinations.S10_BIRTH_TIME_GA) {
            S10BirthTimeGaScreen(
                onNext = { ageHours, gaWeeks, _ ->
                    screening?.answerAgeAndGestation(ageHours, gaWeeks)
                    navController.navigate(Destinations.S11_EARLY_JAUNDICE)
                },
                onBack = {
                    navController.popBackStack()
                }
            )
        }

        // S11 to S13 only collect answers. Which rule applies, and in what order, is the engine's to say once all are given.
        screeningComposable(Destinations.S11_EARLY_JAUNDICE) {
            S11EarlyJaundiceScreen(
                onAnswered = { isEarlyJaundice ->
                    screening?.answerEarlyJaundice(isEarlyJaundice)
                    navController.navigate(Destinations.S12_DANGER_SIGNS)
                },
                onBack = {
                    navController.popBackStack()
                }
            )
        }

        screeningComposable(Destinations.S12_DANGER_SIGNS) {
            S12DangerSignsScreen(
                onAnswered = { hasDangerSigns ->
                    val flow = screening
                    flow?.answerDangerSigns(hasDangerSigns)
                    // danger signs, or early jaundice already answered, settle the outcome: no more questions
                    val settled = flow?.decideEarly()
                    if (settled != null) {
                        navController.navigate(resultRouteFor(settled)) { popUpTo(Destinations.S09_HOME) }
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
                    val flow = screening
                    if (flow != null) {
                        flow.answerPriorPhototherapy(hadPhototherapy)
                        coroutineScope.launch {
                            val after = flow.decide()
                            val next = if (after is ScreeningState.CardCheck) {
                                // no rule decided: the card the phone works with is checked against the trust list now
                                flow.cardChecked(screeningCardCheck())
                            } else {
                                after
                            }
                            if (next is ScreeningState.Capture) {
                                navController.navigate(Destinations.S15_CAPTURE_GUIDE)
                            } else {
                                // Back from a result goes Home, not into the questions again
                                navController.navigate(resultRouteFor(next)) { popUpTo(Destinations.S09_HOME) }
                            }
                        }
                    }
                },
                onBack = {
                    navController.popBackStack()
                }
            )
        }

        // A hard rule decided before the camera (R1 to R5): the engine's answer, in doc 6 Section 3 wording
        screeningComposable(Destinations.S14_RULE_OUTCOME) {
            val flow = screening
            val shown = flow?.currentState?.shownResult()
            if (flow != null && shown != null) {
                S14RuleOutcomeScreen(
                    presentation = shown.presentation,
                    shownAtMs = flow.decidedAtMs,
                    appVersion = appVersion,
                    modelVersion = shown.modelVersion,
                    configVersion = shown.configVersion,
                    cardBatchId = shown.cardBatchId,
                    onSaveToLog = { registerNumber ->
                        coroutineScope.launch {
                            saveScreening(flow, registerNumber, activeDeviceProfileId(), db)
                            navController.navigate(Destinations.S09_HOME) {
                                popUpTo(Destinations.S09_HOME) { inclusive = true }
                            }
                        }
                    }
                )
            } else {
                // nothing to show for this state: Home, never an empty screen
                androidx.compose.runtime.LaunchedEffect(Unit) {
                    navController.navigate(Destinations.S09_HOME) { popUpTo(0) }
                }
            }
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

        // The real camera: the photo is held in memory and analysed at S19. There is no stand-in photo (BUG-2026-003).
        screeningComposable(Destinations.S16_CAMERA) {
            S16CameraScreen(
                onCaptureCompleted = { frame, metadata ->
                    pendingPhoto = frame to metadata
                    navController.navigate(Destinations.S19_ANALYZING) {
                        popUpTo(Destinations.S16_CAMERA) { inclusive = true }
                    }
                },
                onBack = {
                    navController.popBackStack()
                }
            )
        }

        // The photo was refused by the quality gate: the reason in words, and a retake (up to three, then a blood test)
        screeningComposable(Destinations.S17_PHOTO_PROBLEM) {
            val capture = screening?.currentState as? ScreeningState.Capture
            S17PhotoProblemScreen(
                reason = capture?.previousFailure,
                attempt = (capture?.attempt ?: 1) - 1,
                onRetake = {
                    navController.navigate(Destinations.S16_CAMERA) {
                        popUpTo(Destinations.S17_PHOTO_PROBLEM) { inclusive = true }
                    }
                },
                onFallbackToBloodTest = {
                    val after = screening?.giveUp()
                    if (after != null) {
                        navController.navigate(resultRouteFor(after)) { popUpTo(Destinations.S09_HOME) }
                    }
                },
                onBack = {
                    navController.popBackStack()
                }
            )
        }

        // S18 (an optional forehead photo) is not in the flow: the estimate is made from the sternum photo alone. A second
        // site needs the pipeline to take two photos (BUG-2026-090).

        screeningComposable(Destinations.S19_ANALYZING) {
            val flow = screening
            val photo = remember { pendingPhoto } // read once, when the screen is entered
            // the photo is dropped when the worker leaves this screen by any way
            androidx.compose.runtime.DisposableEffect(Unit) { onDispose { pendingPhoto = null } }
            androidx.compose.runtime.LaunchedEffect(Unit) {
                if (flow != null && photo == null) {
                    // the screen was recreated and the photo with it: take another, which does not count as an attempt
                    navController.navigate(
                        Destinations.S16_CAMERA
                    ) { popUpTo(Destinations.S19_ANALYZING) { inclusive = true } }
                }
                if (flow != null && photo != null) {
                    val after = flow.analyze(photo.first, photo.second)
                    pendingPhoto = null // the photo is dropped as soon as the analysis is over
                    if (after is ScreeningState.Capture) {
                        navController.navigate(Destinations.S17_PHOTO_PROBLEM) {
                            popUpTo(Destinations.S19_ANALYZING) { inclusive = true }
                        }
                    } else {
                        navController.navigate(resultRouteFor(after)) { popUpTo(Destinations.S09_HOME) }
                    }
                }
            }
            S19AnalyzingScreen(
                onTimeoutFallback = {
                    // the worker does not want to wait: a blood test, decided by the engine
                    pendingPhoto = null
                    val after = flow?.giveUp()
                    if (after != null) {
                        navController.navigate(resultRouteFor(after)) { popUpTo(Destinations.S09_HOME) }
                    }
                }
            )
        }

        screeningComposable(Destinations.S20_RESULT) {
            val flow = screening
            val shown = flow?.currentState?.shownResult()
            if (flow != null && shown != null) {
                val presentation = shown.presentation
                S20ResultScreen(
                    action = presentation.uiAction,
                    nextStepText = presentation.nextStepText(flow.decidedAtMs),
                    reasonText = presentation.reasonText(),
                    estimatedLow = presentation.interval?.lower,
                    estimatedHigh = presentation.interval?.upper,
                    threshold = presentation.thresholdMgDl,
                    recheckHours = presentation.recheckHours ?: 0,
                    triageEnabled = shown.triageAvailable,
                    appVersion = appVersion,
                    modelVersion = shown.modelVersion,
                    configVersion = shown.configVersion,
                    cardBatchId = shown.cardBatchId,
                    onSaveToLog = { registerNumber ->
                        coroutineScope.launch {
                            saveScreening(flow, registerNumber, activeDeviceProfileId(), db)
                            navController.navigate(Destinations.S09_HOME) {
                                popUpTo(Destinations.S09_HOME) { inclusive = true }
                            }
                        }
                    }
                )
            } else {
                // A screening that was blocked (the card did not verify, the bundle is not intact) has no decision: the only
                // thing to say is to use a blood test.
                S20ResultScreen(
                    action = TriageUiAction.CANNOT_SCREEN,
                    nextStepText = stringResource(
                        com.kitalonlabs.neogold.core.designsystem.R.string.next_use_blood_test
                    ),
                    reasonText = null,
                    estimatedLow = null,
                    estimatedHigh = null,
                    threshold = null,
                    triageEnabled = false,
                    appVersion = appVersion,
                    modelVersion = assets.modelVersion.orEmpty(),
                    configVersion = assets.triageConfig?.configVersion.orEmpty(),
                    onSaveToLog = {
                        navController.navigate(Destinations.S09_HOME) {
                            popUpTo(Destinations.S09_HOME) { inclusive = true }
                        }
                    }
                )
            }
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

        // R07: the encrypted export to a drive (C3) and, below it, the study server card (C4).
        researchSessionComposable(Destinations.R07_EXPORT_SYNC) { session ->
            val study by session.activeStudy.collectAsState()
            val server = studyServer
            val serverStudy = server?.study?.collectAsState()?.value
            val serverPending = server?.pendingUploads?.collectAsState()?.value ?: 0
            // read again whenever the screen opens: the study may have been joined or left, and uploads change the count
            androidx.compose.runtime.LaunchedEffect(Unit) { server?.refresh() }
            var exportProgress by remember { mutableStateOf<ExportProgress?>(null) }
            var isExporting by remember { mutableStateOf(false) }
            var exportCompleted by remember { mutableStateOf(false) }
            var exportFailed by remember { mutableStateOf(false) }
            var exportFileRemains by remember { mutableStateOf(false) }
            // Tied to this screen: leaving it, or a lock that sends the worker back to R01, cancels the export, removes what
            // it staged and discards the file it was writing.
            val exportScope = androidx.compose.runtime.rememberCoroutineScope()
            val contentResolver = androidx.compose.ui.platform.LocalContext.current.contentResolver

            // A place chosen after this screen was left, or after a lock, is deleted by the picker's owner; it is not used.
            androidx.compose.runtime.DisposableEffect(Unit) { onDispose { exportPicker.pending.cancel() } }

            fun startExport(uri: Uri) {
                // a result that arrives after a lock (the picker was open when the screen turned off) is not acted on
                if (isExporting || !session.isSessionOpen()) {
                    exportPicker.pending.reject(uri)
                    return
                }
                isExporting = true
                exportFailed = false
                exportFileRemains = false
                exportProgress = null
                exportScope.launch {
                    val result = researchExport.exportToUri(
                        contentResolver,
                        uri,
                        canContinue = session::isSessionOpen
                    ) { exportProgress = it }
                    isExporting = false
                    result.onSuccess { exportCompleted = true }.onFailure {
                        exportFailed = true
                        exportFileRemains = it is ExportFileRemains
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
                    exportFileRemains = exportFileRemains,
                    showCloudSync = server != null,
                    pendingUploadsCount = serverPending,
                    serverDeviceId = serverStudy?.serverDeviceId,
                    onUploadNowClicked = { server?.uploadNow() },
                    onEnrollClicked = { navController.navigate(Destinations.R08_ENROLLMENT) },
                    onLeaveStudyClicked = { navController.navigate(Destinations.R09_LEAVE_STUDY) },
                    onChooseDestinationClicked = {
                        exportPicker.pending.expect(::startExport)
                        exportPicker.launch(exportFileName(it.studyCode, java.time.Instant.now()))
                    },
                    onDoneClicked = { navController.popBackStack() }
                )
            }
        }
        // R08 and R09 need the study server's view model as well as the research session; without both (the bundle did
        // not verify) the block screen shows, like every research screen.
        fun NavGraphBuilder.studyServerComposable(
            route: String,
            content: @Composable androidx.compose.animation.AnimatedContentScope.(StudyServerViewModel) -> Unit
        ) = researchSessionComposable(route) { _ ->
            val server = studyServer
            if (server != null) content(server) else IntegrityBlockedScreen(integrity)
        }

        studyServerComposable(Destinations.R08_ENROLLMENT) { server ->
            val scanned by server.scannedProfile.collectAsState()
            val error by server.error.collectAsState()
            val busy by server.isBusy.collectAsState()
            // a scan or a message from the last visit is not shown again
            androidx.compose.runtime.LaunchedEffect(Unit) {
                server.clearScan()
                server.clearError()
                server.refresh()
            }
            com.kitalonlabs.neogold.feature.research.ui.R08EnrollmentScreen(
                scannedProfile = scanned,
                isEnrolling = busy,
                error = error,
                onQrScanned = server::onQrScanned,
                onEnrollConfirmed = { code -> server.enroll(code) { navController.popBackStack() } },
                onCancelClicked = {
                    server.clearScan()
                    navController.popBackStack()
                }
            )
        }

        studyServerComposable(Destinations.R09_LEAVE_STUDY) { server ->
            val study by server.study.collectAsState()
            val pending by server.pendingUploads.collectAsState()
            val error by server.error.collectAsState()
            val busy by server.isBusy.collectAsState()
            androidx.compose.runtime.LaunchedEffect(Unit) {
                server.clearError()
                server.refresh()
            }
            // The device ID is gone once the phone has left: nothing to show but the way back.
            val deviceId = study?.serverDeviceId
            if (deviceId == null) {
                androidx.compose.runtime.LaunchedEffect(Unit) { navController.popBackStack() }
            } else {
                com.kitalonlabs.neogold.feature.research.ui.R09LeaveStudyScreen(
                    studyName = study?.displayName.orEmpty(),
                    serverDeviceId = deviceId,
                    serverHost = serverHostOf(study?.serverUrl),
                    unsentItems = pending,
                    isLeaving = busy,
                    error = error,
                    onLeaveConfirmed = { pin -> server.leave(pin) { navController.popBackStack() } },
                    onCancelClicked = { navController.popBackStack() }
                )
            }
        }
    }
}

/** The activity's "save the export" picker: ask for a place with [launch], answers go through [pending]. */
class ExportPicker(val pending: PendingPick<Uri>, val launch: (String) -> Unit)

/** The host of a study server's API address, for display: "https://intake.example.bd/v1/" shows as "intake.example.bd". */
internal fun serverHostOf(serverUrl: String?): String =
    serverUrl.orEmpty().substringAfter("://").substringBefore("/")
