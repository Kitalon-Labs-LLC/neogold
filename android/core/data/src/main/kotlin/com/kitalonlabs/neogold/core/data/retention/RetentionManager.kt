package com.kitalonlabs.neogold.core.data.retention

import com.kitalonlabs.neogold.core.data.datastore.NeoGoldDataStore
import com.kitalonlabs.neogold.core.data.local.NeoGoldDatabase
import com.kitalonlabs.neogold.core.data.local.entity.AuditEventEntity
import com.kitalonlabs.neogold.core.security.EncryptedFileStore
import kotlinx.coroutines.flow.first
import java.util.UUID

data class RetentionReport(
    val registerNumbersErased: Int,
    val screeningSessionsPurged: Int,
    val abandonedSessionsDeleted: Int,
    val researchSessionsPurged: Int
)

class RetentionManager(
    private val database: NeoGoldDatabase,
    private val dataStore: NeoGoldDataStore,
    private val encryptedFileStore: EncryptedFileStore,
    private val serverConfirmationHook: ResearchRetentionServerConfirmationHook =
        NoOpResearchRetentionServerConfirmationHook(),
    private val currentTimeProvider: () -> Long = { System.currentTimeMillis() }
) {

    /**
     * Erases facility register numbers older than [NeoGoldDataStore.registerNumberRetentionDays] (default 60 days).
     * Sets `register_number` to null and `register_number_erased_at` to the current epoch ms.
     * Records `REGISTER_ERASURE` in audit trail with count only (no identifiers).
     */
    suspend fun eraseRegisterNumbers(): Int {
        val now = currentTimeProvider()
        val retentionDays = dataStore.registerNumberRetentionDays.first()
        val cutoffMs = now - (retentionDays.toLong() * MILLIS_PER_DAY)

        val count = database.screeningSessionDao().eraseRegisterNumbersOlderThan(cutoffMs, now)
        if (count > 0) {
            database.auditEventDao().insert(
                AuditEventEntity(
                    id = UUID.randomUUID().toString(),
                    occurredAt = now,
                    eventType = "REGISTER_ERASURE",
                    details = """{"count":$count}"""
                )
            )
        }
        return count
    }

    /**
     * Deletes screening sessions and their captures older than [NeoGoldDataStore.screeningRetentionDays] (default 180 days).
     * Records `RETENTION_PURGE` in audit trail with count only.
     */
    suspend fun purgeScreeningSessions(): Int {
        val now = currentTimeProvider()
        val retentionDays = dataStore.screeningRetentionDays.first()
        val cutoffMs = now - (retentionDays.toLong() * MILLIS_PER_DAY)

        val count = database.screeningSessionDao().deleteSessionsOlderThan("SCREENING", cutoffMs)
        if (count > 0) {
            database.auditEventDao().insert(
                AuditEventEntity(
                    id = UUID.randomUUID().toString(),
                    occurredAt = now,
                    eventType = "RETENTION_PURGE",
                    details = """{"count":$count}"""
                )
            )
        }
        return count
    }

    /**
     * Deletes IN_PROGRESS sessions older than 24 hours.
     */
    suspend fun cleanupAbandonedSessions(): Int {
        val now = currentTimeProvider()
        val cutoffMs = now - MILLIS_PER_DAY
        return database.screeningSessionDao().deleteAbandonedSessions(cutoffMs)
    }

    /**
     * Research local retention (doc 5 Section 2.5):
     * After [ResearchStudyEntity.localRetentionAfterAckDays] (default 14 days) since all related outbox
     * items are ACKED, confirms with server that the session reports COMPLETE or REJECTED,
     * then deletes research encrypted image files and local rows.
     */
    @Suppress(
        "NestedBlockDepth"
    ) // each purge step guards the next: a research row is kept until the server has confirmed it
    suspend fun purgeResearchSessions(): Int {
        val now = currentTimeProvider()
        val activeStudy = database.researchStudyDao().getActiveStudy() ?: return 0
        val retentionDays = activeStudy.localRetentionAfterAckDays
        val cutoffMs = now - (retentionDays.toLong() * MILLIS_PER_DAY)

        val researchSessions = database.screeningSessionDao().getSessionsByMode("RESEARCH")
        var purgedCount = 0

        for (session in researchSessions) {
            val outboxItems = database.outboxDao().getItemsForSession(session.id)
            if (outboxItems.isNotEmpty() && outboxItems.all { it.state == "ACKED" && it.updatedAt < cutoffMs }) {
                val confirmed = serverConfirmationHook.isServerConfirmed(session.id)
                if (confirmed) {
                    // Delete encrypted research images
                    val captures = database.captureDao().getCapturesForSession(session.id)
                    for (capture in captures) {
                        capture.imageFile?.let { fileName ->
                            encryptedFileStore.delete(fileName)
                        }
                    }
                    // Delete session and cascading captures
                    database.screeningSessionDao().deleteSession(session.id)
                    purgedCount++
                }
            }
        }

        if (purgedCount > 0) {
            database.auditEventDao().insert(
                AuditEventEntity(
                    id = UUID.randomUUID().toString(),
                    occurredAt = now,
                    eventType = "RETENTION_PURGE",
                    details = """{"type":"RESEARCH","count":$purgedCount}"""
                )
            )
        }

        return purgedCount
    }

    suspend fun runAllRetentionJobs(): RetentionReport {
        val erased = eraseRegisterNumbers()
        val purgedScreening = purgeScreeningSessions()
        val abandoned = cleanupAbandonedSessions()
        val purgedResearch = purgeResearchSessions()
        return RetentionReport(
            registerNumbersErased = erased,
            screeningSessionsPurged = purgedScreening,
            abandonedSessionsDeleted = abandoned,
            researchSessionsPurged = purgedResearch
        )
    }

    companion object {
        const val MILLIS_PER_DAY = 24L * 60L * 60L * 1000L
    }
}
