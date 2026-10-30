package com.kitalonlabs.neogold

import android.content.Context
import com.kitalonlabs.neogold.core.data.local.NeoGoldDatabase
import com.kitalonlabs.neogold.core.data.local.entity.ResearchStudyEntity
import com.kitalonlabs.neogold.feature.research.data.ResearchError
import com.kitalonlabs.neogold.feature.research.data.ResearchMessage
import com.kitalonlabs.neogold.feature.research.data.StudyServer
import com.kitalonlabs.neogold.feature.research.data.StudyServerFailure
import com.kitalonlabs.neogold.sync.research.ResearchSyncGraph
import com.kitalonlabs.neogold.sync.research.ServerFailureKind
import com.kitalonlabs.neogold.sync.research.classifyServerFailure
import com.kitalonlabs.neogold.sync.research.enrollment.EnrollingDevice
import com.kitalonlabs.neogold.sync.research.worker.ResearchWorkScheduler
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import kotlin.coroutines.cancellation.CancellationException

/**
 * What the research screens know of the study server: [StudyServer] over `:sync:research`, the only module that talks to a
 * network (doc 8 Section 6.1). The feature module sees typed failures only; the reason a join or leave did not work is
 * sorted by [classifyServerFailure] and turned into a [ResearchError], so no server text, host or exception message
 * reaches a screen or a log (doc 8 Section 6.6).
 */
class StudyServerSync(
    private val context: Context,
    private val database: NeoGoldDatabase,
    private val graph: ResearchSyncGraph,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val scheduler: UploadScheduler = UploadScheduler.WorkManagerBacked
) : StudyServer {

    /** The part of WorkManager this class uses, so a test can replace it. */
    interface UploadScheduler {
        fun scheduleBackgroundUpload(context: Context)

        fun triggerUploadNow(context: Context)

        fun cancel(context: Context)

        object WorkManagerBacked : UploadScheduler {
            override fun scheduleBackgroundUpload(context: Context) = ResearchWorkScheduler.scheduleBackgroundUpload(
                context
            )

            override fun triggerUploadNow(context: Context) = ResearchWorkScheduler.triggerUploadNow(context)

            override fun cancel(context: Context) = ResearchWorkScheduler.cancelSync(context)
        }
    }

    override suspend fun enroll(study: ResearchStudyEntity, qrPayload: ByteArray, enrollmentCode: String) {
        // The server is told what this phone measured about itself on first run, never a guess.
        val profile = database.deviceProfileDao().getActiveProfile()
            ?: throw StudyServerFailure(ResearchError(ResearchMessage.ENROLL_NO_DEVICE_PROFILE))
        val device = EnrollingDevice(
            cameraHardwareLevel = profile.cameraHardwareLevel,
            qualification = profile.qualification
        )
        asServerFailure { graph.enrollmentManager.enroll(qrPayload, enrollmentCode, device) }
    }

    override suspend fun leave(study: ResearchStudyEntity) {
        asServerFailure { graph.revocationManager.leaveStudy(study) }
        // nothing more will be sent from this phone, so a queued or backing-off run is dropped
        runScheduler("cancel") { scheduler.cancel(context) }
    }

    override fun uploadNow() {
        scope.launch(io) { runScheduler("upload now") { scheduler.triggerUploadNow(context) } }
    }

    override fun scheduleUploads() {
        scope.launch(io) {
            // Nothing is queued for a phone that is not set up on a server, or when nothing is waiting: the next save,
            // enrollment or app start asks again.
            val enrolled = database.researchStudyDao().getActiveStudy()?.serverDeviceId != null
            if (enrolled && database.outboxDao().getPendingItems().isNotEmpty()) {
                runScheduler("schedule") { scheduler.scheduleBackgroundUpload(context) }
            }
        }
    }

    /** Runs a server action; every failure leaves as a [StudyServerFailure] with a typed message and no text from the server. */
    @Suppress("TooGenericExceptionCaught")
    private suspend fun asServerFailure(action: suspend () -> Unit) {
        try {
            withContext(io) { action() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: StudyServerFailure) {
            throw e
        } catch (e: Exception) {
            val kind = classifyServerFailure(e)
            // the kind and the type of the exception only
            Timber.w("Study server action failed: %s (%s)", kind, e.javaClass.simpleName)
            throw StudyServerFailure(ResearchError(kind.toMessage()))
        }
    }

    @Suppress("TooGenericExceptionCaught")
    private fun runScheduler(what: String, block: () -> Unit) {
        try {
            block()
        } catch (e: Exception) {
            Timber.w("Could not %s: %s", what, e.javaClass.simpleName)
        }
    }
}

private fun ServerFailureKind.toMessage(): ResearchMessage = when (this) {
    ServerFailureKind.CODE_REJECTED -> ResearchMessage.ENROLL_CODE_REJECTED
    ServerFailureKind.SETTINGS_MISMATCH -> ResearchMessage.ENROLL_SETTINGS_MISMATCH
    ServerFailureKind.UNREACHABLE -> ResearchMessage.SERVER_UNREACHABLE
    ServerFailureKind.REFUSED -> ResearchMessage.SERVER_REFUSED
    ServerFailureKind.NO_STUDY -> ResearchMessage.NO_STUDY
    ServerFailureKind.OTHER_STUDY -> ResearchMessage.ENROLL_OTHER_STUDY
    ServerFailureKind.NO_SERVER -> ResearchMessage.ENROLL_NO_SERVER
    ServerFailureKind.UNEXPECTED -> ResearchMessage.UNEXPECTED
}
