package com.kitalonlabs.neogold.sync.research.outbox

import com.kitalonlabs.neogold.core.data.local.dao.CaptureDao
import com.kitalonlabs.neogold.core.data.local.dao.CardBatchDao
import com.kitalonlabs.neogold.core.data.local.dao.OutboxDao
import com.kitalonlabs.neogold.core.data.local.dao.ResearchParticipantDao
import com.kitalonlabs.neogold.core.data.local.dao.ResearchStudyDao
import com.kitalonlabs.neogold.core.data.local.dao.ScreeningSessionDao
import com.kitalonlabs.neogold.core.data.local.dao.TsbMeasurementDao
import com.kitalonlabs.neogold.core.data.local.entity.OutboxEntity
import com.kitalonlabs.neogold.sync.research.api.ApiException
import com.kitalonlabs.neogold.sync.research.api.ResearchApiClient
import java.util.UUID

class ReconciliationManager(
    private val apiClient: ResearchApiClient,
    private val outboxDao: OutboxDao,
    private val studyDao: ResearchStudyDao,
    private val sessionDao: ScreeningSessionDao,
    private val captureDao: CaptureDao,
    private val batchDao: CardBatchDao,
    private val participantDao: ResearchParticipantDao,
    private val tsbDao: TsbMeasurementDao,
    private val appVersion: String
) {
    /**
     * Checks server status of a session before local deletion (doc 7 Section 5.4, TC-SYNC-13).
     * Returns true if the session is COMPLETE or REJECTED on the server.
     * If 404 or RECEIVING, re-queues the session manifest and any missing images, and returns false.
     */
    suspend fun canDeleteSessionLocally(
        sessionId: String,
        baseUrl: String,
        deviceId: String,
        keyAlias: String
    ): Boolean {
        try {
            val status = apiClient.getSessionStatus(
                baseUrl = baseUrl,
                deviceId = deviceId,
                keyAlias = keyAlias,
                sessionId = sessionId,
                appVersion = appVersion
            )
            if (status.status == "COMPLETE" || status.status == "REJECTED") {
                return true
            }

            // Session is still RECEIVING: re-queue missing images
            requeueSessionAndImages(sessionId, status.missing_images)
            return false
        } catch (e: ApiException) {
            if (e.statusCode == 404) {
                // Server does not have the session: re-queue manifest and all its images
                requeueSessionAndImages(sessionId, null)
                return false
            }
            throw e
        }
    }

    private suspend fun requeueSessionAndImages(sessionId: String, missingImageCaptureIds: List<String>?) {
        val now = System.currentTimeMillis()
        val sessionItem = outboxDao.getItemByEntity("SESSION_MANIFEST", sessionId)
        if (sessionItem != null) {
            outboxDao.update(sessionItem.copy(state = "PENDING", attempts = 0, updatedAt = now))
        } else {
            outboxDao.insertOrUpdate(
                OutboxEntity(
                    id = UUID.randomUUID().toString(),
                    entityType = "SESSION_MANIFEST",
                    entityId = sessionId,
                    idempotencyKey = UUID.randomUUID().toString(),
                    payloadSha256 = "",
                    state = "PENDING",
                    createdAt = now,
                    updatedAt = now
                )
            )
        }

        val captures = captureDao.getCapturesForSession(sessionId)
        for (capture in captures) {
            val sha = capture.imageSha256
            if (capture.imageFile != null && sha != null) {
                val shouldRequeue = missingImageCaptureIds == null || missingImageCaptureIds.contains(capture.id)
                if (shouldRequeue) {
                    val imageItem = outboxDao.getItemByEntity("CAPTURE_IMAGE", capture.id)
                    if (imageItem != null) {
                        outboxDao.update(imageItem.copy(state = "PENDING", attempts = 0, updatedAt = now))
                    } else {
                        outboxDao.insertOrUpdate(
                            OutboxEntity(
                                id = UUID.randomUUID().toString(),
                                entityType = "CAPTURE_IMAGE",
                                entityId = capture.id,
                                idempotencyKey = UUID.randomUUID().toString(),
                                payloadSha256 = sha,
                                state = "PENDING",
                                createdAt = now,
                                updatedAt = now
                            )
                        )
                    }
                }
            }
        }
    }

    /**
     * Triggered when data_generation increases from GET /device (doc 7 Section 5.4, TC-SYNC-14).
     * Re-sends every held item (card batches, participants, manifests, images, TSBs).
     */
    suspend fun recheckAndResendAllHeldItems(studyId: String, newGeneration: Long) {
        val now = System.currentTimeMillis()

        // 1. All Card Batches
        val batches = batchDao.getAllBatches()
        for (b in batches) {
            val item = outboxDao.getItemByEntity("CARD_BATCH", b.batchId)
            if (item != null) {
                outboxDao.update(item.copy(state = "PENDING", updatedAt = now))
            } else {
                outboxDao.insertOrUpdate(
                    OutboxEntity(
                        id = UUID.randomUUID().toString(),
                        entityType = "CARD_BATCH",
                        entityId = b.batchId,
                        idempotencyKey = UUID.randomUUID().toString(),
                        payloadSha256 = "",
                        state = "PENDING",
                        createdAt = now,
                        updatedAt = now
                    )
                )
            }
        }

        // 2. All Participants for this study
        val participants = participantDao.getParticipantsForStudy(studyId)
        for (p in participants) {
            val item = outboxDao.getItemByEntity("PARTICIPANT", p.id)
            if (item != null) {
                outboxDao.update(item.copy(state = "PENDING", updatedAt = now))
            } else {
                outboxDao.insertOrUpdate(
                    OutboxEntity(
                        id = UUID.randomUUID().toString(),
                        entityType = "PARTICIPANT",
                        entityId = p.id,
                        idempotencyKey = UUID.randomUUID().toString(),
                        payloadSha256 = "",
                        state = "PENDING",
                        createdAt = now,
                        updatedAt = now
                    )
                )
            }

            // 3. All Sessions for this participant
            val sessions = sessionDao.getSessionsForParticipant(p.id)
            for (s in sessions) {
                val sItem = outboxDao.getItemByEntity("SESSION_MANIFEST", s.id)
                if (sItem != null) {
                    outboxDao.update(sItem.copy(state = "PENDING", updatedAt = now))
                } else {
                    outboxDao.insertOrUpdate(
                        OutboxEntity(
                            id = UUID.randomUUID().toString(),
                            entityType = "SESSION_MANIFEST",
                            entityId = s.id,
                            idempotencyKey = UUID.randomUUID().toString(),
                            payloadSha256 = "",
                            state = "PENDING",
                            createdAt = now,
                            updatedAt = now
                        )
                    )
                }

                // 4. Captures for this session
                val captures = captureDao.getCapturesForSession(s.id)
                for (c in captures) {
                    val cSha = c.imageSha256
                    if (c.imageFile != null && cSha != null) {
                        val cItem = outboxDao.getItemByEntity("CAPTURE_IMAGE", c.id)
                        if (cItem != null) {
                            outboxDao.update(cItem.copy(state = "PENDING", updatedAt = now))
                        } else {
                            outboxDao.insertOrUpdate(
                                OutboxEntity(
                                    id = UUID.randomUUID().toString(),
                                    entityType = "CAPTURE_IMAGE",
                                    entityId = c.id,
                                    idempotencyKey = UUID.randomUUID().toString(),
                                    payloadSha256 = cSha,
                                    state = "PENDING",
                                    createdAt = now,
                                    updatedAt = now
                                )
                            )
                        }
                    }
                }
            }

            // 5. TSB Measurements
            val tsbs = tsbDao.getTsbForParticipant(p.id)
            for (t in tsbs) {
                val tItem = outboxDao.getItemByEntity("TSB", t.id)
                if (tItem != null) {
                    outboxDao.update(tItem.copy(state = "PENDING", updatedAt = now))
                } else {
                    outboxDao.insertOrUpdate(
                        OutboxEntity(
                            id = UUID.randomUUID().toString(),
                            entityType = "TSB",
                            entityId = t.id,
                            idempotencyKey = UUID.randomUUID().toString(),
                            payloadSha256 = "",
                            state = "PENDING",
                            createdAt = now,
                            updatedAt = now
                        )
                    )
                }
            }
        }

        // Update stored data_generation on study
        val study = studyDao.getStudyById(studyId)
        if (study != null) {
            studyDao.update(study.copy(dataGeneration = newGeneration))
        }
    }
}
