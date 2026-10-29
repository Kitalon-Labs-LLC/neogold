package com.kitalonlabs.neogold.core.security.integrity

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The outcome of the startup integrity check, readable by everything that must not run on unverified files:
 * screening, research unlock and the triage switch. It starts as "not verified" and only [updateIntegrity] with a
 * [IntegrityResult.Valid] opens it. There is no global instance; the app container owns the one that matters.
 */
interface IntegrityStateManager : IntegrityChecker {
    val integrityResult: StateFlow<IntegrityResult>
    fun updateIntegrity(result: IntegrityResult)
    override fun isScreeningAllowed(): Boolean
}

class DefaultIntegrityStateManager : IntegrityStateManager {

    private val _integrityResult = MutableStateFlow<IntegrityResult>(IntegrityResult.MissingManifest)
    override val integrityResult: StateFlow<IntegrityResult> = _integrityResult.asStateFlow()

    override fun updateIntegrity(result: IntegrityResult) {
        _integrityResult.value = result
    }

    override fun isScreeningAllowed(): Boolean {
        return _integrityResult.value.isScreeningAllowed
    }
}
