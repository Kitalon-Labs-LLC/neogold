package com.kitalonlabs.neogold.sync.research.enrollment

import com.kitalonlabs.neogold.core.data.local.dao.AuditEventDao
import com.kitalonlabs.neogold.core.data.local.dao.ResearchStudyDao
import com.kitalonlabs.neogold.core.data.local.entity.AuditEventEntity
import com.kitalonlabs.neogold.core.data.local.entity.ResearchStudyEntity
import com.kitalonlabs.neogold.sync.research.api.ApiException
import com.kitalonlabs.neogold.sync.research.api.ResearchApiClient
import com.kitalonlabs.neogold.sync.research.api.models.ErrorCode
import com.kitalonlabs.neogold.sync.research.crypto.DeviceKeyManager
import java.util.UUID

/**
 * Ends the phone's connection to the study server. In every case (the worker leaves, the server revokes the device, the
 * server closes the study) the same thing happens locally: the device key and the device ID go, the study row stays ACTIVE
 * and every session, photo and outbox row stays on the phone for export (doc 7 Section 4.5, TC-SYNC-16). The study stays
 * ACTIVE because export and retention find the study through the active row.
 */
class StudyRevocationManager(
    private val apiClient: ResearchApiClient,
    private val keyManager: DeviceKeyManager,
    private val studyDao: ResearchStudyDao,
    private val auditDao: AuditEventDao,
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
            // Already revoked, unknown to the server, or the study is closed: there is nothing left to tell the server,
            // so the phone cleans up locally.
            if (e.errorCode !in ALREADY_DISCONNECTED) {
                throw e
            }
        }

        disconnect(study, DisconnectReason.LEFT)
    }

    /** The server revoked this device. */
    suspend fun handleDeviceRevoked(study: ResearchStudyEntity) = disconnect(study, DisconnectReason.REVOKED)

    /** The server closed the study: nothing more will be accepted, so the phone stops trying. */
    suspend fun handleStudyClosed(study: ResearchStudyEntity) = disconnect(study, DisconnectReason.CLOSED)

    private suspend fun disconnect(study: ResearchStudyEntity, reason: DisconnectReason) {
        study.deviceKeyAlias?.let { keyManager.deleteDeviceKey(it) }
        // The row as it is now, not the copy read before the network call: a PIN check in between has moved the failure
        // count and the lockout, and writing the old copy back would give those guesses back.
        val current = studyDao.getStudyById(study.id) ?: study
        studyDao.update(current.copy(serverDeviceId = null, deviceKeyAlias = null))
        auditDao.insert(
            AuditEventEntity(
                id = UUID.randomUUID().toString(),
                occurredAt = System.currentTimeMillis(),
                eventType = "DEVICE_DISCONNECTED",
                // the reason only: no study code, host, device ID or participant (doc 5 Section 2.2)
                details = """{"reason":"${reason.name}"}"""
            )
        )
    }

    private enum class DisconnectReason { LEFT, REVOKED, CLOSED }

    private companion object {
        val ALREADY_DISCONNECTED = setOf(ErrorCode.DEVICE_REVOKED, ErrorCode.DEVICE_UNKNOWN, ErrorCode.STUDY_CLOSED)
    }
}
