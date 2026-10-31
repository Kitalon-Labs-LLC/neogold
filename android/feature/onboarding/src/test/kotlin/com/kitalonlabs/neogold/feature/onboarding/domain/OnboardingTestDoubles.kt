package com.kitalonlabs.neogold.feature.onboarding.domain

import com.kitalonlabs.neogold.core.data.local.dao.AuditEventDao
import com.kitalonlabs.neogold.core.data.local.dao.CardBatchDao
import com.kitalonlabs.neogold.core.data.local.dao.DeviceProfileDao
import com.kitalonlabs.neogold.core.data.local.entity.AuditEventEntity
import com.kitalonlabs.neogold.core.data.local.entity.CardBatchEntity
import com.kitalonlabs.neogold.core.data.local.entity.DeviceProfileEntity

/** In-memory stand-ins for the Room DAOs the first-run services use, shared by the onboarding tests. */
internal class FakeCardBatchDao : CardBatchDao {
    val rows = linkedMapOf<String, CardBatchEntity>()
    override suspend fun insert(batch: CardBatchEntity) { rows[batch.batchId] = batch }
    override suspend fun update(batch: CardBatchEntity) { rows[batch.batchId] = batch }
    override suspend fun getBatch(batchId: String) = rows[batchId]
    override suspend fun getAllBatches() = rows.values.toList()
    override suspend fun deleteAll() = rows.clear()
}

internal class FakeDeviceProfileDao : DeviceProfileDao {
    val rows = mutableListOf<DeviceProfileEntity>()
    override suspend fun insert(profile: DeviceProfileEntity) { rows.add(profile) }
    override suspend fun update(profile: DeviceProfileEntity) {
        rows.replaceAll { if (it.id == profile.id) profile else it }
    }
    override suspend fun getActiveProfile() = rows.filter { it.status == "ACTIVE" }.maxByOrNull { it.calibratedAt }
    override suspend fun supersedeOtherProfiles(keepId: String) {
        rows.replaceAll { if (it.status == "ACTIVE" && it.id != keepId) it.copy(status = "SUPERSEDED") else it }
    }
    override suspend fun getProfileById(id: String) = rows.firstOrNull { it.id == id }
    override suspend fun supersedeActiveProfiles() {
        rows.replaceAll { if (it.status == "ACTIVE") it.copy(status = "SUPERSEDED") else it }
    }
    override suspend fun deleteAll() = rows.clear()
}

internal class FakeAuditEventDao : AuditEventDao {
    val rows = mutableListOf<AuditEventEntity>()
    override suspend fun insert(event: AuditEventEntity) { rows.add(event) }
    override suspend fun getAllEvents() = rows.toList()
    override suspend fun getEventsByType(eventType: String) = rows.filter { it.eventType == eventType }
    override suspend fun getEventsSince(sinceTimeMs: Long) = rows.filter { it.occurredAt >= sinceTimeMs }
    override suspend fun deleteAll() = rows.clear()
}
