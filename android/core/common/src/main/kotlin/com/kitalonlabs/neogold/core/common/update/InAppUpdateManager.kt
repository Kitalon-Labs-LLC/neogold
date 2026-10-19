package com.kitalonlabs.neogold.core.common.update

/**
 * Type of in-app update flow (NG-055, TC-ONB-06, doc 12 Section 6).
 */
enum class UpdateFlowType {
    NONE,
    FLEXIBLE,
    IMMEDIATE
}

/**
 * Update availability status.
 *
 * Per doc 12 Section 6 and NG-055:
 * - Priority 0 to 3: Flexible update flow by default (non-blocking).
 * - Priority 4 or 5: Immediate blocking flow (used for clinical safety fixes and emergency releases).
 */
data class AppUpdateStatus(
    val isUpdateAvailable: Boolean,
    val updatePriority: Int, // 0 to 5
    val flowType: UpdateFlowType
) {
    companion object {
        fun create(isAvailable: Boolean, priority: Int): AppUpdateStatus {
            val flow = when {
                !isAvailable -> UpdateFlowType.NONE
                priority >= 4 -> UpdateFlowType.IMMEDIATE
                else -> UpdateFlowType.FLEXIBLE
            }
            return AppUpdateStatus(
                isUpdateAvailable = isAvailable,
                updatePriority = priority,
                flowType = flow
            )
        }
    }
}

/**
 * In-app update manager interface.
 */
interface InAppUpdateManager {
    fun checkForUpdate(): AppUpdateStatus
    fun startUpdate(flowType: UpdateFlowType): Boolean
    fun completeFlexibleUpdate(): Boolean
}

/**
 * Fake implementation of InAppUpdateManager for testing (TC-ONB-06).
 */
class FakeAppUpdateManager : InAppUpdateManager {
    var isUpdateAvailable: Boolean = false
    var updatePriority: Int = 0
    var lastTriggeredFlow: UpdateFlowType = UpdateFlowType.NONE
    var isUpdateCompleted: Boolean = false

    fun setUpdateAvailable(priority: Int) {
        this.isUpdateAvailable = true
        this.updatePriority = priority
    }

    fun setUpdateNotAvailable() {
        this.isUpdateAvailable = false
        this.updatePriority = 0
    }

    override fun checkForUpdate(): AppUpdateStatus {
        return AppUpdateStatus.create(isUpdateAvailable, updatePriority)
    }

    override fun startUpdate(flowType: UpdateFlowType): Boolean {
        this.lastTriggeredFlow = flowType
        return true
    }

    override fun completeFlexibleUpdate(): Boolean {
        this.isUpdateCompleted = true
        return true
    }
}
