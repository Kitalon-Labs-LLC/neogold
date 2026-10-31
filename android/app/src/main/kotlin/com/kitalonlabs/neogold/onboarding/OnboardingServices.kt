package com.kitalonlabs.neogold.onboarding

import com.kitalonlabs.neogold.core.data.datastore.NeoGoldDataStore
import com.kitalonlabs.neogold.engine.camera.qualification.RequalificationReason
import com.kitalonlabs.neogold.feature.onboarding.domain.CardScanService
import com.kitalonlabs.neogold.feature.onboarding.domain.QualificationService
import com.kitalonlabs.neogold.navigation.Destinations
import kotlinx.coroutines.flow.first

/**
 * The first-run flow's working parts, built over the open database (doc 6 Section 5).
 *
 * @param cardScan null when the app's own files did not verify, so there is no signed trust list to check a card
 * against; S06 then shows the "turned off" screen and nothing is accepted
 * @param buildFingerprint this phone's OS build, compared with the one the camera was qualified on
 */
class OnboardingServices(
    val cardScan: CardScanService?,
    val qualification: QualificationService,
    private val dataStore: NeoGoldDataStore,
    private val buildFingerprint: String
) {

    /** Where the app opens, why the camera is to be checked again if it is, and whether screening may be offered. */
    suspend fun firstRunState(): FirstRunState {
        val complete = dataStore.onboardingComplete.first()
        val hasProfile = qualification.hasActiveProfile()
        val requalification = qualification.requalificationReason(buildFingerprint)
        return FirstRunState(
            startDestination = chooseStartDestination(
                onboardingComplete = complete,
                safetyAckVersion = dataStore.safetyAckVersion.first(),
                hasActiveProfile = hasProfile,
                requalification = requalification
            ),
            requalification = requalification,
            screeningEligible = qualification.screeningEligible(buildFingerprint)
        )
    }
}

/**
 * What the app learns about the first-run flow when it opens.
 *
 * @param requalification why the camera is to be checked again, shown on S06
 * @param screeningEligible the phone's camera is qualified and not due to be checked again (H5, TC-DEV-05); screening
 * is offered only when this is true as well as the config switch
 */
data class FirstRunState(
    val startDestination: String,
    val requalification: RequalificationReason?,
    val screeningEligible: Boolean
)

/** The version of the safety notice a user must have acknowledged (S02 to S04, TC-ONB-02). */
const val REQUIRED_SAFETY_ACK_VERSION = 1

/**
 * Which screen the app opens on. Pure, so every case is tested.
 *
 * - First run, or a run that was not finished: the language screen, so the flow starts from its first step.
 * - The notice was acknowledged for an older version: the notice (TC-ONB-03).
 * - The camera qualification is more than 30 days old, was made on another OS build, or is missing: scan the card,
 *   then qualify again (TC-DEV-07). Screening stays off in the meantime ([FirstRunState.screeningEligible]).
 * - Otherwise Home.
 */
internal fun chooseStartDestination(
    onboardingComplete: Boolean,
    safetyAckVersion: Int,
    hasActiveProfile: Boolean,
    requalification: RequalificationReason?
): String = when {
    !onboardingComplete -> Destinations.S01_LANGUAGE
    safetyAckVersion < REQUIRED_SAFETY_ACK_VERSION -> Destinations.S02_S04_SAFETY_NOTICE
    // A phone that finished onboarding without a stored qualification (an older build, or a save that failed) has
    // nothing to show it was measured, so the camera is checked.
    !hasActiveProfile || requalification != null -> Destinations.S06_CARD_SCAN
    else -> Destinations.S09_HOME
}
