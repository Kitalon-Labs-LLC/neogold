package com.kitalonlabs.neogold.feature.onboarding.domain

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.LocalDate

/**
 * What S06 shows. The camera reads the QR many times a second, so only the first read while scanning counts: the card
 * is checked once and its outcome stays on the screen until the user scans again. That also keeps one bad card from
 * writing an audit event for every frame.
 */
class CardScanState(
    private val service: CardScanService,
    private val scope: CoroutineScope
) {
    sealed interface Phase {
        data object Scanning : Phase

        data object Checking : Phase

        data class Verified(val batchId: String, val expiresAt: LocalDate) : Phase

        data class Rejected(val reason: CardRejection) : Phase
    }

    private val _phase = MutableStateFlow<Phase>(Phase.Scanning)
    val phase: StateFlow<Phase> = _phase.asStateFlow()

    /** Called with every QR the camera reads; ignored unless the screen is still looking. */
    fun onPayload(payload: ByteArray) {
        if (!_phase.compareAndSet(Phase.Scanning, Phase.Checking)) return
        scope.launch { _phase.value = check(payload) }
    }

    /** After a refusal the user can try another card. A verified card stays verified. */
    fun scanAgain() {
        if (_phase.value is Phase.Rejected) _phase.value = Phase.Scanning
    }

    @Suppress("TooGenericExceptionCaught") // a storage failure is shown as "could not be saved"; it is never a crash
    private suspend fun check(payload: ByteArray): Phase = try {
        when (val outcome = service.accept(payload)) {
            is CardScanOutcome.Verified -> Phase.Verified(outcome.batchId, outcome.expiresAt)
            is CardScanOutcome.Rejected -> Phase.Rejected(outcome.reason)
        }
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        Phase.Rejected(CardRejection.NOT_SAVED)
    }
}
