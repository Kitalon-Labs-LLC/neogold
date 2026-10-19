package com.kitalonlabs.neogold.core.security.integrity

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

interface IntegrityStateManager : IntegrityChecker {
    val integrityResult: StateFlow<IntegrityResult>
    fun checkIntegrity(context: Context): IntegrityResult
    fun updateIntegrity(result: IntegrityResult)
    override fun isScreeningAllowed(): Boolean

    companion object {
        val instance: IntegrityStateManager by lazy { DefaultIntegrityStateManager() }

        fun checkIntegrity(context: Context): IntegrityResult = instance.checkIntegrity(context)
        fun updateIntegrity(result: IntegrityResult) = instance.updateIntegrity(result)
        fun isScreeningAllowed(): Boolean = instance.isScreeningAllowed()
    }
}

class DefaultIntegrityStateManager(
    private val verifier: BundleManifestVerifier = BundleManifestVerifier()
) : IntegrityStateManager {

    private val _integrityResult = MutableStateFlow<IntegrityResult>(IntegrityResult.MissingManifest)
    override val integrityResult: StateFlow<IntegrityResult> = _integrityResult.asStateFlow()

    override fun checkIntegrity(context: Context): IntegrityResult {
        val result = verifier.verify(context)
        _integrityResult.value = result
        return result
    }

    override fun updateIntegrity(result: IntegrityResult) {
        _integrityResult.value = result
    }

    override fun isScreeningAllowed(): Boolean {
        return _integrityResult.value.isScreeningAllowed
    }
}
