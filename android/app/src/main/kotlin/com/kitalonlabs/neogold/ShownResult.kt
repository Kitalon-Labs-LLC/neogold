package com.kitalonlabs.neogold

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.kitalonlabs.neogold.core.data.local.NeoGoldDatabase
import com.kitalonlabs.neogold.feature.screening.domain.NO_CARD_SCANNED
import com.kitalonlabs.neogold.feature.screening.domain.ResultPresentation
import com.kitalonlabs.neogold.feature.screening.domain.ScreeningFlow
import com.kitalonlabs.neogold.feature.screening.domain.ScreeningState
import com.kitalonlabs.neogold.feature.screening.domain.VersionsRecord
import com.kitalonlabs.neogold.feature.screening.domain.toPresentation
import com.kitalonlabs.neogold.navigation.Destinations
import java.util.UUID

/**
 * The screening in progress, held by the app rather than by a screen, so a rotation or the app lock does not lose the
 * worker's answers. It is empty after a process restart; every screening screen then returns to Home instead of showing
 * nothing or doing nothing (safety review of package E1). It holds answers and decisions, never a photo.
 */
class ScreeningHolder {
    var flow: ScreeningFlow? by mutableStateOf(null)
}

/** What a result screen shows for a screening that ended with a decision. */
class ShownResult(
    val presentation: ResultPresentation,
    /** The versions this screening ran with, for the footer. */
    val versions: VersionsRecord,
    /** False when triage was not available: the screen then says only to use a blood test. */
    val triageAvailable: Boolean
) {
    val modelVersion: String get() = versions.modelVersion
    val configVersion: String get() = versions.configVersion

    /** The card scanned in this screening, or null when a hard rule decided before any card. */
    val cardBatchId: String? get() = versions.cardBatchId.takeIf { it != NO_CARD_SCANNED }
}

/**
 * What the screen for this state shows, or null for a state with no decision (a blocked start). The mapping is the engine's
 * answer through [toPresentation]; nothing is decided here.
 */
fun ScreeningState.shownResult(): ShownResult? = when (this) {
    is ScreeningState.UrgentReferral -> ShownResult(triageResult.toPresentation(), versions, triageAvailable = true)
    is ScreeningState.CannotScreen -> ShownResult(triageResult.toPresentation(), versions, triageAvailable = true)
    is ScreeningState.BloodTestFallback -> ShownResult(triageResult.toPresentation(), versions, triageAvailable = true)
    is ScreeningState.Result -> ShownResult(triageResult.toPresentation(), versions, triageAvailable = true)
    is ScreeningState.TriageUnavailable -> ShownResult(triageResult.toPresentation(), versions, triageAvailable = false)
    else -> null
}

/** Where the worker goes once the flow has reached [state]: a hard rule has its own screen, every other end is the result. */
fun resultRouteFor(state: ScreeningState): String = when (state) {
    is ScreeningState.UrgentReferral, is ScreeningState.CannotScreen -> Destinations.S14_RULE_OUTCOME
    else -> Destinations.S20_RESULT
}

/**
 * Where to go once a photo has been analysed. A result made from the chest photo is not shown yet when the model can also use
 * a forehead photo: the worker is first asked whether to add one (S18, FR-15), so the result on the screen is the final one.
 */
fun routeAfterAnalysis(flow: ScreeningFlow, state: ScreeningState): String =
    if (state is ScreeningState.Result && flow.shouldOfferForehead) {
        Destinations.S18_FOREHEAD_PHOTO
    } else {
        resultRouteFor(state)
    }

/** Writes the screening log row for a finished screening. Nothing is written for a screening with no decision. */
suspend fun saveScreening(
    flow: ScreeningFlow?,
    registerNumber: String?,
    deviceProfileId: String?,
    database: NeoGoldDatabase
) {
    val row = flow?.record(
        sessionId = UUID.randomUUID().toString(),
        nowMs = System.currentTimeMillis(),
        registerNumber = registerNumber,
        deviceProfileId = deviceProfileId
    ) ?: return
    database.screeningSessionDao().insert(row)
}
