package com.kitalonlabs.neogold.feature.research.ui

import com.kitalonlabs.neogold.core.data.local.entity.ResearchStudyEntity
import com.kitalonlabs.neogold.core.data.pin.PinCheckResult
import com.kitalonlabs.neogold.core.security.pin.ResearchSessionManager
import com.kitalonlabs.neogold.engine.cardtrust.StudyProfilePayload
import com.kitalonlabs.neogold.feature.research.data.ResearchError
import com.kitalonlabs.neogold.feature.research.data.ResearchFormats
import com.kitalonlabs.neogold.feature.research.data.ResearchMessage
import com.kitalonlabs.neogold.feature.research.data.ResearchRefusal
import com.kitalonlabs.neogold.feature.research.data.ResearchRepository
import com.kitalonlabs.neogold.feature.research.data.StudyServer
import com.kitalonlabs.neogold.feature.research.data.StudyServerFailure
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.cancellation.CancellationException

/**
 * The server side of research mode: joining the study server (R08), leaving it (R09), "Upload now" and the count of what is
 * waiting (R07). It judges state only; the screens write the text in the language they show.
 *
 * It follows the rules of [ResearchViewModel]: one action at a time ([isBusy]), nothing starts while the session is locked,
 * a result that arrives after a lock is dropped, and a lock clears the scanned QR. A failure of the server layer is a typed
 * [StudyServerFailure]; anything else becomes the generic message, never a crash and never exception text.
 */
class StudyServerViewModel(
    private val repository: ResearchRepository,
    private val server: StudyServer,
    private val sessionManager: ResearchSessionManager? = null,
    /** Told after enrollment or leaving, so the research home (study, site) is read again. */
    private val onStudyChanged: () -> Unit = {},
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Main.immediate + SupervisorJob()),
    /** Where the PIN hashing runs: Argon2 uses 19 MiB and about a second, which must not happen on the main thread. */
    private val cpu: CoroutineDispatcher = Dispatchers.Default
) {
    private val _study = MutableStateFlow<ResearchStudyEntity?>(null)

    /** The study on this phone, read fresh: its `serverDeviceId` says whether the phone is set up on the server. */
    val study: StateFlow<ResearchStudyEntity?> = _study.asStateFlow()

    private val _scannedProfile = MutableStateFlow<StudyProfilePayload?>(null)

    /** The study named by the QR that was just scanned, verified, and found to be the study on this phone. */
    val scannedProfile: StateFlow<StudyProfilePayload?> = _scannedProfile.asStateFlow()
    private var scannedProfileBytes: ByteArray? = null

    private val _pendingUploads = MutableStateFlow(0)
    val pendingUploads: StateFlow<Int> = _pendingUploads.asStateFlow()

    private val _error = MutableStateFlow<ResearchError?>(null)
    val error: StateFlow<ResearchError?> = _error.asStateFlow()

    /** True while an enrollment or a leave runs. A second tap in that time is ignored, not queued. */
    private val _isBusy = MutableStateFlow(false)
    val isBusy: StateFlow<Boolean> = _isBusy.asStateFlow()

    private val lockEpoch: Int get() = sessionManager?.lockCount ?: 0

    /** Read from the session manager itself, not from a copy that trails a lock by one collection. */
    private val sessionIsOpen: Boolean get() = sessionManager?.isUnlocked?.value ?: true

    init {
        if (sessionManager != null) {
            // read here, not in the coroutine: a lock that lands before the collector starts must still be seen
            var clearedAt = sessionManager.lockCount
            scope.launch {
                sessionManager.isUnlocked.collect { unlocked ->
                    // A real lock (the count moved): nothing scanned stays. The first "locked" every new collector receives
                    // is not a lock (same rule as ResearchViewModel).
                    if (!unlocked && sessionManager.lockCount != clearedAt) {
                        clearedAt = sessionManager.lockCount
                        clearScan()
                        _error.value = null
                    }
                }
            }
        }
        refresh()
    }

    /** Reads the study and the number of rows waiting to be uploaded. */
    @Suppress("TooGenericExceptionCaught", "SwallowedException") // a storage error here shows the last known state
    fun refresh() {
        scope.launch {
            try {
                _study.value = repository.getActiveStudy()
                _pendingUploads.value = repository.getPendingUploadsCount()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // keep what is shown; the next action reads again
            }
        }
    }

    fun clearError() {
        _error.value = null
    }

    fun clearScan() {
        _scannedProfile.value = null
        scannedProfileBytes = null
    }

    /**
     * A study QR was scanned for R08: check its signature, and that it is the study on this phone. A QR of another study, or
     * of a study with no server, is refused here; nothing is sent.
     */
    fun onQrScanned(qrPayload: ByteArray) {
        _error.value = null
        val active = _study.value
        repository.verifyStudyProfile(qrPayload)
            .onSuccess { profile ->
                val refusal = enrollmentRefusal(active, profile)
                if (refusal == null) {
                    _scannedProfile.value = profile
                    scannedProfileBytes = qrPayload
                } else {
                    _error.value = ResearchError(refusal)
                }
            }
            .onFailure { _error.value = it.toError() }
    }

    /** Joins the study server with the one-time [code] from the coordinator. */
    fun enroll(code: String, onSuccess: () -> Unit) {
        if (!sessionIsOpen) return
        val study = _study.value ?: return
        val bytes = scannedProfileBytes ?: return
        if (!ResearchFormats.isValidEnrollmentCode(code)) {
            _error.value = ResearchError(ResearchMessage.ENROLL_CODE_FORMAT)
            return
        }
        if (!_isBusy.compareAndSet(expect = false, update = true)) return
        val epoch = lockEpoch
        scope.launch {
            _error.value = null
            val done = try {
                guarded { server.enroll(study, bytes, code.trim().uppercase()) }
            } finally {
                _isBusy.value = false
            }
            // locked while the server was answering: the study row is what it is, nothing is put back on a screen
            if (done == null || epoch != lockEpoch) return@launch
            clearScan()
            sessionManager?.recordUserActivity()
            reload()
            server.scheduleUploads()
            onSuccess()
        }
    }

    /**
     * Leaves the study server. The research PIN is asked for again (the health worker may have left the phone unlocked), and
     * a wrong PIN counts toward the same lockout as at R01. Only the server connection ends: the study, its sessions and its
     * photos stay on the phone, for export.
     */
    fun leave(pin: String, onSuccess: () -> Unit) {
        if (!sessionIsOpen) return
        val study = _study.value ?: return
        if (study.serverDeviceId == null) {
            _error.value = ResearchError(ResearchMessage.NOT_ENROLLED)
            return
        }
        if (!_isBusy.compareAndSet(expect = false, update = true)) return
        val epoch = lockEpoch
        scope.launch {
            _error.value = null
            val done = try {
                val check = guarded { withContext(cpu) { repository.verifyPin(study.id, pin) } }
                if (check == null || epoch != lockEpoch) {
                    null
                } else if (check !is PinCheckResult.Success) {
                    _error.value = check.toError()
                    null
                } else {
                    guarded { server.leave(study) }
                }
            } finally {
                _isBusy.value = false
            }
            if (done == null || epoch != lockEpoch) return@launch
            sessionManager?.recordUserActivity()
            reload()
            onSuccess()
        }
    }

    /** "Upload now" (R07). The pending count is read again a moment later by the screen's own refresh. */
    fun uploadNow() {
        if (!sessionIsOpen) return
        if (_study.value?.serverDeviceId == null) {
            _error.value = ResearchError(ResearchMessage.NOT_ENROLLED)
            return
        }
        sessionManager?.recordUserActivity()
        server.uploadNow()
    }

    private suspend fun reload() {
        refreshNow()
        onStudyChanged()
    }

    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    private suspend fun refreshNow() {
        try {
            _study.value = repository.getActiveStudy()
            _pendingUploads.value = repository.getPendingUploadsCount()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // keep what is shown
        }
    }

    /**
     * Runs [block]. A [StudyServerFailure] becomes its typed message, anything unexpected the generic one, and both give null;
     * a cancellation goes on. Unit results are returned as `Unit` so null always means "did not work".
     */
    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    private suspend fun <T : Any> guarded(block: suspend () -> T): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: StudyServerFailure) {
        _error.value = e.error
        null
    } catch (e: Exception) {
        _error.value = ResearchError(ResearchMessage.UNEXPECTED)
        null
    }
}

/**
 * Why a scanned, verified study QR cannot be used to join the server of the study on this phone, or null if it can: no study
 * is set up yet, the QR is of another study, the phone already has a device ID, or the study has no server.
 */
internal fun enrollmentRefusal(active: ResearchStudyEntity?, profile: StudyProfilePayload): ResearchMessage? = when {
    active == null -> ResearchMessage.NO_STUDY
    active.studyCode != profile.studyCode -> ResearchMessage.ENROLL_OTHER_STUDY
    active.serverDeviceId != null -> ResearchMessage.UNEXPECTED
    profile.host == null -> ResearchMessage.ENROLL_NO_SERVER
    else -> null
}

private fun PinCheckResult.toError(): ResearchError = when (this) {
    is PinCheckResult.WrongPin -> ResearchError(ResearchMessage.WRONG_PIN, attempts = attemptsUntilNextLockout)
    is PinCheckResult.LockedOut -> ResearchError(ResearchMessage.LOCKED_OUT, minutes = remainingMinutes)
    is PinCheckResult.VerifierKeyLost -> ResearchError(ResearchMessage.VERIFIER_KEY_LOST)
    is PinCheckResult.Success -> ResearchError(ResearchMessage.UNEXPECTED)
}

private fun Throwable.toError(): ResearchError =
    (this as? ResearchRefusal)?.error ?: ResearchError(ResearchMessage.UNEXPECTED)
