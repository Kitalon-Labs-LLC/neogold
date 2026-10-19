package com.kitalonlabs.neogold.feature.settings

import com.kitalonlabs.neogold.core.data.datastore.NeoGoldDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * In-memory Fake implementation of NeoGoldDataStore for testing settings flows.
 */
class FakeNeoGoldDataStore : NeoGoldDataStore {
    val languageFlow = MutableStateFlow("bn")
    override val language: Flow<String> = languageFlow
    override suspend fun setLanguage(language: String) {
        languageFlow.value = language
    }

    val safetyAckVersionFlow = MutableStateFlow(0)
    override val safetyAckVersion: Flow<Int> = safetyAckVersionFlow
    override suspend fun setSafetyAckVersion(version: Int) {
        safetyAckVersionFlow.value = version
    }

    val onboardingCompleteFlow = MutableStateFlow(false)
    override val onboardingComplete: Flow<Boolean> = onboardingCompleteFlow
    override suspend fun setOnboardingComplete(complete: Boolean) {
        onboardingCompleteFlow.value = complete
    }

    val activeDeviceProfileIdFlow = MutableStateFlow<String?>(null)
    override val activeDeviceProfileId: Flow<String?> = activeDeviceProfileIdFlow
    override suspend fun setActiveDeviceProfileId(profileId: String?) {
        activeDeviceProfileIdFlow.value = profileId
    }

    val screeningRetentionDaysFlow = MutableStateFlow(90)
    override val screeningRetentionDays: Flow<Int> = screeningRetentionDaysFlow
    override suspend fun setScreeningRetentionDays(days: Int) {
        screeningRetentionDaysFlow.value = days
    }

    val registerNumberRetentionDaysFlow = MutableStateFlow(90)
    override val registerNumberRetentionDays: Flow<Int> = registerNumberRetentionDaysFlow
    override suspend fun setRegisterNumberRetentionDays(days: Int) {
        registerNumberRetentionDaysFlow.value = days
    }

    val keepScreeningLogFlow = MutableStateFlow(true)
    override val keepScreeningLog: Flow<Boolean> = keepScreeningLogFlow
    override suspend fun setKeepScreeningLog(keep: Boolean) {
        keepScreeningLogFlow.value = keep
    }

    val appLockEnabledFlow = MutableStateFlow(false)
    override val appLockEnabled: Flow<Boolean> = appLockEnabledFlow
    override suspend fun setAppLockEnabled(enabled: Boolean) {
        appLockEnabledFlow.value = enabled
    }

    val measurementDigitsFlow = MutableStateFlow("western")
    override val measurementDigits: Flow<String> = measurementDigitsFlow
    override suspend fun setMeasurementDigits(digits: String) {
        measurementDigitsFlow.value = digits
    }

    val crashReportingOptInFlow = MutableStateFlow(false)
    override val crashReportingOptIn: Flow<Boolean> = crashReportingOptInFlow
    override suspend fun setCrashReportingOptIn(optIn: Boolean) {
        crashReportingOptInFlow.value = optIn
    }

    override suspend fun clearAll() {
        languageFlow.value = "bn"
        safetyAckVersionFlow.value = 0
        onboardingCompleteFlow.value = false
        activeDeviceProfileIdFlow.value = null
        screeningRetentionDaysFlow.value = 90
        registerNumberRetentionDaysFlow.value = 90
        keepScreeningLogFlow.value = true
        appLockEnabledFlow.value = false
        measurementDigitsFlow.value = "western"
        crashReportingOptInFlow.value = false
    }
}
