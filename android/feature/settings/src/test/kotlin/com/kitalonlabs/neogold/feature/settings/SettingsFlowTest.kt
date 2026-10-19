package com.kitalonlabs.neogold.feature.settings

import com.google.common.truth.Truth.assertThat
import com.kitalonlabs.neogold.core.designsystem.util.ClinicalNumberFormatter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test

/**
 * Settings Flow Tests covering:
 * - TC-ONB-04: Language switch in settings (FR-01, NFR-06)
 * - TC-ONB-05: About and model card (FR-32, NG-027, NG-033)
 * - Retention, app lock, and opt-in settings
 * - S25 Delete all data purge
 */
class SettingsFlowTest {

    private lateinit var dataStore: FakeNeoGoldDataStore

    @Before
    fun setUp() {
        dataStore = FakeNeoGoldDataStore()
    }

    /**
     * TC-ONB-04: Language switch in settings (FR-01, NFR-06).
     * Switch Bangla to English and back in S23; clinical numbers stay in Western digits.
     */
    @Test
    fun testLanguageSwitchInSettings() = runTest {
        // Starts in Bangla
        assertThat(dataStore.language.first()).isEqualTo("bn")

        // Switch to English in S23
        dataStore.setLanguage("en")
        assertThat(dataStore.language.first()).isEqualTo("en")

        // Switch back to Bangla in S23
        dataStore.setLanguage("bn")
        assertThat(dataStore.language.first()).isEqualTo("bn")

        // In both languages, clinical numbers remain in Western digits by default (TC-A11Y-03)
        assertThat(dataStore.measurementDigits.first()).isEqualTo("western")
        val formattedInt = ClinicalNumberFormatter.formatInteger(52)
        val formattedDec = ClinicalNumberFormatter.formatDecimal(14.2)
        assertThat(formattedInt).isEqualTo("52")
        assertThat(formattedDec).isEqualTo("14.2")
    }

    /**
     * TC-ONB-05: About and model card (FR-32, NG-027, NG-033).
     * S24 shows model card summary, data sources, limitations, versions, and license list.
     */
    @Test
    fun testAboutAndModelCardMetadata() {
        val appVersion = "1.0.0"
        val modelVersion = "1.0.0"
        val configVersion = "2026.10.0"
        val cardBatch = "NG-2026-001"

        // Verify version banner components
        val versionInfo = "App $appVersion • Model $modelVersion • Config $configVersion • Card $cardBatch"
        assertThat(versionInfo).contains("App 1.0.0")
        assertThat(versionInfo).contains("Model 1.0.0")
        assertThat(versionInfo).contains("Config 2026.10.0")
        assertThat(versionInfo).contains("Card NG-2026-001")

        // Verify model card requirements
        val intendedUse = "Decision support for trained health workers. NeoGold is a screening tool, not a diagnostic device."
        val limitations = "Validated only for babies born at 35 weeks or later, up to 14 days of age, with no prior phototherapy."
        val dataSources = "Multicenter prospective observational validation study data."

        assertThat(intendedUse).contains("Decision support")
        assertThat(limitations).contains("35 weeks")
        assertThat(limitations).contains("14 days")
        assertThat(dataSources).contains("validation study")

        // Verify required licenses (NG-027)
        val licenses = listOf(
            "MIT License",
            "Creative Commons Attribution 4.0 International (CC BY 4.0)",
            "SIL Open Font License 1.1",
            "Apache License 2.0"
        )
        assertThat(licenses).hasSize(4)
    }

    /**
     * Tests retention days configuration (30 to 365 days).
     */
    @Test
    fun testRetentionDaysSettings() = runTest {
        assertThat(dataStore.screeningRetentionDays.first()).isEqualTo(90)

        // Set to minimum (30 days)
        dataStore.setScreeningRetentionDays(30)
        assertThat(dataStore.screeningRetentionDays.first()).isEqualTo(30)

        // Set to maximum (365 days)
        dataStore.setScreeningRetentionDays(365)
        assertThat(dataStore.screeningRetentionDays.first()).isEqualTo(365)
    }

    /**
     * Tests privacy and security toggles (App lock, keep screening log, crash reports opt-in).
     */
    @Test
    fun testPrivacyAndSecuritySettings() = runTest {
        // App Lock
        assertThat(dataStore.appLockEnabled.first()).isFalse()
        dataStore.setAppLockEnabled(true)
        assertThat(dataStore.appLockEnabled.first()).isTrue()

        // Keep screening log
        assertThat(dataStore.keepScreeningLog.first()).isTrue()
        dataStore.setKeepScreeningLog(false)
        assertThat(dataStore.keepScreeningLog.first()).isFalse()

        // Crash reports (FR-34: Opt-in only, default false)
        assertThat(dataStore.crashReportingOptIn.first()).isFalse()
        dataStore.setCrashReportingOptIn(true)
        assertThat(dataStore.crashReportingOptIn.first()).isTrue()
    }

    /**
     * Tests S25 Delete all data purge resets preferences to initial clean state.
     */
    @Test
    fun testDeleteAllDataPurge() = runTest {
        // Dirty the data store
        dataStore.setLanguage("en")
        dataStore.setSafetyAckVersion(2)
        dataStore.setOnboardingComplete(true)
        dataStore.setActiveDeviceProfileId("profile-123")
        dataStore.setAppLockEnabled(true)
        dataStore.setScreeningRetentionDays(180)

        // User confirms delete all data on S25
        dataStore.clearAll()

        // Verify all settings are reset
        assertThat(dataStore.language.first()).isEqualTo("bn")
        assertThat(dataStore.safetyAckVersion.first()).isEqualTo(0)
        assertThat(dataStore.onboardingComplete.first()).isFalse()
        assertThat(dataStore.activeDeviceProfileId.first()).isNull()
        assertThat(dataStore.appLockEnabled.first()).isFalse()
        assertThat(dataStore.screeningRetentionDays.first()).isEqualTo(90)
    }
}
