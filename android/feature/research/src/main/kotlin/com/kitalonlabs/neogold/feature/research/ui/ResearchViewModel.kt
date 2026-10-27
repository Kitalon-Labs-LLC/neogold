package com.kitalonlabs.neogold.feature.research.ui

import com.kitalonlabs.neogold.core.data.local.entity.ResearchParticipantEntity
import com.kitalonlabs.neogold.core.data.local.entity.ResearchStudyEntity
import com.kitalonlabs.neogold.core.data.pin.PinCheckResult
import com.kitalonlabs.neogold.core.security.pin.ResearchSessionManager
import com.kitalonlabs.neogold.feature.research.data.CardTrustStatus
import com.kitalonlabs.neogold.feature.research.data.ResearchCaptureInput
import com.kitalonlabs.neogold.feature.research.data.ResearchRepository
import com.kitalonlabs.neogold.feature.research.data.ResearchSessionResult
import com.kitalonlabs.neogold.feature.research.data.TsbInput
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

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
class ResearchViewModel(
    private val repository: ResearchRepository,
    private val sessionManager: ResearchSessionManager? = null,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Main.immediate + SupervisorJob())
) {

    private val _activeStudy = MutableStateFlow<ResearchStudyEntity?>(null)
    val activeStudy: StateFlow<ResearchStudyEntity?> = _activeStudy.asStateFlow()

    private val _isUnlocked = MutableStateFlow(false)
    val isUnlocked: StateFlow<Boolean> = _isUnlocked.asStateFlow()

    private val _isLockedOut = MutableStateFlow(false)
    val isLockedOut: StateFlow<Boolean> = _isLockedOut.asStateFlow()

    private val _lockoutMinutes = MutableStateFlow(0)
    val lockoutMinutes: StateFlow<Int> = _lockoutMinutes.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

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

    private val _pendingUploadsCount = MutableStateFlow(0)
    val pendingUploadsCount: StateFlow<Int> = _pendingUploadsCount.asStateFlow()

    init {
        loadActiveStudy()
        refreshPendingUploads()

        // Observe session lock state
        if (sessionManager != null) {
            scope.launch {
                sessionManager.isUnlocked.collect { unlocked ->
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

    fun unlockOrEnrollWithQr(
        qrPayload: ByteArray,
        pin: String,
        onSuccess: (ResearchStudyEntity) -> Unit
    ) {
        scope.launch {
            _errorMessage.value = null
            val result = repository.unlockWithStudyQr(qrPayload, pin)
            result.onSuccess { study ->
                _activeStudy.value = study
                _isUnlocked.value = true
                _isLockedOut.value = false
                _lockoutMinutes.value = 0
                sessionManager?.unlock(study.id)
                onSuccess(study)
            }.onFailure { err ->
                _errorMessage.value = err.message
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
            _errorMessage.value = "No study enrolled. Please scan study profile QR."
            return
        }
        scope.launch {
            _errorMessage.value = null
            when (val check = repository.verifyPin(study.id, pin)) {
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
                    _errorMessage.value = "PIN locked out for ${check.remainingMinutes} minutes"
                    loadActiveStudy()
                }
                is PinCheckResult.WrongPin -> {
                    _errorMessage.value = "Incorrect PIN. ${check.attemptsUntilNextLockout} attempts remaining before lockout."
                    loadActiveStudy()
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
            _errorMessage.value = null
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
                _errorMessage.value = err.message
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

        scope.launch {
            _errorMessage.value = null
            val result = repository.saveBlindedResearchSession(
                participant = participant,
                ageHours = inputs.ageHours,
                gestationalAgeWeeks = inputs.gestationalAgeWeeks,
                flagEarlyJaundice = inputs.flagEarlyJaundice,
                flagDangerSigns = inputs.flagDangerSigns,
                flagPriorPhototherapy = inputs.flagPriorPhototherapy,
                captures = captures,
                cardBatchBytes = cardBatchBytes,
                tsbInput = tsbInput
            )

            result.onSuccess { sessionResult ->
                _lastSessionResult.value = sessionResult
                _cardTrustStatus.value = sessionResult.cardStatus
                refreshPendingUploads()
                sessionManager?.recordUserActivity()
                onCompleted(sessionResult)
            }.onFailure { err ->
                _errorMessage.value = err.message
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
            _errorMessage.value = null
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
                _errorMessage.value = err.message
            }
        }
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
        _errorMessage.value = null
    }
}

private const val MILLIS_PER_MINUTE = 60_000L
