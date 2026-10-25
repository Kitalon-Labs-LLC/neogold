package com.kitalonlabs.neogold.sync.research.outbox

import com.kitalonlabs.neogold.core.data.local.dao.ResearchStudyDao
import com.kitalonlabs.neogold.core.data.retention.ResearchRetentionServerConfirmationHook

/**
 * Connects Core Data's RetentionManager with Sync Research's ReconciliationManager (doc 7 Section 5.4, TC-SYNC-13).
 * Confirms with the study server before local research images and sessions are purged.
 */
class ResearchRetentionConfirmationHook(
    private val reconciliationManager: ReconciliationManager,
    private val studyDao: ResearchStudyDao
) : ResearchRetentionServerConfirmationHook {

    override suspend fun isServerConfirmed(sessionId: String): Boolean {
        val study = studyDao.getActiveStudy() ?: return false
        val deviceId = study.serverDeviceId ?: return false
        val baseUrl = study.serverUrl ?: return false
        val keyAlias = study.deviceKeyAlias ?: return false

        return try {
            reconciliationManager.canDeleteSessionLocally(
                sessionId = sessionId,
                baseUrl = baseUrl,
                deviceId = deviceId,
                keyAlias = keyAlias
            )
        } catch (_: Exception) {
            // If offline or request fails, do NOT confirm deletion; retry on next retention pass
            false
        }
    }
}
