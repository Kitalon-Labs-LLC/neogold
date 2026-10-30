package com.kitalonlabs.neogold.feature.research.data

import com.kitalonlabs.neogold.core.data.local.entity.ResearchStudyEntity

/**
 * What research mode needs from the study server (doc 7). The feature module does not know the network: `:app` implements
 * this over `:sync:research`, the only module allowed to use the HTTP client (doc 8 Section 6.1).
 *
 * Every failure is a [StudyServerFailure] with a typed reason; the screen writes the text, so no server answer, host or
 * exception message reaches the screen or a log.
 */
interface StudyServer {
    /**
     * Sets the phone up on the server with the one-time [enrollmentCode], for the study named by [qrPayload] (the signed
     * study QR; it is checked again here). On success the study row holds the device ID and key alias.
     */
    suspend fun enroll(study: ResearchStudyEntity, qrPayload: ByteArray, enrollmentCode: String)

    /** Tells the server this phone leaves, then deletes the device key and the device ID. Research data stays on the phone. */
    suspend fun leave(study: ResearchStudyEntity)

    /** "Upload now" (doc 6 R07): a waiting upload run is replaced by one that starts as soon as Wi-Fi allows. */
    fun uploadNow()

    /** Queues the background upload, if the phone is set up on a server. Safe to call often. */
    fun scheduleUploads()
}

/** A server action that did not work, with the typed reason the screen turns into text. */
class StudyServerFailure(val error: ResearchError, cause: Throwable? = null) :
    Exception("Study server action failed: ${error.message}", cause)
