package com.kitalonlabs.neogold.sync.research.enrollment

import com.kitalonlabs.neogold.core.data.local.dao.ResearchStudyDao
import com.kitalonlabs.neogold.core.data.local.entity.ResearchStudyEntity
import com.kitalonlabs.neogold.sync.research.api.ApiException
import com.kitalonlabs.neogold.sync.research.api.ResearchApiClient
import com.kitalonlabs.neogold.sync.research.api.models.ErrorCode
import com.kitalonlabs.neogold.sync.research.crypto.DeviceKeyManager
import java.util.UUID

class StudyRevocationManager(
    private val apiClient: ResearchApiClient,
    private val keyManager: DeviceKeyManager,
    private val studyDao: ResearchStudyDao,
    private val appVersion: String
) {
    suspend fun leaveStudy(
        study: ResearchStudyEntity,
        idempotencyKey: String = UUID.randomUUID().toString()
    ) {
        val serverUrl = study.serverUrl ?: error("No server URL configured for study")
        val deviceId = study.serverDeviceId ?: error("Device is not enrolled in a study")
        val keyAlias = study.deviceKeyAlias ?: error("No device key alias")

        try {
            apiClient.leaveStudy(
                baseUrl = serverUrl,
                deviceId = deviceId,
                keyAlias = keyAlias,
                idempotencyKey = idempotencyKey,
                appVersion = appVersion
            )
        } catch (e: ApiException) {
            // If already revoked on the server (403 DEVICE_REVOKED), proceed with local cleanup
            if (e.errorCode != ErrorCode.DEVICE_REVOKED) {
                throw e
            }
        }

        // Delete only the Keystore key and clear server_device_id
        // Outbox rows and research data stay encrypted on the phone for USB export! (doc 7 Section 4.5, TC-SYNC-16)
        keyManager.deleteDeviceKey(keyAlias)
        val updatedStudy = study.copy(
            serverDeviceId = null,
            deviceKeyAlias = null
        )
        studyDao.update(updatedStudy)
    }

    suspend fun handleDeviceRevoked(study: ResearchStudyEntity) {
        val keyAlias = study.deviceKeyAlias
        if (keyAlias != null) {
            keyManager.deleteDeviceKey(keyAlias)
        }
        val updatedStudy = study.copy(
            serverDeviceId = null,
            deviceKeyAlias = null
        )
        studyDao.update(updatedStudy)
    }
}
