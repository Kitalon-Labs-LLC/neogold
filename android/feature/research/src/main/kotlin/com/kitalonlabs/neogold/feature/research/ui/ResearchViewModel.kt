package com.kitalonlabs.neogold.feature.research.ui

import com.kitalonlabs.neogold.core.data.local.entity.ResearchParticipantEntity
import com.kitalonlabs.neogold.core.data.local.entity.ResearchStudyEntity
import com.kitalonlabs.neogold.core.data.pin.PinCheckResult
import com.kitalonlabs.neogold.core.security.pin.ResearchSessionManager
import com.kitalonlabs.neogold.engine.cardtrust.StudyProfilePayload
import com.kitalonlabs.neogold.feature.research.data.CardTrustStatus
import com.kitalonlabs.neogold.feature.research.data.ResearchCaptureInput
import com.kitalonlabs.neogold.feature.research.data.ResearchRepository
import com.kitalonlabs.neogold.feature.research.data.ResearchSessionResult
import com.kitalonlabs.neogold.feature.research.data.TsbInput
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

data class ResearchClinicalInputs(
    val participantCode: String,
    val consentFormId: String,
    val birthTimeMillis: Long,
    val ageHours: Double,
    val gestationalAgeWeeks: Int,
    val flagEarlyJaundice: Boolean,
    val flagDangerSigns: Boolean,
    val flagPriorPhototherapy: Boolean
)

/**
 * State holder orchestrating research mode UI state, session security lifecycle,
 * participant registration, blinded capture protocol, and paired TSB records.
 */
// One state holder for the whole research flow, R01 to R06; it is split when R08 and R09 are wired (C4).
@Suppress("TooManyFunctions")
class ResearchViewModel(
    private val repository: ResearchRepository,
    private val sessionManager: ResearchSessionManager? = null,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Main.immediate + SupervisorJob()),
    /** Where the PIN hashing runs: Argon2 uses 19 MiB and about a second, which must not happen on the main thread. */
    private val cpu: CoroutineDispatcher = Dispatchers.Default
) {

    private val _activeStudy = MutableStateFlow<ResearchStudyEntity?>(null)
    val activeStudy: StateFlow<ResearchStudyEntity?> = _activeStudy.asStateFlow()

    private val _isUnlocked = MutableStateFlow(false)
    val isUnlocked: StateFlow<Boolean> = _isUnlocked.asStateFlow()

    private val _isLockedOut = MutableStateFlow(false)
    val isLockedOut: StateFlow<Boolean> = _isLockedOut.asStateFlow()

    private val _lockoutMinutes = MutableStateFlow(0)
    val lockoutMinutes: StateFlow<Int> = _lockoutMinutes.asStateFlow()

    private val _error = MutableStateFlow<ResearchError?>(null)

    /** What went wrong, as a typed message; the screen writes it in the language it shows (see [text]). */
    val error: StateFlow<ResearchError?> = _error.asStateFlow()

    private val _currentParticipant = MutableStateFlow<ResearchParticipantEntity?>(null)
    val currentParticipant: StateFlow<ResearchParticipantEntity?> = _currentParticipant.asStateFlow()

    private val _clinicalInputs = MutableStateFlow<ResearchClinicalInputs?>(null)
    val clinicalInputs: StateFlow<ResearchClinicalInputs?> = _clinicalInputs.asStateFlow()

    private val _currentCaptures = MutableStateFlow<List<ResearchCaptureInput>>(emptyList())
    val currentCaptures: StateFlow<List<ResearchCaptureInput>> = _currentCaptures.asStateFlow()

    private val _lastSessionResult = MutableStateFlow<ResearchSessionResult?>(null)
    val lastSessionResult: StateFlow<ResearchSessionResult?> = _lastSessionResult.asStateFlow()

    private val _cardTrustStatus = MutableStateFlow(CardTrustStatus.UNVERIFIED)
    val cardTrustStatus: StateFlow<CardTrustStatus> = _cardTrustStatus.asStateFlow()

    private val _scannedProfile = MutableStateFlow<StudyProfilePayload?>(null)

    /** The study named by the QR that was just scanned and verified, shown before the PIN is asked for. */
    val scannedProfile: StateFlow<StudyProfilePayload?> = _scannedProfile.asStateFlow()
    private var scannedProfileBytes: ByteArray? = null

    /** True while a PIN check or a session save runs. A second tap in that time is ignored, not queued. */
    private val _isBusy = MutableStateFlow(false)
    val isBusy: StateFlow<Boolean> = _isBusy.asStateFlow()

    /**
     * The session's lock count (see [ResearchSessionManager.lockCount]). Work that was started before a lock and finished
     * after it (an unlock, a save) must not touch the state the lock cleared. Zero when there is no session manager.
     */
    private val lockEpoch: Int get() = sessionManager?.lockCount ?: 0

    private val _pendingUploadsCount = MutableStateFlow(0)
    val pendingUploadsCount: StateFlow<Int> = _pendingUploadsCount.asStateFlow()

    init {
        loadActiveStudy()
        refreshPendingUploads()

        // Observe session lock state
        if (sessionManager != null) {
            scope.launch {
                sessionManager.isUnlocked.collect { unlocked ->
                    if (!unlocked) {
                        // Whatever lock it was (timeout, screen off, button), nothing of the session stays in memory.
                        clearSessionState()
                        clearScannedProfile()
                    }
                    _isUnlocked.value = unlocked
                }
            }
        }
    }

    fun loadActiveStudy() {
        scope.launch {
            try {
                val study = repository.getActiveStudy()
                _activeStudy.value = study
                if (study != null) {
                    val now = System.currentTimeMillis()
                    val lockUntil = study.lockedUntil
                    if (lockUntil != null && lockUntil > now) {
                        _isLockedOut.value = true
                        _lockoutMinutes.value = (((lockUntil - now) / MILLIS_PER_MINUTE) + 1).toInt()
                    } else {
                        _isLockedOut.value = false
                        _lockoutMinutes.value = 0
                    }
                }
            } catch (_: Exception) {
            }
        }
    }

    fun refreshPendingUploads() {
        scope.launch {
            try {
                _pendingUploadsCount.value = repository.getPendingUploadsCount()
            } catch (_: Exception) {
            }
        }
    }

    /** A study QR was scanned: check its signature and show which study it is. A QR that fails says why. */
    fun onStudyQrScanned(qrPayload: ByteArray) {
        _error.value = null
        repository.verifyStudyProfile(qrPayload)
            .onSuccess {
                _scannedProfile.value = it
                scannedProfileBytes = qrPayload
            }
            .onFailure { _error.value = it.toError() }
    }

    fun clearScannedProfile() {
        _scannedProfile.value = null
        scannedProfileBytes = null
    }

    /** Enrolls in the scanned study with a new PIN, typed twice. */
    fun enrollWithScannedProfile(pin: String, confirmPin: String, onSuccess: (ResearchStudyEntity) -> Unit) {
        val bytes = scannedProfileBytes ?: return
        if (pin != confirmPin) {
            _error.value = ResearchError(ResearchMessage.PINS_DO_NOT_MATCH)
            return
        }
        unlockOrEnrollWithQr(bytes, pin) {
            clearScannedProfile()
            onSuccess(it)
        }
    }

    fun unlockOrEnrollWithQr(
        qrPayload: ByteArray,
        pin: String,
        onSuccess: (ResearchStudyEntity) -> Unit
    ) {
        if (!_isBusy.compareAndSet(expect = false, update = true)) return
        val epoch = lockEpoch
        scope.launch {
            _error.value = null
            val result = try {
                guarded { withContext(cpu) { repository.unlockWithStudyQr(qrPayload, pin) } }
            } finally {
                _isBusy.value = false
            } ?: return@launch
            if (epoch != lockEpoch) return@launch
            result.onSuccess { study ->
                _activeStudy.value = study
                _isUnlocked.value = true
                _isLockedOut.value = false
                _lockoutMinutes.value = 0
                sessionManager?.unlock(study.id)
                onSuccess(study)
            }.onFailure { err ->
                _error.value = err.toError()
                loadActiveStudy()
            }
        }
    }

    fun unlockWithPin(
        pin: String,
        onSuccess: (ResearchStudyEntity) -> Unit
    ) {
        val study = _activeStudy.value
        if (study == null) {
            _error.value = ResearchError(ResearchMessage.NO_STUDY)
            return
        }
        if (!_isBusy.compareAndSet(expect = false, update = true)) return
        val epoch = lockEpoch
        scope.launch {
            _error.value = null
            val check = try {
                guarded { withContext(cpu) { repository.verifyPin(study.id, pin) } }
            } finally {
                _isBusy.value = false
            } ?: return@launch
            if (epoch != lockEpoch) return@launch
            when (check) {
                is PinCheckResult.Success -> {
                    _isUnlocked.value = true
                    _isLockedOut.value = false
                    _lockoutMinutes.value = 0
                    sessionManager?.unlock(study.id)
                    loadActiveStudy()
                    onSuccess(study)
                }
                is PinCheckResult.LockedOut -> {
                    _isLockedOut.value = true
                    _lockoutMinutes.value = check.remainingMinutes
                    _error.value = ResearchError(ResearchMessage.LOCKED_OUT, minutes = check.remainingMinutes)
                    loadActiveStudy()
                }
                is PinCheckResult.WrongPin -> {
                    _error.value = ResearchError(ResearchMessage.WRONG_PIN, attempts = check.attemptsUntilNextLockout)
                    loadActiveStudy()
                }
                is PinCheckResult.VerifierKeyLost -> {
                    _error.value = ResearchError(ResearchMessage.VERIFIER_KEY_LOST)
                }
            }
        }
    }

    @Suppress("LongParameterList") // reworked with the research session in C2
    fun registerParticipantAndInputs(
        participantCode: String,
        consentFormId: String,
        birthTimeMillis: Long,
        ageHours: Double,
        gestationalAgeWeeks: Int,
        flagEarlyJaundice: Boolean,
        flagDangerSigns: Boolean,
        flagPriorPhototherapy: Boolean,
        onSuccess: () -> Unit
    ) {
        val study = _activeStudy.value ?: return
        scope.launch {
            _error.value = null
            val res = repository.registerParticipant(study.id, participantCode, consentFormId)
            res.onSuccess { participant ->
                _currentParticipant.value = participant
                _clinicalInputs.value = ResearchClinicalInputs(
                    participantCode = participantCode,
                    consentFormId = consentFormId,
                    birthTimeMillis = birthTimeMillis,
                    ageHours = ageHours,
                    gestationalAgeWeeks = gestationalAgeWeeks,
                    flagEarlyJaundice = flagEarlyJaundice,
                    flagDangerSigns = flagDangerSigns,
                    flagPriorPhototherapy = flagPriorPhototherapy
                )
                sessionManager?.recordUserActivity()
                onSuccess()
            }.onFailure { err ->
                _error.value = err.toError()
            }
        }
    }

    fun onCapturesCompleted(
        captures: List<ResearchCaptureInput>,
        onProceedToTsb: () -> Unit
    ) {
        _currentCaptures.value = captures
        sessionManager?.recordUserActivity()
        onProceedToTsb()
    }

    fun completeSessionWithTsb(
        tsbMgDl: Double?,
        drawTimeMillis: Long?,
        method: String?,
        cardBatchBytes: ByteArray? = null,
        onCompleted: (ResearchSessionResult) -> Unit
    ) {
        val participant = _currentParticipant.value ?: return
        val inputs = _clinicalInputs.value ?: return
        val captures = _currentCaptures.value

        val tsbInput = if (tsbMgDl != null && drawTimeMillis != null && method != null) {
            TsbInput(tsbMgDl = tsbMgDl, drawTime = drawTimeMillis, method = method)
        } else {
            null
        }

        if (!_isBusy.compareAndSet(expect = false, update = true)) return
        val epoch = lockEpoch
        scope.launch {
            _error.value = null
            val result = try {
                guarded {
                    repository.saveBlindedResearchSession(
                        participant = participant,
                        ageHours = researchAgeHours(inputs.birthTimeMillis, System.currentTimeMillis()),
                        gestationalAgeWeeks = inputs.gestationalAgeWeeks,
                        flagEarlyJaundice = inputs.flagEarlyJaundice,
                        flagDangerSigns = inputs.flagDangerSigns,
                        flagPriorPhototherapy = inputs.flagPriorPhototherapy,
                        captures = captures,
                        cardBatchBytes = cardBatchBytes,
                        tsbInput = tsbInput
                    )
                }
            } finally {
                _isBusy.value = false
            } ?: return@launch
            // The session locked while it was being saved: the record is kept, but nothing of it is put back in memory or shown.
            if (epoch != lockEpoch) return@launch

            result.onSuccess { sessionResult ->
                _lastSessionResult.value = sessionResult
                _cardTrustStatus.value = sessionResult.cardStatus
                refreshPendingUploads()
                sessionManager?.recordUserActivity()
                onCompleted(sessionResult)
            }.onFailure { err ->
                _error.value = err.toError()
            }
        }
    }

    fun recordDeferredTsb(
        participantCode: String,
        tsbMgDl: Double,
        drawTimeMillis: Long,
        method: String,
        onSuccess: () -> Unit
    ) {
        scope.launch {
            _error.value = null
            val result = repository.recordTsb(
                participantCode = participantCode,
                tsbMgDl = tsbMgDl,
                drawTime = drawTimeMillis,
                method = method
            )
            result.onSuccess {
                refreshPendingUploads()
                sessionManager?.recordUserActivity()
                onSuccess()
            }.onFailure { err ->
                _error.value = err.toError()
            }
        }
    }

    /**
     * Runs [block] and returns its result. Anything unexpected (a database error, no memory for the PIN hash, a lost key)
     * becomes a generic message and null, never a crash; a cancellation goes on.
     */
    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    private suspend fun <T : Any> guarded(block: suspend () -> T): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        _error.value = ResearchError(ResearchMessage.UNEXPECTED)
        null
    }

    /** Clears the message, for a screen that is entered again (a stale message from the last visit is not shown). */
    fun clearError() {
        _error.value = null
    }

    fun lockResearchSession() {
        _isUnlocked.value = false
        sessionManager?.lock()
    }

    fun clearSessionState() {
        _currentParticipant.value = null
        _clinicalInputs.value = null
        _currentCaptures.value = emptyList()
        _lastSessionResult.value = null
        _cardTrustStatus.value = CardTrustStatus.UNVERIFIED
        _error.value = null
    }
}

private const val MILLIS_PER_MINUTE = 60_000L

/**
 * The baby's age in hours at [nowMillis], rounded down to a tenth of an hour (the contract takes multiples of 0.1, doc 7;
 * down is toward the younger age, which is the side of more care). It is worked out when the session is saved, from the
 * birth time that was set, so the minutes spent on the captures are not left out. A birth time after [nowMillis] gives a
 * negative age, which the repository refuses.
 */
internal fun researchAgeHours(birthTimeMillis: Long, nowMillis: Long): Double =
    kotlin.math.floor((nowMillis - birthTimeMillis) / MILLIS_PER_HOUR * TENTHS) / TENTHS

private const val MILLIS_PER_HOUR = 3_600_000.0
private const val TENTHS = 10.0

/** The typed reason of a refusal from the repository, or the generic one for anything else. */
private fun Throwable.toError(): ResearchError =
    (this as? ResearchRefusal)?.error ?: ResearchError(ResearchMessage.UNEXPECTED)
