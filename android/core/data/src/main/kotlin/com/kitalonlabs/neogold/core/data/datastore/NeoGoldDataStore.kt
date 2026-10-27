package com.kitalonlabs.neogold.core.data.datastore

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.Locale

val Context.neoGoldPreferencesDataStore: DataStore<Preferences> by preferencesDataStore(name = "neogold_settings")

interface NeoGoldDataStore {
    val language: Flow<String>
    suspend fun setLanguage(language: String)

    val safetyAckVersion: Flow<Int>
    suspend fun setSafetyAckVersion(version: Int)

    val onboardingComplete: Flow<Boolean>
    suspend fun setOnboardingComplete(complete: Boolean)

    val activeDeviceProfileId: Flow<String?>
    suspend fun setActiveDeviceProfileId(profileId: String?)

    val screeningRetentionDays: Flow<Int>
    suspend fun setScreeningRetentionDays(days: Int)

    val registerNumberRetentionDays: Flow<Int>
    suspend fun setRegisterNumberRetentionDays(days: Int)

    val keepScreeningLog: Flow<Boolean>
    suspend fun setKeepScreeningLog(keep: Boolean)

    val appLockEnabled: Flow<Boolean>
    suspend fun setAppLockEnabled(enabled: Boolean)

    val measurementDigits: Flow<String>
    suspend fun setMeasurementDigits(digits: String)

    val crashReportingOptIn: Flow<Boolean>
    suspend fun setCrashReportingOptIn(optIn: Boolean)

    suspend fun clearAll()
}

class DefaultNeoGoldDataStore(
    private val dataStore: DataStore<Preferences>
) : NeoGoldDataStore {

    constructor(context: Context) : this(context.neoGoldPreferencesDataStore)

    override val language: Flow<String> = dataStore.data.map { prefs ->
        prefs[KEY_LANGUAGE] ?: resolveDefaultLanguage()
    }

    override suspend fun setLanguage(language: String) {
        dataStore.edit { prefs ->
            prefs[KEY_LANGUAGE] = language
        }
    }

    override val safetyAckVersion: Flow<Int> = dataStore.data.map { prefs ->
        prefs[KEY_SAFETY_ACK_VERSION] ?: DEFAULT_SAFETY_ACK_VERSION
    }

    override suspend fun setSafetyAckVersion(version: Int) {
        dataStore.edit { prefs ->
            prefs[KEY_SAFETY_ACK_VERSION] = version
        }
    }

    override val onboardingComplete: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[KEY_ONBOARDING_COMPLETE] ?: DEFAULT_ONBOARDING_COMPLETE
    }

    override suspend fun setOnboardingComplete(complete: Boolean) {
        dataStore.edit { prefs ->
            prefs[KEY_ONBOARDING_COMPLETE] = complete
        }
    }

    override val activeDeviceProfileId: Flow<String?> = dataStore.data.map { prefs ->
        prefs[KEY_ACTIVE_DEVICE_PROFILE_ID]
    }

    override suspend fun setActiveDeviceProfileId(profileId: String?) {
        dataStore.edit { prefs ->
            if (profileId != null) {
                prefs[KEY_ACTIVE_DEVICE_PROFILE_ID] = profileId
            } else {
                prefs.remove(KEY_ACTIVE_DEVICE_PROFILE_ID)
            }
        }
    }

    override val screeningRetentionDays: Flow<Int> = dataStore.data.map { prefs ->
        prefs[KEY_SCREENING_RETENTION_DAYS] ?: DEFAULT_SCREENING_RETENTION_DAYS
    }

    override suspend fun setScreeningRetentionDays(days: Int) {
        require(days in MIN_SCREENING_RETENTION_DAYS..MAX_SCREENING_RETENTION_DAYS) {
            "Screening retention days ($days) must be between $MIN_SCREENING_RETENTION_DAYS and $MAX_SCREENING_RETENTION_DAYS"
        }
        dataStore.edit { prefs ->
            prefs[KEY_SCREENING_RETENTION_DAYS] = days
        }
    }

    override val registerNumberRetentionDays: Flow<Int> = dataStore.data.map { prefs ->
        prefs[KEY_REGISTER_NUMBER_RETENTION_DAYS] ?: DEFAULT_REGISTER_NUMBER_RETENTION_DAYS
    }

    override suspend fun setRegisterNumberRetentionDays(days: Int) {
        require(days in MIN_REGISTER_RETENTION_DAYS..MAX_REGISTER_RETENTION_DAYS) {
            "Register number retention days ($days) must be between $MIN_REGISTER_RETENTION_DAYS and $MAX_REGISTER_RETENTION_DAYS"
        }
        dataStore.edit { prefs ->
            prefs[KEY_REGISTER_NUMBER_RETENTION_DAYS] = days
        }
    }

    override val keepScreeningLog: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[KEY_KEEP_SCREENING_LOG] ?: DEFAULT_KEEP_SCREENING_LOG
    }

    override suspend fun setKeepScreeningLog(keep: Boolean) {
        dataStore.edit { prefs ->
            prefs[KEY_KEEP_SCREENING_LOG] = keep
        }
    }

    override val appLockEnabled: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[KEY_APP_LOCK_ENABLED] ?: DEFAULT_APP_LOCK_ENABLED
    }

    override suspend fun setAppLockEnabled(enabled: Boolean) {
        dataStore.edit { prefs ->
            prefs[KEY_APP_LOCK_ENABLED] = enabled
        }
    }

    override val measurementDigits: Flow<String> = dataStore.data.map { prefs ->
        prefs[KEY_MEASUREMENT_DIGITS] ?: DEFAULT_MEASUREMENT_DIGITS
    }

    override suspend fun setMeasurementDigits(digits: String) {
        dataStore.edit { prefs ->
            prefs[KEY_MEASUREMENT_DIGITS] = digits
        }
    }

    override val crashReportingOptIn: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[KEY_CRASH_REPORTING_OPT_IN] ?: DEFAULT_CRASH_REPORTING_OPT_IN
    }

    override suspend fun setCrashReportingOptIn(optIn: Boolean) {
        dataStore.edit { prefs ->
            prefs[KEY_CRASH_REPORTING_OPT_IN] = optIn
        }
    }

    override suspend fun clearAll() {
        dataStore.edit { prefs ->
            prefs.clear()
        }
    }

    companion object {
        val KEY_LANGUAGE = stringPreferencesKey("language")
        val KEY_SAFETY_ACK_VERSION = intPreferencesKey("safety_ack_version")
        val KEY_ONBOARDING_COMPLETE = booleanPreferencesKey("onboarding_complete")
        val KEY_ACTIVE_DEVICE_PROFILE_ID = stringPreferencesKey("active_device_profile_id")
        val KEY_SCREENING_RETENTION_DAYS = intPreferencesKey("screening_retention_days")
        val KEY_REGISTER_NUMBER_RETENTION_DAYS = intPreferencesKey("register_number_retention_days")
        val KEY_KEEP_SCREENING_LOG = booleanPreferencesKey("keep_screening_log")
        val KEY_APP_LOCK_ENABLED = booleanPreferencesKey("app_lock_enabled")
        val KEY_MEASUREMENT_DIGITS = stringPreferencesKey("measurement_digits")
        val KEY_CRASH_REPORTING_OPT_IN = booleanPreferencesKey("crash_reporting_opt_in")

        const val DEFAULT_SAFETY_ACK_VERSION = 0
        const val DEFAULT_ONBOARDING_COMPLETE = false
        const val DEFAULT_SCREENING_RETENTION_DAYS = 180
        const val MIN_SCREENING_RETENTION_DAYS = 30
        const val MAX_SCREENING_RETENTION_DAYS = 365

        const val DEFAULT_REGISTER_NUMBER_RETENTION_DAYS = 60
        const val MIN_REGISTER_RETENTION_DAYS = 14
        const val MAX_REGISTER_RETENTION_DAYS = 90

        const val DEFAULT_KEEP_SCREENING_LOG = true
        const val DEFAULT_APP_LOCK_ENABLED = false
        const val DEFAULT_MEASUREMENT_DIGITS = "WESTERN"
        const val DEFAULT_CRASH_REPORTING_OPT_IN = false

        private fun resolveDefaultLanguage(): String {
            val systemLanguage = Locale.getDefault().language
            return if (systemLanguage.equals("bn", ignoreCase = true)) {
                "bn"
            } else {
                "en"
            }
        }
    }
}
