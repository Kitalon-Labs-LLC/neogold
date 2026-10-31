package com.kitalonlabs.neogold.onboarding

import com.google.common.truth.Truth.assertThat
import com.kitalonlabs.neogold.engine.camera.qualification.RequalificationReason
import com.kitalonlabs.neogold.navigation.Destinations
import org.junit.Test

/** Where the app opens (TC-ONB-01, TC-ONB-02, TC-ONB-03, TC-DEV-07). */
class StartDestinationTest {

    @Test
    fun aFirstRunStartsAtTheLanguageScreen_TC_ONB_01() {
        assertThat(
            chooseStartDestination(
                onboardingComplete = false,
                safetyAckVersion = 0,
                hasActiveProfile = false,
                requalification = null
            )
        )
            .isEqualTo(Destinations.S01_LANGUAGE)
    }

    @Test
    fun aRunThatWasNotFinishedStartsOverFromTheFirstStep() {
        assertThat(
            chooseStartDestination(
                onboardingComplete = false,
                safetyAckVersion = 1,
                hasActiveProfile = false,
                requalification = null
            )
        )
            .isEqualTo(Destinations.S01_LANGUAGE)
    }

    @Test
    fun aFinishedRunOpensOnHome() {
        assertThat(
            chooseStartDestination(
                onboardingComplete = true,
                safetyAckVersion = REQUIRED_SAFETY_ACK_VERSION,
                hasActiveProfile = true,
                requalification = null
            )
        ).isEqualTo(Destinations.S09_HOME)
    }

    @Test
    fun anOlderAcknowledgedNoticeComesBackBeforeHome_TC_ONB_03() {
        assertThat(
            chooseStartDestination(
                onboardingComplete = true,
                safetyAckVersion = REQUIRED_SAFETY_ACK_VERSION - 1,
                hasActiveProfile = true,
                requalification = null
            )
        ).isEqualTo(Destinations.S02_S04_SAFETY_NOTICE)
    }

    @Test
    fun aDueQualificationSendsTheUserToScanTheCardAgain_TC_DEV_07() {
        for (reason in RequalificationReason.entries) {
            assertThat(
                chooseStartDestination(
                    onboardingComplete = true,
                    safetyAckVersion = REQUIRED_SAFETY_ACK_VERSION,
                    hasActiveProfile = true,
                    requalification = reason
                )
            ).isEqualTo(Destinations.S06_CARD_SCAN)
        }
    }

    @Test
    fun aFinishedRunWithNoStoredQualificationChecksTheCameraAgain_TC_DEV_07() {
        assertThat(
            chooseStartDestination(
                onboardingComplete = true,
                safetyAckVersion = REQUIRED_SAFETY_ACK_VERSION,
                hasActiveProfile = false,
                requalification = null
            )
        ).isEqualTo(Destinations.S06_CARD_SCAN)
    }

    @Test
    fun theNoticeComesBeforeAnythingElse_TC_ONB_02() {
        assertThat(
            chooseStartDestination(
                onboardingComplete = true,
                safetyAckVersion = 0,
                hasActiveProfile = true,
                requalification = RequalificationReason.EXPIRED_30_DAYS
            )
        ).isEqualTo(Destinations.S02_S04_SAFETY_NOTICE)
    }
}
